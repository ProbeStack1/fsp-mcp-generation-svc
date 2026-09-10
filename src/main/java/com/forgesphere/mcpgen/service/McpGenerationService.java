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
        p.setVersionNumber("1.0.0");
        if (p.getIdentity().getSlug() != null && !p.getIdentity().getSlug().isBlank())
            p.setVersionKey(versionKey(p, p.getVersionNumber()));

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

    private static final com.fasterxml.jackson.databind.ObjectMapper DIFF_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Structural equality for a patch section vs. the stored value. A
     *  null patch section means "not supplied" and never counts as a change. */
    private static boolean sectionEq(Object patchValue, Object current) {
        if (patchValue == null) return true;          // not supplied → no change
        if (patchValue == current) return true;
        try { return DIFF_JSON.valueToTree(patchValue).equals(DIFF_JSON.valueToTree(current)); }
        catch (Exception e) { return false; }
    }

    /**
     * The top-level sections whose incoming value actually differs from
     * what's stored. The wizard re-sends the whole project on every
     * "Next", so a plain "field present in body" check marked EVERYTHING
     * changed on every step — this compares values so the activity feed
     * reflects real edits.
     */
    public List<String> diffSections(String id, McpProject patch) {
        McpProject cur = repo.findById(id).orElse(null);
        if (cur == null) return List.of();
        List<String> changed = new ArrayList<>();
        if (!sectionEq(patch.getIdentity(),     cur.getIdentity()))     changed.add("identity");
        if (!sectionEq(patch.getCapabilities(), cur.getCapabilities())) changed.add("capabilities");
        if (!sectionEq(patch.getRuntime(),      cur.getRuntime()))      changed.add("runtime");
        if (!sectionEq(patch.getTransport(),    cur.getTransport()))    changed.add("transport");
        if (!sectionEq(patch.getAuth(),         cur.getAuth()))         changed.add("auth");
        if (!sectionEq(patch.getAdvanced(),     cur.getAdvanced()))     changed.add("advanced");
        if (!sectionEq(patch.getOnboarding(),   cur.getOnboarding()))   changed.add("onboarding");
        if (patch.getConnectorId() != null
                && !java.util.Objects.equals(patch.getConnectorId(), cur.getConnectorId())) changed.add("connector");
        return changed;
    }

    public McpProject update(String id, McpProject patch) {
        McpProject cur = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        boolean touched = false;
        // Only write a section when its value actually changed — keeps
        // updatedAt / versionKey / persistence churn tied to real edits.
        if (patch.getIdentity()     != null && !sectionEq(patch.getIdentity(),     cur.getIdentity()))     { cur.setIdentity(patch.getIdentity());         touched = true; }
        if (patch.getCapabilities() != null && !sectionEq(patch.getCapabilities(), cur.getCapabilities())) { cur.setCapabilities(patch.getCapabilities()); touched = true; }
        if (patch.getRuntime()      != null && !sectionEq(patch.getRuntime(),      cur.getRuntime()))      { cur.setRuntime(patch.getRuntime());           touched = true; }
        if (patch.getTransport()    != null && !sectionEq(patch.getTransport(),    cur.getTransport()))    { cur.setTransport(patch.getTransport());       touched = true; }
        if (patch.getAuth()         != null && !sectionEq(patch.getAuth(),         cur.getAuth()))         { cur.setAuth(patch.getAuth());                 touched = true; }
        if (patch.getAdvanced()     != null && !sectionEq(patch.getAdvanced(),     cur.getAdvanced()))     { cur.setAdvanced(patch.getAdvanced());         touched = true; }
        if (patch.getOwnerEmail()   != null && !java.util.Objects.equals(patch.getOwnerEmail(),   cur.getOwnerEmail()))   { cur.setOwnerEmail(patch.getOwnerEmail());     touched = true; }
        if (patch.getWorkspaceId()  != null && !java.util.Objects.equals(patch.getWorkspaceId(),  cur.getWorkspaceId()))  { cur.setWorkspaceId(patch.getWorkspaceId());   touched = true; }
        if (patch.getOnboardingId() != null && !java.util.Objects.equals(patch.getOnboardingId(), cur.getOnboardingId())) { cur.setOnboardingId(patch.getOnboardingId()); touched = true; }
        if (patch.getConnectorId()  != null && !java.util.Objects.equals(patch.getConnectorId(),  cur.getConnectorId()))  { cur.setConnectorId(patch.getConnectorId());   touched = true; }
        if (patch.getOnboarding()   != null && !sectionEq(patch.getOnboarding(), cur.getOnboarding()))     { cur.setOnboarding(patch.getOnboarding());     touched = true; }
        if (patch.getSource()       != null && !sectionEq(patch.getSource(),     cur.getSource()))         { cur.setSource(patch.getSource());             touched = true; }
        if (patch.getSpecMetadataId() != null && !java.util.Objects.equals(patch.getSpecMetadataId(), cur.getSpecMetadataId())) { cur.setSpecMetadataId(patch.getSpecMetadataId()); touched = true; }
        if (patch.getSpecName()       != null && !java.util.Objects.equals(patch.getSpecName(),       cur.getSpecName()))       { cur.setSpecName(patch.getSpecName());       touched = true; }
        if (patch.getSpecSource()     != null && !java.util.Objects.equals(patch.getSpecSource(),     cur.getSpecSource()))     { cur.setSpecSource(patch.getSpecSource());   touched = true; }
        if (patch.getTestRunResults() != null && !sectionEq(patch.getTestRunResults(), cur.getTestRunResults()))               { cur.setTestRunResults(patch.getTestRunResults()); touched = true; }
        if (!touched) return cur;   // genuine no-op — don't bump updatedAt or persist
        if (cur.getVersionNumber() != null && cur.getIdentity() != null && cur.getIdentity().getSlug() != null)
            cur.setVersionKey(versionKey(cur, cur.getVersionNumber()));
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
        return clone(id, actor, newSlug, null, null);
    }

    public McpProject clone(String id, McpProject.AuditActor actor, String newSlug, String newVersion, String displayName) {
        McpProject src = mustGet(id);
        McpProject copy = deepCopy(src);
        copy.setId(UUID.randomUUID().toString());
        copy.setCloneOf(src.getId());
        copy.setVersionOf(null);
        String initialVersion = newVersion == null || newVersion.isBlank() ? "1.0.0" : newVersion.trim();
        if (!initialVersion.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))
            throw new IllegalArgumentException("Clone version must be a release number such as 1.2.3");
        copy.setVersionNumber(initialVersion);
        copy.setVersionKey(null);
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
                    ? newSlug.trim()
                    : safeSlug(copy.getIdentity().getSlug()).substring(0, Math.min(58, safeSlug(copy.getIdentity().getSlug()).length())) + "-copy";
            if (!slug.matches("[a-z][a-z0-9-]{1,62}")) throw new IllegalArgumentException("Clone slug must use lowercase letters, digits and hyphens (2-63 characters)");
            String base = slug;
            int suffix = 2;
            while (!repo.findByWorkspaceIdAndIdentitySlugOrderByCreatedAtDesc(copy.getWorkspaceId(), slug).isEmpty()) {
                if (newSlug != null && !newSlug.isBlank()) throw new IllegalArgumentException("A project with this clone slug already exists");
                String ending = "-" + suffix++;
                slug = base.substring(0, Math.min(base.length(), 63 - ending.length())) + ending;
            }
            copy.getIdentity().setSlug(slug);
            copy.setVersionKey(versionKey(copy, copy.getVersionNumber()));
            if (displayName != null && !displayName.isBlank()) copy.getIdentity().setDisplayName(displayName.trim());
            else if (copy.getIdentity().getDisplayName() != null) {
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
        var siblings = repo.findByWorkspaceIdAndIdentitySlugOrderByCreatedAtDesc(src.getWorkspaceId(), src.getIdentity().getSlug());
        String latest = src.getVersionNumber() == null ? "0.1.0" : src.getVersionNumber();
        for (McpProject sibling : siblings) {
            String candidate = sibling.getVersionNumber();
            if (candidate != null && candidate.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)") && compareVersions(candidate, latest) > 0) latest = candidate;
        }
        String version = newVersion != null && !newVersion.isBlank() ? newVersion.trim() : nextSemver(latest);
        if (!version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))
            throw new IllegalArgumentException("Version must be a release number such as 1.2.3");
        if (compareVersions(version, latest) <= 0) throw new IllegalArgumentException("New version must be greater than " + latest);
        copy.setVersionNumber(version);
        copy.setVersionKey(versionKey(copy, version));
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
        copy.setGenerated(null);
        copy.setMockServerId(null);
        copy.setTestCollectionObjectKey(null);
        copy.setDevBranch(null);
        copy.setBranchTag(null);
        if (copy.getTransport() != null && copy.getTransport().getBaseUrl() != null
                && (copy.getTransport().getBaseUrl().equals(copy.getDeployedServiceUrl())
                || copy.getTransport().getBaseUrl().equals(copy.getDeployedMcpUrl()))) copy.getTransport().setBaseUrl(null);
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
              var patch = new java.math.BigInteger(parts[2]);
              return parts[0] + "." + parts[1] + "." + patch.add(java.math.BigInteger.ONE);
        } catch (NumberFormatException e) {
            return prev + ".1";
        }
    }

    private static int compareVersions(String first, String second) {
        String[] a = first.split("\\."); String[] b = second.split("\\.");
        if (a.length != 3 || b.length != 3) throw new IllegalArgumentException("Existing version is not a valid release number");
        for (int i = 0; i < 3; i++) {
            int comparison = new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i]));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static String versionKey(McpProject p, String version) {
        return String.valueOf(p.getWorkspaceId()) + "|" + p.getIdentity().getSlug() + "|" + version;
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
        // Backfills a default URI for any resource missing one (returns a
        // review-warning per fix) instead of throwing and blocking the
        // wizard — see GeneratorUtils.validateResources.
        List<String> resourceWarnings =
                com.forgesphere.mcpgen.generator.GeneratorUtils.validateResources(p);
        String lang = p.getRuntime() == null ? "typescript" : p.getRuntime().getLanguage();
        String transportKind = p.getTransport() == null ? "streamable-http" : p.getTransport().getKind();
        if ("java".equalsIgnoreCase(lang) && !"streamable-http".equals(transportKind))
            throw new IllegalArgumentException("Java generation currently supports Streamable HTTP; select that transport.");
        if ("http-sse".equals(transportKind))
            throw new IllegalArgumentException("Legacy SSE generation is not implemented. Select Streamable HTTP or stdio.");
        String authKind = p.getAuth() == null ? "none" : p.getAuth().getKind();
        if (authKind != null && !List.of("none", "bearer", "api-key").contains(authKind))
            throw new IllegalArgumentException("OAuth/custom authentication requires a verified middleware implementation and cannot be generated yet.");
        // Auto-provision the shared secret. bearer / api-key auth is the
        // default, but clicking "Generate" on the token in the wizard is
        // optional — and a state flush can lag the generate click, so the
        // token may still be blank here even when the user did generate one.
        // Either way, if it's blank we mint it now and persist it below.
        // Without this the deploy bakes NO MCP_AUTH_TOKEN / MCP_API_KEY into
        // mcp.yml and the deployed server 401s every request; minting here
        // keeps the deployed env var and the value the Test page / inspector
        // send in lock-step.
        if (("bearer".equals(authKind) || "api-key".equals(authKind))
                && (p.getAuth().getGeneratedToken() == null || p.getAuth().getGeneratedToken().isBlank())) {
            p.getAuth().setGeneratedToken(
                    com.forgesphere.mcpgen.generator.GeneratorUtils.randomToken());
            resourceWarnings = new ArrayList<>(resourceWarnings);
            resourceWarnings.add("No " + authKind + " token was set — generated one automatically. "
                    + "It is baked into the deploy and shown on the Test page.");
            log.info("Auto-generated {} token for MCP project {} (none was set)", authKind, p.getId());
        }
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
                .files(files).totalBytes(total).generatedAt(Instant.now())
                .warnings(resourceWarnings).build());
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
        return postProcessor.clientConfigs(p);
    }

    /** Tiny bridge so the service can reuse the manifest builder in GeneratorUtils. */
    static final class GeneratorUtilsProxy {
        static Map<String, Object> manifest(McpProject p) {
            return com.forgesphere.mcpgen.generator.GeneratorUtils.manifest(p);
        }
    }
}
