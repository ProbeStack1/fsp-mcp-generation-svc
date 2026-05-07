package com.forgesphere.mcpgen.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * Global CORS — configurable via `mcpgen.cors.origins`. Wildcard methods +
 * headers because the consuming platform's auth filter will add whatever
 * header it needs (Authorization, X-Api-Key, custom session, …).
 */
@Configuration
public class CorsConfig {

    @Value("${mcpgen.cors.origins:*}")
    private String originsCsv;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration cfg = new CorsConfiguration();
        List<String> origins = Arrays.stream(originsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (origins.isEmpty() || origins.contains("*")) {
            cfg.addAllowedOriginPattern("*");
        } else {
            origins.forEach(cfg::addAllowedOrigin);
        }
        cfg.addAllowedMethod("*");
        cfg.addAllowedHeader("*");
        cfg.setAllowCredentials(false);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return new CorsFilter(src);
    }
}
