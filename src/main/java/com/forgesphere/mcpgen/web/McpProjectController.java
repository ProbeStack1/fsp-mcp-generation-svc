package com.forgesphere.mcpgen.web;

import com.forgesphere.mcpgen.dto.Dtos.*;
import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.AuditActor;
import com.forgesphere.mcpgen.model.McpProject.DeployEntry;
import com.forgesphere.mcpgen.service.AuditService;
import com.forgesphere.mcpgen.service.McpGenerationService;
import com.forgesphere.mcpgen.service.McpProbeService;
import com.forgesphere.mcpgen.service.MicroserviceBridgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.*;

/**
 * Single REST controller — keeps the surface area small and predictable.
 * Mount point (from `server.servlet.context-path`): `/mcp-generate/v1/api`
 *
 * ────────────────────────────────────────────────────────────────────
 *   PROJECT CRUD
 *   POST   /projects                  — create
 *   GET    /projects                  — list (owner / workspace filter)
 *   GET    /projects/{id}             — fetch
 *   PUT    /projects/{id}             — full-or-partial update (patch semantics)
 *   DELETE /projects/{id}             — delete
 *
 *   GENERATION
 *   POST   /projects/{id}/generate    — materialise files into Mongo
 *   POST   /projects/generate-inline  — generate from a spec without persisting (preview)
 *   GET    /projects/{id}/files       — list generated files (path + bytes only)
 *   GET    /projects/{id}/files/content?path=...  — fetch a single file's content
 *   GET    /projects/{id}/download    — stream a .zip
 *
 *   CLIENT CONFIG
 *   GET    /projects/{id}/client-configs  — Claude / Cursor / ForgeQ snippets
 *
 *   LIVE HELPERS
 *   POST   /probe                     — probe any MCP URL (mock supported)
 *   POST   /call                      — call one tool on an MCP URL (mock supported)
 *   GET    /token                     — generate a random bearer token
 * ────────────────────────────────────────────────────────────────────
 */
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class McpProjectController {

    private final McpGenerationService svc;
    private final McpProbeService probeSvc;
    private final MicroserviceBridgeService bridgeSvc;
    private final AuditService audit;

    /** Pull caller identity from the project body (set by the frontend's
     *  `withCreateAudit` / `withUpdateAudit` helpers exactly as the senior
     *  team's onboarding & microservice services do). Falls back to the
     *  project's own ownerEmail so internal calls still produce a
     *  meaningful audit entry. */
    private AuditActor actorFromBody(String email, McpProject p) {
        if (email == null || email.isBlank()) return audit.fallbackActor(p);
        return audit.actor(email, null);
    }

    // ------------- CRUD -------------
    @PostMapping
    public Envelope<McpProject> create(@RequestBody McpProject body) {
        // Senior's convention: `createdBy` + `updatedBy` come in as
        // top-level strings on the payload. Persist them as-is so any
        // existing Microservice/Proxy reader picks them up unchanged.
        String actorEmail = body.getCreatedBy();
        if (actorEmail == null || actorEmail.isBlank()) actorEmail = body.getUpdatedBy();
        McpProject created = svc.create(body);
        if (actorEmail != null && !actorEmail.isBlank()) {
            created.setCreatedBy(actorEmail);
            created.setUpdatedBy(actorEmail);
        }
        audit.recordCreate(created, actorFromBody(actorEmail, created));
        audit.save(created);
        return Envelope.ok(created);
    }

    @GetMapping
    public Envelope<List<McpProject>> list(@RequestParam(required = false) String ownerEmail,
                                           @RequestParam(required = false) String workspaceId) {
        return Envelope.ok(svc.list(ownerEmail, workspaceId));
    }

    @GetMapping("/{id}")
    public Envelope<McpProject> get(@PathVariable String id) {
        return svc.get(id).map(Envelope::ok)
                .orElse(Envelope.fail("project not found: " + id));
    }

    @PutMapping("/{id}")
    public Envelope<McpProject> update(@PathVariable String id, @RequestBody McpProject body) {
        // Capture which top-level sections were touched so the activity
        // feed shows e.g. "edited Identity, Capabilities" rather than
        // an opaque "updated" marker.
        List<String> changed = new ArrayList<>();
        if (body.getIdentity()     != null) changed.add("identity");
        if (body.getCapabilities() != null) changed.add("capabilities");
        if (body.getRuntime()      != null) changed.add("runtime");
        if (body.getTransport()    != null) changed.add("transport");
        if (body.getAuth()         != null) changed.add("auth");
        if (body.getAdvanced()     != null) changed.add("advanced");
        if (body.getOnboarding()   != null) changed.add("onboarding");
        if (body.getConnectorId()  != null) changed.add("connector");

        String actorEmail = body.getUpdatedBy();
        if (actorEmail == null || actorEmail.isBlank()) actorEmail = body.getCreatedBy();

        McpProject saved = svc.update(id, body);
        // Mirror senior's pattern — keep the top-level `updatedBy` in sync
        // so the catalog table renders the latest editor uniformly.
        if (actorEmail != null && !actorEmail.isBlank()) {
            saved.setUpdatedBy(actorEmail);
        }
        audit.recordEdit(saved, actorFromBody(actorEmail, saved), changed, "Updated: " + String.join(", ", changed));
        audit.save(saved);
        return Envelope.ok(saved);
    }

    @DeleteMapping("/{id}")
    public Envelope<Map<String, Object>> delete(@PathVariable String id) {
        svc.delete(id);
        return Envelope.ok(Map.of("deleted", id));
    }

    // ------------- Generation -------------
    @PostMapping("/{id}/generate")
    public Envelope<GenerateResponse> generate(@PathVariable String id) {
        McpProject p = svc.generate(id);
        return Envelope.ok(toGenerateResponse(p));
    }

    @PostMapping("/generate-inline")
    public Envelope<Map<String, Object>> generateInline(@RequestBody McpProject spec) {
        McpProject p = svc.generateInline(spec);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", toGenerateResponse(p));
        // Include full file content for inline previews — caller is the wizard UI.
        out.put("files", p.getGenerated().getFiles());
        return Envelope.ok(out);
    }

    @GetMapping("/{id}/files")
    public Envelope<List<FileSummary>> listFiles(@PathVariable String id) {
        McpProject p = svc.get(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        if (p.getGenerated() == null) p = svc.generate(id);
        return Envelope.ok(p.getGenerated().getFiles().stream()
                .map(f -> new FileSummary(f.getPath(), f.getBytes(), f.getMimeHint()))
                .toList());
    }

    @GetMapping("/{id}/files/content")
    public Envelope<Map<String, Object>> fileContent(@PathVariable String id, @RequestParam String path) {
        McpProject p = svc.get(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        if (p.getGenerated() == null) p = svc.generate(id);
        return p.getGenerated().getFiles().stream()
                .filter(f -> f.getPath().equals(path)).findFirst()
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", f.getPath());
                    m.put("content", f.getContent());
                    m.put("mimeHint", f.getMimeHint());
                    return Envelope.ok(m);
                })
                .orElse(Envelope.fail("file not found: " + path));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String id) {
        McpProject p = svc.get(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        String slug = p.getIdentity() == null || p.getIdentity().getSlug() == null ? "mcp-server" : p.getIdentity().getSlug();

        // If we already persisted a zip on a previous download, just
        // stream that — same artefact, no regeneration drift.
        byte[] cached = svc.downloadStoredZip(p);
        StreamingResponseBody body = (cached != null)
                ? out -> out.write(cached)
                : out -> { try { svc.streamZip(p, out); } catch (Exception e) { throw new RuntimeException(e); } };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + slug + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    // ------------- Deploy bridge -------------
    /**
     * Mirror the generated project into the shared `microservice` +
     * `deployment_artifacts` + `codegen_results` collections (and uploads
     * the zip to the shared GCS bucket). After this call the existing
     * `apiDevelopmentService.uploadToGitHub(microserviceId)` endpoint can
     * pick up our artifact transparently.
     *
     * Returns `{ microserviceId, deploymentArtifactId, codeGenResultId, gcsBucket, gcsArchivePath, fileCount }`.
     */
    @PostMapping("/{id}/deploy-to-github")
    public Envelope<Map<String, Object>> deployToGithub(@PathVariable String id) {
        McpProject p = svc.get(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        return Envelope.ok(bridgeSvc.mirror(p));
    }

    /**
     * Push the generated MCP code DIRECTLY to the user's GitHub repo
     * using the token from the connector linked to this project. This
     * is the endpoint the wizard calls on "Push & Deploy" — it solves
     * the "repo is empty" problem caused by the  api-development
     * team's template-repo+workflow_dispatch flow.
     *
     * Returns `{ repoFullName, repoUrl, branch, pushedCount, failedCount, pushedFiles, failedFiles }`.
     */
    @PostMapping("/{id}/push-to-github")
    public Envelope<Map<String, Object>> pushToGithub(@PathVariable String id,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        McpProject p = svc.get(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        String actorEmail = body == null ? null : (String) body.get("updatedBy");
        if ((actorEmail == null || actorEmail.isBlank()) && body != null) actorEmail = (String) body.get("createdBy");

        Map<String, Object> result;
        String status = "success";
        String error = null;
        try {
            result = bridgeSvc.pushToGitHub(p);
        } catch (Exception ex) {
            status = "failed";
            error  = ex.getMessage();
            result = new LinkedHashMap<>();
            result.put("error", error);
        }
        // Re-fetch to pick up the bridge's side-effect writes (commitSha
        // is written by the bridge on pushedCommitSha after `save`).
        p = svc.get(id).orElse(p);
        if (actorEmail != null && !actorEmail.isBlank()) p.setUpdatedBy(actorEmail);
        Integer pushed = (Integer) result.getOrDefault("pushedCount", null);
        audit.recordPush(p, actorFromBody(actorEmail, p),
                p.getPushedRepoFullName(), p.getPushedRepoUrl(),
                p.getPushedBranch(), p.getPushedCommitSha(),
                pushed, status, error);
        audit.save(p);
        if ("failed".equals(status)) return Envelope.fail(error);
        return Envelope.ok(result);
    }

    /**
     * Read-only workflow-run lookup. Replaces the senior team's
     * `/api-development/v1/api-development/{msId}/deploy-to-github/latest-run`
     * endpoint which is currently broken by a uniform-bucket-level-access
     * policy on their GCS bucket. We use the same connector PAT that
     * pushed the files to call the GitHub Runs API directly. Response
     * shape matches what the senior endpoint used to return so the
     * front-end's `DeployStatusPanel` works without changes.
     */
    @GetMapping("/{id}/workflow-runs/latest")
    public Envelope<Map<String, Object>> latestWorkflowRun(@PathVariable String id) {
        McpProject p = svc.get(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        Map<String, Object> out = bridgeSvc.getLatestWorkflowRun(p);
        // ── Upsert-on-any-status audit write ─────────────────────────
        // Earlier this block only recorded when `status="completed"`,
        // which meant the `totalDeploys` counter stayed at 0 until the
        // workflow finished. User feedback: "deploy start hote hi
        // count badhna chahiye". We now call `upsertDeployFromPoll`
        // on ANY runId — first sighting inserts (counter +1), later
        // sightings update the same row, and a terminal transition
        // bumps success/failed exactly once.
        try {
            McpProject fresh = svc.get(id).orElse(p);
            String runId      = (String) out.get("runId");
            String status     = (String) out.get("status");
            String conclusion = (String) out.get("conclusion");
            String runUrl     = (String) out.get("htmlUrl");
            String deployed   = fresh.getDeployedServiceUrl();
            if (runId != null && !runId.isBlank()) {
                // Failure attribution — only meaningful at terminal time.
                String failedStep   = null;
                String failedReason = null;
                boolean terminal = "completed".equalsIgnoreCase(status)
                        && conclusion != null && !"success".equalsIgnoreCase(conclusion);
                if (terminal) {
                    try {
                        Map<String, Object> steps = bridgeSvc.getWorkflowRunSteps(fresh, runId);
                        @SuppressWarnings("unchecked")
                        java.util.List<Map<String, Object>> jobs =
                                (java.util.List<Map<String, Object>>) steps.getOrDefault("jobs", java.util.List.of());
                        outer:
                        for (Map<String, Object> job : jobs) {
                            @SuppressWarnings("unchecked")
                            java.util.List<Map<String, Object>> sList =
                                    (java.util.List<Map<String, Object>>) job.getOrDefault("steps", java.util.List.of());
                            for (Map<String, Object> step : sList) {
                                String sConcl = String.valueOf(step.getOrDefault("conclusion", ""));
                                if ("failure".equalsIgnoreCase(sConcl)
                                        || "cancelled".equalsIgnoreCase(sConcl)
                                        || "timed_out".equalsIgnoreCase(sConcl)) {
                                    failedStep   = String.valueOf(step.getOrDefault("name", "(unknown)"));
                                    failedReason = "Step \"" + failedStep + "\" reported "
                                            + sConcl.toLowerCase()
                                            + " in job \"" + job.getOrDefault("name", "?") + "\". "
                                            + "Open the workflow run on GitHub for the full byte-by-byte log.";
                                    break outer;
                                }
                            }
                        }
                    } catch (Exception ignore) { /* best-effort */ }
                    if (failedStep == null) {
                        failedStep   = "deploy-to-cloud-run";
                        failedReason = "Workflow concluded \"" + conclusion + "\". Open the run on GitHub for details.";
                    }
                }
                audit.upsertDeployFromPoll(fresh, actorFromBody(fresh.getUpdatedBy(), fresh),
                        runId, runUrl, status, conclusion, deployed, null,
                        failedStep, failedReason, fresh.getPushedCommitSha());
                audit.save(fresh);
            }
        } catch (Exception ex) {
            // Audit is best-effort — never block the wizard's status poll.
        }
        return Envelope.ok(out);
    }

    /** Per-job/step matrix for a single workflow run id. Drives the 11-step animation. */
    @GetMapping("/{id}/workflow-runs/{runId}/steps")
    public Envelope<Map<String, Object>> workflowRunSteps(@PathVariable String id,
                                                          @PathVariable String runId) {
        McpProject p = svc.get(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        return Envelope.ok(bridgeSvc.getWorkflowRunSteps(p, runId));
    }

    // ------------- Client configs -------------
    @GetMapping("/{id}/client-configs")
    public Envelope<Map<String, Object>> configs(@PathVariable String id) {
        McpProject p = svc.get(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        return Envelope.ok(svc.clientConfigs(p));
    }

    // ------------- Helpers -------------
    private GenerateResponse toGenerateResponse(McpProject p) {
        var g = p.getGenerated();
        return new GenerateResponse(p.getId(), g.getFiles().size(), g.getTotalBytes(),
                g.getFiles().stream().map(f -> new FileSummary(f.getPath(), f.getBytes(), f.getMimeHint())).toList(),
                g.getGeneratedAt().toString());
    }
}
