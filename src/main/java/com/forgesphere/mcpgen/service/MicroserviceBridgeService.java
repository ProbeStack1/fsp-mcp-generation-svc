package com.forgesphere.mcpgen.service;

import com.forgesphere.mcpgen.bridge.BridgeGcsClient;
import com.forgesphere.mcpgen.bridge.BridgeProperties;
import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpDeployStepLog;
import com.forgesphere.mcpgen.repo.McpDeployStepLogRepository;
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

    /** Stamped as {@code projectType} on every mirrored doc. This is BOTH
     *  the "came from the MCP wizard" audit marker AND the CICD-profile
     *  asset-type key fsp-api-development-svc's DeployService looks up
     *  ({@code pipelineConfigs["MCP"]}). Was "MCP_SERVER"; DeployService /
     *  MergeService fold that legacy value onto "MCP". */
    private static final String PROJECT_TYPE_MCP = "MCP";

    /** {@code _class} discriminators — must match  mapper. */
    private static final String CLASS_CODEGEN_RESULT =
            "com.probestack.forgesphere.apidevelopment.model.CodeGenResult";
    private static final String CLASS_DEPLOYMENT_ARTIFACT =
            "com.probestack.forgesphere.apidevelopment.model.DeploymentArtifact";
    private static final String CLASS_MICROSERVICE =
            "com.probestack.forgesphere.apidevelopment.model.Microservice";

    /** Signed URL expiry —  uses 60 minutes. */
    private static final long SIGNED_URL_TTL_MINUTES = 60;

    /** Longer expiry for the URL handed to the onboarding pipeline: a
     *  workflow_dispatch can sit queued / be retried well beyond an hour,
     *  and this URL is the pipeline's ONLY way to fetch the bundle. */
    private static final long PIPELINE_SIGNED_URL_TTL_MINUTES = 720; // 12h

    private static final DateTimeFormatter DATE_FOLDER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MongoTemplate mongo;
    private final McpProjectRepository projects;
    private final McpDeployStepLogRepository stepLogRepo;
    private final McpGenerationService genSvc;
    private final BridgeGcsClient gcs;
    private final BridgeProperties props;

    // Same CICD profile service the microservice/proxy/kong pipeline reads
    // branch strategy from (fsp-api-development-svc's DeployService).
    @org.springframework.beans.factory.annotation.Value(
            "${cicd.service.url:https://forgesphere.probestack.io/cicd-automation/v1/api/cicd-config}")
    private String cicdServiceBaseUrl;

    // fsp-api-development-svc base URL — the pipeline deploy path POSTs to
    // {this}/v1/api-development/{microserviceId}/deploy-to-github after a mirror.
    @org.springframework.beans.factory.annotation.Value(
            "${api-development.service.url:https://forgesphere.probestack.io/api-development}")
    private String apiDevelopmentServiceUrl;

    // Public base URL of THIS service, used to build the bundle-download URL
    // (`{base}/mcp-generate/v1/api/projects/{id}/artifact.zip`) handed to the
    // onboarding pipeline. Falls back to the shared ingress host.
    @org.springframework.beans.factory.annotation.Value(
            "${mcpgen.public-base-url:https://forgesphere.probestack.io}")
    private String mcpgenPublicBaseUrl;

    // Feature flag: true  → `/push-to-github` runs the pipeline path
    //               false → legacy direct Git Data API push (fallback)
    @org.springframework.beans.factory.annotation.Value(
            "${mcpgen.deploy.pipeline-enabled:true}")
    private boolean pipelineDeployEnabled;

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
        // DeployService.buildDeployContext reads microservice.repositoryName
        // first when resolving the repo the pipeline should create/reuse.
        doc.put("repositoryName", project.getRepositoryName());
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
        // 0) The pipeline resolves SCM (token/org) + branch strategy from the
        //    CICD profile keyed by onboardingId — it is mandatory now.
        if (project.getOnboardingId() == null || project.getOnboardingId().isBlank()) {
            throw new IllegalStateException(
                "This MCP project has no onboardingId. Complete onboarding before deploying "
              + "so the CICD pipeline profile can be resolved.");
        }

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
        //    revision pushes target the SAME rows. The in-memory McpProject
        //    can lose these (the generator returns a fresh instance, and
        //    `generated` is @Transient so mirror() re-runs the generator on
        //    a second call) — so when they're missing we RECOVER them from
        //    the already-written mirror docs (keyed by mcpProjectId) before
        //    minting fresh ObjectIds. Without this a second deploy call
        //    writes a *duplicate* codegen_results / microservice / artifact
        //    row, and fsp-api-development-svc's findByMicroserviceId(...)
        //    then blows up with IncorrectResultSizeDataAccessException
        //    ("Multiple records found …").
        // Resolution order per mirror doc: (1) id already persisted on the
        // project, (2) id recovered from an existing mirror row by mcpProjectId,
        // (3) a DETERMINISTIC id derived from the project id. Step 3 replaces the
        // old "mint a random ObjectId" fallback — two mirror() calls that race
        // (the wizard fires it from more than one step) used to each mint their
        // own random id and both upsert, producing two codegen_results rows with
        // the same microserviceId, which then makes fsp-api-development-svc's
        // findByMicroserviceId(..).orElse* blow up with "Multiple records found".
        // A stable seed makes concurrent calls converge on ONE _id so the
        // replaceOne(upsert) is naturally idempotent.
        String microserviceId = firstNonBlank(
                mcp.getMicroserviceMirrorId(),
                lookupMirrorId(props.getColl().getMicroservice(), mcp.getId()),
                stableObjectId(mcp.getId() + "|microservice"));
        String artifactId = firstNonBlank(
                mcp.getDeploymentArtifactId(),
                lookupMirrorId(props.getColl().getDeploymentArtifacts(), mcp.getId()),
                stableObjectId(mcp.getId() + "|artifact"));
        String codeGenResultId = firstNonBlank(
                mcp.getCodeGenResultId(),
                lookupMirrorId(props.getColl().getCodegenResults(), mcp.getId()),
                stableObjectId(mcp.getId() + "|codegen"));
        // Fresh generationId + UUID on every push so the GCS key never collides.
        String generationId = UUID.randomUUID().toString();
        String fileUuid     = UUID.randomUUID().toString();
        String slug = mcp.getIdentity() != null && mcp.getIdentity().getSlug() != null
                ? mcp.getIdentity().getSlug() : "mcp-server";

        // 2b) Repo name the pipeline will create/reuse. Persist on the project
        //     so a re-deploy always lands in the SAME repo. Matches the
        //     microservice flow, which reads microservice.repositoryName in
        //     DeployService.buildDeployContext (no collision check there —
        //     onboarding.yml clones-or-reuses an existing repo of that name).
        String repositoryName = withMcpSuffix(
                firstNonBlank(mcp.getRepositoryName(), deriveRepoNameFromProject(mcp)));
        mcp.setRepositoryName(repositoryName);

        log.info("[bridge] mirroring project={} → microserviceId={} artifactId={} codeGenResultId={} repo={} files={}",
                mcp.getId(), microserviceId, artifactId, codeGenResultId, repositoryName, fileCount);

        // 3) Build zip + upload to GCS ( exact path pattern).
        byte[] zipBytes = buildZipBytes(mcp);
        String archiveFileName = slug + ".zip";
        String dateFolder = LocalDate.now(ZoneOffset.UTC).format(DATE_FOLDER);
        String objectKey = microserviceId + "/" + dateFolder + "/" + fileUuid + "_" + archiveFileName;
        String gcsObjectPath = gcs.upload(objectKey, zipBytes);
        String gcsArchivePath = "gs://" + gcs.getBucket() + "/" + gcsObjectPath;
        log.info("[bridge] uploaded zip to {} bytes={}", gcsArchivePath, zipBytes.length);

        // 3b) Resolve the URL the onboarding pipeline will `curl -L` to fetch
        //     the bundle. PRIMARY: this service's stable /artifact.zip route
        //     (re-signs fresh on every hit → survives a long queue delay).
        //     FALLBACK: a direct 12h V4 signed URL, chosen automatically when
        //     a preflight shows the route is gated / unreachable. DeployService
        //     forwards this verbatim as ZIP_URL because we DON'T set
        //     gcsArchivePath on the mirror doc (so it won't try to re-sign it
        //     with its own service account — the source of the old 404s).
        ArtifactUrl artifact = resolvePipelineArtifactUrl(mcp.getId(), gcsObjectPath);
        mcp.setArtifactUrlMode(artifact.mode());
        log.info("[bridge] pipeline bundle URL mode={} url={}", artifact.mode(), artifact.url());

        // 4) Write the three mirror docs (codegen_results FIRST so the
        //    microservice doc can reference it). gcsArchivePath is passed as
        //    null ON PURPOSE — see 3b.
        upsertCodegenResults(mcp, microserviceId, codeGenResultId, generationId, slug,
                null, artifact.url(), archiveFileName, zipBytes.length, fileUuid);
        upsertMicroserviceDoc(mcp, microserviceId, codeGenResultId);
        upsertDeploymentArtifact(mcp, microserviceId, artifactId);

        // 4b) Self-heal: an earlier non-idempotent run may have left DUPLICATE
        //     rows for this project (same mcpProjectId, different _id). That's
        //     what makes fsp-api-development-svc's findByMicroserviceId(..)
        //     throw "Multiple records found …". Drop every row for this
        //     project except the canonical one we just upserted. Scoped by
        //     mcpProjectId so it can only ever touch MCP-mirrored rows.
        pruneDuplicateMirrorDocs(props.getColl().getCodegenResults(), codeGenResultId, mcp.getId(), microserviceId);
        pruneDuplicateMirrorDocs(props.getColl().getMicroservice(), microserviceId, mcp.getId(), null);
        pruneDuplicateMirrorDocs(props.getColl().getDeploymentArtifacts(), artifactId, mcp.getId(), null);

        // 5) Persist mirror state on our doc for idempotency. We keep the real
        //    gcsArchivePath HERE (our own field) even though the mirror doc
        //    omits it — /artifact.zip re-signs from this.
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
        out.put("repositoryName",       repositoryName);
        out.put("gcsBucket",            gcs.getBucket());
        out.put("gcsArchivePath",       gcsArchivePath);
        out.put("archiveDownloadUrl",   artifact.url());
        out.put("artifactUrlMode",      artifact.mode());
        out.put("archiveFileName",      archiveFileName);
        out.put("archiveSizeBytes",     zipBytes.length);
        out.put("fileCount",            fileCount);
        log.info("[bridge] mirror complete project={} microserviceId={}", mcp.getId(), microserviceId);
        return out;
    }

    /** {url, mode} pair — mode is "ENDPOINT" or "SIGNED". */
    private record ArtifactUrl(String url, String mode) {}

    /**
     * Decides which URL the onboarding pipeline should fetch the bundle from.
     *
     *  PRIMARY  — {@code {publicBase}/mcp-generate/v1/api/projects/{id}/artifact.zip}
     *             (stable; 302-redirects to a freshly-signed GCS URL on every
     *             hit, so a workflow that sits queued for hours still works).
     *  FALLBACK — a direct {@value #PIPELINE_SIGNED_URL_TTL_MINUTES}-minute V4
     *             signed URL, used when a preflight shows the route is gated by
     *             the ingress (401/403/redirect-to-login) or unreachable.
     *
     *  Both forms work with {@code curl -L}. Best-effort: any preflight error
     *  is treated as "route not usable" and we fall back to the signed URL.
     */
    private ArtifactUrl resolvePipelineArtifactUrl(String projectId, String gcsObjectPath) {
        String signed = gcs.signedUrl(gcsObjectPath, PIPELINE_SIGNED_URL_TTL_MINUTES);
        if (projectId == null || projectId.isBlank()) {
            return new ArtifactUrl(signed, "SIGNED");
        }
        String endpoint = mcpgenPublicBaseUrl.replaceAll("/+$", "")
                + "/mcp-generate/v1/api/projects/" + projectId + "/artifact.zip";
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(endpoint))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .header("Range", "bytes=0-0")
                    .GET().build();
            var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc == 302 || sc == 301 || sc == 307 || sc == 308) {
                String loc = resp.headers().firstValue("location").orElse("");
                if (loc.contains("storage.googleapis.com")) {
                    return new ArtifactUrl(endpoint, "ENDPOINT");
                }
                log.warn("[bridge] /artifact.zip preflight redirected to non-GCS location '{}' — using signed URL", loc);
                return new ArtifactUrl(signed, "SIGNED");
            }
            if (sc == 200 || sc == 206) {
                String ct = resp.headers().firstValue("content-type").orElse("").toLowerCase();
                if (ct.startsWith("application/zip") || ct.startsWith("application/octet-stream")) {
                    return new ArtifactUrl(endpoint, "ENDPOINT");
                }
                log.warn("[bridge] /artifact.zip preflight 200 with content-type '{}' — using signed URL", ct);
                return new ArtifactUrl(signed, "SIGNED");
            }
            log.warn("[bridge] /artifact.zip preflight status {} — using signed URL", sc);
            return new ArtifactUrl(signed, "SIGNED");
        } catch (Exception e) {
            log.warn("[bridge] /artifact.zip preflight failed ({}) — using signed URL", e.getMessage());
            return new ArtifactUrl(signed, "SIGNED");
        }
    }

    /**
     * Fresh {@value #PIPELINE_SIGNED_URL_TTL_MINUTES}-minute signed URL for a
     * project's most recently mirrored bundle. Backs the {@code /artifact.zip}
     * route so every pipeline fetch — even a retry hours later — gets a live
     * link. Returns null when the project was never mirrored.
     */
    public String freshSignedBundleUrl(McpProject p) {
        String gs = p == null ? null : p.getGcsArchivePath();
        if (gs == null || gs.isBlank()) return null;
        String prefix = "gs://" + gcs.getBucket() + "/";
        String objectPath = gs.startsWith(prefix) ? gs.substring(prefix.length())
                : gs.replaceFirst("^gs://[^/]+/", "");
        return gcs.signedUrl(objectPath, PIPELINE_SIGNED_URL_TTL_MINUTES);
    }

    private static String firstNonBlank(String... vals) {
        if (vals != null) {
            for (String v : vals) if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    /**
     * Recover a previously-written mirror doc's {@code _id} for this MCP
     * project (keyed by {@code mcpProjectId}). Returns the newest match as
     * a hex string, or null when none exists. If more than one is found the
     * data is already duplicated — we log it and reuse the newest so at
     * least this run doesn't add a third.
     */
    /**
     * Delete every doc in {@code collection} for this MCP project
     * ({@code mcpProjectId}) whose {@code _id} is not {@code keepId} — i.e.
     * leftovers from a pre-idempotency run. Best-effort; a failure here
     * never fails the deploy.
     */
    private void pruneDuplicateMirrorDocs(String collection, String keepId,
                                          String mcpProjectId, String microserviceId) {
        if (keepId == null) return;
        java.util.List<org.bson.Document> ors = new java.util.ArrayList<>();
        if (mcpProjectId != null && !mcpProjectId.isBlank()) {
            ors.add(new org.bson.Document("mcpProjectId", mcpProjectId));
        }
        // Also match by microserviceId: a stray codegen_results row written by a
        // path that doesn't stamp mcpProjectId (e.g. fsp-api-development-svc's
        // own codegen) still has to go, because that service looks the row up by
        // microserviceId and throws on more than one.
        if (microserviceId != null && !microserviceId.isBlank()) {
            ors.add(new org.bson.Document("microserviceId", microserviceId));
        }
        if (ors.isEmpty()) return;
        try {
            org.bson.Document filter = new org.bson.Document("$or", ors)
                    .append("_id", new org.bson.Document("$ne", parseIdMaybe(keepId)));
            long removed = bridgeColl(collection).deleteMany(filter).getDeletedCount();
            if (removed > 0) {
                log.warn("[bridge] pruned {} stale {} row(s) (mcpProjectId={} / microserviceId={}, kept _id={})",
                        removed, collection, mcpProjectId, microserviceId, keepId);
            }
        } catch (Exception e) {
            log.warn("[bridge] pruneDuplicateMirrorDocs({}) failed: {}", collection, e.getMessage());
        }
    }

    /**
     * Deterministic 24-hex ObjectId string derived from {@code seed}. Same seed
     * → same id on every call, so concurrent {@code mirror()} invocations
     * converge on one {@code _id} per mirror collection instead of each minting
     * a random ObjectId and racing the de-dup prune.
     */
    private static String stableObjectId(String seed) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(24);
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return new ObjectId().toHexString();
        }
    }

    private String lookupMirrorId(String collection, String mcpProjectId) {
        if (mcpProjectId == null || mcpProjectId.isBlank()) return null;
        try {
            java.util.List<org.bson.Document> hits = new java.util.ArrayList<>();
            bridgeColl(collection)
                    .find(new org.bson.Document("mcpProjectId", mcpProjectId))
                    .sort(new org.bson.Document("_id", -1))
                    .limit(5)
                    .into(hits);
            if (hits.isEmpty()) return null;
            if (hits.size() > 1) {
                log.warn("[bridge] {} has {} rows for mcpProjectId={} — reusing newest _id; older rows are stale",
                        collection, hits.size(), mcpProjectId);
            }
            Object id = hits.get(0).get("_id");
            return id == null ? null : id.toString();
        } catch (Exception e) {
            log.warn("[bridge] lookupMirrorId({}, {}) failed: {}", collection, mcpProjectId, e.getMessage());
            return null;
        }
    }

    public boolean isPipelineDeployEnabled() {
        return pipelineDeployEnabled;
    }

    /**
     * Pipeline deploy path (the default for {@code POST /projects/{id}/push-to-github}).
     *
     *   1. {@link #mirror(McpProject)} — writes the codegen_results /
     *      microservice / deployment_artifacts docs the same shape
     *      fsp-api-development-svc's own generate step writes, uploads the
     *      bundle to the shared bucket, and resolves the ZIP_URL.
     *   2. POST {@code {apiDevelopmentServiceUrl}/v1/api-development/{microserviceId}/deploy-to-github}
     *      — DeployService resolves SCM (token/org) + branch strategy from the
     *      CICD "MCP" pipeline profile and dispatches ForgeCrux/ps-onboarding's
     *      onboarding.yml, which creates the repo and pushes the bundle. The
     *      repo's own .github/workflows/mcp.yml then deploys to Cloud Run.
     *
     * The repo/branch the pipeline targets are recorded on the McpProject
     * here (at dispatch time) so the status poller / DeployStatusReaper can
     * find the workflow run — the direct-push path used to set these only
     * after a successful push.
     */
    public Map<String, Object> deployViaPipeline(McpProject project, String actorEmail) {
        // The wizard usually calls POST /deploy-to-github (which runs mirror())
        // and then POST /push-to-github (this) back-to-back. Re-running mirror()
        // here would re-upload the bundle and rewrite every mirror doc for no
        // reason — and any hiccup in the id-reuse path risks a duplicate row.
        // If the project was mirrored moments ago, reuse it; otherwise mirror.
        Map<String, Object> mirrorOut;
        boolean freshMirror = project.getMicroserviceMirrorId() != null
                && !project.getMicroserviceMirrorId().isBlank()
                && project.getMirroredAt() != null
                && project.getMirroredAt().isAfter(Instant.now().minusSeconds(600));
        if (freshMirror) {
            log.info("[pipeline] reusing mirror from {} for project={}", project.getMirroredAt(), project.getId());
            mirrorOut = new LinkedHashMap<>();
            mirrorOut.put("microserviceId", project.getMicroserviceMirrorId());
            mirrorOut.put("repositoryName", project.getRepositoryName());
        } else {
            mirrorOut = mirror(project);
        }
        String microserviceId = String.valueOf(mirrorOut.get("microserviceId"));

        String url = apiDevelopmentServiceUrl.replaceAll("/+$", "")
                + "/v1/api-development/" + microserviceId + "/deploy-to-github";

        Map<String, Object> data;
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            var reqB = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"));
            if (actorEmail != null && !actorEmail.isBlank()) {
                reqB.header("x-user-email", actorEmail).header("userEmail", actorEmail);
            }
            var resp = http.send(reqB.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                // Surface api-development's own error message verbatim (same
                // text a microservice deploy would show — e.g. "Pipeline
                // config not found for asset type: MCP") instead of wrapping
                // it in transport noise.
                String msg = null;
                try {
                    msg = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(resp.body()).path("message").asText(null);
                } catch (Exception ignore) { /* not JSON */ }
                throw new IllegalStateException(
                        (msg != null && !msg.isBlank())
                                ? msg
                                : "Deploy request failed (HTTP " + resp.statusCode() + ").");
            }
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
            com.fasterxml.jackson.databind.JsonNode d = root.path("data");
            if (d.isMissingNode() || d.isNull()) {
                throw new IllegalStateException("api-development deploy-to-github: no data in response: "
                        + truncate(resp.body(), 600));
            }
            data = new com.fasterxml.jackson.databind.ObjectMapper()
                    .convertValue(d, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to call api-development deploy-to-github: " + e.getMessage(), e);
        }

        String orgName  = String.valueOf(data.getOrDefault("orgName", ""));
        String repoName = String.valueOf(data.getOrDefault("repoName", project.getRepositoryName()));
        String branch   = data.get("branch") == null ? null : String.valueOf(data.get("branch"));
        String deploymentId = data.get("deploymentId") == null ? null : String.valueOf(data.get("deploymentId"));

        McpProject fresh = project.getId() == null ? project
                : projects.findById(project.getId()).orElse(project);
        if (!orgName.isBlank() && !repoName.isBlank()) {
            fresh.setPipelineRepoFullName(orgName + "/" + repoName);
            fresh.setPipelineRepoUrl("https://github.com/" + orgName + "/" + repoName);
        }
        fresh.setPipelineBranch(branch);
        fresh.setDeploymentId(deploymentId);
        fresh.setDeployTriggeredAt(Instant.now());
        if (fresh.getRepositoryName() == null || fresh.getRepositoryName().isBlank()) {
            fresh.setRepositoryName(repoName);
        }
        if (fresh.getId() != null) projects.save(fresh);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("microserviceId", microserviceId);
        out.put("deploymentId",   deploymentId);
        out.put("repoFullName",   fresh.getPipelineRepoFullName());
        out.put("repoUrl",        fresh.getPipelineRepoUrl());
        out.put("branch",         branch);
        out.put("status",         data.getOrDefault("status", "TRIGGERED"));
        out.put("artifactUrlMode", mirrorOut.get("artifactUrlMode"));
        out.put("message",        data.getOrDefault("message",
                "Pipeline triggered — repository will be created and deployed shortly."));
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
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
        String base;
        if (id != null && id.getSlug() != null && !id.getSlug().isBlank()) {
            base = id.getSlug().trim();
        } else if (id != null && id.getDisplayName() != null && !id.getDisplayName().isBlank()) {
            String sanitised = id.getDisplayName().toLowerCase().trim()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            base = sanitised.isBlank()
                    ? "mcp-server-" + (mcp.getId() != null ? mcp.getId() : UUID.randomUUID().toString())
                    : sanitised;
        } else {
            base = "mcp-server-" + (mcp.getId() != null ? mcp.getId() : UUID.randomUUID().toString());
        }
        return withMcpSuffix(base);
    }

    /**
     * MCP repos always end in {@code -mcp} — the same convention Apigee
     * proxies ({@code -px}) / shared-flows ({@code -sf}) / Kong services
     * ({@code -kgs}) use, so the same OpenAPI spec generating both a
     * microservice AND an MCP server doesn't collide on one repo name.
     * Idempotent — never doubles the suffix.
     */
    private static String withMcpSuffix(String name) {
        if (name == null || name.isBlank()) return "mcp-server-mcp";
        String n = name.trim();
        return n.toLowerCase().endsWith("-mcp") ? n : n + "-mcp";
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

    /** Root node holding {@code strategies} / {@code pipelineConfigs} —
     *  the CICD endpoint sometimes wraps the payload in {@code data}. */
    private com.fasterxml.jackson.databind.JsonNode cicdRoot(String onboardingId) throws Exception {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        var req = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(cicdServiceBaseUrl + "/" + onboardingId + "/all?filtered=true"))
                .header("Accept", "application/json")
                .GET().build();
        var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("CICD config lookup for onboarding " + onboardingId
                    + " returned " + resp.statusCode());
        }
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(resp.body());
        return root.path("data").has("pipelineConfigs") || root.path("data").has("strategies")
                ? root.path("data") : root;
    }

    /**
     * Reads {@code pipelineConfigs.MCP.scm.{token,orgUser}} from the CICD
     * profile — the SAME source DeployService uses for the push. Used by the
     * read-only status poller so it authenticates GitHub Actions reads with
     * the pipeline's token/org, not a connector. Returns null when the MCP
     * pipeline isn't configured (caller then falls back to the connector).
     */
    private String[] fetchCicdScm(String onboardingId) {
        if (onboardingId == null || onboardingId.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode scm = cicdRoot(onboardingId)
                    .path("pipelineConfigs").path("MCP").path("scm");
            String token = scm.path("token").asText(null);
            String org   = scm.path("orgUser").asText(null);
            if (token == null || token.isBlank() || org == null || org.isBlank()) return null;
            return new String[]{ token, org };
        } catch (Exception e) {
            log.warn("[runs] CICD scm lookup for onboarding {} failed: {}", onboardingId, e.getMessage());
            return null;
        }
    }

    /**
     * The branch tagged {@code dev} in the profile's default strategy — the
     * branch the pipeline pushes the bundle to, and therefore the branch
     * {@code mcp.yml}'s push-trigger ({@code ${devBranch}}) must be baked to.
     * Returns null when unresolvable so the generator keeps its "main"
     * default instead of breaking generation.
     */
    public String fetchCicdDevBranch(String onboardingId) {
        if (onboardingId == null || onboardingId.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode strategies = cicdRoot(onboardingId).path("strategies");
            if (!strategies.isArray() || strategies.isEmpty()) return null;
            com.fasterxml.jackson.databind.JsonNode def = null;
            for (var s : strategies) {
                if (s.path("isDefault").asBoolean(false)) { def = s; break; }
            }
            if (def == null) def = strategies.get(0);
            for (var b : def.path("branches")) {
                if ("dev".equals(b.path("tag").asText(null))) {
                    String name = b.path("name").asText(null);
                    return (name == null || name.isBlank()) ? null : name.trim();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[bridge] CICD dev-branch lookup for onboarding {} failed: {}", onboardingId, e.getMessage());
            return null;
        }
    }

    /**
     * The {@code name} of the branch tagged {@code merge} ("Merge-To Branch",
     * e.g. "release") in the profile's default strategy. Baked into the
     * generated {@code mcp.yml} as {@code branch_tag}. Empty string when
     * unresolvable so the workflow just carries a blank value rather than
     * failing generation.
     */
    public String resolveBranchTag(McpProject p) {
        String onboardingId = p == null ? null : p.getOnboardingId();
        if (onboardingId == null || onboardingId.isBlank()) return "";
        try {
            com.fasterxml.jackson.databind.JsonNode strategies = cicdRoot(onboardingId).path("strategies");
            if (!strategies.isArray() || strategies.isEmpty()) return "";
            com.fasterxml.jackson.databind.JsonNode def = null;
            for (var s : strategies) {
                if (s.path("isDefault").asBoolean(false)) { def = s; break; }
            }
            if (def == null) def = strategies.get(0);
            for (var b : def.path("branches")) {
                if ("merge".equals(b.path("tag").asText(null))) {
                    String name = b.path("name").asText(null);
                    return (name == null || name.isBlank()) ? "" : name.trim();
                }
            }
            return "";
        } catch (Exception e) {
            log.warn("[bridge] CICD merge-branch lookup for onboarding {} failed: {}", onboardingId, e.getMessage());
            return "";
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
     * The branch the pipeline pushes the bundle to — and therefore the
     * branch {@code .github/workflows/mcp.yml}'s push-trigger
     * ({@code ${devBranch}}) must be baked to. Called at {@code generate()}
     * time.
     *
     * Source of truth is the CICD profile's default-strategy "dev" branch
     * (the SAME value DeployService pushes to). The connector's saved
     * {@code sourceCodeManagement.branch} is only a fallback for legacy
     * projects that haven't got a CICD profile yet. Falls back to "main"
     * when nothing resolves so generation never breaks.
     */
    /**
     * Resolve the CONCRETE dev branch this MCP is pushed to and deployed from.
     * Mirrors fsp-api-development-svc's {@code GitBranchResolver}: the
     * connector's saved {@code sourceCodeManagement.branch} is authoritative
     * (that's the branch the pipeline push actually lands on), and a CICD
     * default-strategy branch is only usable when it's a real name — the
     * strategy "name" is frequently a branch-protection glob like
     * {@code feature-*}, which is NOT a branch and must never reach the
     * generated workflow's push trigger or its deploy {@code if:} guard.
     */
    public String resolveDevBranch(McpProject p) {
        try {
            String fromConnector = fetchConnectorBranch(p);
            if (isConcreteBranch(fromConnector)) return fromConnector.trim();

            String fromCicd = fetchCicdDevBranch(p.getOnboardingId());
            if (isConcreteBranch(fromCicd)) return fromCicd.trim();

            log.warn("[bridge] resolveDevBranch: no concrete dev branch for project {} "
                    + "(connector='{}', cicd='{}') — defaulting to 'main'. Save a "
                    + "source-control branch on the linked connector to fix this.",
                    p.getId(), fromConnector, fromCicd);
            return "main";
        } catch (Exception e) {
            log.warn("[bridge] resolveDevBranch failed for project {}: {}", p.getId(), e.getMessage());
            return "main";
        }
    }

    /** A branch value is usable only if it's non-blank and not a glob pattern. */
    private static boolean isConcreteBranch(String b) {
        return b != null && !b.isBlank() && !b.contains("*");
    }

    /**
     * The connector's saved {@code sourceCodeManagement.branch}. Resolved by
     * connector id first, then (like GitBranchResolver) by the project's
     * organizationId so an org-level connector still counts.
     */
    private String fetchConnectorBranch(McpProject p) {
        org.bson.Document conn = null;
        String connectorId = resolveConnectorId(p);
        if (connectorId != null && !connectorId.isBlank()) {
            conn = bridgeColl(props.getColl().getConnector())
                    .find(new org.bson.Document("_id", parseIdMaybe(connectorId)))
                    .first();
        }
        if (conn == null) {
            String orgId = resolveOrganizationId(p);
            if (orgId != null && !orgId.isBlank()) {
                conn = bridgeColl(props.getColl().getConnector())
                        .find(new org.bson.Document("organizationId", orgId))
                        .first();
            }
        }
        if (conn == null) return null;
        org.bson.Document scm = conn.get("sourceCodeManagement", org.bson.Document.class);
        return scm == null ? null : scm.getString("branch");
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

    // Same hardcoded suffix as the frontend's lib/deploymentUrl.js
    // (MICROSERVICE_CLOUD_RUN_SUFFIX) — every service in probestack-prod/
    // us-central1 (microservice OR MCP) gets this exact Cloud Run URL
    // hash, since it's derived from the PROJECT+REGION, not the service.
    private static final String MCP_CLOUD_RUN_SUFFIX = "spipnh6wiq-uc.a.run.app";

    /**
     * Constructs the expected Cloud Run URL from the project's slug —
     * instant, no network call, no waiting on GitHub Actions. Returns
     * null when there's no slug to build from (identity never filled in),
     * which is the only case that still needs the artifact-based fetch.
     */
    private String buildDeployedUrlFromSlug(McpProject project) {
        if (project.getIdentity() == null || project.getIdentity().getSlug() == null) return null;
        String normalized = project.getIdentity().getSlug().toLowerCase().trim()
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) return null;
        return "https://" + normalized + "-" + MCP_CLOUD_RUN_SUFFIX;
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

            // On a fresh SUCCESS, set the deployed URL IMMEDIATELY by
            // constructing it — same approach as the frontend's
            // lib/deploymentUrl.js (MICROSERVICE_CLOUD_RUN_SUFFIX):
            // Cloud Run's URL hash is derived from the PROJECT+REGION,
            // not the individual service, so it's identical for every
            // service deployed to probestack-prod/us-central1 and can be
            // built from the slug alone the instant the run reports
            // success — no need to wait on (or even call) the GitHub
            // Actions artifact API. Only falls back to the slower
            // artifact-based fetch when there's no slug to construct
            // from at all.
            if ("SUCCESS".equals(deployStatus)
                    && (project.getDeployedServiceUrl() == null || project.getDeployedServiceUrl().isBlank())) {
                String healthPath = project.getAdvanced() != null && project.getAdvanced().getHealthCheck() != null
                        && project.getAdvanced().getHealthCheck().getPath() != null
                        && !project.getAdvanced().getHealthCheck().getPath().isBlank()
                        ? project.getAdvanced().getHealthCheck().getPath() : "/healthz";
                String constructedBase = buildDeployedUrlFromSlug(project);
                if (constructedBase != null) {
                    project.setDeployedServiceUrl(constructedBase);
                    project.setDeployedMcpUrl(constructedBase + "/mcp");
                    project.setDeployedHealthUrl(constructedBase + healthPath);
                    project.setDeployedAt(java.time.Instant.now());
                } else {
                    // No slug on this project — the only remaining way to
                    // learn the URL is the workflow's own artifact.
                    DeployedUrls fetched = fetchDeployedUrlFromArtifact(
                            creds.orgOrUser, creds.repo, String.valueOf(firstRun.get("id")), creds.token);
                    if (fetched != null && fetched.url() != null && !fetched.url().isBlank()) {
                        String base = fetched.url().replaceAll("/+$", "");
                        project.setDeployedServiceUrl(base);
                        project.setDeployedMcpUrl(fetched.mcpUrl() != null && !fetched.mcpUrl().isBlank()
                                ? fetched.mcpUrl() : base + "/mcp");
                        project.setDeployedHealthUrl(fetched.healthUrl() != null && !fetched.healthUrl().isBlank()
                                ? fetched.healthUrl() : base + healthPath);
                        project.setDeployedAt(java.time.Instant.now());
                    }
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
            out.put("headSha", firstRun.get("headSha"));
            out.put("headBranch", firstRun.get("headBranch"));
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

    /**
     * Per-step log text for one job of a workflow run.
     *
     * <p>GitHub only exposes step logs as a ZIP:
     * {@code GET /actions/jobs/{jobId}/logs} → 302 → a short-lived signed URL
     * that serves {@code application/zip} with one {@code "<n>_<step name>.txt"}
     * per step. We follow the redirect ourselves (the blob URL rejects the
     * {@code Authorization} header), unzip in memory, strip GitHub's per-line
     * ISO timestamps, tail-truncate each step to keep the payload sane, and
     * return a list keyed by step number.</p>
     *
     * <p>Best-effort: any failure returns {@code steps: []} (+ an {@code error}
     * string) — the UI just shows its "logs not captured" fallback.</p>
     *
     * @return {@code { "jobId": <id>, "steps": [ {number, name, log}, … ] }}
     */
    public Map<String, Object> getJobStepLogs(McpProject project, String runId, String jobId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        out.put("steps", java.util.List.of());
        if (jobId == null || jobId.isBlank()) return out;

        // 1) Stored failure logs win — GitHub purges Actions logs after ~90d and
        //    the reaper already grabbed the failing job's logs at terminal time.
        if (project != null && project.getId() != null && runId != null && !runId.isBlank()) {
            try {
                var stored = stepLogRepo.findByProjectIdAndRunIdAndJobId(project.getId(), runId, jobId).orElse(null);
                if (stored != null && stored.getSteps() != null && !stored.getSteps().isEmpty()) {
                    java.util.List<Map<String, Object>> steps = new java.util.ArrayList<>();
                    for (var s : stored.getSteps()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("number", s.getNumber());
                        m.put("name", s.getName());
                        m.put("conclusion", s.getConclusion());
                        m.put("log", s.getLog());
                        steps.add(m);
                    }
                    out.put("steps", steps);
                    out.put("source", "stored");
                    return out;
                }
            } catch (Exception ignore) { /* fall through to live fetch */ }
        }

        // 2) Live fetch from GitHub.
        var creds = resolveGitHubCreds(project);
        if (creds == null) { out.put("error", "No GitHub connector configured"); return out; }
        try {
            java.util.List<Map<String, Object>> steps = fetchJobStepLogsFromGitHub(creds, jobId, 60_000);
            out.put("steps", steps);
            out.put("source", "github");
            if (steps.isEmpty()) out.put("error", "GitHub returned no step logs for this job (run may be too old or still in progress).");
            return out;
        } catch (Exception ex) {
            log.warn("[runs] job-log fetch failed for job {}: {}", jobId, ex.getMessage());
            out.put("error", ex.getMessage());
            return out;
        }
    }

    /**
     * Downloads + unzips one job's GitHub Actions log archive into an ordered
     * list of {@code {number, name, log}} maps. {@code perStepMax} tail-truncates
     * each step. Throws on transport/zip errors so the caller can surface them.
     */
    private java.util.List<Map<String, Object>> fetchJobStepLogsFromGitHub(GhCreds creds, String jobId, int perStepMax)
            throws Exception {
        // Step 1: /jobs/{id}/logs → 302 to a short-lived signed URL. Do NOT
        // follow it with the auth header attached (the storage host 403s it).
        java.net.http.HttpClient apiClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        String api = "https://api.github.com/repos/" + creds.orgOrUser + "/" + creds.repo
                + "/actions/jobs/" + jobId + "/logs";
        var apiResp = apiClient.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(api))
                        .timeout(java.time.Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + creds.token)
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());

        byte[] zipBytes;
        int sc = apiResp.statusCode();
        if (sc / 100 == 3) {
            String loc = apiResp.headers().firstValue("location").orElse(null);
            if (loc == null || loc.isBlank()) throw new IllegalStateException("no redirect location (HTTP " + sc + ")");
            // Step 2: plain client, follows further redirects, NO auth header.
            java.net.http.HttpClient blobClient = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build();
            var blobResp = blobClient.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(loc))
                            .timeout(java.time.Duration.ofSeconds(40)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (blobResp.statusCode() / 100 != 2) throw new IllegalStateException("log archive HTTP " + blobResp.statusCode());
            zipBytes = blobResp.body();
        } else if (sc / 100 == 2) {
            zipBytes = apiResp.body();
        } else {
            throw new IllegalStateException("GitHub job-logs HTTP " + sc
                    + " — " + new String(apiResp.body(), java.nio.charset.StandardCharsets.UTF_8)
                            .replaceAll("\\s+", " ").substring(0, Math.min(200, apiResp.body().length)));
        }
        if (zipBytes == null || zipBytes.length < 4) throw new IllegalStateException("empty log archive");

        java.util.regex.Pattern numbered = java.util.regex.Pattern.compile("^(\\d+)_(.+?)\\.txt$");
        java.util.regex.Pattern tsPat =
                java.util.regex.Pattern.compile("(?m)^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z\\s?");
        java.util.List<Map<String, Object>> steps = new java.util.ArrayList<>();
        int fallbackIdx = 0;
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (!base.toLowerCase().endsWith(".txt")) continue;
                String text = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                text = tsPat.matcher(text).replaceAll("");
                if (text.length() > perStepMax) {
                    text = "…(truncated " + (text.length() - perStepMax) + " earlier chars)…\n"
                            + text.substring(text.length() - perStepMax);
                }
                var m = numbered.matcher(base);
                Integer num;
                String stepName;
                if (m.matches()) {
                    num = Integer.parseInt(m.group(1));
                    stepName = m.group(2).replace('_', ' ');
                } else {
                    num = ++fallbackIdx;
                    stepName = base.substring(0, base.length() - 4).replace('_', ' ');
                }
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("number", num);
                step.put("name", stepName);
                step.put("log", text);
                steps.add(step);
            }
        }
        steps.sort(java.util.Comparator.comparingInt(s -> ((Number) s.get("number")).intValue()));
        return steps;
    }

    /**
     * Persist the FAILING job(s)' step logs for a deploy run so they survive
     * GitHub's ~90-day log retention. Called by the reaper the instant it sees
     * a non-{@code success} terminal conclusion. Idempotent by {@code runId}.
     *
     * <p>Bounded: only the first failing step and everything after it is kept
     * per job, and the whole run is capped at {@code MAX_RUN_BYTES} (~500 KB).</p>
     */
    public void persistFailedDeployLogs(McpProject project, String runId) {
        if (project == null || project.getId() == null || runId == null || runId.isBlank()) return;
        final int MAX_RUN_BYTES = 500_000;
        try {
            if (stepLogRepo.existsByProjectIdAndRunId(project.getId(), runId)) return;
            var creds = resolveGitHubCreds(project);
            if (creds == null) return;

            Map<String, Object> jobsWrap = getWorkflowRunSteps(project, runId);
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> jobs =
                    (java.util.List<Map<String, Object>>) jobsWrap.getOrDefault("jobs", java.util.List.of());

            int remaining = MAX_RUN_BYTES;
            for (Map<String, Object> job : jobs) {
                String jobConcl = String.valueOf(job.getOrDefault("conclusion", ""));
                if ("success".equalsIgnoreCase(jobConcl) || jobConcl.isBlank() || "null".equals(jobConcl)) continue;
                if (remaining <= 0) break;

                String jobId = String.valueOf(job.get("id"));
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, Object>> jobSteps =
                        (java.util.List<Map<String, Object>>) job.getOrDefault("steps", java.util.List.of());

                // Index of the first failing step; keep it + everything after.
                int firstFail = -1;
                for (int i = 0; i < jobSteps.size(); i++) {
                    String sc = String.valueOf(jobSteps.get(i).getOrDefault("conclusion", ""));
                    if (!sc.isBlank() && !"success".equalsIgnoreCase(sc) && !"skipped".equalsIgnoreCase(sc) && !"null".equals(sc)) {
                        firstFail = i;
                        break;
                    }
                }

                java.util.List<Map<String, Object>> logSteps;
                try {
                    logSteps = fetchJobStepLogsFromGitHub(creds, jobId, Math.min(remaining, 80_000));
                } catch (Exception fe) {
                    log.warn("[reaper] failed-log fetch for job {} run {}: {}", jobId, runId, fe.getMessage());
                    continue;
                }

                java.util.List<McpDeployStepLog.StepLog> kept = new java.util.ArrayList<>();
                int total = 0;
                for (Map<String, Object> ls : logSteps) {
                    int lsNum = ((Number) ls.getOrDefault("number", 0)).intValue();
                    if (firstFail >= 0 && lsNum < (firstFail + 1)) continue; // GH step numbers are 1-based
                    String logText = String.valueOf(ls.getOrDefault("log", ""));
                    if (total + logText.length() > remaining) {
                        int room = Math.max(0, remaining - total);
                        logText = room == 0 ? "…(omitted — run byte budget reached)…"
                                : "…(truncated)…\n" + logText.substring(Math.max(0, logText.length() - room));
                    }
                    total += logText.length();
                    String stepConcl = null;
                    if (firstFail >= 0 && (lsNum - 1) < jobSteps.size() && (lsNum - 1) >= 0) {
                        stepConcl = String.valueOf(jobSteps.get(lsNum - 1).getOrDefault("conclusion", ""));
                    }
                    kept.add(McpDeployStepLog.StepLog.builder()
                            .number(lsNum)
                            .name(String.valueOf(ls.getOrDefault("name", "step " + lsNum)))
                            .conclusion(stepConcl)
                            .log(logText)
                            .build());
                    if (total >= remaining) break;
                }
                if (kept.isEmpty()) continue;
                remaining -= total;

                stepLogRepo.save(McpDeployStepLog.builder()
                        .projectId(project.getId())
                        .runId(runId)
                        .jobId(jobId)
                        .jobName(String.valueOf(job.getOrDefault("name", "job")))
                        .conclusion(jobConcl)
                        .steps(kept)
                        .totalBytes(total)
                        .truncated(total >= Math.min(MAX_RUN_BYTES, 80_000))
                        .createdAt(Instant.now())
                        .build());
                log.info("[reaper] stored {} failed-step log(s) for run {} job {} ({} bytes)",
                        kept.size(), runId, jobId, total);
            }
        } catch (Exception ex) {
            log.warn("[reaper] persistFailedDeployLogs run {} failed: {}", runId, ex.getMessage());
        }
    }

    // Internal connector-credential record returned by resolveGitHubCreds.
    private record GhCreds(String token, String orgOrUser, String repo) {}

    /**
     * Reads {token, orgOrUser, repo} off the project's connector.
     *
     * `repo` is NOT read from the connector's own {@code sourceCodeManagement.repo}
     * field — mirrors pushToGitHub() above: a connector is often shared across
     * multiple MCP projects and rarely has an explicit repo pinned on it (repo
     * name is derived per-project instead, collision-checked, and persisted as
     * {@code project.pushedRepoFullName} once the first push succeeds). Requiring
     * {@code scm.repo} here meant this always returned null — "No GitHub
     * connector configured" — for any project that pushed successfully through
     * the normal (derived-name) path, since the connector's `repo` field was
     * never populated to begin with. Prefer the project's own pushed repo,
     * falling back to the connector's explicit repo for the rare case it's set
     * before a first push has happened.
     */
    private GhCreds resolveGitHubCreds(McpProject project) {
        // ─── repo ───────────────────────────────────────────────────────
        // Pipeline path records "<org>/<repo>" on pipelineRepoFullName at
        // dispatch time; the legacy direct-push path uses pushedRepoFullName.
        String repoFull = firstNonBlank(project.getPipelineRepoFullName(), project.getPushedRepoFullName());
        String repo = null, repoOrg = null;
        if (repoFull != null) {
            String[] parts = repoFull.split("/", 2);
            if (parts.length == 2) { repoOrg = parts[0]; repo = parts[1]; }
            else repo = repoFull;
        }

        // ─── token + org: CICD "MCP" pipeline profile first ──────────────
        String token = null, orgOrUser = null;
        String[] cicdScm = fetchCicdScm(project.getOnboardingId());
        if (cicdScm != null) {
            token = cicdScm[0];
            orgOrUser = cicdScm[1];
        } else {
            // Fallback for legacy direct-push projects: the saved connector.
            String connectorId = resolveConnectorId(project);
            if (connectorId != null) {
                org.bson.Document conn = bridgeColl(props.getColl().getConnector())
                        .find(new org.bson.Document("_id", parseIdMaybe(connectorId)))
                        .first();
                org.bson.Document scm = conn == null ? null
                        : conn.get("sourceCodeManagement", org.bson.Document.class);
                if (scm != null) {
                    token = scm.getString("token");
                    orgOrUser = scm.getString("orgOrUser");
                    if (repo == null || repo.isBlank()) repo = scm.getString("repo");
                }
            }
        }

        // Prefer the org that actually owns the repo when we know it.
        if (repoOrg != null && !repoOrg.isBlank()) orgOrUser = repoOrg;

        if (token == null || orgOrUser == null || repo == null || repo.isBlank()) return null;
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
        out.put("headSha",       extractString (slice, "head_sha"));
        out.put("headBranch",    extractString (slice, "head_branch"));
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