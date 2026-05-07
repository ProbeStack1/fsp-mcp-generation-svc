package com.forgesphere.mcpgen.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Storage configuration. Bind via:
 *   forgesphere.storage.bucket=...
 *   forgesphere.storage.project-id=...
 *   forgesphere.storage.credentials-encoded=<base64 service-account json>
 *   forgesphere.storage.fallback-dir=/tmp/forgesphere-mcp-zips
 */
@Data
@ConfigurationProperties(prefix = "forgesphere.storage")
public class StorageProperties {

    /** GCS bucket name. Required for GCS mode. */
    private String bucket;

    /** GCP project id (optional — inferred from credentials if absent). */
    private String projectId;

    /** Base64-encoded service-account JSON. Empty → ADC; empty + no ADC → local-disk fallback. */
    private String credentialsEncoded;

    /** Used by the local-disk fallback only. */
    private String fallbackDir = "/tmp/forgesphere-mcp-zips";
}
