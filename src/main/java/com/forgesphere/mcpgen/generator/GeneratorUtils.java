package com.forgesphere.mcpgen.generator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.forgesphere.mcpgen.model.McpProject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility helpers shared by every language generator — JSON pretty-
 * printing, filename sanitisation, common manifest / README blocks.
 * Pure static functions; no dependencies on Spring.
 */
public final class GeneratorUtils {

    private static final ObjectMapper PRETTY = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private GeneratorUtils() {}

    /**
     * Backfills a default URI for any resource that was saved without one
     * (common when a resource arrives via AI Synthesize or a pasted
     * capabilities JSON rather than the Resources modal, which enforces
     * the field). Mutates {@code spec} in place and returns one
     * human-readable warning per auto-filled resource so the UI can nudge
     * the user to review it.
     *
     * <p>This used to {@code throw} and hard-block generation. A blank URI
     * is recoverable — {@code resource://<name>} is a valid MCP URI and
     * the scaffold handles it — so we fill it and warn instead of
     * stopping the wizard dead.
     */
    public static List<String> validateResources(McpProject spec) {
        List<String> warnings = new ArrayList<>();
        if (spec.getCapabilities() == null || spec.getCapabilities().getResources() == null) return warnings;
        int index = 0;
        for (var resource : spec.getCapabilities().getResources()) {
            index++;
            if (resource == null) continue;
            if (resource.getUriTemplate() == null || resource.getUriTemplate().isBlank()) {
                String name = resource.getName() != null && !resource.getName().isBlank()
                        ? resource.getName() : ("resource-" + index);
                String uri = "resource://" + sanitise(name);
                resource.setUriTemplate(uri);
                warnings.add("Resource '" + name + "' had no URI — defaulted it to '" + uri
                        + "'. Review it in Design → Resources.");
            }
        }
        return warnings;
    }

    /**
     * 32-byte hex secret for {@code bearer} / {@code api-key} auth —
     * identical in shape to the wizard's "Generate" button
     * ({@code McpLiveController#token}). Used to auto-provision a token at
     * generation time when the user left it blank, so the value baked into
     * {@code mcp.yml} ({@code MCP_AUTH_TOKEN} / {@code MCP_API_KEY}) and the
     * value the Test page / MCP inspector send always agree.
     */
    public static String randomToken() {
        byte[] buf = new byte[32];
        new java.security.SecureRandom().nextBytes(buf);
        return java.util.HexFormat.of().formatHex(buf);
    }

    public static String pretty(Object obj) {
        try { return PRETTY.writeValueAsString(obj); }
        catch (JsonProcessingException e) { return "{}"; }
    }

    /** Safe file-system name from a tool / prompt / slug. */
    public static String sanitise(String raw) {
        if (raw == null || raw.isBlank()) return "_";
          String name = raw.trim().toLowerCase()
                  .replaceAll("[^a-z0-9]+", "_")
                  .replaceAll("^_+|_+$", "");
          return name.isEmpty() ? "_" : Character.isDigit(name.charAt(0)) ? "_" + name : name;
    }

    public static String indent(int n) {
        return " ".repeat(Math.max(0, n));
    }

