package com.forgesphere.mcpgen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An MCP-generation project = one run of the wizard, persisted across
 * steps so the user can come back and edit. The whole wizard state lives
 * inside a single document to keep the API simple.
 *
 * Only `identity` and `capabilities` are the *actual* MCP spec — the
 * rest are runtime / transport / auth knobs that shape the generated
 * code. `generated` is populated after the first `POST /generate` call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "mcp_projects")
public class McpProject {

    @Id
    private String id;                    // generated UUID
    private String mockServerId; 

    @Indexed private String ownerEmail;   // free-form identifier supplied by caller
    @Indexed private String workspaceId;  // free-form identifier supplied by caller

    /**
     * Reference to the upstream `onboard_context._id`. Same field
     * microservice ({@code forgeq-microservice-mgmt-svc}) uses to link
     * its docs back to an Application onboarding.
     */
    @Indexed private String onboardingId;

    /**
     * Reference to the upstream `connector._id` chosen via 
     * `ConnectorModal` (sourceCodeManagement / cloudProvider /
     * databaseConnector are stored upstream — we just hold the pointer).
     */
    @Indexed private String connectorId;

    /**
     * Denormalised snapshot of the onboarding context — kept in sync at
     * write time so dashboards don't need a join with onboard_context.
     * Same pattern as microservice docs.
     */
    private Onboarding onboarding;

    /**
     * Provenance: 'blank' or the starter-template id the user picked
     * (e.g. 'github-issues', 'postgres-readonly'). Surfaces in dashboards
     * so we can answer "what % of MCP servers came from templates?"
     */
    @Indexed private String source;

    /**
     * Pointer back to the OpenAPI spec (in apiDesignService's own store)
     * that Step 3's capabilities were parsed from. The spec upload/select
     * itself always succeeds and the file stays in apiDesignService —
     * this project doc just never remembered WHICH one, so reopening the
     * wizard had no way to re-fetch the content for re-linting even
     * though it was never actually deleted. Saved alongside `capabilities`
     * so Step 4 can call apiDesignService.getSpecContent(specMetadataId)
     * again on resume instead of only showing the already-parsed result.
     */
    private String  specMetadataId;
    private String  specName;
    private String  specSource;   // 'library' | 'imported' | etc — same values Step 3 already uses

    /**
     * GCS object key (NOT the transient signed URL — that lives on
     * {@code generated.testCollectionUrl} and never survives a reload) of
     * the last scenario-based test collection built for this project.
     * Persisted so Step 8 can be fetched fresh through our own backend
     * (see {@code GET /projects/{id}/test-collection}) any time, in any
     * session, instead of only right after a same-session generate.
     */
    private String  testCollectionObjectKey;

    /**
     * Pointer to the persisted ZIP in object storage (set after first
     * download). Allows users to re-download from any device without
     * regenerating.
     */
    private String  zipObjectPath;
    private Long    zipBytes;
    private String  zipContentType;
    private Instant zipUploadedAt;

    private Identity    identity;
    private Capabilities capabilities;
    private Runtime     runtime;
    private Transport   transport;
    private Auth        auth;
    private Advanced    advanced;

    /**
     * In-memory only — explicitly NOT persisted to MongoDB. Generated source
     * files can be tens of KBs per file × dozens of files per project, which
     * was bloating the Mongo doc and burning Atlas storage. The canonical
     * artifact lives in GCS (see {@code zipObjectPath} / {@code gcsArchivePath});
     * any reader that needs the file list calls {@code McpGenerationService.generate(id)}
     * which recomputes deterministically from the spec.
     */
    @Transient
    private Generated   generated;        // null until `generate` is called; never written to Mongo

    @Indexed private Instant createdAt;
    private Instant updatedAt;
    private Instant lastDownloadedAt;     // set when the zip is fetched

    /**
     * Top-level audit identifiers — same shape Microservice / Proxy /
     * Onboarding docs already store. Frontend pushes the user's email
     * via the request body (per `onboardingService.js#withCreateAudit`)
     * and the catalog table reads from these exact fields so the
     * "Created By / Updated By" columns stay uniform across the platform.
     */
    private String createdBy;
    private String updatedBy;

    /**
     * Detailed activity log — our own structured feed for the in-wizard
     * "Deploy history" + "Activity timeline" panels. Senior's docs
     * don't need this; it lives alongside the top-level strings so we
     * never break their existing readers.
     */
    private AuditTrail auditTrail;

    /** "private" (default — only the creator sees this in the MCP Test Studio
     *  "My MCPs" filter) or "public" (every workspace user sees it). Auto-derived
     *  from auth selection: `auth.kind == 'none'` ⇒ public, else private. */
    private String visibility;

