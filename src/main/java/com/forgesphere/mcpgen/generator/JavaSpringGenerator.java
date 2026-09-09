package com.forgesphere.mcpgen.generator;

import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.GeneratedFile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Java (Spring Boot) MCP server scaffold. Produces a minimal Maven
 * project that wires up the MCP HTTP transport and exposes one
 * placeholder controller per tool. The user implements the tool body.
 *
 * Notes on what's emitted:
 *   • `HealthController` — exposes `/healthz` (and `/readyz`) returning
 *     `{"status":"ok"}`. Cloud Run's deploy workflow probes this so the
 *     image is only considered healthy once Spring has finished boot.
 *   • `OpenApiConfig` — wires up springdoc-openapi-ui. Swagger UI is
 *     served at `/swagger-ui.html` and the raw spec at `/v3/api-docs`.
 *   • `application.properties` listens on `${PORT:8080}` — Cloud Run
 *     injects `PORT=8080` so the JAR boots straight into the right
 *     port without any extra wiring.
 */
@Component
public class JavaSpringGenerator implements CodeGenerator {

    @Override public String language() { return "java"; }

    @Override
    public List<GeneratedFile> generate(McpProject spec) {
        var id   = spec.getIdentity();
        String artifact = id == null ? "mcp-server" : id.getSlug();
        String groupId  = "com.forgesphere.generated";
        String pkgLeaf  = artifact.replace("-", "").toLowerCase();
        String pkg      = groupId + "." + pkgLeaf;
        String displayName = id == null ? "mcp-server" : id.getDisplayName();
        // Fall back to the slug when no explicit displayName was sent by
        // the wizard — keeps the JavaSpring path from NPE-ing on payloads
        // where only the bare identity fields are populated (the FE
        // optionally surfaces displayName, not always).
        if (displayName == null || displayName.isBlank()) {
            displayName = (id != null && id.getSlug() != null && !id.getSlug().isBlank())
                    ? id.getSlug() : "mcp-server";
        }
        String displayQuoted = "\"" + displayName.replace("\"", "\\\"") + "\"";

        List<GeneratedFile> files = new ArrayList<>();

        files.add(file("pom.xml", pom(groupId, artifact).replace("<version>0.1.0</version>", "<version>" + GeneratorUtils.projectVersion(spec) + "</version>")
                .replace(artifact + "-0.1.0", artifact + "-" + GeneratorUtils.projectVersion(spec)), "xml"));

        String pkgDir = "src/main/java/" + groupId.replace('.', '/') + "/" + pkgLeaf;
        files.add(file(pkgDir + "/Application.java", """
                package %s;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class Application {
                    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
                }
                """.formatted(pkg), "java"));

        files.add(file(pkgDir + "/HealthController.java", """
                package %s;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                import java.util.Map;

                /**
                 * Lightweight liveness + readiness endpoints. Cloud Run's deploy
                 * pipeline curls `/healthz` after a rollout — the deploy is only
                 * marked successful once this returns 200.
                 */
                @RestController
                public class HealthController {
                    @GetMapping("/healthz")
                    public Map<String, Object> healthz() {
                        return Map.of("status", "ok", "service", %s);
                    }

                    @GetMapping("/readyz")
                    public Map<String, Object> readyz() {
                        return Map.of("status", "ready", "service", %s);
                    }
                }
                """.formatted(pkg, displayQuoted, displayQuoted), "java"));

        files.add(file(pkgDir + "/OpenApiConfig.java", """
                package %s;

                import io.swagger.v3.oas.models.OpenAPI;
                import io.swagger.v3.oas.models.info.Info;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                /**
                 * Swagger UI lives at `/swagger-ui.html` (springdoc default) and
                 * the raw OpenAPI document at `/v3/api-docs`. Both routes are
                 * automatically wired by `springdoc-openapi-starter-webmvc-ui`.
                 */
                @Configuration
                public class OpenApiConfig {
                    @Bean
                    public OpenAPI openApi() {
                        return new OpenAPI().info(new Info()
                                .title(%s)
                                .version(%s)
                                .description("Auto-generated MCP server endpoints."));
                    }
                }
                """.formatted(pkg, displayQuoted, GeneratorUtils.pretty(GeneratorUtils.projectVersion(spec))), "java"));

        files.add(file(pkgDir + "/McpController.java", GeneratorUtils.template("McpController.java.template").replace("__PACKAGE__", pkg).replace("System.getenv(", "RuntimeEnvironment.get("), "java"));
        files.add(file(pkgDir + "/RuntimeEnvironment.java", GeneratorUtils.template("RuntimeEnvironment.java.template").replace("__PACKAGE__", pkg), "java"));
        files.removeIf(f -> f.getPath().endsWith("/HealthController.java"));
        files.add(file(pkgDir + "/McpHttpFilter.java", GeneratorUtils.template("McpHttpFilter.java.template").replace("__PACKAGE__", pkg), "java"));
        files.add(file("src/main/resources/server-spec.json", GeneratorUtils.runtimeSpec(spec), "json"));
        files.add(file("src/main/resources/application.properties",
                "spring.config.import=optional:file:.env[.properties]\nserver.port=${PORT:8080}\nspring.application.name=" + artifact + "\nspringdoc.swagger-ui.path=/swagger-ui.html\nspringdoc.api-docs.path=/v3/api-docs\n",
                "properties"));

        files.add(file(".env.example", GeneratorUtils.envExample(spec), "dotenv"));
        String readyEnv = GeneratorUtils.envReady(spec);
        if (readyEnv != null) files.add(file(".env", readyEnv, "dotenv"));
        files.add(file("Dockerfile", dockerfile(spec, artifact).replace(artifact + "-0.1.0.jar", artifact + "-" + GeneratorUtils.projectVersion(spec) + ".jar"), "docker"));
        files.add(file("mcp.json", GeneratorUtils.pretty(GeneratorUtils.manifest(spec)), "json"));
        files.add(file("README.md", GeneratorUtils.commonReadme(spec) +
                "\n## Run locally\n\n```bash\nmvn spring-boot:run\n# or:\nmvn package && java -jar target/" + artifact + "-" + GeneratorUtils.projectVersion(spec) + ".jar\n```\n\n" +
                "## Endpoints\n\n" +
                "- `POST /mcp`            — JSON-RPC entry point\n" +
                "- `GET  /healthz`        — liveness probe (used by Cloud Run deploy)\n" +
                "- `GET  /readyz`         — readiness probe\n" +
                "- `GET  /swagger-ui.html` — interactive API docs\n" +
                "- `GET  /v3/api-docs`    — raw OpenAPI document\n",
                "markdown"));
        files.add(file(".gitignore", "target/\n.mvn/wrapper/maven-wrapper.jar\n.idea/\n*.iml\n.env\n", "gitignore"));


        // Senior dev's pipeline picks this workflow up — pushes the
        // image to the registry and rolls out a deploy. Same shape
        // across all languages.
        {
            String workflowYml = GeneratorUtils.buildGithubWorkflow(spec);
            files.add(GeneratedFile.builder()
                    .path(".github/workflows/mcp.yml")
                    .content(workflowYml)
                    .bytes(workflowYml == null ? 0 : workflowYml.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .mimeHint("text/yaml")
                    .build());
        }

        return files;
    }

    private String pom(String groupId, String artifact) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>0.1.0</version>
                  <packaging>jar</packaging>

                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.2.0</version>
                  </parent>

                  <properties>
                    <java.version>17</java.version>
                  </properties>

                  <dependencies>
                    <dependency><groupId>com.github.erosb</groupId><artifactId>everit-json-schema</artifactId><version>1.14.4</version></dependency>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-actuator</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springdoc</groupId>
                      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                      <version>2.3.0</version>
                    </dependency>
                  </dependencies>

                  <build>
                    <finalName>%s-0.1.0</finalName>
                    <plugins>
                      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(groupId, artifact, artifact);
    }

