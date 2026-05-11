package com.forgesphere.mcpgen.bridge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the bridge GCS client at startup. If
 * {@code forgesphere.bridge.gcs.bucket} is missing we still register a
 * disabled stub so the bridge service can throw a friendly error
 * instead of an opaque {@code NoSuchBeanDefinitionException}.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(BridgeProperties.class)
public class BridgeAutoConfiguration {

    @Bean
    public BridgeGcsClient bridgeGcsClient(BridgeProperties props) {
        BridgeProperties.Gcs gcs = props.getGcs();
        if (gcs.getBucket() == null || gcs.getBucket().isBlank()) {
            log.warn("[bridge] forgesphere.bridge.gcs.bucket is blank — deploy-to-github will fail until set");
            return new BridgeGcsClient(null, null, gcs.getPathPrefix(), gcs.getContentType()) {
                @Override public String upload(String objectKey, byte[] bytes) {
                    throw new IllegalStateException(
                            "Bridge GCS bucket is not configured. Set forgesphere.bridge.gcs.bucket "
                          + "to the team's artefact bucket (e.g. 'forgesphere').");
                }
            };
        }
        BridgeGcsClient c = BridgeGcsClient.build(gcs);
        log.info("[bridge] using GCS bucket={} prefix={} project={}",
                gcs.getBucket(), gcs.getPathPrefix(),
                gcs.getProjectId() != null ? gcs.getProjectId() : "(default)");
        return c;
    }
}
