package com.forgesphere.mcpgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.GeneratedFile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Post-processor that runs right after a language-specific
 * {@code CodeGenerator} finishes. Its job is to make sure the file list
 * actually reflects what the user picked in Step 7's
 * "Integrations &amp; Tests" + "MCP Clients" sub-tabs:
 *
 * <ul>
 *   <li>strip per-test-kind files if the user unchecked them
 *       (unit / integration / contract / fuzz / load),</li>
 *   <li>append a Postman collection at {@code postman/collection.json}
 *       when {@code helpers.postman} is on,</li>
 *   <li>append an MCP Inspector workspace at
 *       {@code .mcp-inspector/workspace.json} when
 *       {@code helpers.mcpInspector} is on,</li>
 *   <li>append a Dockerfile when {@code helpers.dockerfile} is on and
 *       the transport is HTTP-flavoured (no sense shipping one for
 *       stdio), and</li>
 *   <li>append the chosen client config snippets (Claude, Cursor,
 *       ForgeQ, VS Code) under {@code client-configs/}.</li>
 * </ul>
 *
 * <p>Everything written here also makes it into the eventual
 * Step-11 bundle automatically — the bundle builder reads the same
 * {@code generated.files} list — so we get a single source of truth.</p>
 */
@Service
public class GenerationPostProcessor {

