package com.forgesphere.mcpgen.bridge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the cross-service bridge that mirrors generated MCP
 * artefacts into the shared schema so the existing
 * {@code POST /api-development/.../{microserviceId}/deploy-to-github}
 * endpoint can publish them without any upstream changes.
 *
 * Two pieces of state live here:
 *
 *   1. GCS coordinates — bucket / project / SA credentials / path prefix
 *      for uploading the generated zip. The pipeline reads
 *      {@code codegen_results.gcsArchivePath} dynamically, so the path
 *      prefix is purely a convention to make MCP artefacts easy to spot
 *      next to normal microservice artefacts in the same bucket.
 *
 *   2. Collection name overrides — keep them as properties so a schema
 *      rename upstream is a config change, not a code change.
 *
 * Default values match what the api-development service uses today.
 */
@Data
@ConfigurationProperties(prefix = "forgesphere.bridge")
public class BridgeProperties {

    /** GCS settings for the upload of the generated zip. */
    private Gcs gcs = new Gcs();

    /** Mongo collection name overrides. */
    private Coll coll = new Coll();

    @Data
    public static class Gcs {
        /**  team's artefact bucket. e.g. {@code forgesphere}. */
        private String bucket;
        /** GCP project id (optional — inferred from credentials when blank). */
        private String projectId;
        /** Base64-encoded service-account JSON. Empty → ADC. */
        private String credentialsEncoded;
        /**
         * Prefix every uploaded object gets. Keeping MCP artefacts under
         * a distinct prefix (e.g. {@code generated-artifacts/mcpservers/})
         * makes them easy to spot side-by-side with the  team's
         * own microservice artefacts in the same bucket.
         */
        private String pathPrefix = "generated-artifacts/mcpservers/";
        /** Object content type written to GCS. */
        private String contentType = "application/zip";
    }

    @Data
    public static class Coll {
        /**
         * Database that the bridge writes the microservice / deployment /
         * codegen rows into.  Must match the database the api-development
         * service reads from — historically that has been the URI default
         * (probestack-forgeq) even though THIS service overrides
         * spring.data.mongodb.database to probestack-forgesphere.  Leave
         * blank to fall back to the MongoTemplate's database (legacy
         * behaviour, only safe when both services share a single DB).
         */
        private String database              = "";
        private String microservice          = "microservice";
        private String deploymentArtifacts   = "deployment_artifacts";
        private String codegenResults        = "codegen_results";
        /**
         * Connector collection — read-only lookup used as a fallback when
         * `mcpProject.onboarding.organizationId` is missing on a push.
         * The bridge resolves the org from the connector doc so the
         * mirrored microservice/deployment rows always carry the correct
         * organizationId (required by the  api-development service
         * to find the GitHub token).
         */
        private String connector             = "connector";
    }
}