    /** The `mcp.json` manifest any generator drops into the bundle root.
     *  ForgeQ can pick it up verbatim to register the catalog entry. */
    public static Map<String, Object> manifest(McpProject spec) {
        Map<String, Object> m = new LinkedHashMap<>();
        var id = spec.getIdentity();
        var t  = spec.getTransport();
        var a  = spec.getAuth();
        m.put("slug",         id == null ? "" : id.getSlug());
        m.put("name",         id == null ? "" : id.getDisplayName());
        m.put("version", projectVersion(spec));
        m.put("description",  id == null ? "" : id.getSummary());
        m.put("category",     id == null ? "" : id.getCategory());
        m.put("transport",    t  == null ? "STREAMABLE_HTTP" : normaliseTransport(t.getKind()));
        m.put("serverUrl",    t  == null ? "" : (t.getBaseUrl() == null ? "" : t.getBaseUrl()));
        m.put("requiresAuth", a  != null && !"none".equalsIgnoreCase(a.getKind()));
        List<Map<String, Object>> tools = new ArrayList<>();
        if (spec.getCapabilities() != null) {
            for (var tool : spec.getCapabilities().getTools()) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("name",        tool.getName());
                tm.put("description", tool.getDescription());
                tm.put("inputSchema", tool.getInputSchema());
                tools.add(tm);
            }
        }
        m.put("tools", tools);
        m.put("resources", spec.getCapabilities() == null ? List.of() : spec.getCapabilities().getResources());
        m.put("prompts", spec.getCapabilities() == null ? List.of() : spec.getCapabilities().getPrompts());
        return m;
    }

    public static String template(String name) {
        try (var input = GeneratorUtils.class.getResourceAsStream("/templates/runtime/" + name)) {
            if (input == null) throw new IllegalStateException("Missing runtime template: " + name);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read runtime template: " + name, e);
        }
    }

    public static String runtimeSpec(McpProject spec) {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("identity", spec.getIdentity());
        runtime.put("version", projectVersion(spec));
        runtime.put("capabilities", spec.getCapabilities());
        runtime.put("transport", spec.getTransport());
        runtime.put("advanced", spec.getAdvanced());
        runtime.put("auth", spec.getAuth() == null ? Map.of("kind", "none") :
                Map.of("kind", spec.getAuth().getKind() == null ? "none" : spec.getAuth().getKind(),
                        "headerName", spec.getAuth().getHeaderName() == null ? "X-API-Key" : spec.getAuth().getHeaderName()));
        return pretty(runtime);
    }

    public static String projectVersion(McpProject spec) {
        return spec.getVersionNumber() == null || spec.getVersionNumber().isBlank() ? "0.1.0" : spec.getVersionNumber();
    }

    public static String normaliseTransport(String kind) {
        if (kind == null) return "STREAMABLE_HTTP";
        return switch (kind.toLowerCase()) {
            case "stdio"           -> "STDIO";
            case "http-sse"        -> "HTTP_SSE";
            case "streamable-http" -> "STREAMABLE_HTTP";
            default                -> kind.toUpperCase();
        };
    }

    public static String commonReadme(McpProject spec) {
        var id = spec.getIdentity();
        var t  = spec.getTransport();
        var a  = spec.getAuth();
        int toolCount     = spec.getCapabilities() == null ? 0 : spec.getCapabilities().getTools().size();
        int resourceCount = spec.getCapabilities() == null ? 0 : spec.getCapabilities().getResources().size();
        int promptCount   = spec.getCapabilities() == null ? 0 : spec.getCapabilities().getPrompts().size();

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(id == null ? "MCP Server" : id.getDisplayName()).append("\n\n");
        sb.append(id == null ? "" : id.getSummary()).append("\n\n");
        sb.append("## Capabilities\n\n");
        sb.append("- ").append(toolCount).append(" tool").append(toolCount == 1 ? "" : "s").append("\n");
        sb.append("- ").append(resourceCount).append(" resource").append(resourceCount == 1 ? "" : "s").append("\n");
        sb.append("- ").append(promptCount).append(" prompt").append(promptCount == 1 ? "" : "s").append("\n\n");
        sb.append("## Transport\n\n`").append(t == null ? "streamable-http" : t.getKind()).append("`\n\n");
        if (t != null && t.getBaseUrl() != null && !t.getBaseUrl().isBlank()) {
            sb.append("Base URL: `").append(t.getBaseUrl()).append("`\n\n");
        }
        sb.append("## Authentication\n\n`").append(a == null ? "none" : a.getKind()).append("`\n\n");
        if (a != null && "bearer".equalsIgnoreCase(a.getKind())) {
            sb.append("Set `MCP_AUTH_TOKEN` in the environment. The server will require `Authorization: Bearer <token>` on every request.\n\n");
        }
        if (a != null && "api-key".equalsIgnoreCase(a.getKind())) sb.append("Set `MCP_API_KEY`. Send it in `").append(a.getHeaderName() == null ? "X-API-Key" : a.getHeaderName()).append("`.\n\n");
        sb.append("## Run and verify\n\nCopy `.env.example` to `.env` and fill the token and upstream values. HTTP tool calls use the OpenAPI server URL, or `UPSTREAM_BASE_URL` when set; `UPSTREAM_AUTHORIZATION` supplies upstream credentials.\n\nFor HTTP, set `MCP_SERVER_URL` and run `node tests/contract/run.mjs`. Integration calls require `MCP_TEST_ARGUMENTS` (a JSON object mapping tool names to test arguments) and `node tests/integration/run.mjs`. Use a test upstream because tool calls execute real operations. For stdio, use `MCP_STDIO_COMMAND` containing a JSON array such as `[\"node\",\"dist/index.js\"]` instead of an HTTP URL. All protocol runners require Node.js 20+. Native unit tests use npm test, pytest or mvn test.\n\n");
        sb.append("## License\n\n`").append(id == null ? "MIT" : id.getLicense()).append("`\n\n");
        sb.append("---\n_Generated by ForgeQ MCP Generation._\n");
        return sb.toString();
    }

    /**
     * Generates the `.env.example` template. When bearer auth is on, the
     * token line shows the actual generated token so the user can simply
     * `cp .env.example .env` and run `npm run dev` with auth working out
     * of the box.
     */
    public static String envExample(McpProject spec) {
        var a = spec.getAuth();
        StringBuilder sb = new StringBuilder();
        sb.append("# Copy this file to .env, fill placeholders, then run the generated project.\n");
        if (a != null && "bearer".equalsIgnoreCase(a.getKind())) {
            sb.append("MCP_AUTH_TOKEN=").append(a.getGeneratedToken() == null ? "<generate-me>" : a.getGeneratedToken()).append("\n");
        }
        if (a != null && "api-key".equalsIgnoreCase(a.getKind())) {
            sb.append("MCP_API_KEY=").append(a.getGeneratedToken() == null ? "<your-api-key>" : a.getGeneratedToken()).append("\n");
        }
        sb.append("PORT=").append(spec.getRuntime() == null || "typescript".equals(spec.getRuntime().getLanguage()) ? "3500" : "8080").append("\nUPSTREAM_BASE_URL=\nUPSTREAM_AUTHORIZATION=\n");
        return sb.toString();
    }

    /**
     * Generates a ready-to-use `.env` file that the user does NOT need to
     * edit before running `npm run dev`. The auth token here is identical
     * to what the wizard's Step 8 / Test page fills into the "Authorization
     * header" field, so the two never go out of sync. When bearer auth is
     * disabled we return {@code null} so callers can skip writing the file.
     */
    public static String envReady(McpProject spec) {
        var a = spec.getAuth();
        if (a == null || !("bearer".equalsIgnoreCase(a.getKind()) || "api-key".equalsIgnoreCase(a.getKind()))) {
            return null;
        }
        String token = a.getGeneratedToken();
        if (token == null || token.isBlank()) return null;
        return envExample(spec);
    }

    public static String dockerfile(McpProject spec) {
        String lang = spec.getRuntime() == null ? "typescript" : spec.getRuntime().getLanguage();
        String ver  = spec.getRuntime() == null ? "" : (spec.getRuntime().getLanguageVersion() == null ? "" : spec.getRuntime().getLanguageVersion());
        return switch (lang) {
            case "python" -> """
                    FROM python:%s-slim
                    WORKDIR /app
                    COPY requirements.txt .
                    RUN pip install --no-cache-dir -r requirements.txt
                    COPY . .
                    ENV PORT=8080
                    EXPOSE 8080
                    CMD ["python", "server.py"]
                    """.formatted(ver.startsWith("py") ? ver.substring(2) : "3.12");
            case "java"   -> """
                    FROM eclipse-temurin:%s-jre
                    WORKDIR /app
                    COPY target/*.jar app.jar
                    EXPOSE 3500
                    ENTRYPOINT ["java","-jar","/app/app.jar"]
                    """.formatted(ver.startsWith("java") ? ver.substring(4) : "17");
            default       -> """
                    # Multi-stage Node build.
                    #
                    # WHY a builder stage?  TypeScript (`tsc`) lives under
                    # devDependencies, so `npm install --production` leaves
                    # the image without `tsc` and `npm run build` then fails
                    # with `sh: tsc: not found`.  We install ALL deps, build,
                    # then copy only what's needed into the runtime image.
                    FROM node:%s-alpine AS builder
                    WORKDIR /app
                    COPY package.json package-lock.json* ./
                    # Install every dep, including dev, so `tsc` is present.
                    # `--no-audit --no-fund` quietens the npm log noise.
                    RUN npm install --no-audit --no-fund
                    COPY . .
                    # Tolerate projects without a build script — some MCPs
                    # are pure JS / no transpile step.
                    RUN npm run build --if-present
                    # Drop devDependencies once the build is done so the
                    # final image stays small.
                    RUN npm prune --production

                    FROM node:%s-alpine AS runtime
                    WORKDIR /app
                    ENV NODE_ENV=production
                    COPY --from=builder /app /app
                    EXPOSE 3500
                    CMD ["npm", "start"]
                    """.formatted(
                            ver.startsWith("node") ? ver.substring(4) : "20",
                            ver.startsWith("node") ? ver.substring(4) : "20");
        };
    }

    /**
     * Reads the GitHub Actions workflow template at
     * {@code src/main/resources/templates/github-workflows/mcp.yml.template}
     * and substitutes the three optional placeholders. Returns
     * {@code null} when the template file is missing or contains only
     * comments / whitespace — that's the signal to OMIT the workflow
     * file from the zip rather than ship a broken stub.
     *
     * dev: paste the real workflow body into the template file.
     * Three placeholders are available (all optional):
     *   ${slug}        → e.g. weather-pro-mcp
     *   ${language}    → typescript | python | java | raw
     *   ${langSteps}   → per-language GitHub Actions setup + test steps
     *   ${connectorId} → the onboarding connector id (empty when unset)
     *   ${devBranch}   → connector's saved source branch (falls back to
     *                    "main" until a connector is saved) — the
     *                    push-trigger and deploy-job's branch gate both
     *                    target this so deploy fires off the same branch
     *                    the wizard's push() actually pushes to.
     *   ${branchTag}   → CICD default-strategy "merge" branch name (e.g.
     *                    "release"); baked as `branch_tag`. Empty when unset.
     *   ${cicdConfigId}→ the onboarding id used to call the CICD filtered
     *                    config API (`/cicd-config/{id}/all`); baked as
     *                    `cicd_config_id`. Empty when unset.
     */
    public static String buildGithubWorkflow(McpProject p) {
        String slug = p.getIdentity() != null && p.getIdentity().getSlug() != null && !p.getIdentity().getSlug().isBlank()
                ? p.getIdentity().getSlug() : "mcp-server";
        String lang = p.getRuntime() != null && p.getRuntime().getLanguage() != null
                ? p.getRuntime().getLanguage() : "typescript";
        String connectorId = p.getConnectorId() != null ? p.getConnectorId() : "";
        // The branch the wizard's push() actually pushes to (connector's
        // saved CICD "dev" branch) — baked into the workflow's trigger so
        // deploy fires off the SAME branch the code lands on. Falls back
        // to "main" when unresolved (no connector saved yet).
        String devBranch = p.getDevBranch() != null && !p.getDevBranch().isBlank()
                ? p.getDevBranch() : "main";
        String branchTag    = p.getBranchTag()    != null ? p.getBranchTag()    : "";
        // Second push-trigger branch: the CICD "merge"-tagged branch
        // (branchTag, e.g. "release"), appended under on.push.branches so
        // deploy also fires when code lands on the release branch. Mirrors
        // how the api-development proxy flow carries both the feature branch
        // and the merge branch — but on push only, no pull_request trigger.
        // Omitted when branchTag is unset or identical to the dev branch.
        String extraPushBranches = (!branchTag.isBlank() && !branchTag.equals(devBranch))
                ? "\n      - " + branchTag
                : "";
        // The id the pipeline uses to fetch the CICD filtered config —
        // that's the onboarding id (same value DeployService passes to
        // /cicd-config/{id}/all).
        String cicdConfigId = p.getOnboardingId() != null ? p.getOnboardingId() : "";

        // Port + health path used by the deploy-to-Cloud-Run step.
        // Defaults match what TypeScriptGenerator/PythonGenerator emit
        // (port 3500, /healthz). Java generator already deploys on 8080.
        String port = "3500";
        String healthPath = "/healthz";
        if ("java".equals(lang)) port = "8080";
        if (p.getAdvanced() != null && p.getAdvanced().getHealthCheck() != null
                && p.getAdvanced().getHealthCheck().getPath() != null
                && !p.getAdvanced().getHealthCheck().getPath().isBlank()) {
            healthPath = p.getAdvanced().getHealthCheck().getPath();
        }

        String tmpl = readTemplate("/templates/github-workflows/mcp.yml.template");
        if (tmpl == null) return null;

        // Strip pure-comment files — the placeholder template ships with
        // only `#` lines and that should NOT produce a workflow.
        boolean hasRealContent = tmpl.lines()
                .map(String::trim)
                .anyMatch(l -> !l.isEmpty() && !l.startsWith("#"));
        if (!hasRealContent) return null;

        return tmpl
                .replace("${slug}",        slug)
                .replace("${language}",    lang)
                .replace("${langSteps}",   buildLanguageSteps(lang))
                .replace("${langEnvVars}", buildLanguageEnvVars(p, lang))
                .replace("${connectorId}", connectorId)
                .replace("${port}",        port)
                .replace("${healthPath}",  healthPath)
                .replace("${devBranch}",   devBranch)
                .replace("${extraPushBranches}", extraPushBranches)
                .replace("${branchTag}",   branchTag)
                .replace("${cicdConfigId}", cicdConfigId);
    }

    /**
     * Per-language `--set-env-vars` payload for the Cloud Run deploy
     * step. Different runtimes have different conventional env vars; we
     * keep this list focused so the deploy line stays readable.
     *
     * <p>When the project uses bearer / api-key auth we ALSO inject the
     * token here ({@code MCP_AUTH_TOKEN} / {@code MCP_API_KEY}). The
     * generated server's auth middleware reads it from the environment,
     * and the {@code .env} that carries it in dev is {@code .gitignore}d
     * — so the onboarding pipeline (which does {@code git add .}) never
     * ships it, and the deployed server would 401 every request with
     * {@code {"error":"unauthorized"}}. This is the same token the Test
     * page / MCP inspector sends, so the two always agree.
     */
    private static String buildLanguageEnvVars(McpProject p, String language) {
        String base = switch (language) {
            case "python" -> "PYTHONUNBUFFERED=1,MCP_SERVER_NAME=${{ env.SERVICE_NAME }},MCP_CONNECTOR_ID=${MCP_CONNECTOR_ID}";
            case "java"   -> "SPRING_PROFILES_ACTIVE=cloud,MCP_SERVER_NAME=${{ env.SERVICE_NAME }},MCP_CONNECTOR_ID=${MCP_CONNECTOR_ID}";
            default       -> "NODE_ENV=production,MCP_SERVER_NAME=${{ env.SERVICE_NAME }},MCP_CONNECTOR_ID=${MCP_CONNECTOR_ID}";
        };

        var a = p == null ? null : p.getAuth();
        if (a != null && a.getGeneratedToken() != null && !a.getGeneratedToken().isBlank()) {
            String kind  = a.getKind() == null ? "" : a.getKind().trim().toLowerCase();
            String token = a.getGeneratedToken().trim();
            if ("bearer".equals(kind)) {
                base += ",MCP_AUTH_TOKEN=" + token;
            } else if ("api-key".equals(kind) || "apikey".equals(kind)) {
                base += ",MCP_API_KEY=" + token;
            }
        }
        return base;
    }

    /** Best-effort classpath read; returns {@code null} on any error. */
    private static String readTemplate(String classpath) {
        try (var in = GeneratorUtils.class.getResourceAsStream(classpath)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Per-language test/build steps slotted into the workflow above.
     *  Output is indented with 6 spaces so each line sits correctly
     *  under the `steps:` block (which is itself indented 4 spaces
     *  beneath `jobs.<id>:`). Without this indent the substitution
     *  produces invalid YAML — list items end up at column 0.
     *
     *  NOTE: `cache: npm/pip/maven` is intentionally OMITTED because
     *  the generator does not ship a lock file (package-lock.json /
     *  requirements.lock / mvnw). setup-node@v4 with `cache: npm`
     *  errors out at "Dependencies lock file is not found" before
     *  any run step executes — that's the 5-10 second failure we
     *  saw on the first real deploy. Without `cache:` the action
     *  is happy and falls back to a cold install (~15 s extra), and
     *  every subsequent step works. */
    private static String buildLanguageSteps(String language) {
        String body = switch (language) {
            case "python" -> """
                    - uses: actions/setup-python@v5
                      with:
                        python-version: '3.11'
                    - name: Install deps
                      run: pip install -r requirements.txt
                    - name: Run tests
                      run: pytest -q""";
            case "java" -> """
                    - uses: actions/setup-java@v4
                      with:
                        distribution: 'temurin'
                        java-version: '17'
                    - name: Build
                      run: mvn -B -DskipTests package
                    - name: Run tests
                      run: mvn -B test""";
            case "raw" -> """
                    - name: Validate manifest
                      run: |
                        jq . mcp.json > /dev/null && echo 'mcp.json OK'""";
            default -> """
                    - uses: actions/setup-node@v4
                      with:
                        node-version: '20'
                    - name: Install deps
                      run: npm install
                    - name: Build
                      run: npm run build
                    - name: Run tests
                      run: npm test --silent""";
        };
        // Re-indent every line with 6 spaces so the steps land directly
        // under `    steps:` (which expects each list item at column 6).
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            out.append("      ").append(line).append("\n");
        }
        // Trim the trailing newline so the YAML doesn't end with a blank line.
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == '\n') out.setLength(len - 1);
        return out.toString();
    }
}