    /**
     * Deploy bridge state — populated by `POST /projects/{id}/deploy-to-github`.
     * The deploy endpoint mirrors our generated zip into the team's
     * shared `microservice` / `deployment_artifacts` / `codegen_results`
     * collections and then forwards to the existing api-development push pipeline.
     * The zip itself lives in GCS at `gcsArchivePath` (relative to the
     * shared bucket) — Mongo only stores the pointer.
     */
    @Indexed private String microserviceMirrorId;   // _id of mirrored doc in `microservice`
    private String  deploymentArtifactId;           // _id of mirrored doc in `deployment_artifacts`
    private String  codeGenResultId;                // _id of mirrored doc in `codegen_results`
    private String  gcsArchivePath;                 // gs object key under shared bucket
    private Instant mirroredAt;                     // last successful mirror write

    /**
     * Pipeline-deploy state — populated when `POST /projects/{id}/push-to-github`
     * runs the pipeline path (mirror → api-development `deploy-to-github` →
     * ForgeCrux `onboarding.yml`). The pipeline creates the repo and pushes,
     * so the repo/branch are recorded here at dispatch time (NOT after a
     * direct push) so the status poller / reaper can find the workflow run.
     */
    private String  repositoryName;                 // collision-free repo name we asked the pipeline to create
    private String  pipelineRepoFullName;           // "<org>/<repo>" resolved from CICD + repositoryName
    private String  pipelineRepoUrl;                // https://github.com/<org>/<repo>
    private String  pipelineBranch;                 // dev branch the pipeline pushed to (== mcp.yml ${devBranch})
    private String  deploymentId;                   // api-development DeploymentHistory id from the dispatch response
    private Instant deployTriggeredAt;              // when we dispatched the pipeline (bounds the reaper working set)
    /** Which artifact URL the mirror handed the pipeline: ENDPOINT (our
     *  /artifact.zip proxy) or SIGNED (a long-TTL V4 GCS signed URL) —
     *  chosen by a preflight on the endpoint at dispatch time. */
    private String  artifactUrlMode;

    /**
     * The connector's configured source branch (e.g. the CICD profile's
     * "dev" branch), resolved fresh at every {@code generate()} call so
     * the embedded GitHub Actions workflow's push-trigger always targets
     * the SAME branch the wizard actually pushes code to. Not persisted —
     * always recomputed from the connector so it can never go stale if
     * the connector is changed after a project already has a doc.
     */
    @Transient
    private String  devBranch;

    private String  pushedRepoFullName;             // e.g. "ForgeCrux/offer-subscription-sf"
    private String  pushedRepoUrl;
    private String  pushedBranch;
    private String  pushedCommitSha;
    private String  pushedActionsUrl;
    private Integer pushedFileCount;
    private Instant pushedAt;

    private String  latestRunId;                    // GitHub Actions run id
    private String  latestRunStatus;                // queued|in_progress|completed
    private String  latestRunConclusion;            // success|failure|cancelled|null
    private String  latestRunUrl;
    private Instant latestRunCheckedAt;

    /** Deployed service base URL parsed from the workflow's `deployment-url`
     *  artifact. Surfaced on the Test Studio "auto-register" flow and on the
     *  Dashboard MCP card so users land on the live URL with one click. */
    private String  deployedServiceUrl;
    /** {@code deployedServiceUrl + "/mcp"} — read straight off the same
     *  artifact (the workflow already computes it) rather than having the
     *  frontend re-derive it, so it's always exactly right even if the
     *  transport path convention ever changes. */
    private String  deployedMcpUrl;
    /** {@code deployedServiceUrl + <healthCheck.path>} — same idea. */
    private String  deployedHealthUrl;
    private Instant deployedAt;

    // ---------------- lifecycle flags ----------------

    /**
     * Soft-delete marker. List endpoints filter these out by default so a
     * project disappears from the catalog without losing its history.
     * Pair with {@code deleteEvent} for the audit trail. A deleted project
     * can be restored via {@code POST /projects/{id}/restore} as long as
     * it has not been hard-purged.
     */
    @Indexed private boolean softDeleted;
    private DeleteEvent deleteEvent;

    /**
     * Deprecation marks the project as "do not consume new" while keeping
     * the artifacts available for existing callers. Mirrors the same flag
     * used on senior's microservice docs.
     */
    private boolean deprecated;
    private Instant deprecatedAt;
    private String  deprecatedBy;
    private String  deprecationReason;

    /**
     * Parent pointer for projects produced by {@code POST /projects/{id}/clone}.
     * Lets the catalog show "Cloned from …" and lets us walk the lineage.
     */
    private String cloneOf;

