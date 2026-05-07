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
 * Note: the official Anthropic SDK is JS / Python-first; for JVM we
 * rely on a community-maintained shim + raw JSON-RPC over HTTP. The
 * scaffold makes this explicit in the README.
 */
@Component
public class JavaSpringGenerator implements CodeGenerator {

    @Override public String language() { return "java"; }

    @Override
    public List<GeneratedFile> generate(McpProject spec) {
        var id   = spec.getIdentity();
        String artifact = id == null ? "mcp-server" : id.getSlug();
        String groupId  = "com.forgesphere.generated";

        List<GeneratedFile> files = new ArrayList<>();

        files.add(file("pom.xml", pom(groupId, artifact), "xml"));

        String pkgDir = "src/main/java/" + groupId.replace('.', '/') + "/" + artifact.replace("-", "");
        files.add(file(pkgDir + "/Application.java", """
                package %s.%s;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class Application {
                    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
                }
                """.formatted(groupId, artifact.replace("-", "")), "java"));

        files.add(file(pkgDir + "/McpController.java", """
                package %s.%s;

                import org.springframework.web.bind.annotation.*;
                import java.util.*;

                /**
                 * JSON-RPC-over-HTTP entry point. Handles:
                 *   - initialize
                 *   - tools/list
                 *   - tools/call
                 * Fill in the tool bodies where marked TODO.
                 */
                @RestController
                @RequestMapping("/mcp")
                public class McpController {

                    @PostMapping
                    public Map<String, Object> handle(@RequestBody Map<String, Object> req) {
                        String method = (String) req.getOrDefault("method", "");
                        Object id = req.get("id");
                        switch (method) {
                            case "initialize":
                                return rpc(id, Map.of(
                                    "protocolVersion", "2024-11-05",
                                    "serverInfo", Map.of("name", %s, "version", "0.1.0"),
                                    "capabilities", Map.of("tools", Map.of("listChanged", false))));
                            case "tools/list":
                                return rpc(id, Map.of("tools", listTools()));
                            case "tools/call":
                                @SuppressWarnings("unchecked") Map<String,Object> params = (Map<String,Object>) req.getOrDefault("params", Map.of());
                                return rpc(id, Map.of("content", List.of(Map.of("type","text","text", callTool((String)params.get("name"), (Map)params.get("arguments"))))));
                            default:
                                return Map.of("jsonrpc","2.0","id",id,"error", Map.of("code",-32601,"message","method not found"));
                        }
                    }

                    private Map<String, Object> rpc(Object id, Object result) {
                        return Map.of("jsonrpc","2.0","id",id,"result",result);
                    }

                    private List<Map<String, Object>> listTools() {
                        // TODO: fill with the tools defined in the wizard
                        return List.of();
                    }

                    private String callTool(String name, Map<String, Object> args) {
                        return "TODO: implement " + name + " with " + args;
                    }
                }
                """.formatted(groupId, artifact.replace("-", ""),
                '"' + (id == null ? "mcp-server" : id.getDisplayName()) + '"'), "java"));

        files.add(file("src/main/resources/application.properties",
                "server.port=${PORT:3500}\nspring.application.name=" + artifact + "\n", "properties"));

        files.add(file(".env.example", GeneratorUtils.envExample(spec), "dotenv"));
        files.add(file("Dockerfile", GeneratorUtils.dockerfile(spec), "docker"));
        files.add(file("mcp.json", GeneratorUtils.pretty(GeneratorUtils.manifest(spec)), "json"));
        files.add(file("README.md", GeneratorUtils.commonReadme(spec) +
                "\n## Run locally\n\n```bash\n./mvnw spring-boot:run\n# or:\n./mvnw package && java -jar target/" + artifact + "-0.1.0.jar\n```\n", "markdown"));
        files.add(file(".gitignore", "target/\n.mvn/wrapper/maven-wrapper.jar\n.idea/\n*.iml\n.env\n", "gitignore"));


        // Senior dev's pipeline picks this workflow up — pushes the
        // image to the registry and rolls out a deploy. Same shape
        // across all languages.
        files.add(GeneratedFile.builder()
                .path(".github/workflows/mcp.yml")
                .content(GeneratorUtils.buildGithubWorkflow(spec))
                .mimeHint("text/yaml")
                .build());

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
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
                  </dependencies>

                  <build>
                    <plugins>
                      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(groupId, artifact);
    }

    private GeneratedFile file(String path, String content, String hint) {
        return GeneratedFile.builder().path(path).content(content).bytes(content.getBytes().length).mimeHint(hint).build();
    }
}
