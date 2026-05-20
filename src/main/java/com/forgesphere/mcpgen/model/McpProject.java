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
    private Instant deployedAt;

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
}
