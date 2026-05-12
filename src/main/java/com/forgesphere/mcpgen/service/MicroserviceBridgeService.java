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
        if (p.getGenerated() == null
                || p.getGenerated().getFiles() == null
                || p.getGenerated().getFiles().isEmpty()) {
            log.info("[push] project={} has no generated files - running generator", p.getId());
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
        String repo      = scm.getString("repo");
        String branch    = scm.getString("branch");
        if (token == null || token.isBlank()) throw new IllegalStateException("Connector has no GitHub token.");
        if (orgOrUser == null || orgOrUser.isBlank()) throw new IllegalStateException("Connector has no orgOrUser.");
        if (repo == null || repo.isBlank()) throw new IllegalStateException("Connector has no repo.");
        if (branch == null || branch.isBlank()) branch = "main";

        log.info("[push] target=https://github.com/{}/{} branch={} files={}",
                orgOrUser, repo, branch, mcp.getGenerated().getFiles().size());

        // ─── Make sure the repo exists ──────────────────────────────
        // GitHub returns 404 if the repo isn't there. Create it under
        // the user's account when missing. We can't reliably know if
        // `orgOrUser` is a user or an org from the connector, so we
        // try the user endpoint first and fall back to the org one on
        // 404 — same heuristic the GitHub CLI uses.
        ensureRepoExists(orgOrUser, repo, branch, token, scm);

        // ─── Push each file via the Contents API ────────────────────
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.util.List<Map<String, Object>> pushedFiles = new java.util.ArrayList<>();
        java.util.List<Map<String, Object>> failedFiles = new java.util.ArrayList<>();
        String commitMessage = "Push from ForgeSphere MCP wizard";
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

        // ─── Persist push state on our McpProject doc ────────────────
        String repoUrl = "https://github.com/" + orgOrUser + "/" + repo;
        mcp.setPushedRepoFullName(orgOrUser + "/" + repo);
        mcp.setPushedRepoUrl(repoUrl);
        mcp.setPushedBranch(branch);
        mcp.setPushedFileCount(pushedFiles.size());
        mcp.setPushedAt(Instant.now());
        if (mcp.getId() != null) projects.save(mcp);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repoFullName", orgOrUser + "/" + repo);
        out.put("repoUrl",      repoUrl);
        out.put("branch",       branch);
        out.put("pushedCount",  pushedFiles.size());
        out.put("failedCount",  failedFiles.size());
        out.put("pushedFiles",  pushedFiles);
        out.put("failedFiles",  failedFiles);
        log.info("[push] done project={} pushed={} failed={}",
                mcp.getId(), pushedFiles.size(), failedFiles.size());
        return out;
    }

    /**
     * Create the GitHub repo if it doesn't exist yet. Empty new repos
     * have no default branch, so we also initialise `branch` with an
     * empty README so subsequent PUT /contents calls don't 404 on
     * "ref not found".
     */
    private void ensureRepoExists(String orgOrUser, String repo, String branch, String token, org.bson.Document scm) {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        try {
            var get = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("https://api.github.com/repos/" + orgOrUser + "/" + repo))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var resp = http.send(get, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return;   // repo exists — nothing to do
            if (resp.statusCode() != 404) {
                log.warn("[push] repo lookup non-200/404: {} {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("[push] repo lookup failed (will try to create): {}", e.getMessage());
        }

        boolean isPrivate = scm.containsKey("isPrivate")
                ? Boolean.TRUE.equals(scm.getBoolean("isPrivate")) : false;
        // Try as a user first (POST /user/repos). If the token doesn't
        // own that user (e.g. orgOrUser is actually an org), retry as
        // an org (POST /orgs/{org}/repos).
        String createBody = "{\"name\":\"" + repo + "\",\"private\":" + isPrivate + ",\"auto_init\":true}";
        try {
            var create = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("https://api.github.com/user/repos"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(createBody))
                    .build();
            var resp = http.send(create, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 201) {
                log.info("[push] created user repo {}/{}", orgOrUser, repo);
                return;
            }
            log.info("[push] user repo create returned {}, falling back to org endpoint", resp.statusCode());
        } catch (Exception e) {
            log.warn("[push] user repo create failed: {}", e.getMessage());
        }
        try {
            var create = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("https://api.github.com/orgs/" + orgOrUser + "/repos"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(createBody))
                    .build();
            var resp = http.send(create, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 201) {
                log.info("[push] created org repo {}/{}", orgOrUser, repo);
                return;
            }
            throw new IllegalStateException("Couldn't create repo: " + resp.statusCode() + " " + resp.body());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create GitHub repo " + orgOrUser + "/" + repo + ": " + e.getMessage(), e);
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
        String url = "https://api.github.com/repos/" + orgOrUser + "/" + repo + "/contents/"
                + java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8).replace("%2F", "/");

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

    private void upsertMicroserviceDoc(McpProject p, String microserviceId, String codeGenResultId) {
        var ob = p.getOnboarding();
        var id = p.getIdentity();
        String organizationId = resolveOrganizationId(p);
        Date now = Date.from(Instant.now());
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id",                 parseIdMaybe(microserviceId));
        doc.put("codeGenResultId",     codeGenResultId);
        doc.put("projectType",         PROJECT_TYPE_MCP);
        // ---- onboarding snapshot (denormalised, same pattern as ) ----
        doc.put("organizationId",      organizationId);
        doc.put("businessUnit",        ob == null ? null : ob.getBusinessUnit());
        doc.put("teamName",            ob == null ? null : ob.getTeamName());
        doc.put("applicationName",     ob == null ? null : ob.getApplicationName());
        doc.put("applicationId",       ob == null ? null : ob.getApplicationId());
        doc.put("onboardingId",        p.getOnboardingId());
        doc.put("apiName",             id == null ? null : id.getDisplayName());
        doc.put("projectOwner",        ob == null ? null : ob.getProjectOwner());
        doc.put("ownerEmail",          ob == null ? null : ob.getOwnerEmail());
        doc.put("projectSME",          ob == null ? null : ob.getProjectSME());
        doc.put("projectSMEEmail",     ob == null ? null : ob.getProjectSMEEmail());
        doc.put("projectDLEmail",      ob == null ? null : ob.getProjectDLEmail());
        doc.put("expectedGoLiveDate",  ob == null ? null : ob.getExpectedGoLiveDate());
        doc.put("testerName",          ob == null ? null : ob.getTesterName());
        doc.put("testerEmail",         ob == null ? null : ob.getTesterEmail());
        doc.put("serviceNowGroupName", ob == null ? null : ob.getServiceNowGroupName());
        doc.put("serviceNowEmail",     ob == null ? null : ob.getServiceNowEmail());
        doc.put("consumerIds",         ob == null || ob.getConsumerIds() == null
                                            ? List.of() : ob.getConsumerIds());
        doc.put("connectorId",         resolveConnectorId(p));
        doc.put("mcpProjectId",        p.getId());
        doc.put("mcpSlug",             id == null ? null : id.getSlug());
        doc.put("createdAt",           now);
        doc.put("updatedAt",           now);
        doc.put("_class",              CLASS_MICROSERVICE);

        bridgeColl(props.getColl().getMicroservice()).replaceOne(
                new org.bson.Document("_id", parseIdMaybe(microserviceId)),
                new org.bson.Document(doc),
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
}