    private final ObjectMapper json = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Mutate {@code files} in place to match {@code project}'s
     * {@code onboarding.generationOptions}. Returns the same list for
     * chaining convenience.
     */
    public List<GeneratedFile> apply(McpProject project, List<GeneratedFile> files) {
        Options opts = readOptions(project);
        // Replace the old per-tool smoke scaffolds with executable native tests.
        files.removeIf(f -> "tests/tools.test.ts".equals(f.getPath()));
        if (opts.testsUnit) {
            String language = lang(project);
            if ("typescript".equals(language)) replaceFile(files, "tests/http.test.ts", com.forgesphere.mcpgen.generator.GeneratorUtils.template("http.test.ts"), "typescript");
            if ("python".equals(language)) replaceFile(files, "tests/test_server.py", com.forgesphere.mcpgen.generator.GeneratorUtils.template("test_server.py"), "python");
            if ("java".equals(language)) {
                String pkg = "com.forgesphere.generated." + safeSlug(project).replace("-", "").toLowerCase();
                replaceFile(files, "src/test/java/" + pkg.replace('.', '/') + "/McpControllerTest.java",
                        com.forgesphere.mcpgen.generator.GeneratorUtils.template("McpControllerTest.java.template").replace("__PACKAGE__", pkg), "java");
            }
        }

        // 1) Drop test files the user unchecked. We classify by path
        //    pattern because the generators emit different filenames per
        //    language (tests/tools.test.ts, tests/test_tools.py, etc.).
        files.removeIf(f -> isUnitTest(f.getPath())        && !opts.testsUnit);
        files.removeIf(f -> isIntegrationTest(f.getPath()) && !opts.testsIntegration);
        files.removeIf(f -> isContractTest(f.getPath())    && !opts.testsContract);
        files.removeIf(f -> isFuzzTest(f.getPath())        && !opts.testsFuzz);
        files.removeIf(f -> isLoadTest(f.getPath())        && !opts.testsLoad);

        // 2) If the user kept integration/contract on but the language
        //    generator didn't ship a starter for that kind, scaffold one
        //    here so the test-cases panel has something to display.
        if (opts.testsIntegration && !hasFileMatching(files, GenerationPostProcessor::isIntegrationTest)) {
            files.add(integrationTestStub(project));
        }
        if (opts.testsContract && !hasFileMatching(files, GenerationPostProcessor::isContractTest)) {
            files.add(contractTestStub(project));
        }
        if (opts.testsFuzz && !hasFileMatching(files, GenerationPostProcessor::isFuzzTest)) {
            files.add(fuzzTestStub(project));
        }
        if (opts.testsLoad && !hasFileMatching(files, GenerationPostProcessor::isLoadTest)
                && isHttpTransport(project)) {
            files.add(loadTestStub(project));
        }

        replaceFile(files, "tests/protocol.mjs", com.forgesphere.mcpgen.generator.GeneratorUtils.template("protocol.mjs"), "javascript");
        // 3) Helper artefacts. We dedupe by path so re-generation never
        //    creates two "postman/collection.json" entries.
        if (opts.helpersPostman && isHttpTransport(project)) {
            replaceFile(files, "postman/collection.json", buildPostman(project), "json");
        }
        if (opts.helpersPostman && !isHttpTransport(project)) replaceFile(files, "postman/README.md",
                "Postman collections require an HTTP endpoint. This project uses stdio; run the generated protocol tests with MCP_STDIO_COMMAND, or regenerate using Streamable HTTP to use Postman.\n", "markdown");
        if (opts.helpersMcpInspector) {
            replaceFile(files, ".mcp-inspector/workspace.json", buildInspector(project), "json");
        }
        if (opts.helpersDockerfile && isHttpTransport(project) && !hasFileMatching(files, "Dockerfile"::equals)) {
            replaceFile(files, "Dockerfile", buildDockerfile(project), "dockerfile");
        }
        if (opts.helpersGithubWorkflows
                && !hasFileMatching(files, p -> p.startsWith(".github/workflows/"))) {
            replaceFile(files, ".github/workflows/ci.yml", buildCiWorkflow(project), "yaml");
        }

        // 4) Client config snippets — same files the bundle would emit,
        //    but inlined into the source tree so Step 8 can preview them
        //    and `git diff` shows what changed across versions.
        if (opts.clientClaude)
            replaceFile(files, "client-configs/claude-desktop.json", buildClaudeConfig(project), "json");
        if (opts.clientClaude && isHttpTransport(project)) {
            replaceFile(files, "client-configs/http-bridge.mjs", com.forgesphere.mcpgen.generator.GeneratorUtils.template("http-bridge.mjs"), "javascript");
            replaceFile(files, "client-configs/package.json", "{\"private\":true,\"type\":\"module\",\"dependencies\":{\"@modelcontextprotocol/sdk\":\"^1.12.0\"}}", "json");
            replaceFile(files, "client-configs/README.md", "Run `npm install --prefix client-configs`. Replace absolute path placeholders in the client JSON with the extracted project location and replace token placeholders. Claude Desktop uses the included stdio-to-HTTP adapter. Cursor and VS Code use the HTTP endpoint directly. Set mcpUrl in Postman to your running endpoint.\n", "markdown");
        }
        if (opts.helpersGithubWorkflows && !isHttpTransport(project)) {
            files.removeIf(f -> f.getPath().startsWith(".github/workflows/"));
            replaceFile(files, ".github/workflows/ci.yml", buildCiWorkflow(project), "yaml");
        }
        if (opts.clientCursor)
            replaceFile(files, "client-configs/cursor-mcp.json",     buildCursorConfig(project), "json");
        if (opts.clientForgeQ)
            replaceFile(files, "client-configs/forgeq.json",         buildForgeqConfig(project), "json");
        if (opts.clientVscode)
            replaceFile(files, "client-configs/vscode.json",         buildVscodeConfig(project), "json");

        if (!opts.helpersDockerfile) files.removeIf(f -> "Dockerfile".equals(f.getPath()));
        if (!opts.helpersGithubWorkflows) files.removeIf(f -> f.getPath().startsWith(".github/workflows/"));
        if (!opts.testsUnit) for (GeneratedFile file : files) {
            if (file.getPath().startsWith(".github/workflows/")) {
                String content = file.getContent().replace("run: pytest -q", "run: echo 'Unit tests disabled in generation options'")
                        .replace("run: npm test --silent", "run: echo 'Unit tests disabled in generation options'")
                        .replace("run: mvn -B test", "run: echo 'Unit tests disabled in generation options'");
                file.setContent(content); file.setBytes(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
        }

        return files;
    }

    // ─────────── Option reader ─────────────────────────────────────────

    /** Tiny POJO so the apply() body stays readable. */
    private static class Options {
        boolean testsUnit        = true;
        boolean testsIntegration = true;
        boolean testsContract    = true;
        boolean testsFuzz        = false;
        boolean testsLoad        = false;
        boolean helpersPostman         = true;
        boolean helpersMcpInspector    = true;
        boolean helpersGithubWorkflows = true;
        boolean helpersDockerfile      = true;
        boolean clientClaude  = true;
        boolean clientCursor  = true;
        boolean clientForgeQ  = true;
        boolean clientVscode  = false;
    }

    /**
     * Pull the user's checkboxes off the project document. Anything we
     * can't read falls back to the defaults in {@link Options} so a
     * project created before the picker shipped still generates
     * sensibly. {@code generationOptions} lives on
     * {@code onboarding.generationOptions} — that's where the wizard
     * persists it (see SubIntegrationsTests / SubMcpClients).
     */
    @SuppressWarnings("unchecked")
    private Options readOptions(McpProject project) {
        Options o = new Options();
        if (project == null || project.getOnboarding() == null) return o;
        Map<String, Object> opts = project.getOnboarding().getGenerationOptions();
        if (opts == null || opts.isEmpty()) return o;
        Map<String, Object> tests   = asMap(opts.get("tests"));
        Map<String, Object> helpers = asMap(opts.get("helpers"));
        Map<String, Object> clients = asMap(opts.get("clients"));
        o.testsUnit              = boolOr(tests.get("unit"),        o.testsUnit);
        o.testsIntegration       = boolOr(tests.get("integration"), o.testsIntegration);
        o.testsContract          = boolOr(tests.get("contract"),    o.testsContract);
        o.testsFuzz              = boolOr(tests.get("fuzz"),        o.testsFuzz);
        o.testsLoad              = boolOr(tests.get("load"),        o.testsLoad);
        o.helpersPostman         = boolOr(helpers.get("postman"),         o.helpersPostman);
        o.helpersMcpInspector    = boolOr(helpers.get("mcpInspector"),    o.helpersMcpInspector);
        o.helpersGithubWorkflows = boolOr(helpers.get("githubWorkflows"), o.helpersGithubWorkflows);
        o.helpersDockerfile      = boolOr(helpers.get("dockerfile"),      o.helpersDockerfile);
        o.clientClaude  = boolOr(clients.get("claudeDesktop"), o.clientClaude);
        o.clientCursor  = boolOr(clients.get("cursor"),        o.clientCursor);
        o.clientForgeQ  = boolOr(clients.get("forgeq"),        o.clientForgeQ);
        o.clientVscode  = boolOr(clients.get("vscode"),        o.clientVscode);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return (v instanceof Map<?, ?>) ? (Map<String, Object>) v : Collections.emptyMap();
    }

    private static boolean boolOr(Object v, boolean fallback) {
        if (v instanceof Boolean b) return b;
        return fallback;
    }

    // ─────────── Path classifiers ──────────────────────────────────────

    /** Path patterns shared across the language generators. We chose
     *  these conventions on purpose so this classifier stays simple — if
     *  a future generator deviates, add the new pattern here. */
    static boolean isUnitTest(String path) {
        if (path == null) return false;
        if (path.contains("/integration") || path.contains("/contract")
                || path.contains("/fuzz") || path.contains("/load")) return false;
        return path.endsWith(".test.ts")
                || path.endsWith(".test.tsx")
                || path.endsWith(".spec.ts")
                || path.startsWith("tests/test_")
                || path.startsWith("tests/tools.test.")
                || path.endsWith("Test.java")
                || path.endsWith("Tests.java");
    }
    static boolean isIntegrationTest(String path) {
        return path != null && (path.contains("/integration/") || path.contains("integration.test"));
    }
    static boolean isContractTest(String path) {
        return path != null && (path.contains("/contract/") || path.contains("contract.test"));
    }
    static boolean isFuzzTest(String path) {
        return path != null && (path.contains("/fuzz/") || path.contains("fuzz.test"));
    }
    static boolean isLoadTest(String path) {
        return path != null && (path.contains("/load/") || path.contains("load.test"));
    }

    private static boolean hasFileMatching(List<GeneratedFile> files,
                                           java.util.function.Predicate<String> pathPred) {
        return files.stream().anyMatch(f -> pathPred.test(f.getPath()));
    }

    /** Insert or replace a file with the given path. Keeps the list
     *  free of duplicates when generate is called twice in a row. */
    private void replaceFile(List<GeneratedFile> files, String path, String content, String kind) {
        files.removeIf(f -> path.equals(f.getPath()));
        files.add(GeneratedFile.builder()
                .path(path)
                .content(content == null ? "" : content)
                .mimeHint(kind)
                .bytes(content == null ? 0 : content.getBytes().length)
                .build());
    }

    private static boolean isHttpTransport(McpProject p) {
        return p != null && p.getTransport() != null
                && p.getTransport().getKind() != null
                && p.getTransport().getKind().toLowerCase().contains("http");
    }

    // ─────────── Stubs for the test kinds the generators skip ─────────

    private GeneratedFile integrationTestStub(McpProject p) { return protocolTest("integration"); }
    private GeneratedFile contractTestStub(McpProject p) { return protocolTest("contract"); }
    private GeneratedFile fuzzTestStub(McpProject p) { return protocolTest("fuzz"); }
    private GeneratedFile loadTestStub(McpProject p) { return protocolTest("load"); }
    private GeneratedFile protocolTest(String kind) {
        return file("tests/" + kind + "/run.mjs", "process.env.MCP_TEST_KIND = '" + kind + "';\nawait import('../protocol.mjs');\n", "javascript");
    }
    private String buildPostman(McpProject p) {
        return new TestCollectionGenerator().generate(p).get("postmanCollection");
    }
    private Map<String, Object> postmanItem(String name, String body, McpProject p) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("method", "POST");
        String url = p.getTransport() == null || p.getTransport().getBaseUrl() == null
                ? "http://localhost:3500/mcp" : p.getTransport().getBaseUrl();
        req.put("url",    Map.of("raw", url));
        req.put("header", List.of(Map.of("key", "Content-Type", "value", "application/json")));
        req.put("body",   Map.of("mode", "raw", "raw", body));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("request", req);
        return item;
    }

    private String buildInspector(McpProject p) {
        Map<String, Object> entry = new LinkedHashMap<>(clientEntry(p));
        entry.put("type", isHttpTransport(p) ? "streamable-http" : "stdio");
        return tryWrite(Map.of("mcpServers", Map.of(safeSlug(p), entry)));
    }

    private String buildDockerfile(McpProject p) {
        String lang = lang(p);
        return switch (lang) {
            case "python" -> """
                    FROM python:3.12-slim
                    WORKDIR /app
                    COPY . .
                    RUN pip install --no-cache-dir -r requirements.txt
                    CMD ["python", "-m", "server"]
                    """;
            case "java"   -> """
                    FROM eclipse-temurin:17-jdk-jammy AS build
                    WORKDIR /app
                    COPY . .
                    RUN ./mvnw -q package -DskipTests

                    FROM eclipse-temurin:17-jre-jammy
                    COPY --from=build /app/target/*.jar /app/app.jar
                    CMD ["java","-jar","/app/app.jar"]
                    """;
            default       -> """
                    FROM node:20-alpine
                    WORKDIR /app
                    COPY . .
                    RUN npm install && npm run build
                    CMD ["node", "dist/index.js"]
                    """;
        };
    }

    /** Pre-baked CI: lint + test + build matrix. Picks up the test
     *  scripts the language generator already wired into the project
     *  manifest so it works without further user intervention. */
    private String buildCiWorkflow(McpProject p) {
        String lang = lang(p);
        return switch (lang) {
            case "python" -> """
                    name: CI
                    on:
                      push:
                        branches: [ "**" ]
                      pull_request:
                    jobs:
                      build:
                        runs-on: ubuntu-latest
                        steps:
                          - uses: actions/checkout@v4
                          - uses: actions/setup-python@v5
                            with: { python-version: "3.12" }
                          - run: pip install -r requirements.txt
                          - run: ruff check .
                          - run: pytest -q
                    """;
            case "java"   -> """
                    name: CI
                    on:
                      push:
                        branches: [ "**" ]
                      pull_request:
                    jobs:
                      build:
                        runs-on: ubuntu-latest
                        steps:
                          - uses: actions/checkout@v4
                          - uses: actions/setup-java@v4
                            with: { distribution: "temurin", java-version: "17" }
                          - run: mvn -B verify
                    """;
            default       -> """
                    name: CI
                    on:
                      push:
                        branches: [ "**" ]
                      pull_request:
                    jobs:
                      build:
                        runs-on: ubuntu-latest
                        steps:
                          - uses: actions/checkout@v4
                          - uses: actions/setup-node@v4
                            with: { node-version: "20" }
                          - run: npm install
                          - run: npm run build
                          - run: npm test
                    """;
        };
    }

    private String buildClaudeConfig(McpProject p) {
        if (isHttpTransport(p)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("command", "node");
            entry.put("args", List.of("/absolute/path/to/client-configs/http-bridge.mjs", clientEntry(p).get("url")));
            if (p.getAuth() != null && "bearer".equals(p.getAuth().getKind())) entry.put("env", Map.of("MCP_AUTH_TOKEN", "<your-token>"));
            if (p.getAuth() != null && "api-key".equals(p.getAuth().getKind())) entry.put("env", Map.of("MCP_API_KEY", "<your-token>", "MCP_API_KEY_HEADER", p.getAuth().getHeaderName() == null ? "X-API-Key" : p.getAuth().getHeaderName()));
            return tryWrite(Map.of("mcpServers", Map.of(safeSlug(p), entry)));
        }
        return tryWrite(Map.of("mcpServers", Map.of(safeSlug(p),
                clientEntry(p))));
    }

    private String buildCursorConfig(McpProject p) {
        return tryWrite(Map.of("mcpServers", Map.of(safeSlug(p),
                clientEntry(p))));
    }

    private String buildForgeqConfig(McpProject p) {
        return tryWrite(com.forgesphere.mcpgen.generator.GeneratorUtils.manifest(p));
    }

    private String buildVscodeConfig(McpProject p) {
        // Continue.dev / Cline both read this shape from .vscode/mcp.json.
        Map<String, Object> entry = new LinkedHashMap<>(clientEntry(p));
        entry.put("type", isHttpTransport(p) ? "http" : "stdio");
        return tryWrite(Map.of("servers", Map.of(safeSlug(p), entry)));
    }

    private Map<String, Object> clientEntry(McpProject p) {
        if (isHttpTransport(p)) {
            String url = p.getTransport().getBaseUrl();
            if (url == null || url.isBlank()) url = "http://localhost:" + ("typescript".equals(lang(p)) ? "3500" : "8080") + "/mcp";
            java.net.URI uri = java.net.URI.create(url);
            if (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath())) url = uri.resolve("/mcp").toString();
            Map<String, Object> entry = new LinkedHashMap<>(); entry.put("url", url);
            if (p.getAuth() != null && "bearer".equals(p.getAuth().getKind())) entry.put("headers", Map.of("Authorization", "Bearer <your-token>"));
            if (p.getAuth() != null && "api-key".equals(p.getAuth().getKind())) entry.put("headers", Map.of(p.getAuth().getHeaderName() == null ? "X-API-Key" : p.getAuth().getHeaderName(), "<your-token>"));
            return entry;
        }
        return switch (lang(p)) {
            case "python" -> Map.of("command", "python", "args", List.of("/absolute/path/to/server.py"));
            case "java" -> Map.of("command", "java", "args", List.of("-jar", "/absolute/path/to/target/" + safeSlug(p) + "-0.1.0.jar"));
            default -> Map.of("command", "node", "args", List.of("/absolute/path/to/dist/index.js"));
        };
    }