    /**
     * Build a self-contained Dockerfile that runs `mvn package` inside the
     * build stage so the Cloud Run runner doesn't need a pre-built JAR.
     * `EXPOSE 8080` matches the port Cloud Run forwards traffic to.
     */
    private String dockerfile(McpProject spec, String artifact) {
        String javaVer = "17";
        if (spec.getRuntime() != null && spec.getRuntime().getLanguageVersion() != null
                && spec.getRuntime().getLanguageVersion().startsWith("java")) {
            javaVer = spec.getRuntime().getLanguageVersion().substring(4);
        }
        return """
                # ── Builder stage ───────────────────────────────────────────
                FROM maven:3.9-eclipse-temurin-%s AS builder
                WORKDIR /build
                COPY pom.xml ./
                RUN mvn -B -q dependency:go-offline
                COPY src ./src
                RUN mvn -B -q -DskipTests package

                # ── Runtime stage ───────────────────────────────────────────
                FROM eclipse-temurin:%s-jre
                WORKDIR /app
                COPY --from=builder /build/target/%s-0.1.0.jar /app/app.jar
                ENV PORT=8080
                EXPOSE 8080
                ENTRYPOINT ["java","-jar","/app/app.jar"]
                """.formatted(javaVer, javaVer, artifact);
    }

    private GeneratedFile file(String path, String content, String hint) {
        return GeneratedFile.builder().path(path).content(content).bytes(content.getBytes().length).mimeHint(hint).build();
    }
}
