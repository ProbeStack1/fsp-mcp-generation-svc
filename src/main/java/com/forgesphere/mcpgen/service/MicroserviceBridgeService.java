package com.forgesphere.mcpgen.service;

import com.forgesphere.mcpgen.bridge.BridgeGcsClient;
import com.forgesphere.mcpgen.bridge.BridgeProperties;
import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.repo.McpProjectRepository;
import com.mongodb.client.MongoCollection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MicroserviceBridgeService
 * ─────────────────────────
 * Mirrors a generated MCP server into the  api-development team's
 * shared schema (the SAME shape their own {@code generateCode} pipeline
 * writes), so the existing
 * {@code POST /api-development/v1/api-development/{microserviceId}/upload-to-github}
 * endpoint can publish it transparently — zero upstream changes.
 *
 * Three mirror docs are written, exactly matching the schema observed
 * on a real {@code codegen_results} document:
 *
 *   1. {@code codegen_results} — pointer + metadata only. The actual
 *      zip lives in GCS at {@code gcsArchivePath}; this doc only
 *      records the path. All fields below match the  schema
 *      verbatim ({@code generationId}, {@code artifactId},
 *      {@code projectPath}, {@code archivePath}, {@code sourceArchivePath},
 *      {@code gcsArchivePath}, {@code archiveDownloadUrl},
 *      {@code archiveFileName}, {@code archiveSizeBytes}, {@code status},
 *      {@code generationTimestamp}, {@code generatedFiles},
 *      {@code messages}, {@code _class}).
 *
 *   2. {@code microservice} — master record. Links back to row 1 via
 *      {@code codeGenResultId}. Carries onboarding snapshot + connector
 *      so  pipeline has all the metadata for the GitHub push.
 *
 *   3. {@code deployment_artifacts} — version label + env tracking.
 *
 * GCS layout:
 *   {@code gs://<bucket>/generated-artifacts/microservice/{microserviceId}/{YYYY-MM-DD}/{uuid}_{artifactId}.zip}
 *   identical to  pattern — the {@code microservice} folder is
 *   shared on purpose so a single signed-URL service-account works for
 *   both  normal artefacts and our MCP artefacts.
 *
 * Idempotency:
 *   On re-runs the three mirror {@code _id}s are reused so the docs are
 *   updated in place. The GCS object key is fresh on every push (new
 *   date folder / new UUID), so  pipeline always sees the
 *   latest zip — same behaviour as their normal flow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MicroserviceBridgeService {

    /** Marker stamped on every mirrored doc so audits can spot
     *  artefacts that came from the MCP wizard at a glance. */
    private static final String PROJECT_TYPE_MCP = "MCP_SERVER";

    /** {@code _class} discriminators — must match  mapper. */
    private static final String CLASS_CODEGEN_RESULT =
            "com.probestack.forgesphere.apidevelopment.model.CodeGenResult";
    private static final String CLASS_DEPLOYMENT_ARTIFACT =
            "com.probestack.forgesphere.apidevelopment.model.DeploymentArtifact";
    private static final String CLASS_MICROSERVICE =
            "com.probestack.forgesphere.apidevelopment.model.Microservice";

    /** Signed URL expiry —  uses 60 minutes. */
    private static final long SIGNED_URL_TTL_MINUTES = 60;

    private static final DateTimeFormatter DATE_FOLDER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MongoTemplate mongo;
    private final McpProjectRepository projects;
    private final McpGenerationService genSvc;
    private final BridgeGcsClient gcs;
    private final BridgeProperties props;

    // Same CICD profile service the microservice/proxy/kong pipeline reads
    // branch strategy from (fsp-api-development-svc's DeployService).
    @org.springframework.beans.factory.annotation.Value(
            "${cicd.service.url:https://forgesphere.probestack.io/cicd-automation/v1/api/cicd-config}")
    private String cicdServiceBaseUrl;

    /**
     * Returns the {@link MongoCollection} the bridge should write to,
     * honouring {@code forgesphere.bridge.coll.database} when set.
     *
     *  - Empty / null database  → fall back to {@link MongoTemplate#getCollection(String)}
     *    (writes to the template's own database, the legacy behaviour).
     *  - Non-empty database     → use the underlying factory to access
     *    a foreign DB so the  api-development service finds the
     *    mirrored docs even when this service's
     *    {@code spring.data.mongodb.database} points elsewhere.
     */
    private MongoCollection<org.bson.Document> bridgeColl(String collectionName) {
        final String db = props.getColl().getDatabase();
        if (db == null || db.isBlank()) {
            return mongo.getCollection(collectionName);
        }
        return mongo.getMongoDatabaseFactory().getMongoDatabase(db).getCollection(collectionName);
    }

    // ─────────── NEW: Early microservice creation ───────────────────────

    /**
     * Creates a minimal microservice record for the MCP project (without
     * any deployment artefact or zip). This allows the project to be used
     * with downstream services (mock, contract, test, etc.) immediately.
     *
     * @param project the MCP project
     * @return the id of the newly created (or existing) microservice record
     */
    public String createMicroserviceOnly(McpProject project) {
        String microserviceId = project.getMicroserviceMirrorId();
        if (microserviceId != null && !microserviceId.isBlank()) {
            // Already exists – just return it (idempotent)
            return microserviceId;
        }

        // Generate a fresh ObjectId for the microservice doc
        microserviceId = new ObjectId().toHexString();
        project.setMicroserviceMirrorId(microserviceId);

        // Build the microservice doc using the same logic as upsertMicroserviceDoc,
        // but without codeGenResultId or deployment fields.
        org.bson.Document doc = buildMicroserviceDocument(project, null);

        // Insert the doc
        bridgeColl(props.getColl().getMicroservice()).insertOne(doc);
        log.info("[bridge] Created microservice record {} for MCP project {}",
                microserviceId, project.getId());

        // Save the project with the new microserviceId
        projects.save(project);
        return microserviceId;
    }

    /**
     * Builds the microservice document from the MCP project data.
     * Reused by createMicroserviceOnly and upsertMicroserviceDoc.
     */
    private org.bson.Document buildMicroserviceDocument(McpProject project, String codeGenResultId) {
        var ob = project.getOnboarding();
        var id = project.getIdentity();
        String organizationId = resolveOrganizationId(project);
        Date now = Date.from(Instant.now());

        org.bson.Document doc = new org.bson.Document();
        doc.put("_id", parseIdMaybe(project.getMicroserviceMirrorId()));
        doc.put("projectType", PROJECT_TYPE_MCP);
        doc.put("organizationId", organizationId);
        doc.put("businessUnit", ob == null ? null : ob.getBusinessUnit());
        doc.put("teamName", ob == null ? null : ob.getTeamName());
        doc.put("applicationName", ob == null ? null : ob.getApplicationName());
        doc.put("applicationId", ob == null ? null : ob.getApplicationId());
        doc.put("onboardingId", project.getOnboardingId());
        doc.put("apiName", id == null ? null : id.getDisplayName());
        doc.put("projectOwner", ob == null ? null : ob.getProjectOwner());
        doc.put("ownerEmail", ob == null ? null : ob.getOwnerEmail());
        doc.put("projectSME", ob == null ? null : ob.getProjectSME());
        doc.put("projectSMEEmail", ob == null ? null : ob.getProjectSMEEmail());
        doc.put("projectDLEmail", ob == null ? null : ob.getProjectDLEmail());
        doc.put("expectedGoLiveDate", ob == null ? null : ob.getExpectedGoLiveDate());
        doc.put("testerName", ob == null ? null : ob.getTesterName());
        doc.put("testerEmail", ob == null ? null : ob.getTesterEmail());
        doc.put("serviceNowGroupName", ob == null ? null : ob.getServiceNowGroupName());
        doc.put("serviceNowEmail", ob == null ? null : ob.getServiceNowEmail());
        doc.put("consumerIds", ob == null || ob.getConsumerIds() == null
                ? List.of() : ob.getConsumerIds());
        doc.put("connectorId", resolveConnectorId(project));
        doc.put("mcpProjectId", project.getId());
        doc.put("mcpSlug", id == null ? null : id.getSlug());
        doc.put("createdAt", now);
        doc.put("updatedAt", now);
        doc.put("_class", CLASS_MICROSERVICE);

        if (codeGenResultId != null && !codeGenResultId.isBlank()) {
            doc.put("codeGenResultId", codeGenResultId);
        }
        return doc;
    }

    /**
     * Public entry point. Returns the bridge identifiers so the caller
     * can chain the existing {@code uploadToGitHub(microserviceId)} call.
     */
    public Map<String, Object> mirror(McpProject project) {
        // 1) Generate on demand — push pipelines expect a non-empty artefact.
        McpProject p = project;
        if (p.getGenerated() == null
                || p.getGenerated().getFiles() == null
                || p.getGenerated().getFiles().isEmpty()) {
            log.info("[bridge] project={} has no generated files — running generator", p.getId());
            p = genSvc.generate(p.getId());
        }
        final McpProject mcp = p;
        final int fileCount = mcp.getGenerated().getFiles().size();

        // 2) Reuse mirror ids if we've published this project before so
        //    revision pushes target the SAME microservice row.
        String microserviceId   = mcp.getMicroserviceMirrorId() != null
                ? mcp.getMicroserviceMirrorId() : new ObjectId().toHexString();
        String artifactId       = mcp.getDeploymentArtifactId() != null
                ? mcp.getDeploymentArtifactId() : new ObjectId().toHexString();
        String codeGenResultId  = mcp.getCodeGenResultId() != null
                ? mcp.getCodeGenResultId() : new ObjectId().toHexString();
        // Fresh generationId + UUID on every push so the GCS key never collides.
        String generationId = UUID.randomUUID().toString();
        String fileUuid     = UUID.randomUUID().toString();
        String slug = mcp.getIdentity() != null && mcp.getIdentity().getSlug() != null
                ? mcp.getIdentity().getSlug() : "mcp-server";

        log.info("[bridge] mirroring project={} → microserviceId={} artifactId={} codeGenResultId={} files={}",
                mcp.getId(), microserviceId, artifactId, codeGenResultId, fileCount);

        // 3) Build zip + upload to GCS ( exact path pattern).
        byte[] zipBytes = buildZipBytes(mcp);
        String archiveFileName = slug + ".zip";
        String dateFolder = LocalDate.now(ZoneOffset.UTC).format(DATE_FOLDER);
        String objectKey = microserviceId + "/" + dateFolder + "/" + fileUuid + "_" + archiveFileName;
        String gcsObjectPath = gcs.upload(objectKey, zipBytes);
        String gcsArchivePath = "gs://" + gcs.getBucket() + "/" + gcsObjectPath;
        String archiveDownloadUrl = gcs.signedUrl(gcsObjectPath, SIGNED_URL_TTL_MINUTES);
        log.info("[bridge] uploaded zip to {} bytes={}", gcsArchivePath, zipBytes.length);

        // 4) Write the three mirror docs (codegen_results FIRST so the
        //    microservice doc can reference it).
        upsertCodegenResults(mcp, microserviceId, codeGenResultId, generationId, slug,
                gcsArchivePath, archiveDownloadUrl, archiveFileName, zipBytes.length, fileUuid);
        upsertMicroserviceDoc(mcp, microserviceId, codeGenResultId);
        upsertDeploymentArtifact(mcp, microserviceId, artifactId);

        // 5) Persist mirror state on our doc for idempotency.
        mcp.setMicroserviceMirrorId(microserviceId);
        mcp.setDeploymentArtifactId(artifactId);
        mcp.setCodeGenResultId(codeGenResultId);
        mcp.setGcsArchivePath(gcsArchivePath);
        mcp.setMirroredAt(Instant.now());
        if (mcp.getId() != null) projects.save(mcp);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("microserviceId",       microserviceId);
        out.put("deploymentArtifactId", artifactId);
        out.put("codeGenResultId",      codeGenResultId);
        out.put("generationId",         generationId);
        out.put("gcsBucket",            gcs.getBucket());
        out.put("gcsArchivePath",       gcsArchivePath);
        out.put("archiveDownloadUrl",   archiveDownloadUrl);
        out.put("archiveFileName",      archiveFileName);
        out.put("archiveSizeBytes",     zipBytes.length);
        out.put("fileCount",            fileCount);
        log.info("[bridge] mirror complete project={} microserviceId={}", mcp.getId(), microserviceId);
        return out;
    }

    /**
     * Push the generated MCP files DIRECTLY to the user's GitHub repo
     * using the token stored on their saved connector. We bypass the
     *  api-development service's "trigger workflow_dispatch"
     * model because that model assumes a template-repo pattern owned
     * by the  team; it doesn't work for "push to MY own repo".
     *
     *  - Reads `orgOrUser` / `repo` / `branch` / `token` from the
     *    connector doc that {@code mcpProject.connectorId} (or the
     *    bridge's connector-by-org fallback) resolves to.
     *  - Walks {@code mcpProject.generated.files} and posts each one
     *    via {@code PUT /repos/{owner}/{repo}/contents/{path}}.
     *    Existing files get updated (sha is fetched first); new files
     *    get created. This is the only GitHub API call that works for
     *    a brand-new EMPTY repo (Git Data API requires at least one
     *    commit to start from).
     *  - Returns a map with the resulting repo URL + per-file results
     *    so the UI can surface "Pushed N files to {repo}".
     *
     *  Best-effort: a single file failure does NOT abort the loop —
     *  we collect errors and return them so the user can see which
     *  files landed and which didn't. This avoids the worst-case
     *  "partial push, repo half-empty" support story.
     */
    public Map<String, Object> pushToGitHub(McpProject project) {
        McpProject p = project;
        // ALWAYS regenerate before push so any backend-template fix
        // (workflow YAML / Dockerfile / package.json) lands in the
        // pushed code. Without this, edits made through the wizard
        // wouldn't reach the repo because the McpProject.generated.files
        // cache pre-dates them. Idempotent — `generate(id)` overwrites
        // the cached zip.
        if (p.getId() != null) {
            log.info("[push] regenerating files before push project={}", p.getId());
            p = genSvc.generate(p.getId());
        } else if (p.getGenerated() == null
                || p.getGenerated().getFiles() == null
                || p.getGenerated().getFiles().isEmpty()) {
            log.info("[push] project has no id + no files - running inline generator");
            p = genSvc.generate(p.getId());
        }
        final McpProject mcp = p;

        // ─── Resolve connector → GitHub credentials ──────────────────
        String connectorId = resolveConnectorId(mcp);
        if (connectorId == null) {
            throw new IllegalStateException(
                "No GitHub connector found for this MCP. Open onboarding and save a connector first.");
        }
        org.bson.Document conn;
        try {
            conn = bridgeColl(props.getColl().getConnector())
                    .find(new org.bson.Document("_id", parseIdMaybe(connectorId)))
                    .first();
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't load connector " + connectorId + ": " + e.getMessage(), e);
        }
        if (conn == null) {
            throw new IllegalStateException("Connector " + connectorId + " not found.");
        }
        org.bson.Document scm = conn.get("sourceCodeManagement", org.bson.Document.class);
        if (scm == null) {
            throw new IllegalStateException("Connector " + connectorId + " has no sourceCodeManagement block.");
        }
        String token     = scm.getString("token");
        String orgOrUser = scm.getString("orgOrUser");
        String branch    = scm.getString("branch");
        if (token == null || token.isBlank()) throw new IllegalStateException("Connector has no GitHub token.");
        if (orgOrUser == null || orgOrUser.isBlank()) throw new IllegalStateException("Connector has no orgOrUser.");
        if (branch == null || branch.isBlank()) branch = "main";

        // ─── Resolve repo name — collision-safe, per-PROJECT ──────────
        // Two rules:
        //   1. This exact project already pushed before → ALWAYS reuse
        //      the same repo it used last time (a regenerate must land
        //      back in the same place), regardless of what the connector
        //      or derivation would produce now.
        //   2. First-ever push for this project → derive a candidate
        //      name (connector's explicit repo if set, else the
        //      project's slug/displayName/id) and check GitHub for a
        //      collision. A connector is often SHARED across multiple
        //      MCP projects, so persisting one project's derived name
        //      onto the shared connector (the old behaviour) meant a
        //      second, unrelated service with the same/derived name
        //      would silently land inside the FIRST project's repo. If
        //      the candidate is already taken, keep appending "-2",
        //      "-3", … until a free name is found — never push into an
        //      existing repo this project didn't itself create.
        String repo;
        if (mcp.getPushedRepoFullName() != null && !mcp.getPushedRepoFullName().isBlank()) {
            String[] parts = mcp.getPushedRepoFullName().split("/", 2);
            repo = parts.length == 2 ? parts[1] : mcp.getPushedRepoFullName();
            log.info("[push] project {} already has a repo — reusing '{}'", mcp.getId(), repo);
        } else {
            String explicit = scm.getString("repo");
            String candidate = (explicit != null && !explicit.isBlank())
                    ? explicit : deriveRepoNameFromProject(mcp);
            repo = resolveCollisionFreeRepoName(orgOrUser, candidate, token);
            if (!repo.equals(candidate)) {
                log.info("[push] '{}' already exists under {} — using '{}' instead so we don't overwrite an unrelated repo",
                        candidate, orgOrUser, repo);
            } else {
                log.info("[push] resolved repo name '{}' for project {}", repo, mcp.getId());
            }
        }

        log.info("[push] target=https://github.com/{}/{} branch={} files={}",
                orgOrUser, repo, branch, mcp.getGenerated().getFiles().size());

        // ─── Make sure the repo exists ──────────────────────────────
        // GitHub returns 404 if the repo isn't there. Create it under
        // the user's account when missing. We can't reliably know if
        // `orgOrUser` is a user or an org from the connector, so we
        // try the user endpoint first and fall back to the org one on
        // 404 — same heuristic the GitHub CLI uses.
        ensureRepoExists(orgOrUser, repo, branch, token, scm);

        // ─── Push ALL files in a SINGLE commit (one workflow run) ───
        // Per-file `PUT /contents/{path}` creates one commit per file, which
        // means a fresh project with 10 files triggers 10 GitHub Actions
        // runs (one per push). Switch to the Git Data API so all files
        // land in ONE atomic commit → ONE workflow run, regardless of
        // file count.
        //
        // Strategy:
        //   1. resolve the branch head SHA (repo is guaranteed to exist
        //      with an init commit thanks to `ensureRepoExists` above)
        //   2. POST /git/blobs for every file body (base64)
        //   3. POST /git/trees with base_tree=headSha + entries pointing
        //      at the new blobs
        //   4. POST /git/commits referencing the new tree + parent=head
        //   5. PATCH /git/refs/heads/{branch} → fast-forward to new commit
        //
        // Falls back to per-file PUT only if a step in the batched flow
        // hits an unrecoverable error (network, 5xx, etc.) so the demo
        // never silently fails.
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.util.List<Map<String, Object>> pushedFiles = new java.util.ArrayList<>();
        java.util.List<Map<String, Object>> failedFiles = new java.util.ArrayList<>();
        String commitMessage = "Push from ForgeSphere MCP wizard";
        String headCommitSha = null;
        try {
            headCommitSha = pushAllFilesInOneCommit(http, orgOrUser, repo, branch,
                    mcp.getGenerated().getFiles(), token, commitMessage);
            for (var gf : mcp.getGenerated().getFiles()) {
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("path", gf.getPath());
                ok.put("status", 201);
                pushedFiles.add(ok);
            }
            log.info("[push] batched commit succeeded: {} files in 1 commit (sha={})",
                    pushedFiles.size(), headCommitSha);
        } catch (Exception batchEx) {
            log.warn("[push] batched commit failed — falling back to per-file PUT: {}", batchEx.getMessage());
            pushedFiles.clear();
            for (var gf : mcp.getGenerated().getFiles()) {
                String path = gf.getPath();
                String content = gf.getContent() == null ? "" : gf.getContent();
                try {
                    Map<String, Object> ok = putFile(http, orgOrUser, repo, branch, path, content, token, commitMessage);
                    pushedFiles.add(ok);
                } catch (Exception e) {
                    log.warn("[push] {} → {} FAILED: {}", path, repo, e.getMessage());
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("path", path);
                    err.put("error", e.getMessage());
                    failedFiles.add(err);
                }
            }
        }

        // ─── Create the rest of the branch strategy (staging/main/etc.) ─
        // Same idea as the microservice/proxy/kong pipeline's
        // `branches_to_create` — read the org's default CICD strategy and
        // fan the pushed commit out to every non-dev branch so this repo
        // ends up with the same multi-branch layout, instead of the code
        // only ever living on the one branch the connector points at.
        // Best-effort: a CICD-profile hiccup should never fail the push
        // itself, since the primary branch already has the code.
        try {
            List<String> branchesToCreate = fetchBranchesToCreate(mcp.getOnboardingId());
            for (String extraBranch : branchesToCreate) {
                if (extraBranch == null || extraBranch.isBlank() || extraBranch.equalsIgnoreCase(branch)) continue;
                ensureBranchExists(http, orgOrUser, repo, extraBranch, branch, token);
            }
            if (!branchesToCreate.isEmpty()) {
                log.info("[push] created/verified branch strategy for {}/{}: {}", orgOrUser, repo, branchesToCreate);
            }
        } catch (Exception e) {
            log.warn("[push] couldn't apply CICD branch strategy for project {}: {}", mcp.getId(), e.getMessage());
        }

        // ─── Persist push state on our McpProject doc ────────────────
        String repoUrl = "https://github.com/" + orgOrUser + "/" + repo;
        mcp.setPushedRepoFullName(orgOrUser + "/" + repo);
        mcp.setPushedRepoUrl(repoUrl);
        mcp.setPushedBranch(branch);
        mcp.setPushedFileCount(pushedFiles.size());
        mcp.setPushedAt(Instant.now());
        if (headCommitSha != null && !headCommitSha.isBlank()) {
            mcp.setPushedCommitSha(headCommitSha);
        }
        if (mcp.getId() != null) projects.save(mcp);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repoFullName", orgOrUser + "/" + repo);
        out.put("repoUrl",      repoUrl);
        out.put("branch",       branch);
        out.put("pushedCount",  pushedFiles.size());
        out.put("failedCount",  failedFiles.size());
        out.put("pushedFiles",  pushedFiles);
        out.put("failedFiles",  failedFiles);
        if (headCommitSha != null) out.put("commitSha", headCommitSha);
        log.info("[push] done project={} pushed={} failed={}",
                mcp.getId(), pushedFiles.size(), failedFiles.size());
        return out;
    }

    /**
     * Push every generated file in a SINGLE atomic commit using GitHub's
     * Git Data API. Returns the new commit SHA on success or throws on
     * the first unrecoverable error so the caller can fall back to the
     * legacy per-file PUT path.
     *
     * Wire sequence (all calls go to api.github.com with the connector token):
     *   1. GET  /repos/{o}/{r}/git/ref/heads/{branch}      — current ref
     *   2. POST /repos/{o}/{r}/git/blobs                   — once per file
     *   3. POST /repos/{o}/{r}/git/trees                   — one tree, base_tree=headSha
     *   4. POST /repos/{o}/{r}/git/commits                 — one commit
     *   5. PATCH /repos/{o}/{r}/git/refs/heads/{branch}    — fast-forward
     */
    private String pushAllFilesInOneCommit(java.net.http.HttpClient http,
                                           String orgOrUser, String repo, String branch,
                                           java.util.List<McpProject.GeneratedFile> files,
                                           String token, String commitMessage) throws Exception {
        String apiRoot = "https://api.github.com/repos/" + orgOrUser + "/" + repo;

        // (1) current head — repo was already initialised by ensureRepoExists()
        var refReq = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(apiRoot + "/git/ref/heads/" + branch))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        var refResp = http.send(refReq, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (refResp.statusCode() != 200) {
            throw new IllegalStateException("git/ref/heads/" + branch + " returned " + refResp.statusCode() + ": " + refResp.body());
        }
        String headSha = extractFirstJsonString(refResp.body(), "\"sha\":\"");
        if (headSha == null) throw new IllegalStateException("Could not parse head SHA from ref response");

        // (2) one blob per file
        java.util.List<String[]> blobs = new java.util.ArrayList<>(); // [path, blobSha]
        for (var gf : files) {
            String content = gf.getContent() == null ? "" : gf.getContent();
            String b64 = java.util.Base64.getEncoder().encodeToString(
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String blobBody = "{\"content\":\"" + b64 + "\",\"encoding\":\"base64\"}";
            var blobReq = java.net.http.HttpRequest.newBuilder(java.net.URI.create(apiRoot + "/git/blobs"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(blobBody))
                    .build();
            var blobResp = http.send(blobReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (blobResp.statusCode() != 201 && blobResp.statusCode() != 200) {
                throw new IllegalStateException("git/blobs failed for " + gf.getPath() + ": "
                        + blobResp.statusCode() + " " + blobResp.body());
            }
            String blobSha = extractFirstJsonString(blobResp.body(), "\"sha\":\"");
            if (blobSha == null) throw new IllegalStateException("blob sha missing for " + gf.getPath());
            blobs.add(new String[]{ gf.getPath(), blobSha });
        }

        // (3) tree referencing every blob, layered on top of current head
        StringBuilder tree = new StringBuilder("{\"base_tree\":\"")
                .append(headSha).append("\",\"tree\":[");
        for (int i = 0; i < blobs.size(); i++) {
            if (i > 0) tree.append(",");
            tree.append("{\"path\":\"").append(escapeJson(blobs.get(i)[0]))
                .append("\",\"mode\":\"100644\",\"type\":\"blob\",\"sha\":\"")
                .append(blobs.get(i)[1]).append("\"}");
        }
        tree.append("]}");
        var treeReq = java.net.http.HttpRequest.newBuilder(java.net.URI.create(apiRoot + "/git/trees"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(tree.toString()))
                .build();
        var treeResp = http.send(treeReq, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (treeResp.statusCode() != 201 && treeResp.statusCode() != 200) {
            throw new IllegalStateException("git/trees failed: " + treeResp.statusCode() + " " + treeResp.body());
        }
        String newTreeSha = extractFirstJsonString(treeResp.body(), "\"sha\":\"");
        if (newTreeSha == null) throw new IllegalStateException("tree sha missing");

        // (4) commit pointing at the new tree, parent = current head
        String commitBody = "{\"message\":\"" + escapeJson(commitMessage) + "\","
                + "\"tree\":\"" + newTreeSha + "\","
                + "\"parents\":[\"" + headSha + "\"]}";
        var commitReq = java.net.http.HttpRequest.newBuilder(java.net.URI.create(apiRoot + "/git/commits"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(commitBody))
                .build();
        var commitResp = http.send(commitReq, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (commitResp.statusCode() != 201 && commitResp.statusCode() != 200) {
            throw new IllegalStateException("git/commits failed: " + commitResp.statusCode() + " " + commitResp.body());
        }
        String newCommitSha = extractFirstJsonString(commitResp.body(), "\"sha\":\"");
        if (newCommitSha == null) throw new IllegalStateException("commit sha missing");

        // (5) fast-forward the ref to our new commit
        String patchBody = "{\"sha\":\"" + newCommitSha + "\"}";
        var patchReq = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(apiRoot + "/git/refs/heads/" + branch))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(patchBody))
                .build();
        var patchResp = http.send(patchReq, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (patchResp.statusCode() != 200) {
            throw new IllegalStateException("git/refs PATCH failed: " + patchResp.statusCode() + " " + patchResp.body());
        }
        return newCommitSha;
    }

    /** Find the value of the FIRST occurrence of {@code "key":"…"} in a
     *  JSON blob. Good enough for the handful of GitHub Data API fields
     *  we read here (sha, ref) without dragging in a JSON parser. */
    private static String extractFirstJsonString(String body, String needle) {
        if (body == null) return null;
        int idx = body.indexOf(needle);
        if (idx < 0) return null;
        int start = idx + needle.length();
        int end = body.indexOf('"', start);
        if (end < start) return null;
        return body.substring(start, end);
    }

    /**
     * Create the GitHub repo if it doesn't exist yet. Empty new repos
     * have no default branch, so we also initialise `branch` with an
     * empty README so subsequent PUT /contents calls don't 404 on
     * "ref not found".
     *
     * Smart owner-resolution:
     *   1. GET /user → find the authenticated user (token owner).
     *   2. If `orgOrUser` == token user → POST /user/repos (creates under user).
     *   3. Otherwise → POST /orgs/{orgOrUser}/repos (creates under the org).
     *   4. After auto_init, GitHub returns the default branch name (usually
     *      "main"). If the connector's saved `branch` differs, we create
     *      that branch from the default's HEAD so subsequent file PUTs
     *      target the correct ref.
     *   5. Poll until the branch ref is queryable (auto_init is async).
     */
    /**
     * Repo name to use when the connector doesn't have one configured —
     * prefers the project's slug (already a clean, GitHub-safe identifier:
     * lowercase, hyphenated, no spaces — see the frontend's slug generator),
     * falls back to sanitising the display name, and finally to a
     * guaranteed-unique "mcp-server-{id}" so this never blocks a push.
     */
    private String deriveRepoNameFromProject(McpProject mcp) {
        McpProject.Identity id = mcp.getIdentity();
        if (id != null && id.getSlug() != null && !id.getSlug().isBlank()) {
            return id.getSlug().trim();
        }
        if (id != null && id.getDisplayName() != null && !id.getDisplayName().isBlank()) {
            String sanitised = id.getDisplayName().toLowerCase().trim()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (!sanitised.isBlank()) return sanitised;
        }
        String suffix = mcp.getId() != null ? mcp.getId() : UUID.randomUUID().toString();
        return "mcp-server-" + suffix;
    }

    /**
     * Returns {@code candidate} unchanged if no repo by that name exists
     * yet under {@code orgOrUser}; otherwise keeps appending "-2", "-3", …
     * until it finds a free one. Best-effort: if the existence check
     * itself fails (network hiccup), treats the name as free rather than
     * blocking the push — worst case is the ordinary "repo already
     * exists, reuse it" path in {@link #ensureRepoExists}, not data loss.
     */
    private String resolveCollisionFreeRepoName(String orgOrUser, String candidate, String token) {
        String base = (candidate == null || candidate.isBlank()) ? "mcp-server" : candidate.trim();
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        String name = base;
        for (int n = 2; n <= 50; n++) {
            if (!repoExistsOnGithub(http, orgOrUser, name, token)) return name;
            name = base + "-" + n;
        }
        // Extremely unlikely (50 collisions in a row) — fall back to a
        // guaranteed-unique suffix rather than looping forever.
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private boolean repoExistsOnGithub(java.net.http.HttpClient http, String orgOrUser, String repo, String token) {
        try {
            var get = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("https://api.github.com/repos/" + orgOrUser + "/" + repo))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var resp = http.send(get, java.net.http.HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            log.warn("[push] repo-exists check failed for {}/{}: {}", orgOrUser, repo, e.getMessage());
            return false;
        }
    }

    private void ensureRepoExists(String orgOrUser, String repo, String branch, String token, org.bson.Document scm) {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

        // ─── 1. Check if repo already exists ──────────────────────────
        boolean repoExists = false;
        String existingDefaultBranch = null;
        try {
            var get = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("https://api.github.com/repos/" + orgOrUser + "/" + repo))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var resp = http.send(get, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                repoExists = true;
                int idx = resp.body().indexOf("\"default_branch\":\"");
                if (idx > -1) {
                    int start = idx + 18;
                    int end = resp.body().indexOf('"', start);
                    if (end > start) existingDefaultBranch = resp.body().substring(start, end);
                }
                log.info("[push] repo {}/{} already exists (default_branch={})",
                        orgOrUser, repo, existingDefaultBranch);
            } else if (resp.statusCode() != 404) {
                log.warn("[push] repo lookup non-200/404: {} {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("[push] repo lookup failed (will try to create): {}", e.getMessage());
        }

        // ─── 2. If repo exists, ensure the target branch exists ───────
        if (repoExists) {
            ensureBranchExists(http, orgOrUser, repo, branch, existingDefaultBranch, token);
            return;
        }

        // ─── 3. Repo doesn't exist — create it under correct owner ────
        // Find the authenticated user so we know which create endpoint
        // to hit. /user/repos creates under the token owner regardless
        // of the "name" you pass, so we MUST use /orgs/{org}/repos when
        // the target is an org.
        String authenticatedUser = null;
        try {
            var who = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("https://api.github.com/user"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var resp = http.send(who, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                int idx = resp.body().indexOf("\"login\":\"");
                if (idx > -1) {
                    int start = idx + 9;
                    int end = resp.body().indexOf('"', start);
                    if (end > start) authenticatedUser = resp.body().substring(start, end);
                }
            }
        } catch (Exception e) {
            log.warn("[push] /user lookup failed: {}", e.getMessage());
        }

        boolean isPrivate = scm.containsKey("isPrivate")
                ? Boolean.TRUE.equals(scm.getBoolean("isPrivate")) : false;
        String createBody = "{\"name\":\"" + repo + "\",\"private\":" + isPrivate
                + ",\"auto_init\":true,\"description\":\"MCP server generated by ForgeSphere\"}";
        boolean targetIsAuthenticatedUser = orgOrUser != null
                && authenticatedUser != null
                && orgOrUser.equalsIgnoreCase(authenticatedUser);

        boolean created = false;
        if (targetIsAuthenticatedUser) {
            created = tryCreate(http, "https://api.github.com/user/repos", createBody, token, orgOrUser, repo);
        } else {
            // Target is an org (or different user) — must use org endpoint
            created = tryCreate(http, "https://api.github.com/orgs/" + orgOrUser + "/repos",
                    createBody, token, orgOrUser, repo);
            if (!created) {
                // Fallback: maybe it's actually a user the token can write to (collaborator scenario)
                log.info("[push] org create failed, falling back to /user/repos");
                created = tryCreate(http, "https://api.github.com/user/repos", createBody, token, orgOrUser, repo);
            }
        }
        if (!created) {
            throw new IllegalStateException("Couldn't create GitHub repo " + orgOrUser + "/" + repo
                    + " (token user=" + authenticatedUser + "). Make sure the PAT has 'repo' scope"
                    + " and (if the target is an org) 'admin:org' / org membership.");
        }

        // ─── 4. Wait for auto_init commit, then create target branch ──
        // GitHub auto_init creates `main` (or `master` for legacy accounts).
        // We poll for the default branch to be ready, then if the user
        // wants a different branch, fork it off the default.
        String newRepoDefaultBranch = waitForDefaultBranch(http, orgOrUser, repo, token);
        if (newRepoDefaultBranch == null) {
            // Last-resort: assume "main"
            newRepoDefaultBranch = "main";
        }
        ensureBranchExists(http, orgOrUser, repo, branch, newRepoDefaultBranch, token);
    }

    private boolean tryCreate(java.net.http.HttpClient http, String url, String body,
                              String token, String orgOrUser, String repo) {
        try {
            var create = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var resp = http.send(create, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 201) {
                log.info("[push] created repo {}/{} via {}", orgOrUser, repo, url);
                return true;
            }
            log.warn("[push] create via {} returned {}: {}", url, resp.statusCode(),
                    resp.body() != null && resp.body().length() > 240 ? resp.body().substring(0, 240) : resp.body());
            return false;
        } catch (Exception e) {
            log.warn("[push] create via {} failed: {}", url, e.getMessage());
            return false;
        }
    }

    /**
     * Poll the freshly-created repo until its initial commit is queryable.
     * GitHub's auto_init is asynchronous — file PUTs can 404 with
     * "ref not found" for ~1-2s after the create call returns 201.
     * Returns the default branch name once ready, or null on timeout.
     */
    private String waitForDefaultBranch(java.net.http.HttpClient http, String orgOrUser, String repo, String token) {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                var get = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("https://api.github.com/repos/" + orgOrUser + "/" + repo))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github+json")
                        .GET().build();
                var resp = http.send(get, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    int idx = resp.body().indexOf("\"default_branch\":\"");
                    if (idx > -1) {
                        int start = idx + 18;
                        int end = resp.body().indexOf('"', start);
                        if (end > start) {
                            String defBranch = resp.body().substring(start, end);
                            // Confirm the branch ref is queryable
                            var refReq = java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create("https://api.github.com/repos/" + orgOrUser + "/" + repo
                                            + "/git/refs/heads/" + defBranch))
                                    .header("Authorization", "Bearer " + token)
                                    .header("Accept", "application/vnd.github+json")
                                    .GET().build();
                            var refResp = http.send(refReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                            if (refResp.statusCode() == 200) {
                                log.info("[push] default branch {} ready on {}/{} (attempt {})",
                                        defBranch, orgOrUser, repo, attempt + 1);
                                return defBranch;
                            }
                        }
                    }
                }
            } catch (Exception ignored) { /* retry */ }
            try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
        }
        log.warn("[push] default branch not ready on {}/{} after 10 attempts", orgOrUser, repo);
        return null;
    }

    /**
     * Ensure the user-requested `branch` exists in the repo. If it
     * doesn't, fork it from `sourceBranch` (usually the repo default).
     */
    /**
     * Reads the org's default CICD branch strategy (same endpoint/shape
     * {@code fsp-api-development-svc}'s DeployService.buildBranchesToCreate
     * reads for microservice/proxy/kong) and returns every branch name
     * except the "dev" one — that one is the push target itself, passed
     * separately as {@code branch} at the call site.
     *
     * @param onboardingId the McpProject's linked onboarding id; if null/
     *                      blank or the CICD call fails, returns an empty
     *                      list so the caller's best-effort wrapper just
     *                      skips branch creation rather than failing.
     */
    private List<String> fetchBranchesToCreate(String onboardingId) {
        if (onboardingId == null || onboardingId.isBlank()) return List.of();
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
            var req = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(cicdServiceBaseUrl + "/" + onboardingId + "/all?filtered=true"))
                    .header("Accept", "application/json")
                    .GET().build();
            var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[push] CICD config lookup for onboarding {} returned {}", onboardingId, resp.statusCode());
                return List.of();
            }
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(resp.body());
            com.fasterxml.jackson.databind.JsonNode strategies = root.path("data").has("strategies")
                    ? root.path("data").path("strategies") : root.path("strategies");
            if (!strategies.isArray() || strategies.isEmpty()) return List.of();

            com.fasterxml.jackson.databind.JsonNode defaultStrategy = null;
            for (var s : strategies) {
                if (s.path("isDefault").asBoolean(false)) { defaultStrategy = s; break; }
            }
            if (defaultStrategy == null) defaultStrategy = strategies.get(0);

            List<String> result = new ArrayList<>();
            for (var b : defaultStrategy.path("branches")) {
                String tag = b.path("tag").asText(null);
                String name = b.path("name").asText(null);
                if ("dev".equals(tag) || name == null || name.isBlank()) continue;
                result.add(name);
            }
            return result;
        } catch (Exception e) {
            log.warn("[push] failed to fetch CICD branch strategy for onboarding {}: {}", onboardingId, e.getMessage());
            return List.of();
        }
    }

    private void ensureBranchExists(java.net.http.HttpClient http, String orgOrUser, String repo,
                                    String branch, String sourceBranch, String token) {
        if (branch == null || branch.isBlank()) return;
        // Check if branch already exists
        try {
            var get = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("https://api.github.com/repos/" + orgOrUser + "/" + repo
                            + "/git/refs/heads/" + branch))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var resp = http.send(get, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return; // already exists
        } catch (Exception ignored) { /* will try to create */ }

        if (sourceBranch == null || sourceBranch.equalsIgnoreCase(branch)) {
            log.info("[push] branch {} not present and no source to fork from — file PUTs will create it",
                    branch);
            return;
        }

        // Get source branch's HEAD sha
        String sourceSha = null;
        try {
            var get = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("https://api.github.com/repos/" + orgOrUser + "/" + repo
                            + "/git/refs/heads/" + sourceBranch))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var resp = http.send(get, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                int idx = resp.body().indexOf("\"sha\":\"");
                if (idx > -1) {
                    int start = idx + 7;
                    int end = resp.body().indexOf('"', start);
                    if (end > start) sourceSha = resp.body().substring(start, end);
                }
            }
        } catch (Exception e) {
            log.warn("[push] couldn't get source branch HEAD: {}", e.getMessage());
        }
        if (sourceSha == null) {
            log.warn("[push] no source SHA for {} — leaving branch creation to file PUTs", sourceBranch);
            return;
        }

        // Create the target branch
        try {
            String body = "{\"ref\":\"refs/heads/" + branch + "\",\"sha\":\"" + sourceSha + "\"}";
            var create = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("https://api.github.com/repos/" + orgOrUser + "/" + repo + "/git/refs"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var resp = http.send(create, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 201) {
                log.info("[push] created branch {} from {} on {}/{}",
                        branch, sourceBranch, orgOrUser, repo);
            } else {
                log.warn("[push] branch create returned {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("[push] branch create failed: {}", e.getMessage());
        }
    }

    /**
     * Upsert a single file at `path` in the user's repo using the
     * Contents API. Handles the case where the file already exists by
     * fetching its current SHA and resending it on the PUT.
     */
    private Map<String, Object> putFile(java.net.http.HttpClient http,
                                        String orgOrUser, String repo, String branch,
                                        String path, String content, String token, String commitMessage)
            throws Exception {
        // Encode each path segment separately so '/' stays a separator
        // but spaces and special chars become %xx (URLEncoder is for query
        // strings — its '+' for space is invalid in path segments).
        StringBuilder encodedPath = new StringBuilder();
        boolean first = true;
        for (String seg : path.split("/")) {
            if (!first) encodedPath.append("/");
            encodedPath.append(java.net.URLEncoder.encode(seg, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20"));
            first = false;
        }
        String url = "https://api.github.com/repos/" + orgOrUser + "/" + repo + "/contents/" + encodedPath;

        // 1) Try to GET the existing file to grab its sha.
        String sha = null;
        try {
            var get = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(url + "?ref=" + branch))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var resp = http.send(get, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                int idx = resp.body().indexOf("\"sha\":\"");
                if (idx > -1) {
                    int start = idx + 7;
                    int end   = resp.body().indexOf('"', start);
                    if (end > start) sha = resp.body().substring(start, end);
                }
            }
        } catch (Exception ignored) { /* treat as new file */ }

        String b64 = java.util.Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder body = new StringBuilder("{");
        body.append("\"message\":\"").append(escapeJson(commitMessage)).append("\",");
        body.append("\"content\":\"").append(b64).append("\",");
        body.append("\"branch\":\"").append(branch).append("\"");
        if (sha != null) body.append(",\"sha\":\"").append(sha).append("\"");
        body.append("}");

        var put = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        var resp = http.send(put, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 && resp.statusCode() != 201) {
            throw new IllegalStateException("GitHub PUT " + path + " returned " + resp.statusCode() + ": " + resp.body());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("updated", sha != null);
        out.put("status", resp.statusCode());
        return out;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Mongo {@code _id} interop with Spring Data lookups.
     *
     *  The  api-development service writes microservice docs via
     *  Spring Data, which stores {@code _id} as a real BSON {@code ObjectId}.
     *  Its {@code findById(String)} consumer then casts the input
     *  string to {@code ObjectId} before the query.  If the bridge had
     *  inserted our doc with a plain Java {@link String} {@code _id}
     *  (which the raw driver does by default — that's what produced
     *  the original 404 ‟Microservice not found with id: …" error),
     *  the cast wouldn't match and the lookup would fail.
     *
     *  Returning an {@link ObjectId} when the hex string is 24 hex
     *  chars makes our inserts compatible with the  service's
     *  Spring Data lookup AND keeps our update queries matching the
     *  rows we just wrote.  Non-hex ids (e.g. UUIDs that pre-date this
     *  fix) keep their original String value so existing rows still
     *  resolve.
     */
    private static Object parseIdMaybe(String raw) {
        if (raw != null && raw.length() == 24 && raw.matches("[0-9a-fA-F]{24}")) {
            try { return new ObjectId(raw); } catch (Exception ignore) { /* fall through */ }
        }
        return raw;
    }

    /**
     * Resolve the organizationId for a project.
     *
     *  Preferred source is {@code mcpProject.onboarding.organizationId}
     *  — that's what the  microservice flow stores. When the UI
     *  hasn't been able to populate it (e.g. the shared OnboardingModal
     *  doesn't include it in its callback payload), we fall back to
     *  reading {@code connector.organizationId} from the connector doc
     *  the user already saved via the shared ConnectorModal — that doc
     *  always has the right org.
     *
     *  Final fallback is null which lets the  service reject the
     *  push cleanly instead of silently mirroring with a wrong org.
     */
    private String resolveOrganizationId(McpProject p) {
        var ob = p.getOnboarding();
        if (ob != null && ob.getOrganizationId() != null && !ob.getOrganizationId().isBlank()) {
            return ob.getOrganizationId();
        }
        String connectorId = p.getConnectorId();
        if (connectorId == null || connectorId.isBlank()) return null;
        try {
            org.bson.Document conn = bridgeColl(props.getColl().getConnector())
                    .find(new org.bson.Document("_id", parseIdMaybe(connectorId)))
                    .first();
            if (conn == null) return null;
            Object org = conn.get("organizationId");
            return org == null ? null : org.toString();
        } catch (Exception e) {
            log.warn("[bridge] connector lookup failed for {}: {}", connectorId, e.getMessage());
            return null;
        }
    }

    /**
     * Resolve the connectorId to stamp onto the mirrored microservice
     * doc so the  api-development service finds the user's saved
     * GitHub credentials instead of falling back to ForgeCrux's default
     * repo.
     *
     * Preferred source is {@code mcpProject.connectorId} — what the UI
     * sets when the user saves the ConnectorModal. When the UI couldn't
     * capture the id (existing connectors picked from a dropdown
     * without a Save) we fall back to looking up the newest connector
     * doc for this organization, which is exactly how the 
     * microservice flow resolves it implicitly.
     */
    private String resolveConnectorId(McpProject p) {
        if (p.getConnectorId() != null && !p.getConnectorId().isBlank()) {
            return p.getConnectorId();
        }
        String org = resolveOrganizationId(p);
        if (org == null) return null;
        try {
            org.bson.Document conn = bridgeColl(props.getColl().getConnector())
                    .find(new org.bson.Document("organizationId", org))
                    .sort(new org.bson.Document("_id", -1))
                    .first();
            if (conn == null) return null;
            Object cid = conn.get("_id");
            return cid == null ? null : cid.toString();
        } catch (Exception e) {
            log.warn("[bridge] connector by-org lookup failed for {}: {}", org, e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the connector's saved source branch (the one the wizard
     * actually pushes to — the CICD profile's "dev" branch, saved on the
     * connector via the ConnectorModal). Called at {@code generate()} time
     * so the embedded {@code .github/workflows/mcp.yml}'s push-trigger is
     * baked in to match, instead of being hardcoded to "main" while the
     * code actually lands on a different branch.
     *
     * Falls back to "main" whenever a connector isn't resolvable yet
     * (brand-new project, no connector saved) so generation never breaks —
     * the workflow just targets "main" until a real connector is saved,
     * same as the previous hardcoded behaviour.
     */
    public String resolveDevBranch(McpProject p) {
        try {
            String connectorId = resolveConnectorId(p);
            if (connectorId == null) return "main";
            org.bson.Document conn = bridgeColl(props.getColl().getConnector())
                    .find(new org.bson.Document("_id", parseIdMaybe(connectorId)))
                    .first();
            if (conn == null) return "main";
            org.bson.Document scm = conn.get("sourceCodeManagement", org.bson.Document.class);
            if (scm == null) return "main";
            String branch = scm.getString("branch");
            return (branch == null || branch.isBlank()) ? "main" : branch.trim();
        } catch (Exception e) {
            log.warn("[bridge] resolveDevBranch failed for project {}: {}", p.getId(), e.getMessage());
            return "main";
        }
    }

    // ───────────────────────────────────────────────── doc builders

    /**
     * Writes the {@code codegen_results} row. Field-for-field match
     * with  actual schema:
     * <pre>
     *   _id, microserviceId, generationId, artifactId, projectPath,
     *   archivePath, sourceArchivePath, gcsArchivePath, archiveDownloadUrl,
     *   archiveFileName, archiveSizeBytes, status, generationTimestamp,
     *   generatedFiles[], messages[], createdAt, updatedAt, _class
     * </pre>
     * {@code generatedFiles} is the array of file PATH STRINGS only —
     * NOT objects with content. Same as  docs.
     */
    private void upsertCodegenResults(McpProject p, String microserviceId, String codeGenResultId,
                                      String generationId, String slug,
                                      String gcsArchivePath, String archiveDownloadUrl,
                                      String archiveFileName, int archiveSizeBytes, String fileUuid) {
        List<String> generatedFiles = p.getGenerated().getFiles().stream()
                .map(McpProject.GeneratedFile::getPath)
                .toList();

        List<String> messages = new ArrayList<>();
        messages.add("Generated " + generatedFiles.size() + " files from MCP spec");
        if (p.getRuntime() != null && p.getRuntime().getLanguage() != null) {
            messages.add("Target language: " + p.getRuntime().getLanguage());
        }
        if (p.getTransport() != null && p.getTransport().getKind() != null) {
            messages.add("Transport: " + p.getTransport().getKind());
        }
        if (p.getCapabilities() != null) {
            int tools     = p.getCapabilities().getTools()     == null ? 0 : p.getCapabilities().getTools().size();
            int resources = p.getCapabilities().getResources() == null ? 0 : p.getCapabilities().getResources().size();
            int prompts   = p.getCapabilities().getPrompts()   == null ? 0 : p.getCapabilities().getPrompts().size();
            messages.add("MCP capabilities: " + tools + " tool(s), " + resources + " resource(s), " + prompts + " prompt(s)");
        }
        messages.add("Created MCP project archive");

        String projectPath = "./generated-projects/" + generationId + "/" + slug;
        Date now = Date.from(Instant.now());

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id",                  parseIdMaybe(codeGenResultId));
        doc.put("microserviceId",       microserviceId);
        doc.put("generationId",         generationId);
        doc.put("artifactId",           slug);
        doc.put("projectPath",          projectPath);
        // archivePath / sourceArchivePath are HTTP download endpoints in
        //  flow. For MCP we point them at our own /download
        // endpoint so  pipeline (or anyone debugging) can fetch
        // the zip via HTTPS too. If absent it just doesn't matter — the
        // push pipeline reads `gcsArchivePath` first.
        doc.put("archivePath",          null);
        doc.put("sourceArchivePath",    null);
        doc.put("gcsArchivePath",       gcsArchivePath);
        doc.put("archiveDownloadUrl",   archiveDownloadUrl);
        doc.put("archiveFileName",      archiveFileName);
        doc.put("archiveSizeBytes",     archiveSizeBytes);
        doc.put("status",               "SUCCESS");
        doc.put("generationTimestamp",  Instant.now().toString());
        doc.put("generatedFiles",       generatedFiles);
        doc.put("messages",             messages);
        // ---- additive (safe, ignored by  mapper) ----
        doc.put("projectType",          PROJECT_TYPE_MCP);
        doc.put("mcpProjectId",         p.getId());
        doc.put("createdAt",            now);
        doc.put("updatedAt",            now);
        doc.put("_class",               CLASS_CODEGEN_RESULT);

        bridgeColl(props.getColl().getCodegenResults()).replaceOne(
                new org.bson.Document("_id", parseIdMaybe(codeGenResultId)),
                new org.bson.Document(doc),
                new com.mongodb.client.model.ReplaceOptions().upsert(true));
        log.debug("[bridge] upserted {} _id={}", props.getColl().getCodegenResults(), codeGenResultId);
    }

    /**
     * Upsert the microservice document using the shared builder.
     * This method now reuses buildMicroserviceDocument to keep the logic DRY.
     */
    private void upsertMicroserviceDoc(McpProject p, String microserviceId, String codeGenResultId) {
        p.setMicroserviceMirrorId(microserviceId);
        org.bson.Document doc = buildMicroserviceDocument(p, codeGenResultId);
        bridgeColl(props.getColl().getMicroservice()).replaceOne(
                new org.bson.Document("_id", parseIdMaybe(microserviceId)),
                doc,
                new com.mongodb.client.model.ReplaceOptions().upsert(true));
        log.debug("[bridge] upserted {} _id={}", props.getColl().getMicroservice(), microserviceId);
    }

    private void upsertDeploymentArtifact(McpProject p, String microserviceId, String artifactId) {
        String organizationId = resolveOrganizationId(p);
        Date now = Date.from(Instant.now());
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id",                  parseIdMaybe(artifactId));
        doc.put("microserviceId",       microserviceId);
        doc.put("organizationId",       organizationId);
        doc.put("projectType",          PROJECT_TYPE_MCP);
        doc.put("artifactVersion",      "v1.0.0");
        doc.put("artifactLabel",        "v1.0.0-mcp-" + Instant.now().toString());
        doc.put("mcpProjectId",         p.getId());
        doc.put("deployedEnvironments", List.of());
        doc.put("createdAt",            now);
        doc.put("updatedAt",            now);
        doc.put("_class",               CLASS_DEPLOYMENT_ARTIFACT);

        bridgeColl(props.getColl().getDeploymentArtifacts()).replaceOne(
                new org.bson.Document("_id", parseIdMaybe(artifactId)),
                new org.bson.Document(doc),
                new com.mongodb.client.model.ReplaceOptions().upsert(true));
        log.debug("[bridge] upserted {} _id={}", props.getColl().getDeploymentArtifacts(), artifactId);
    }

    /** Materialise the zip in-memory by reusing the same streamer the
     *  download endpoint exposes. Saves us from duplicating the zip
     *  serialization logic. */
    private byte[] buildZipBytes(McpProject p) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            genSvc.streamZip(p, baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build zip bytes for mirror: " + e.getMessage(), e);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Workflow-run lookups — replaces senior's api-development endpoint
    // which is currently broken by a GCS uniform-bucket-level-access
    // policy. We hit the GitHub Runs API directly using the same
    // connector PAT we used to push files. Repo-scoped reads only — no
    // writes — so this stays read-safe.
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns a normalised "latest run" payload that matches the shape
     * the senior team's /deploy-to-github/latest-run endpoint returned
     * before it broke. Fields:
     *
     *   { repo, repoName, runFound, runId, deploymentStatus, deployedServiceUrl?,
     *     run: { id, name, displayTitle, status, conclusion, htmlUrl, createdAt, updatedAt } }
     *
     * `deploymentStatus` is derived: completed+success → SUCCESS,
     * completed+failure|cancelled|timed_out → FAILED, anything else → IN_PROGRESS.
     */
    public Map<String, Object> getLatestWorkflowRun(McpProject project) {
        var creds = resolveGitHubCreds(project);
        if (creds == null) {
            return Map.of("runFound", false, "message", "No GitHub connector configured");
        }

        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        String url = "https://api.github.com/repos/" + creds.orgOrUser + "/" + creds.repo
                + "/actions/runs?per_page=5";
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("Authorization", "Bearer " + creds.token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[runs] GitHub list-runs {} {}", resp.statusCode(), resp.body());
                return Map.of("runFound", false, "message", "GitHub list-runs failed: " + resp.statusCode());
            }
            // Pull the first run + minimal status fields via shallow JSON parsing
            // so we don't need to pull a JSON library purely for this.
            String body = resp.body();
            Map<String, Object> firstRun = parseFirstRun(body);
            String repoFullName = creds.orgOrUser + "/" + creds.repo;
            if (firstRun == null) {
                return Map.of("runFound", false, "repo", repoFullName);
            }
            String status     = (String) firstRun.getOrDefault("status", "");
            String conclusion = (String) firstRun.getOrDefault("conclusion", "");
            String deployStatus = "IN_PROGRESS";
            if ("completed".equalsIgnoreCase(status)) {
                deployStatus = switch (conclusion.toLowerCase()) {
                    case "success"            -> "SUCCESS";
                    case "failure", "cancelled", "timed_out" -> "FAILED";
                    default                   -> "COMPLETED";
                };
            }

            // On a fresh SUCCESS, pull the Cloud Run URL out of the
            // "deployment-url" artifact the workflow uploads (see
            // mcp.yml's "Upload Deployed URL Artifact" step) — this is
            // the ONLY place that URL exists; Cloud Run assigns it at
            // deploy time and it can't be predicted/derived beforehand.
            // Without this, `deployedServiceUrl` was NEVER populated by
            // anything and the UI could never show it.
            if ("SUCCESS".equals(deployStatus)
                    && (project.getDeployedServiceUrl() == null || project.getDeployedServiceUrl().isBlank())) {
                DeployedUrls fetched = fetchDeployedUrlFromArtifact(
                        creds.orgOrUser, creds.repo, String.valueOf(firstRun.get("id")), creds.token);
                if (fetched != null && fetched.url() != null && !fetched.url().isBlank()) {
                    String base = fetched.url().replaceAll("/+$", "");
                    // The artifact JSON normally carries mcpUrl/healthUrl
                    // pre-computed, but fall back to plain concatenation
                    // (same convention every generated server follows —
                    // "<base>/mcp" and "<base><healthCheck.path>") if an
                    // older workflow run's artifact predates those fields.
                    String healthPath = project.getAdvanced() != null && project.getAdvanced().getHealthCheck() != null
                            && project.getAdvanced().getHealthCheck().getPath() != null
                            && !project.getAdvanced().getHealthCheck().getPath().isBlank()
                            ? project.getAdvanced().getHealthCheck().getPath() : "/healthz";
                    project.setDeployedServiceUrl(base);
                    project.setDeployedMcpUrl(fetched.mcpUrl() != null && !fetched.mcpUrl().isBlank()
                            ? fetched.mcpUrl() : base + "/mcp");
                    project.setDeployedHealthUrl(fetched.healthUrl() != null && !fetched.healthUrl().isBlank()
                            ? fetched.healthUrl() : base + healthPath);
                    project.setDeployedAt(java.time.Instant.now());
                }
            }

            // Persist key fields onto McpProject so the dashboard /
            // listings have up-to-date workflow state too.
            try {
                project.setLatestRunId(String.valueOf(firstRun.get("id")));
                project.setLatestRunStatus(status);
                project.setLatestRunConclusion(conclusion);
                project.setLatestRunUrl((String) firstRun.get("htmlUrl"));
                project.setLatestRunCheckedAt(java.time.Instant.now());
                projects.save(project);
            } catch (Exception ignore) { /* best-effort persistence */ }

            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("repo", repoFullName);
            out.put("repoName", creds.repo);
            out.put("runFound", true);
            out.put("runId", firstRun.get("id"));
            out.put("deploymentStatus", deployStatus);
            if (project.getDeployedServiceUrl() != null) {
                out.put("deployedServiceUrl", project.getDeployedServiceUrl());
                if (project.getDeployedMcpUrl() != null) out.put("deployedMcpUrl", project.getDeployedMcpUrl());
                if (project.getDeployedHealthUrl() != null) out.put("deployedHealthUrl", project.getDeployedHealthUrl());
            }
            out.put("run", firstRun);
            return out;
        } catch (Exception e) {
            log.warn("[runs] latest-run lookup failed: {}", e.getMessage());
            return Map.of("runFound", false, "error", e.getMessage());
        }
    }

    /** Everything the "deployment-url" artifact carries — read straight
     *  off it instead of re-deriving mcpUrl/healthUrl on the frontend, so
     *  they're always exactly what the workflow actually computed. */
    private record DeployedUrls(String url, String mcpUrl, String healthUrl) {}

    /**
     * Downloads the workflow's "deployment-url" artifact (a small zip
     * containing {@code .deploy-url.json}, written by mcp.yml's "Save
     * Deployed URL as Artifact File" step) and extracts the Cloud Run
     * service URL + its /mcp and health-check siblings. Three GitHub API
     * calls: list artifacts for the run → find the one named
     * "deployment-url" → download + unzip it. Best-effort — returns null
     * on any failure (artifact not ready yet, expired, workflow predates
     * this artifact step, parse error) so a hiccup here never breaks
     * status polling.
     */
    private DeployedUrls fetchDeployedUrlFromArtifact(String orgOrUser, String repo, String runId, String token) {
        if (runId == null || runId.isBlank()) return null;
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
            String listUrl = "https://api.github.com/repos/" + orgOrUser + "/" + repo
                    + "/actions/runs/" + runId + "/artifacts";
            var listReq = java.net.http.HttpRequest.newBuilder(java.net.URI.create(listUrl))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var listResp = http.send(listReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (listResp.statusCode() != 200) return null;

            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(listResp.body());
            String downloadUrl = null;
            for (var a : root.path("artifacts")) {
                if ("deployment-url".equals(a.path("name").asText(null))) {
                    downloadUrl = a.path("archive_download_url").asText(null);
                    break;
                }
            }
            if (downloadUrl == null || downloadUrl.isBlank()) return null;

            var dlReq = java.net.http.HttpRequest.newBuilder(java.net.URI.create(downloadUrl))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var dlResp = http.send(dlReq, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (dlResp.statusCode() != 200) return null;

            try (var zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(dlResp.body()))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    if (entry.getName().endsWith(".deploy-url.json")) {
                        byte[] content = zin.readAllBytes();
                        com.fasterxml.jackson.databind.JsonNode urlJson =
                                new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
                        String svcUrl = urlJson.path("url").asText(null);
                        if (svcUrl == null || svcUrl.isBlank()) continue;
                        String mcpUrl = urlJson.path("mcpUrl").asText(null);
                        String healthUrl = urlJson.path("healthUrl").asText(null);
                        return new DeployedUrls(svcUrl, mcpUrl, healthUrl);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[runs] fetchDeployedUrlFromArtifact failed for {}/{} run={}: {}",
                    orgOrUser, repo, runId, e.getMessage());
            return null;
        }
    }

    /**
     * Returns per-job step matrix for one workflow run id. Same response
     * shape we ask the senior endpoint to give us — `{ jobs: [{ name,
     * status, conclusion, steps: [{ name, status, conclusion }] }] }` —
     * so the DeployStatusPanel can animate the 11 mcp.yml steps without
     * caring whether the data came from us or them.
     */
    public Map<String, Object> getWorkflowRunSteps(McpProject project, String runId) {
        if (runId == null || runId.isBlank()) return Map.of("jobs", java.util.List.of());
        var creds = resolveGitHubCreds(project);
        if (creds == null) return Map.of("jobs", java.util.List.of());

        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        String url = "https://api.github.com/repos/" + creds.orgOrUser + "/" + creds.repo
                + "/actions/runs/" + runId + "/jobs";
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("Authorization", "Bearer " + creds.token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return Map.of("jobs", java.util.List.of());
            return Map.of("jobs", parseJobs(resp.body()));
        } catch (Exception e) {
            log.warn("[runs] steps lookup failed: {}", e.getMessage());
            return Map.of("jobs", java.util.List.of());
        }
    }

    // Internal connector-credential record returned by resolveGitHubCreds.
    private record GhCreds(String token, String orgOrUser, String repo) {}

    /** Reads {token, orgOrUser, repo} off the project's connector. */
    private GhCreds resolveGitHubCreds(McpProject project) {
        String connectorId = resolveConnectorId(project);
        if (connectorId == null) return null;
        org.bson.Document conn = bridgeColl(props.getColl().getConnector())
                .find(new org.bson.Document("_id", parseIdMaybe(connectorId)))
                .first();
        if (conn == null) return null;
        org.bson.Document scm = conn.get("sourceCodeManagement", org.bson.Document.class);
        if (scm == null) return null;
        String token = scm.getString("token");
        String orgOrUser = scm.getString("orgOrUser");
        String repo = scm.getString("repo");
        if (token == null || orgOrUser == null || repo == null) return null;
        return new GhCreds(token, orgOrUser, repo);
    }

    /**
     * Shallow JSON parser for the runs-list response — we only need
     * id/name/status/conclusion/htmlUrl/createdAt/updatedAt of the
     * first entry. Avoids adding a JSON binding library for one method.
     */
    private static Map<String, Object> parseFirstRun(String body) {
        int idx = body.indexOf("\"workflow_runs\":[");
        if (idx < 0) return null;
        int start = body.indexOf('{', idx);
        if (start < 0) return null;
        int depth = 0, end = start;
        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { end = i + 1; break; } }
        }
        String slice = body.substring(start, end);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id",            extractLong   (slice, "id"));
        out.put("name",          extractString (slice, "name"));
        out.put("displayTitle",  extractString (slice, "display_title"));
        out.put("status",        extractString (slice, "status"));
        out.put("conclusion",    extractString (slice, "conclusion"));
        out.put("htmlUrl",       extractString (slice, "html_url"));
        out.put("createdAt",     extractString (slice, "created_at"));
        out.put("updatedAt",     extractString (slice, "updated_at"));
        return out;
    }

    /** Same approach for the /jobs endpoint — pulls each job + its steps. */
    private static java.util.List<Map<String, Object>> parseJobs(String body) {
        java.util.List<Map<String, Object>> jobs = new java.util.ArrayList<>();
        int idx = body.indexOf("\"jobs\":[");
        if (idx < 0) return jobs;
        int cursor = idx + 8;
        while (cursor < body.length()) {
            int start = body.indexOf('{', cursor);
            if (start < 0) break;
            int depth = 0, end = start;
            for (int i = start; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) { end = i + 1; break; } }
            }
            String jobSlice = body.substring(start, end);
            Map<String, Object> job = new java.util.LinkedHashMap<>();
            // Forward identifying + outcome fields…
            job.put("id",         extractLong  (jobSlice, "id"));
            job.put("name",       extractString(jobSlice, "name"));
            job.put("status",     extractString(jobSlice, "status"));
            job.put("conclusion", extractString(jobSlice, "conclusion"));
            // …plus GitHub-side timestamps + run link so the deployment
            // status panel can render Duration / "Open" links per job.
            // The senior endpoint exposes these as camelCase, so we mirror.
            job.put("htmlUrl",     extractString(jobSlice, "html_url"));
            job.put("startedAt",   extractString(jobSlice, "started_at"));
            job.put("completedAt", extractString(jobSlice, "completed_at"));
            job.put("steps",       parseSteps(jobSlice));
            jobs.add(job);
            cursor = end + 1;
            // Stop when we leave the jobs array (depth tracking is
            // expensive; we just break on `]` immediately after the job).
            int nextComma = body.indexOf(',', cursor);
            int closeArr = body.indexOf(']', cursor);
            if (closeArr > 0 && (nextComma < 0 || closeArr < nextComma)) break;
        }
        return jobs;
    }

    private static java.util.List<Map<String, Object>> parseSteps(String jobSlice) {
        java.util.List<Map<String, Object>> steps = new java.util.ArrayList<>();
        int idx = jobSlice.indexOf("\"steps\":[");
        if (idx < 0) return steps;
        int cursor = idx + 9;
        while (cursor < jobSlice.length()) {
            int start = jobSlice.indexOf('{', cursor);
            int closeArr = jobSlice.indexOf(']', cursor);
            if (start < 0 || (closeArr > 0 && closeArr < start)) break;
            int depth = 0, end = start;
            for (int i = start; i < jobSlice.length(); i++) {
                char c = jobSlice.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) { end = i + 1; break; } }
            }
            String stepSlice = jobSlice.substring(start, end);
            Map<String, Object> step = new java.util.LinkedHashMap<>();
            // GitHub returns `number` (int), `started_at`, `completed_at`
            // per step. The frontend deployment status panel reads
            // step.number (for grouping/highlighting),
            // step.startedAt + step.completedAt (for the Duration cell
            // and the "completed at" timestamp). Without these fields
            // the panel correctly shows N/A — so we extract them all
            // and mirror to camelCase to match the senior endpoint.
            step.put("number",      extractLong  (stepSlice, "number"));
            step.put("name",        extractString(stepSlice, "name"));
            step.put("status",      extractString(stepSlice, "status"));
            step.put("conclusion",  extractString(stepSlice, "conclusion"));
            step.put("startedAt",   extractString(stepSlice, "started_at"));
            step.put("completedAt", extractString(stepSlice, "completed_at"));
            steps.add(step);
            cursor = end + 1;
        }
        return steps;
    }

    private static String extractString(String slice, String key) {
        String marker = "\"" + key + "\":";
        int i = slice.indexOf(marker);
        if (i < 0) return null;
        int from = i + marker.length();
        while (from < slice.length() && Character.isWhitespace(slice.charAt(from))) from++;
        if (from >= slice.length()) return null;
        if (slice.charAt(from) == 'n') return null;            // null
        if (slice.charAt(from) != '"') return null;            // not a string
        int end = slice.indexOf('"', from + 1);
        return end > from ? slice.substring(from + 1, end) : null;
    }

    private static Long extractLong(String slice, String key) {
        String marker = "\"" + key + "\":";
        int i = slice.indexOf(marker);
        if (i < 0) return null;
        int from = i + marker.length();
        StringBuilder sb = new StringBuilder();
        while (from < slice.length()) {
            char c = slice.charAt(from);
            if (Character.isDigit(c)) sb.append(c);
            else if (!sb.isEmpty()) break;
            else if (!Character.isWhitespace(c)) break;
            from++;
        }
        if (sb.isEmpty()) return null;
        try { return Long.parseLong(sb.toString()); } catch (NumberFormatException e) { return null; }
    }
}