package com.forgesphere.mcpgen.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the right {@link StorageClient} at startup:
 *   • If {@code forgesphere.storage.bucket} is set → real GCS client.
 *   • Otherwise → local-disk fallback (dev-friendly, no creds needed).
 *
 * Same auto-config layout as the forgeq mgmt services for consistency.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    @Bean
    public StorageClient storageClient(StorageProperties props) {
        if (props.getBucket() != null && !props.getBucket().isBlank()) {
            try {
                StorageClient c = GcsStorageClient.build(props);
                log.info("[storage] using GCS bucket={} project={}",
                        props.getBucket(), props.getProjectId() != null ? props.getProjectId() : "(default)");
                return c;
            } catch (Exception e) {
                log.error("[storage] GCS init failed ({}). Falling back to local-disk.", e.getMessage());
            }
        }
        return LocalDiskStorageClient.build(props);
    }
}