    /**
     * Parent pointer + semver string for projects produced by
     * {@code POST /projects/{id}/version}. {@code versionOf} points at the
     * previous version's document id; {@code versionNumber} is the semver
     * the user picked for THIS document.
     */
    private String versionOf;
    private String versionNumber;          // e.g. "1.0.0"

    /**
     * Per-step completion ledger driving the wizard's progress bar and the
     * "Created by X on date" stamp on each completed step. Append-only —
     * the most recent entry for a given {@code stepNumber} wins on read.
     */
    @Builder.Default private List<StepCompletion> stepCompletion = new java.util.ArrayList<>();

    /**
     * Records of test/tool/mock executions triggered from the wizard. Kept
     * bounded to the most recent 50 entries by the service layer.
     */
    @Builder.Default private List<RunEntry> runHistory = new java.util.ArrayList<>();

    // ---------------- nested types ----------------

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Onboarding {
        private String organizationId;
        private String businessUnit;
        private String teamName;
        private String applicationName;
        private String applicationId;
        private String projectOwner;
        private String ownerEmail;
        private String projectSME;
        private String projectSMEEmail;
        private String projectDLEmail;
        private String expectedGoLiveDate;    // ISO date string from <input type=date>
        private String goLiveDate;
        private String testerName;
        private String testerEmail;
        private String serviceNowGroupName;
        private String serviceNowGroup;
        private String serviceNowEmail;
        @Builder.Default private List<String> consumerIds = List.of();

        /**
         * Step 2 (Requirements). Missing from this model entirely was the
         * bug behind "requirement nahi dikhta" — the frontend always sent
         * these two fields, but Jackson silently drops unknown JSON
         * properties when binding to a POJO with no matching field, so
         * they were never actually persisted despite the user typing
         * real text and the save call returning 200.
         */
        private String functionalRequirements;
        private String nonFunctionalRequirements;

        /**
         * Free-form bag where the wizard's Step 7 substeps stash the
         * user's generation choices (test kinds, helper artefacts,
         * client configs to ship). Stored as a {@code Map} on purpose
         * — adding a new option later should not require redeploying
         * the model.
         */
        @Builder.Default private java.util.Map<String, Object> generationOptions = new java.util.LinkedHashMap<>();

        /** Catalog summary mirrored from senior's requirements-svc so
         *  list pages can render a one-liner without a join. */
        private String requirementSummary;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Identity {
        private String displayName;
        private String slug;
        private String summary;
        private String description;
        private String category;      // AI, Code, Database, Productivity, DevOps …
        private String emoji;
        private String license;       // MIT, Apache-2.0, BSD-3, Proprietary
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Capabilities {
        @Builder.Default private List<Tool>     tools     = List.of();
        @Builder.Default private List<Resource> resources = List.of();
        @Builder.Default private List<Prompt>   prompts   = List.of();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Tool {
        private String name;                  // snake_case
        private String description;
        private Map<String, Object> inputSchema;   // JSON Schema
        private String outputType;            // structured-json | text | markdown | image
        private String sideEffects;           // read-only | writes | destructive
        private String implementationHint;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Resource {
        private String uriTemplate;           // e.g. "repo://{owner}/{name}/issues"
        private String name;
        private String description;
        private String mimeType;              // application/json, text/plain, …
        private String mode;                  // static | dynamic
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Prompt {
        private String name;                  // snake_case
        private String description;
        @Builder.Default private List<PromptArg> arguments = List.of();
        private String template;              // markdown body with {{placeholders}}
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PromptArg {
        private String name;
        private String description;
        private boolean required;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Runtime {
        private String language;        // typescript | python | java | raw
        private String languageVersion; // e.g. node20, py3.12, java17
        private String sdkVersion;      // e.g. "^1.0.0"
        private String bundler;         // TS: tsx | tsup | esbuild
        private String runner;          // py: python | uv | docker
        private String buildTool;       // java: maven | gradle
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Transport {
        private String kind;            // stdio | streamable-http | http-sse
        private String baseUrl;         // only when kind != stdio
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Auth {
        private String kind;            // none | bearer | api-key | oauth | custom
        private String headerName;      // e.g. "Authorization"
        private String generatedToken;  // only when kind == bearer (random hex)
        private OAuthConfig oauth;      // only when kind == oauth
        private String customMiddleware;// only when kind == custom
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OAuthConfig {
        private String issuerUrl;
        private String clientId;
        private String audience;
        @Builder.Default private List<String> scopes = List.of();
    }

    /**
     * Production-hardening knobs. All optional — the generator uses sane
     * defaults if this whole section is null.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Advanced {
        @Builder.Default private RateLimit   rateLimit    = RateLimit.builder().enabled(false).requestsPerMinute(60).build();
        @Builder.Default private Cors        cors         = Cors.builder().enabled(true).allowedOrigins("*").build();
        @Builder.Default private FlagPath    logging      = FlagPath.builder().enabled(true).build();
        @Builder.Default private FlagPath    healthCheck  = FlagPath.builder().enabled(true).path("/healthz").build();
        @Builder.Default private FlagPath    metrics      = FlagPath.builder().enabled(false).path("/metrics").build();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RateLimit { private boolean enabled; private int requestsPerMinute; }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Cors { private boolean enabled; private String allowedOrigins; }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FlagPath { private boolean enabled; private String path; }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Generated {
        @Builder.Default private List<GeneratedFile> files = List.of();
        private int totalBytes;
        private Instant generatedAt;
        private String checksum;
        // ─── NEW: URL for the scenario-based test collection (Postman + metadata) ───
        private String testCollectionUrl;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class GeneratedFile {
        private String path;            // e.g. "src/tools/get_issue.ts"
        private String content;         // UTF-8
        private int bytes;
        private String mimeHint;        // for syntax highlighting on the FE
    }

    // ─────────── Audit / activity ────────────────────────────────────────
    /**
     * Single actor stamp used inside edit/push/deploy entries. Carries the
     * user's email (matching the `userEmail` localStorage key used by the
     * senior team's onboarding / microservice services), a display name
     * when available, and the UTC timestamp the action was performed.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AuditActor {
        private String email;       // = `createdBy` / `updatedBy` top-level
        private String name;        // optional display name
        private Instant timestamp;  // UTC
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EditEntry {
        private AuditActor by;
        @Builder.Default private List<String> fieldsChanged = List.of();
        private String note;        // human readable summary
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PushEntry {
        private AuditActor by;
        private String repoFullName;
        private String repoUrl;
        private String branch;
        private String commitSha;
        private Integer fileCount;
        private String status;      // success | failed
        private String errorMessage;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DeployEntry {
        private AuditActor by;
        private String runId;
        private String runUrl;
        private String status;      // queued | in_progress | completed
        private String conclusion;  // success | failure | cancelled | null
        private String deployedUrl;
        private Long   durationMs;
        private String failedStep;      // name of the first failing step
        private String failedReason;    // log excerpt for that step
        private String rolledBackFrom;  // runId we rolled back from (if any)
        private String commitSha;       // pushedCommitSha at time of deploy
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AuditTrail {
        private AuditActor createdBy;
        private AuditActor lastUpdatedBy;
        @Builder.Default private List<EditEntry>   editHistory   = new java.util.ArrayList<>();
        @Builder.Default private List<PushEntry>   pushHistory   = new java.util.ArrayList<>();
        @Builder.Default private List<DeployEntry> deployHistory = new java.util.ArrayList<>();
        @Builder.Default private Integer totalEdits          = 0;
        @Builder.Default private Integer totalPushes         = 0;
        @Builder.Default private Integer totalDeploys        = 0;
        @Builder.Default private Integer totalDeploysSuccess = 0;
        @Builder.Default private Integer totalDeploysFailed  = 0;
    }

    /**
     * One row in {@link #stepCompletion}. The service appends a new row
     * every time a wizard step is marked complete (or re-completed on
     * re-entry), so callers can either read the latest entry per step or
     * walk the full history.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StepCompletion {
        private Integer stepNumber;     // 1..11
        private String  stepName;       // e.g. "MCP Design Validation"
        private AuditActor completedBy;
        private String  status;         // success | skipped | failed
        private String  note;           // optional, e.g. validator summary
    }

    /**
     * One row in {@link #runHistory}. Captures a single tool-call or
     * test-suite execution started from the wizard. Heavy outputs (full
     * tool response, large log) live in GCS; this row only carries the
     * summary the audit timeline needs.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RunEntry {
        private AuditActor by;
        private String kind;            // tool-simulate | test-case | static-analysis
        private String target;          // tool name / test id
        private String status;          // success | failed | timeout
        private Long   durationMs;
        private String resultSummary;   // short, human readable
        private String resultObjectKey; // optional GCS path for the full payload
    }

    /**
     * Captured when {@link #softDeleted} flips to {@code true}. Restoring
     * the project keeps the event for the audit trail but flips the flag
     * back to {@code false}.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DeleteEvent {
        private AuditActor by;
        private String reason;
        private Instant restoredAt;     // populated only after a restore
        private AuditActor restoredBy;
    }
}