    // ─────────── small helpers ────────────────────────────────────────

    private static String lang(McpProject p) {
        return p.getRuntime() == null
                ? "typescript"
                : String.valueOf(p.getRuntime().getLanguage()).toLowerCase();
    }

    public Map<String, Object> clientConfigs(McpProject p) {
        try {
            return Map.of("claudeDesktop", json.readValue(buildClaudeConfig(p), Map.class),
                    "cursor", json.readValue(buildCursorConfig(p), Map.class),
                    "forgeq", json.readValue(buildForgeqConfig(p), Map.class));
        } catch (Exception e) { throw new IllegalStateException("Cannot build MCP client configs", e); }
    }

    private static String safeSlug(McpProject p) {
        if (p.getIdentity() == null || p.getIdentity().getSlug() == null
                || p.getIdentity().getSlug().isBlank()) return "mcp-server";
        return p.getIdentity().getSlug();
    }

    private static String clientCommand(McpProject p) {
        String lang = lang(p);
        return switch (lang) {
            case "python" -> "python -m " + safeSlug(p).replace('-', '_');
            case "java"   -> "java -jar " + safeSlug(p) + ".jar";
            default       -> "npx " + safeSlug(p);
        };
    }

    private String tryWrite(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { return "{}"; }
    }

    private static GeneratedFile file(String path, String content, String lang) {
        return GeneratedFile.builder()
                .path(path).content(content).mimeHint(lang)
                .bytes(content == null ? 0 : content.getBytes().length)
                .build();
    }
}
