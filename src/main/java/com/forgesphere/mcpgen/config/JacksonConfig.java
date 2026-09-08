package com.forgesphere.mcpgen.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * forge-auth-lib's own autoconfiguration ({@code ForgeAuthnSecurityAutoConfiguration}) defines its
 * own plain {@code ObjectMapper} bean ({@code forgeAuthnObjectMapper}, used for its own JSON error
 * responses) with no modules registered. Once that bean exists, Boot's own {@code
 * JacksonAutoConfiguration} — the one that would normally register {@code JavaTimeModule}
 * automatically from whatever's on the classpath — backs off ({@code @ConditionalOnMissingBean}),
 * and Spring MVC's JSON message converter ends up using forge-auth-lib's bare one instead. The
 * result, confirmed for real on sibling services with the same forge-auth-lib dependency and no
 * prior ObjectMapper bean of their own (fsp-contract-testing-svc, fsp-compliance-svc,
 * fsp-code-review-svc): any response containing a plain {@code java.time.Instant} field throws
 * {@code InvalidDefinitionException: Java 8 date/time type java.time.Instant not supported by
 * default} instead of serializing — this service has several (McpProject#createdAt, etc.).
 * <p>
 * This service's own, {@code @Primary}, fully-configured {@code ObjectMapper} bean is what reliably
 * wins here — {@code @Primary} resolves the now-multiple-candidates ambiguity in this bean's favor
 * everywhere an unqualified {@code ObjectMapper} is autowired, forge-auth-lib's own beans included.
 * <p>
 * Built from the injected {@code Jackson2ObjectMapperBuilder} (Boot's own, still driven by this
 * service's own {@code spring.jackson.serialization.write-dates-as-timestamps=false} and {@code
 * spring.jackson.default-property-inclusion=non_null} properties) rather than a from-scratch
 * builder, so this fix adds JavaTimeModule without silently dropping that existing customization.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.modulesToInstall(new JavaTimeModule()).build();
    }
}
