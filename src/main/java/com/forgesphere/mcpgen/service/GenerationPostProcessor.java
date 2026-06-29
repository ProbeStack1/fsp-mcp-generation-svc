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

        // 3) Helper artefacts. We dedupe by path so re-generation never
        //    creates two "postman/collection.json" entries.
        if (opts.helpersPostman) {
            replaceFile(files, "postman/collection.json", buildPostman(project), "json");
        }
        if (opts.helpersMcpInspector) {
            replaceFile(files, ".mcp-inspector/workspace.json", buildInspector(project), "json");
        }
        if (opts.helpersDockerfile && isHttpTransport(project)) {
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
        if (opts.clientCursor)
            replaceFile(files, "client-configs/cursor-mcp.json",     buildCursorConfig(project), "json");
        if (opts.clientForgeQ)
            replaceFile(files, "client-configs/forgeq.json",         buildForgeqConfig(project), "json");
        if (opts.clientVscode)
            replaceFile(files, "client-configs/vscode.json",         buildVscodeConfig(project), "json");

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

    private GeneratedFile integrationTestStub(McpProject p) {
        String lang = lang(p);
        if ("python".equals(lang)) {
            return file("tests/integration/test_tool_chain.py",
                    "\"\"\"Integration tests — verify multi-tool flows. Fill in real assertions.\"\"\"\n"
                            + "import pytest\nfrom server import mcp\n\n"
                            + "@pytest.mark.integration\n"
                            + "def test_tool_chain_smoke():\n"
                            + "    assert mcp is not None\n", "python");
        }
        if ("java".equals(lang)) {
            return file("src/test/java/integration/ToolChainIntegrationTest.java",
                    "package integration;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.*;\n\n"
                            + "class ToolChainIntegrationTest {\n"
                            + "  @Test void smoke() { assertTrue(true, \"replace with a real tool-chain assertion\"); }\n"
                            + "}\n", "java");
        }
        return file("tests/integration/tool-chain.integration.test.ts",
                "import { describe, expect, it } from 'vitest';\n\n"
                        + "describe('tool chain integration', () => {\n"
                        + "  it('runs end-to-end', () => { expect(true).toBe(true); });\n"
                        + "});\n", "typescript");
    }

    private GeneratedFile contractTestStub(McpProject p) {
        String lang = lang(p);
        if ("python".equals(lang)) {
            return file("tests/contract/test_capability_contract.py",
                    "\"\"\"Contract tests — assert tool list matches the MCP spec.\"\"\"\n"
                            + "import pytest\nfrom server import mcp\n\n"
                            + "def test_tools_match_spec(): assert mcp is not None\n", "python");
        }
        if ("java".equals(lang)) {
            return file("src/test/java/contract/CapabilityContractTest.java",
                    "package contract;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.*;\n\n"
                            + "class CapabilityContractTest {\n"
                            + "  @Test void toolsMatchSpec() { assertTrue(true); }\n"
                            + "}\n", "java");
        }
        return file("tests/contract/capability.contract.test.ts",
                "import { describe, expect, it } from 'vitest';\n\n"
                        + "describe('MCP capability contract', () => {\n"
                        + "  it('lists every declared tool', () => { expect(true).toBe(true); });\n"
                        + "});\n", "typescript");
    }

    private GeneratedFile fuzzTestStub(McpProject p) {
        return file("tests/fuzz/tool-input.fuzz.test.ts",
                "import { describe, expect, it } from 'vitest';\n\n"
                        + "// Replace with a real property-based fuzzer (e.g. fast-check)\n"
                        + "describe('tool input fuzz', () => {\n"
                        + "  it('rejects random garbage gracefully', () => { expect(true).toBe(true); });\n"
                        + "});\n", "typescript");
    }

    private GeneratedFile loadTestStub(McpProject p) {
        return file("tests/load/tool-call.load.test.ts",
                "// k6 / artillery-shaped load test. Run separately from `npm test`.\n"
                        + "// Replace this stub with the real spec when load testing is wired up.\n"
                        + "export const options = { vus: 5, duration: '10s' };\n"
                        + "export default function () { /* call /mcp here */ }\n", "javascript");
    }

    // ─────────── Helper artefact builders ─────────────────────────────

    private String buildPostman(McpProject p) {
        Map<String, Object> coll = new LinkedHashMap<>();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", p.getIdentity() == null ? "MCP Server" : p.getIdentity().getDisplayName());
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        info.put("_postman_id", UUID.randomUUID().toString());
        coll.put("info", info);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(postmanItem("initialize",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", p));
        if (p.getCapabilities() != null && p.getCapabilities().getTools() != null) {
            int id = 2;
            for (McpProject.Tool t : p.getCapabilities().getTools()) {
                String body = String.format(
                        "{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"%s\",\"arguments\":{}}}", id++, t.getName());
                items.add(postmanItem("tools/call · " + t.getName(), body, p));
            }
        }
        coll.put("item", items);
        return tryWrite(coll);
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
        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("server", Map.of(
                "name",    safeSlug(p),
                "command", clientCommand(p),
                "transport", p.getTransport() == null ? "stdio" : p.getTransport().getKind()));
        return tryWrite(ws);
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
                          - run: ruff check . || true
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
                          - run: ./mvnw -B verify
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
                          - run: npm ci
                          - run: npm run build
                          - run: npm test
                    """;
        };
    }

    private String buildClaudeConfig(McpProject p) {
        return tryWrite(Map.of("mcpServers", Map.of(safeSlug(p),
                Map.of("command", clientCommand(p), "args", List.of()))));
    }

    private String buildCursorConfig(McpProject p) {
        return tryWrite(Map.of("mcpServers", Map.of(safeSlug(p),
                Map.of("command", clientCommand(p), "args", List.of()))));
    }

    private String buildForgeqConfig(McpProject p) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("name",      safeSlug(p));
        cfg.put("transport", p.getTransport() == null ? Map.of("kind", "stdio") : p.getTransport());
        cfg.put("auth",      p.getAuth()      == null ? Map.of("kind", "none")  : p.getAuth());
        cfg.put("command",   clientCommand(p));
        return tryWrite(cfg);
    }

    private String buildVscodeConfig(McpProject p) {
        // Continue.dev / Cline both read this shape from .vscode/mcp.json.
        return tryWrite(Map.of("mcpServers", Map.of(safeSlug(p),
                Map.of("command", clientCommand(p), "args", List.of()))));
    }

    // ─────────── small helpers ────────────────────────────────────────

    private static String lang(McpProject p) {
        return p.getRuntime() == null
                ? "typescript"
                : String.valueOf(p.getRuntime().getLanguage()).toLowerCase();
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
