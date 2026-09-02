package com.forgesphere.mcpgen.service;

import com.forgesphere.mcpgen.generator.CodeGenerator;
import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.Generated;
import com.forgesphere.mcpgen.repo.McpProjectRepository;
import com.forgesphere.mcpgen.storage.StorageClient;
import com.forgesphere.mcpgen.storage.StoredObject;
import com.forgesphere.mcpgen.service.MicroserviceBridgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Orchestrator for everything the controller layer doesn't do itself:
 * CRUD, generation, zip streaming/persistence, and client-config snippet assembly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpGenerationService {

    private final McpProjectRepository repo;
    private final List<CodeGenerator> generators;
    private final StorageClient storage;
    private final GenerationPostProcessor postProcessor;
    private final MongoTemplate mongoTemplate;

    @Lazy
    @Autowired
    private MicroserviceBridgeService bridgeService; // Lazy to break circular dependency

    // Same property GcsStorageClient itself binds to (see
    // StorageProperties/StorageAutoConfiguration) — needed here because
    // StoredObject.objectPath is a BARE key ("mcp-zips/…"), not a full
    // "gs://bucket/…" URI, but codegen_results.gcsArchivePath must be
    // the full URI or senior's contract-testing/analysis/peer-review
    // BundleDownloadService rejects it with "Invalid GCS path".
    @Value("${forgesphere.storage.bucket:}")
    private String storageBucket;

    // ---- NEW: save method (used by bundle builder) ----
    public McpProject save(McpProject p) {
        return repo.save(p);
    }

    // ------------------------------------------------------------- CRUD

    /**
     * Creates a new MCP project and immediately mirrors it into a microservice
     * record so downstream services can be called.
     */
    public McpProject create(McpProject p) {
        p.setId(UUID.randomUUID().toString());
        Instant now = Instant.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        if (p.getIdentity() == null)    p.setIdentity(McpProject.Identity.builder().build());
        if (p.getCapabilities() == null) p.setCapabilities(McpProject.Capabilities.builder().build());
        if (p.getRuntime() == null)     p.setRuntime(McpProject.Runtime.builder().language("typescript").sdkVersion("^1.0.0").bundler("tsx").build());
        if (p.getTransport() == null)   p.setTransport(McpProject.Transport.builder().kind("streamable-http").baseUrl("http://localhost:3500/mcp").build());
        if (p.getAuth() == null)        p.setAuth(McpProject.Auth.builder().kind("bearer").headerName("Authorization").build());
        if (p.getAdvanced() == null)    p.setAdvanced(McpProject.Advanced.builder().build());

        McpProject saved = repo.save(p);

        // Create the mirrored microservice record (uses lazy proxy)
        try {
            String microserviceId = bridgeService.createMicroserviceOnly(saved);
            saved.setMicroserviceMirrorId(microserviceId);
            saved = repo.save(saved);
            log.info("Created microservice mirror {} for MCP project {}", microserviceId, saved.getId());
        } catch (Exception e) {
            log.error("Failed to create microservice mirror for project {}: {}", saved.getId(), e.getMessage(), e);
        }

        return saved;
    }

    public List<McpProject> list(String ownerEmail, String workspaceId) {
        return list(ownerEmail, workspaceId, false);
    }

    /**
     * Catalog read with explicit control over whether soft-deleted projects
     * should be included. The default ({@code includeDeleted = false}) is
     * what the UI uses; admin trash views pass {@code true}.
     */
    public List<McpProject> list(String ownerEmail, String workspaceId, boolean includeDeleted) {
        if (ownerEmail != null && !ownerEmail.isBlank()) {
            return includeDeleted
                    ? repo.findByOwnerEmailOrderByCreatedAtDesc(ownerEmail)
                    : repo.findByOwnerEmailAndSoftDeletedFalseOrderByCreatedAtDesc(ownerEmail);
        }
        if (workspaceId != null && !workspaceId.isBlank()) {
            return includeDeleted
                    ? repo.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId)
                    : repo.findByWorkspaceIdAndSoftDeletedFalseOrderByCreatedAtDesc(workspaceId);
        }
        return includeDeleted
                ? repo.findAll()
                : repo.findBySoftDeletedFalseOrderByCreatedAtDesc();
    }

    public Optional<McpProject> get(String id) { return repo.findById(id); }

    public McpProject update(String id, McpProject patch) {
        McpProject cur = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        if (patch.getIdentity()     != null) cur.setIdentity(patch.getIdentity());
        if (patch.getCapabilities() != null) cur.setCapabilities(patch.getCapabilities());
        if (patch.getRuntime()      != null) cur.setRuntime(patch.getRuntime());
        if (patch.getTransport()    != null) cur.setTransport(patch.getTransport());
        if (patch.getAuth()         != null) cur.setAuth(patch.getAuth());
        if (patch.getAdvanced()     != null) cur.setAdvanced(patch.getAdvanced());
        if (patch.getOwnerEmail()   != null) cur.setOwnerEmail(patch.getOwnerEmail());
        if (patch.getWorkspaceId()  != null) cur.setWorkspaceId(patch.getWorkspaceId());
        if (patch.getOnboardingId() != null) cur.setOnboardingId(patch.getOnboardingId());
        if (patch.getConnectorId()  != null) cur.setConnectorId(patch.getConnectorId());
        if (patch.getOnboarding()   != null) cur.setOnboarding(patch.getOnboarding());
        if (patch.getSource()       != null) cur.setSource(patch.getSource());
        if (patch.getSpecMetadataId() != null) cur.setSpecMetadataId(patch.getSpecMetadataId());
        if (patch.getSpecName()       != null) cur.setSpecName(patch.getSpecName());
        if (patch.getSpecSource()     != null) cur.setSpecSource(patch.getSpecSource());
        if (patch.getTestRunResults() != null) cur.setTestRunResults(patch.getTestRunResults());
        cur.setUpdatedAt(Instant.now());
        return repo.save(cur);
    }

    /**
     * Hard delete — wipes the document outright. Kept only for the
     * legacy admin purge path; the wizard always uses {@link #softDelete}
     * via the controller.
     */
    public void delete(String id) { repo.deleteById(id); }

    // ----------------------------------------------------- Lifecycle ops

    /**
     * Mark a project as deleted without removing the document. The catalog
     * filters {@code softDeleted == true} out by default; admins can still
     * see and restore the row via {@code includeDeleted = true} on list.
     */
    public McpProject softDelete(String id, McpProject.AuditActor actor, String reason) {
        McpProject cur = mustGet(id);
        cur.setSoftDeleted(true);
        cur.setDeleteEvent(McpProject.DeleteEvent.builder()
                .by(actor)
                .reason(reason)
                .build());
        cur.setUpdatedAt(Instant.now());
        if (actor != null && actor.getEmail() != null) cur.setUpdatedBy(actor.getEmail());
        return repo.save(cur);
    }

    /**
     * Inverse of {@link #softDelete}. Keeps the delete event in place so
     * the audit timeline still shows the deletion, just stamps the
     * restore actor/timestamp on the same event.
     */
    public McpProject restore(String id, McpProject.AuditActor actor) {
        McpProject cur = mustGet(id);
        if (!cur.isSoftDeleted()) return cur; // idempotent
        cur.setSoftDeleted(false);
        if (cur.getDeleteEvent() != null) {
            cur.getDeleteEvent().setRestoredAt(Instant.now());
            cur.getDeleteEvent().setRestoredBy(actor);
        }
        cur.setUpdatedAt(Instant.now());
        if (actor != null && actor.getEmail() != null) cur.setUpdatedBy(actor.getEmail());
        return repo.save(cur);
    }

    /**
     * Flip the {@code deprecated} flag. The catalog still surfaces the
     * project but ribbons it with a "deprecated" badge so new consumers
     * know not to onboard against it.
     */
    public McpProject deprecate(String id, McpProject.AuditActor actor, String reason) {
        McpProject cur = mustGet(id);
        cur.setDeprecated(true);
        cur.setDeprecatedAt(Instant.now());
        cur.setDeprecatedBy(actor == null ? null : actor.getEmail());
        cur.setDeprecationReason(reason);
        cur.setUpdatedAt(Instant.now());
        if (actor != null && actor.getEmail() != null) cur.setUpdatedBy(actor.getEmail());
        return repo.save(cur);
    }

    public McpProject undeprecate(String id, McpProject.AuditActor actor) {
        McpProject cur = mustGet(id);
        cur.setDeprecated(false);
        cur.setDeprecatedAt(null);
        cur.setDeprecatedBy(null);
        cur.setDeprecationReason(null);
        cur.setUpdatedAt(Instant.now());
        if (actor != null && actor.getEmail() != null) cur.setUpdatedBy(actor.getEmail());
        return repo.save(cur);
    }

    /**
     * Deep-copy of a project under a new id. Identity slug is suffixed
     * with {@code -copy} (or the caller-supplied {@code newSlug}) to avoid
     * collisions in the workspace. All generation artifacts are cleared
     * so the clone starts at Step 1's "needs generate" state.
     */
    public McpProject clone(String id, McpProject.AuditActor actor, String newSlug) {
        McpProject src = mustGet(id);
        McpProject copy = deepCopy(src);
        copy.setId(UUID.randomUUID().toString());
        copy.setCloneOf(src.getId());
        copy.setVersionOf(null);
        copy.setVersionNumber(null);
        copy.setSoftDeleted(false);
        copy.setDeleteEvent(null);
        copy.setDeprecated(false);
        copy.setDeprecatedAt(null);
        copy.setDeprecatedBy(null);
        copy.setDeprecationReason(null);
        // Reset everything that's tied to a specific generate/push/deploy
        // lifecycle so the clone re-enters the wizard at "needs generate".
        resetLifecycleState(copy);
        // Slug nudge so the clone doesn't collide with the original in the same workspace.
        if (copy.getIdentity() != null) {
            String slug = newSlug != null && !newSlug.isBlank()
                    ? newSlug
                    : safeSlug(copy.getIdentity().getSlug()) + "-copy";
            copy.getIdentity().setSlug(slug);
            if (copy.getIdentity().getDisplayName() != null) {
                copy.getIdentity().setDisplayName(copy.getIdentity().getDisplayName() + " (Copy)");
            }
        }
        Instant now = Instant.now();
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        if (actor != null && actor.getEmail() != null) {
            copy.setCreatedBy(actor.getEmail());
            copy.setUpdatedBy(actor.getEmail());
        }

        McpProject savedCopy = repo.save(copy);

        // NEW: Create a fresh microservice record for the clone
        try {
            String microserviceId = bridgeService.createMicroserviceOnly(savedCopy);
            savedCopy.setMicroserviceMirrorId(microserviceId);
            savedCopy = repo.save(savedCopy);
            log.info("Created microservice mirror {} for cloned MCP project {}", microserviceId, savedCopy.getId());
        } catch (Exception e) {
            log.error("Failed to create microservice mirror for cloned project {}: {}", savedCopy.getId(), e.getMessage(), e);
        }

        return savedCopy;
    }

    /**
     * Produce a new document representing a fresh semver of an existing
     * project. Same slug/workspace; the {@code versionOf} pointer chains
     * the new doc back to its predecessor so the catalog can render the
     * version timeline.
     */
    public McpProject version(String id, McpProject.AuditActor actor, String newVersion) {
        McpProject src = mustGet(id);
        McpProject copy = deepCopy(src);
        copy.setId(UUID.randomUUID().toString());
        copy.setVersionOf(src.getId());
        copy.setCloneOf(null);
        copy.setVersionNumber(newVersion != null && !newVersion.isBlank()
                ? newVersion
                : nextSemver(src.getVersionNumber()));
        // A new version starts fresh on the whole generate/push/deploy
        // lifecycle — identical reset to clone() so the two never drift.
        resetLifecycleState(copy);
        copy.setSoftDeleted(false);
        copy.setDeleteEvent(null);
        copy.setDeprecated(false);
        copy.setDeprecatedAt(null);
        copy.setDeprecatedBy(null);
        copy.setDeprecationReason(null);
        Instant now = Instant.now();
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        if (actor != null && actor.getEmail() != null) {
            copy.setCreatedBy(actor.getEmail());
            copy.setUpdatedBy(actor.getEmail());
        }

        McpProject savedCopy = repo.save(copy);

        // NEW: Create a fresh microservice record for the version
        try {
            String microserviceId = bridgeService.createMicroserviceOnly(savedCopy);
            savedCopy.setMicroserviceMirrorId(microserviceId);
            savedCopy = repo.save(savedCopy);
            log.info("Created microservice mirror {} for versioned MCP project {}", microserviceId, savedCopy.getId());
        } catch (Exception e) {
            log.error("Failed to create microservice mirror for versioned project {}: {}", savedCopy.getId(), e.getMessage(), e);
        }

        return savedCopy;
    }

    /**
     * Null out every field tied to one specific generate → push → deploy
     * lifecycle. Shared by {@link #clone} and {@link #version} so a fresh copy
     * always re-enters the wizard at "needs generate" with no stale mirror ids,
     * pipeline repo pointers, GitHub-Actions run status, deployed URLs, cached
     * bundle zip or Step-8 test results carried over from its parent. Keeping
     * this in one place means a newly added lifecycle field only has to be
     * reset once for both operations.
     */
    private static void resetLifecycleState(McpProject copy) {
        // Mongo mirror docs (codegen_results / microservice / deployment_artifacts)
        copy.setMicroserviceMirrorId(null);
        copy.setDeploymentArtifactId(null);
        copy.setCodeGenResultId(null);
        copy.setGcsArchivePath(null);
        copy.setMirroredAt(null);
        // Pipeline deploy pointers (fsp-api-development-svc dispatch)
        copy.setRepositoryName(null);
        copy.setPipelineRepoFullName(null);
        copy.setPipelineRepoUrl(null);
        copy.setPipelineBranch(null);
        copy.setDeploymentId(null);
        copy.setDeployTriggeredAt(null);
        copy.setArtifactUrlMode(null);
        // Legacy direct-push metadata
        copy.setPushedRepoFullName(null);
        copy.setPushedRepoUrl(null);
        copy.setPushedBranch(null);
        copy.setPushedCommitSha(null);
        copy.setPushedActionsUrl(null);
        copy.setPushedFileCount(null);
        copy.setPushedAt(null);
        // GitHub Actions run tracking
        copy.setLatestRunId(null);
        copy.setLatestRunStatus(null);
        copy.setLatestRunConclusion(null);
        copy.setLatestRunUrl(null);
        copy.setLatestRunCheckedAt(null);
        // Deployed endpoints
        copy.setDeployedServiceUrl(null);
        copy.setDeployedMcpUrl(null);
        copy.setDeployedHealthUrl(null);
        copy.setDeployedAt(null);
        // Cached bundle zip
        copy.setZipObjectPath(null);
        copy.setZipBytes(null);
        copy.setZipContentType(null);
        copy.setZipUploadedAt(null);
        copy.setLastDownloadedAt(null);
        // Step-8 test-run results are tied to the parent's generated code
        copy.setTestRunResults(null);
        // Wizard progress + history restart
        copy.setStepCompletion(new java.util.ArrayList<>());
        copy.setRunHistory(new java.util.ArrayList<>());
        copy.setAuditTrail(null);
    }

    /**
     * Append a {@link McpProject.StepCompletion} entry. The wizard calls
     * this whenever a step transitions to "done" — the controller passes
     * the actor stamped by the request body.
     */
    public McpProject markStepComplete(String id, int stepNumber, String stepName,
                                       McpProject.AuditActor actor, String status, String note) {
        McpProject cur = mustGet(id);
        List<McpProject.StepCompletion> ledger = cur.getStepCompletion();
        if (ledger == null) ledger = new java.util.ArrayList<>();
        ledger.add(McpProject.StepCompletion.builder()
                .stepNumber(stepNumber)
                .stepName(stepName)
                .completedBy(stampActor(actor))
                .status(status == null ? "success" : status)
                .note(note)
                .build());
        cur.setStepCompletion(ledger);
        cur.setUpdatedAt(Instant.now());
        if (actor != null && actor.getEmail() != null) cur.setUpdatedBy(actor.getEmail());
        return repo.save(cur);
    }

    /**
     * Record an execution row and trim the buffer. The 50-row cap keeps
     * the Mongo document well under the 16 MB BSON limit even after years
     * of activity.
     */
    public McpProject recordRun(String id, McpProject.RunEntry entry) {
        McpProject cur = mustGet(id);
        List<McpProject.RunEntry> hist = cur.getRunHistory();
        if (hist == null) hist = new java.util.ArrayList<>();
        if (entry.getBy() != null) entry.setBy(stampActor(entry.getBy()));
        hist.add(0, entry);                                  // newest first
        if (hist.size() > 50) hist = new java.util.ArrayList<>(hist.subList(0, 50));
        cur.setRunHistory(hist);
        cur.setUpdatedAt(Instant.now());
        return repo.save(cur);
    }

    // ─────────── private helpers ────────────────────────────────────

    private McpProject mustGet(String id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
    }

    private static McpProject.AuditActor stampActor(McpProject.AuditActor a) {
        if (a == null) return null;
        if (a.getTimestamp() == null) a.setTimestamp(Instant.now());
        return a;
    }

    private static String safeSlug(String s) {
        if (s == null || s.isBlank()) return "mcp-server";
        return s;
    }

    /**
     * Best-effort semver bump: "1.2.3" → "1.2.4", missing → "1.0.0".
     * The caller can override by passing an explicit value to
     * {@link #version}.
     */
    private static String nextSemver(String prev) {
        if (prev == null || prev.isBlank()) return "1.0.0";
        String[] parts = prev.split("\\.");
        if (parts.length != 3) return prev + ".1";
        try {
            int patch = Integer.parseInt(parts[2]);
            return parts[0] + "." + parts[1] + "." + (patch + 1);
        } catch (NumberFormatException e) {
            return prev + ".1";
        }
    }

    /**
     * Mongo-aware deep copy: serialise to JSON via Jackson and read back.
     * Beats hand-rolled Lombok copy because it handles every nested
     * record automatically and silently drops the {@code @Transient}
     * {@code generated} field.
     */
    private McpProject deepCopy(McpProject src) {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        try {
            String json = m.writeValueAsString(src);
            return m.readValue(json, McpProject.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deep-copy McpProject " + src.getId(), e);
        }
    }

    // --------------------------------------------------------- Generate
    public McpProject generate(String id) {
        McpProject p = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        return generateInto(p);
    }

    /** Generate directly from a spec without persisting — used for the live preview in Step 6. */
    public McpProject generateInline(McpProject spec) {
        if (spec.getId() == null) spec.setId(UUID.randomUUID().toString());
        return generateInto(spec);
    }

    private McpProject generateInto(McpProject p) {
        String lang = p.getRuntime() == null ? "typescript" : p.getRuntime().getLanguage();
        CodeGenerator gen = generators.stream()
                .filter(g -> g.language().equalsIgnoreCase(lang))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported language: " + lang));
        // Resolve the connector's dev branch BEFORE generating so the
        // embedded .github/workflows/mcp.yml's push-trigger is baked in
        // to match the branch `push()` will actually push to — deploy
        // then fires off the SAME branch the code lands on, instead of
        // the old hardcoded "main" that silently went stale after the
        // first push. Best-effort: falls back to "main" on any failure.
        try {
            p.setDevBranch(bridgeService.resolveDevBranch(p));
        } catch (Exception e) {
            p.setDevBranch("main");
        }
        // The "merge-to" branch name from the CICD default strategy —
        // baked into mcp.yml as `branch_tag` for the pipeline's
        // merge/promote step. Best-effort: blank on any failure.
        try {
            p.setBranchTag(bridgeService.resolveBranchTag(p));
        } catch (Exception e) {
            p.setBranchTag("");
        }
        var files = new ArrayList<>(gen.generate(p));
        // Honour the user's Step 7 picks — strip test kinds they
        // unchecked, add postman/inspector/Dockerfile/client configs
        // they enabled. Has to run BEFORE we tally `totalBytes` so the
        // catalog stat is accurate.
        postProcessor.apply(p, files);
        int total = files.stream().mapToInt(f -> f.getBytes()).sum();
        p.setGenerated(Generated.builder()
                .files(files).totalBytes(total).generatedAt(Instant.now()).build());
        // Invalidate any cached zip — the in-memory files have just
        // changed and the previously-uploaded archive (if any) is now
        // stale. Leaving it pointed at the old object meant downloads
        // kept handing the user yesterday's bytes even after a
        // backend code change or a "Generate again" click.
        p.setZipObjectPath(null);
        p.setZipBytes(null);
        p.setZipContentType(null);
        p.setZipUploadedAt(null);
        p.setUpdatedAt(Instant.now());
        if (p.getId() != null && repo.existsById(p.getId())) repo.save(p);
        return p;
    }

    // -------------------------------------------------------------- Zip
    public void streamZip(McpProject p, OutputStream out) throws Exception {
        if (p.getGenerated() == null || p.getGenerated().getFiles().isEmpty()) generateInto(p);

        // Write the zip to a buffer so we can persist + stream simultaneously.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            for (var f : p.getGenerated().getFiles()) {
                ZipEntry e = new ZipEntry(f.getPath());
                zip.putNextEntry(e);
                zip.write(f.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        byte[] bytes = buf.toByteArray();

        // Persist to object storage so the user can re-download from
        // anywhere — first download wins; subsequent downloads stream
        // straight from the saved object.
        if (p.getId() != null && repo.existsById(p.getId())) {
            try {
                if (p.getZipObjectPath() == null) {
                    String path = "mcp-zips/" + p.getId() + "/" + slugForZip(p) + ".zip";
                    StoredObject so = storage.upload(path, bytes, "application/zip");
                    p.setZipObjectPath(so.getObjectPath());
                    p.setZipBytes(so.getBytes());
                    p.setZipContentType(so.getContentType());
                    p.setZipUploadedAt(so.getUploadedAt());
                }
                p.setLastDownloadedAt(Instant.now());
                repo.save(p);
                // ──────────────────────────────────────────────────
                // Mirror the GCS path into `codegen_results` so senior's
                // contract-testing-svc / analysis-svc / peer-review-svc
                // BundleDownloadService finds the MCP zip the same way
                // it finds microservice zips (it queries `codegen_results`
                // by microserviceId first, then falls back to api-dev).
                // We keep the schema identical to the microservice
                // pipeline so no senior-side change is needed.
                // ──────────────────────────────────────────────────
                mirrorIntoCodegenResults(p);
            } catch (Exception ex) {
                log.warn("[zip] storage upload failed (continuing with stream-only): {}", ex.getMessage());
            }
        }

        out.write(bytes);
    }

    /** Re-stream a previously-stored zip without regenerating. */
    public byte[] downloadStoredZip(McpProject p) {
        if (p.getZipObjectPath() == null) return null;
        try {
            byte[] bytes = storage.download(p.getZipObjectPath());
            p.setLastDownloadedAt(Instant.now());
            repo.save(p);
            return bytes;
        } catch (Exception ex) {
            log.warn("[zip] stored fetch failed ({}): {}", p.getZipObjectPath(), ex.getMessage());
            return null;
        }
    }

    private String slugForZip(McpProject p) {
        String slug = p.getIdentity() != null && p.getIdentity().getSlug() != null && !p.getIdentity().getSlug().isBlank()
                ? p.getIdentity().getSlug()
                : "mcp-server";
        return slug.replaceAll("[^a-z0-9-]+", "-");
    }

    /**
     * Upsert a record into {@code codegen_results} so senior's
     * BundleDownloadService (analysis/contract-testing/peer-review)
     * picks the MCP zip via the same query path it uses for
     * microservice zips.
     *
     * Schema mirrors the microservice contract:
     * <pre>
     *   {
     *     microserviceId : &lt;project.id (or mirror id when set)&gt;,
     *     gcsArchivePath : "gs://bucket/path/to.zip"   (local fallback writes a file:// URI),
     *     archiveFileName: "&lt;slug&gt;.zip",
     *     targetType     : "MCP_SERVER",
     *     updatedAt      : Instant
     *   }
     * </pre>
     *
     * We deliberately store under BOTH ids when a mirror id exists —
     * senior's lookups use whatever id the UI sends, which may be
     * either the MCP project id or the legacy microserviceMirrorId.
     */
    private void mirrorIntoCodegenResults(McpProject p) {
        if (mongoTemplate == null || p.getZipObjectPath() == null) return;
        try {
            // BUG FIX: `p.getZipObjectPath()` is a BARE object key (e.g.
            // "mcp-zips/{id}/mcp-server.zip") — StoredObject keeps bucket
            // and objectPath as separate fields, upload() never returns a
            // combined URI. Writing that bare key straight into
            // `gcsArchivePath` is exactly what caused
            // "Invalid GCS path: mcp-zips/…/mcp-server.zip" from Code
            // Analysis (and any other senior service reading this row) —
            // their parser expects a full "gs://bucket/key" URI. Prefix it
            // ourselves when we know the bucket (real GCS); on local-disk
            // fallback (no bucket configured) leave the bare key as-is —
            // that path never reaches a real GCS-backed consumer anyway.
            String gcsPath = (storageBucket != null && !storageBucket.isBlank()
                    && !p.getZipObjectPath().startsWith("gs://"))
                    ? "gs://" + storageBucket + "/" + p.getZipObjectPath()
                    : p.getZipObjectPath();
            String fileName = slugForZip(p) + ".zip";
            Update u = new Update()
                    .set("microserviceId",  p.getId())
                    .set("gcsArchivePath",  gcsPath)
                    .set("archiveFileName", fileName)
                    .set("targetType",      "MCP_SERVER")
                    .set("updatedAt",       Instant.now());
            mongoTemplate.upsert(Query.query(Criteria.where("microserviceId").is(p.getId())),
                    u, "codegen_results");
            String mirror = p.getMicroserviceMirrorId();
            if (mirror != null && !mirror.isBlank() && !mirror.equals(p.getId())) {
                mongoTemplate.upsert(Query.query(Criteria.where("microserviceId").is(mirror)),
                        new Update()
                                .set("microserviceId",  mirror)
                                .set("gcsArchivePath",  gcsPath)
                                .set("archiveFileName", fileName)
                                .set("targetType",      "MCP_SERVER")
                                .set("updatedAt",       Instant.now()),
                        "codegen_results");
            }
            log.info("[codegen_results] mirrored MCP {} → {}", p.getId(), gcsPath);
        } catch (Exception ex) {
            log.warn("[codegen_results] mirror failed: {}", ex.getMessage());
        }
    }

    // --------------------------------------------------- Client configs
    public Map<String, Object> clientConfigs(McpProject p) {
        String slug = p.getIdentity() == null ? "mcp-server" : p.getIdentity().getSlug();
        String baseUrl = p.getTransport() == null || p.getTransport().getBaseUrl() == null
                ? "http://localhost:3500/mcp" : p.getTransport().getBaseUrl();
        String transport = p.getTransport() == null ? "streamable-http" : p.getTransport().getKind();
        boolean bearer = p.getAuth() != null && "bearer".equalsIgnoreCase(p.getAuth().getKind());
        String token = bearer && p.getAuth().getGeneratedToken() != null ? p.getAuth().getGeneratedToken() : "<your-token>";

        Map<String, Object> serverEntry = new LinkedHashMap<>();
        serverEntry.put("url", baseUrl);
        serverEntry.put("transport", transport);
        if (bearer) serverEntry.put("headers", Map.of("Authorization", "Bearer " + token));

        Map<String, Object> claude = Map.of("mcpServers", Map.of(slug, serverEntry));
        Map<String, Object> cursor = Map.of("mcpServers", Map.of(slug, serverEntry));
        Map<String, Object> forgeq = GeneratorUtilsProxy.manifest(p);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("claudeDesktop", claude);
        out.put("cursor", cursor);
        out.put("forgeq", forgeq);
        return out;
    }

    /** Tiny bridge so the service can reuse the manifest builder in GeneratorUtils. */
    static final class GeneratorUtilsProxy {
        static Map<String, Object> manifest(McpProject p) {
            return com.forgesphere.mcpgen.generator.GeneratorUtils.manifest(p);
        }
    }
}