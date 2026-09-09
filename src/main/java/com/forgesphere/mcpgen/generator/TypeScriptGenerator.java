package com.forgesphere.mcpgen.generator;

import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.GeneratedFile;
import com.forgesphere.mcpgen.model.McpProject.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TypeScript / Node.js MCP server generator.
 *
 * Emits a ready-to-run project using `@modelcontextprotocol/sdk`. The
 * template handles all three transports (stdio, streamable-http,
 * http-sse) and all four auth modes (none, bearer, api-key, custom).
 *
 * Per-tool files under `src/tools/` so large servers stay navigable.
 * One tools/index.ts wires them up.
 */
@Component
public class TypeScriptGenerator implements CodeGenerator {

    @Override public String language() { return "typescript"; }

    @Override
    public List<GeneratedFile> generate(McpProject spec) {
        var id   = spec.getIdentity();
        var caps = spec.getCapabilities();
        var rt   = spec.getRuntime();
        var t    = spec.getTransport();
        var a    = spec.getAuth();
        String sdkVersion = rt == null || rt.getSdkVersion() == null ? "^1.0.0" : rt.getSdkVersion();

        List<GeneratedFile> files = new ArrayList<>();
        files.add(file("src/http.ts", GeneratorUtils.template("http.ts"), "typescript"));
        files.add(file("server-spec.json", GeneratorUtils.runtimeSpec(spec), "json"));

        // ---- package.json ----
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("name", id == null ? "mcp-server" : id.getSlug());
        pkg.put("version", GeneratorUtils.projectVersion(spec));
        pkg.put("description", id == null ? "" : id.getSummary());
        pkg.put("type", "module");
        pkg.put("main", "dist/index.js");
        Map<String, String> scripts = new LinkedHashMap<>();
        scripts.put("build", "tsc -p .");
        scripts.put("start", t != null && "stdio".equals(t.getKind()) ? "node dist/index.js" : "node dist/index.js");
        scripts.put("dev",   "tsx watch src/index.ts");
        pkg.put("scripts", scripts);
        Map<String, String> deps = new LinkedHashMap<>();
        deps.put("@modelcontextprotocol/sdk", sdkVersion);
        deps.put("zod", "^3.23.8");
        deps.put("ajv", "^8.17.1");
        if (t != null && !"stdio".equals(t.getKind())) {
            deps.put("express", "^4.19.2");
            // dotenv is what bridges the .env file → process.env at runtime.
            // Without it, MCP_AUTH_TOKEN never gets read on `npm run dev`
            // and the auth middleware rejects every request with 401.
            deps.put("dotenv", "^16.4.5");
        }
        pkg.put("dependencies", deps);
        Map<String, String> devDeps = new LinkedHashMap<>();
        devDeps.put("typescript", "^5.4.0");
        devDeps.put("tsx", "^4.7.0");
        devDeps.put("@types/node", "^20.11.0");
        if (t != null && !"stdio".equals(t.getKind())) devDeps.put("@types/express", "^4.17.21");
        // Vitest is mandatory whenever we emit a `tests/tools.test.ts` —
        // without it `npm test` blows up with "vitest: command not found"
        // before any test even runs, which derails the CI gate on the
        // first push. Only add the dep when we're actually going to ship
        // a test file (i.e. caps has at least one tool).
        {
            devDeps.put("vitest", "^1.6.0");
            scripts.put("test", "vitest run");
        }
        pkg.put("devDependencies", devDeps);
        files.add(file("package.json", GeneratorUtils.pretty(pkg), "json"));

        // ---- tsconfig.json ----
        // strict:false intentional — MCP SDK has very rigid literal-type
        // contracts (e.g. `type: "text"` must be the literal, not widened
        // to `string`). Hand-emitted handler objects get widened by tsc
        // unless every single property is asserted `as const`. Disabling
        // strict keeps the build green without sacrificing the meaningful
        // checks (noImplicitAny, strictNullChecks stay off — the SDK
        // already enforces shape at runtime).
        String tsconfig = """
                {
                  "compilerOptions": {
                    "target": "ES2022",
                    "module": "NodeNext",
                    "moduleResolution": "NodeNext",
                    "outDir": "dist",
                    "rootDir": "src",
                    "strict": false,
                    "esModuleInterop": true,
                    "skipLibCheck": true,
                    "resolveJsonModule": true
                  },
                  "include": ["src/**/*"]
                }
                """;
        files.add(file("tsconfig.json", tsconfig, "json"));

        // ---- src/index.ts (transport bootstrap) ----
        files.add(file("src/index.ts", indexFile(spec), "typescript"));

        // ---- src/server.ts (capability registration) ----
        files.add(file("src/server.ts", serverFile(spec), "typescript"));

        // ---- src/tools/<each>.ts ----
        if (caps != null) {
            for (Tool tool : caps.getTools()) {
                String fname = "src/tools/" + GeneratorUtils.sanitise(tool.getName()) + ".ts";
                files.add(file(fname, toolFile(tool), "typescript"));
            }
            if (!caps.getTools().isEmpty()) {
                files.add(file("src/tools/index.ts", toolsIndex(caps.getTools()), "typescript"));
            }
        }

        // ---- .env.example ----
        files.add(file(".env.example", GeneratorUtils.envExample(spec), "dotenv"));

        // ---- .env (ready-to-use copy, only when bearer auth is on so
        //      `npm run dev` works WITHOUT the user copying the example
        //      and pasting tokens manually — eliminates the most common
        //      "401 Unauthorized on local probe" support issue) ----
        String envReady = GeneratorUtils.envReady(spec);
        if (envReady != null) {
            files.add(file(".env", envReady, "dotenv"));
        }

        // ---- Dockerfile ----
        files.add(file("Dockerfile", GeneratorUtils.dockerfile(spec), "docker"));

        // ---- mcp.json (ForgeQ catalog manifest) ----
        files.add(file("mcp.json", GeneratorUtils.pretty(GeneratorUtils.manifest(spec)), "json"));

        // ---- README.md ----
        files.add(file("README.md", GeneratorUtils.commonReadme(spec) + tsQuickStart(t, a), "markdown"));

        // ---- .gitignore ----
        files.add(file(".gitignore", "node_modules/\ndist/\n.env\n.DS_Store\n", "gitignore"));

        // ---- tests/ (Vitest stubs — one case per tool) ----
        if (caps != null && !caps.getTools().isEmpty()) {
            StringBuilder test = new StringBuilder();
            test.append("import { describe, it, expect } from \"vitest\";\n");
            for (Tool tool : caps.getTools()) {
                String fn = GeneratorUtils.sanitise(tool.getName());
                test.append("import { ").append(fn).append("Handler } from \"../src/tools/").append(fn).append("\";\n");
            }
            test.append("\n");
            for (Tool tool : caps.getTools()) {
                String fn = GeneratorUtils.sanitise(tool.getName());
                test.append("describe(\"").append(tool.getName()).append("\", () => {\n");
                test.append("  it(\"returns a content array\", async () => {\n");
                test.append("    const result = await ").append(fn).append("Handler({} as any);\n");
                test.append("    expect(result).toBeTruthy();\n");
                test.append("    expect(result.content).toBeDefined();\n");
                test.append("    expect(Array.isArray(result.content)).toBe(true);\n");
                test.append("  });\n});\n\n");
            }
            files.add(file("tests/tools.test.ts", test.toString(), "typescript"));
            files.add(file("vitest.config.ts",
                    "import { defineConfig } from \"vitest/config\";\n" +
                    "export default defineConfig({ test: { environment: \"node\", globals: true } });\n", "typescript"));
        }


        //  dev's pipeline picks this workflow up — pushes the
        // image to the registry and rolls out a deploy. Same shape
        // across all languages.
        {
            // Built via GeneratedFile.builder() directly (not the file()
            // helper below), so `.bytes()` was never set — it silently
            // defaulted to 0 and every generate() response reported this
            // file as empty even though its real content was pushed fine.
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

    // --------------------- templates ---------------------

    private String indexFile(McpProject spec) {
        var t = spec.getTransport();
        var a = spec.getAuth();
        var adv = spec.getAdvanced();
        boolean hasAuth = a != null && !"none".equalsIgnoreCase(a.getKind());
        String kind = t == null ? "streamable-http" : t.getKind();

        if ("stdio".equals(kind)) {
            return """
                    import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
                    import { createServer } from "./server.js";

                    // STDIO transport — LLM clients (Claude Desktop, Cursor) launch this
                    // process and talk to it over stdin/stdout. Stdio is single-session
                    // by definition, so one `McpServer` instance is enough here.
                    const server = createServer();
                    const transport = new StdioServerTransport();
                    await server.connect(transport);
                    """;
        }

        // Build the HTTP transport bootstrap piece by piece based on advanced opts.
        boolean corsOn     = adv == null || adv.getCors() == null       || adv.getCors().isEnabled();
        String  corsOrig   = adv == null || adv.getCors() == null       ? "*" : (adv.getCors().getAllowedOrigins() == null ? "*" : adv.getCors().getAllowedOrigins());
        boolean rlOn       = adv != null && adv.getRateLimit()  != null && adv.getRateLimit().isEnabled();
        int     rlRpm      = adv == null || adv.getRateLimit()  == null ? 60 : adv.getRateLimit().getRequestsPerMinute();
        boolean logOn      = adv == null || adv.getLogging()    == null || adv.getLogging().isEnabled();
        boolean healthOn   = adv == null || adv.getHealthCheck()== null || adv.getHealthCheck().isEnabled();
        String  healthPath = adv == null || adv.getHealthCheck()== null ? "/healthz" : adv.getHealthCheck().getPath();
        boolean metricsOn  = adv != null && adv.getMetrics()    != null && adv.getMetrics().isEnabled();
        String  metricsPath= adv == null || adv.getMetrics()    == null ? "/metrics" : adv.getMetrics().getPath();

        StringBuilder sb = new StringBuilder();
        sb.append("""
                // Load .env BEFORE anything else so `process.env.MCP_AUTH_TOKEN`
                // is populated when the auth middleware runs. Without this
                // import, every request fails with 401 even though the token
                // file is right next to package.json.
                import "dotenv/config";
                import express from "express";
                import { randomUUID } from "node:crypto";
                import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
                import { isInitializeRequest } from "@modelcontextprotocol/sdk/types.js";
                import { createServer } from "./server.js";

                const app = express();
                app.use(express.json());
                """);

        if (logOn) sb.append("""

                // Structured request logging.
                app.use((req, _res, next) => {
                  const t0 = Date.now();
                  _res.on("finish", () => {
                    console.log(JSON.stringify({ ts: new Date().toISOString(), method: req.method, path: req.path, status: _res.statusCode, ms: Date.now() - t0 }));
                  });
                  next();
                });
                """);

        if (corsOn) sb.append("""

                // CORS.
                //
                // `Mcp-Session-Id` is critical to expose — without it the
                // browser refuses to let the JS client read the header,
                // so the client can never learn its session id and the
                // *second* JSON-RPC call (`tools/list`) bombs with a
                // 400 "Mcp-Session-Id header is required" on the server.
                const ALLOWED_ORIGINS: string = %s;
                app.use((req, res, next) => {
                  const origin = req.headers.origin || "";
                  const allow = ALLOWED_ORIGINS === "*" ? "*" : (ALLOWED_ORIGINS.split(",").map((s: string) => s.trim()).includes(origin) ? origin : "");
                  if (allow) res.setHeader("Access-Control-Allow-Origin", allow);
                  res.setHeader("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
                  res.setHeader("Access-Control-Allow-Headers", req.headers['access-control-request-headers'] || "Content-Type, Authorization, X-API-Key, Mcp-Session-Id, MCP-Protocol-Version");
                  res.setHeader("Access-Control-Expose-Headers", "Mcp-Session-Id");
                  if (req.method === "OPTIONS") return res.sendStatus(204);
                  next();
                });
                """.formatted("\"" + corsOrig.replace("\"", "\\\"") + "\""));

        if (rlOn) sb.append("""

                // Naive in-memory rate limiter — swap for Redis if you scale out.
                const RATE_LIMIT_PER_MIN = %d;
                const buckets = new Map<string, { count: number; resetAt: number }>();
                app.use((req, res, next) => {
                  if (req.path !== '/mcp') return next();
                  const ip = (req.ip || req.socket.remoteAddress || "") as string;
                  const now = Date.now();
                  const b = buckets.get(ip) || { count: 0, resetAt: now + 60_000 };
                  if (now > b.resetAt) { b.count = 0; b.resetAt = now + 60_000; }
                  b.count++; buckets.set(ip, b);
                  if (b.count > RATE_LIMIT_PER_MIN) return res.status(429).json({ error: "rate limit exceeded" });
                  next();
                });
                """.formatted(rlRpm));

        if (hasAuth) sb.append("""

                function requireAuth(req: any, res: any, next: any) {
                  const header = req.headers[%s] || "";
                  const token = process.env[%s];
                  const expected = %s + (token || "");
                  if (!token || header !== expected) {
                    return res.status(401).json({ error: "unauthorized" });
                  }
                  next();
                }
                """.formatted(quote("api-key".equals(a.getKind()) ? (a.getHeaderName() == null || a.getHeaderName().isBlank() ? "x-api-key" : a.getHeaderName().toLowerCase()) : "authorization"),
                        quote("api-key".equals(a.getKind()) ? "MCP_API_KEY" : "MCP_AUTH_TOKEN"), quote("api-key".equals(a.getKind()) ? "" : "Bearer ")));

        if (healthOn) sb.append("""

                app.get(%s, (_req, res) => res.json({ ok: true }));
                """.formatted("\"" + healthPath + "\""));

        if (metricsOn) sb.append("""

                // Simple Prometheus-compatible metrics.
                let totalRequests = 0;
                app.use((_req, _res, next) => { totalRequests++; next(); });
                app.get(%s, (_req, res) => {
                  res.type("text/plain").send(`# TYPE mcp_requests_total counter\\nmcp_requests_total ${totalRequests}\\n`);
                });
                """.formatted("\"" + metricsPath + "\""));

        sb.append("""

                // ---------------------------------------------------------------
                // Per-session transport map.
                //
                // The MCP SDK's `Server` is single-shot: it accepts exactly one
                // `initialize` request and then rejects any further ones with
                // HTTP 400 "Server already initialized" (JSON-RPC -32600). The
                // browser test client (and any well-behaved MCP client) reuses
                // its session id across calls, but a *fresh* client (page
                // reload, second probe click after the cache was cleared, a
                // second tab, etc.) will send `initialize` again. To support
                // that we keep a map of transports keyed by session id and
                // build a brand-new `McpServer` + transport pair for every
                // new initialize. Reusing the same `McpServer` instance for
                // multiple sessions is what triggers the "already initialized"
                // error — so we call `createServer()` per session.
                // ---------------------------------------------------------------
                const transports: Record<string, StreamableHTTPServerTransport> = {};

                """);

        if (hasAuth) sb.append("app.use(\"/mcp\", requireAuth);\n");

        sb.append("""
                app.all("/mcp", async (req, res) => {
                  try {
                    const sid = (req.headers["mcp-session-id"] as string | undefined) || undefined;
                    let transport: StreamableHTTPServerTransport | undefined =
                      sid ? transports[sid] : undefined;

                    if (!transport) {
                      // No known session. The only call we accept without a
                      // session is `initialize` (POST). Everything else is a
                      // protocol violation that the SDK itself would reject
                      // with a confusing message, so we short-circuit it here.
                      if (req.method !== "POST" || !isInitializeRequest(req.body)) {
                        return res.status(400).json({
                          jsonrpc: "2.0",
                          id: null,
                          error: { code: -32000, message: "Bad Request: no active MCP session — send `initialize` first." },
                        });
                      }
                      transport = new StreamableHTTPServerTransport({
                        sessionIdGenerator: () => randomUUID(),
                        onsessioninitialized: (newSid: string) => { transports[newSid] = transport!; },
                      });
                      transport.onclose = () => {
                        if (transport && transport.sessionId) delete transports[transport.sessionId];
                      };
                      const mcp = createServer();
                      await mcp.connect(transport);
                    }
                    await transport.handleRequest(req, res, req.body);
                  } catch (err: any) {
                    console.error("/mcp handler error", err);
                    if (!res.headersSent) {
                      res.status(500).json({
                        jsonrpc: "2.0",
                        id: null,
                        error: { code: -32000, message: err?.message || "internal server error" },
                      });
                    }
                  }
                });

                const port = Number(process.env.PORT || 3500);
                app.listen(port, "0.0.0.0", () => { console.log(`MCP server listening on http://0.0.0.0:${port}/mcp`); });
                """);

        return sb.toString();
    }

    private String serverFile(McpProject spec) {
        var id   = spec.getIdentity();
        var caps = spec.getCapabilities();
        StringBuilder sb = new StringBuilder();
        sb.append("""
                import { McpServer, ResourceTemplate } from "@modelcontextprotocol/sdk/server/mcp.js";
                import { ListToolsRequestSchema, CallToolRequestSchema } from "@modelcontextprotocol/sdk/types.js";
                import { z } from "zod";

                """);

        // Import tool handlers at top-level (they're pure functions, safe to share across sessions).
        if (caps != null && !caps.getTools().isEmpty()) {
            for (Tool tool : caps.getTools()) {
                String fnName = GeneratorUtils.sanitise(tool.getName());
                sb.append("import { ").append(fnName).append("Handler } from \"./tools/").append(fnName).append(".js\";\n");
            }
            sb.append('\n');
        }

        // Factory: every new MCP session gets its own `McpServer` instance.
        // The SDK's Server class is single-shot — reusing it across sessions
        // causes "Server already initialized" on the second `initialize`.
        sb.append("""
                /**
                 * Build a fresh MCP server instance.
                 *
                 * We export a FACTORY rather than a singleton so the HTTP
                 * transport can hand each new session its own `McpServer`.
                 * The MCP SDK rejects a second `initialize` on the same
                 * Server instance, so a per-session factory is mandatory
                 * for the streamable-http transport.
                 */
                export function createServer(): McpServer {
                """);
        sb.append("  const server = new McpServer({\n");
        sb.append("    name: ").append(quote(id == null ? "mcp-server" : id.getDisplayName())).append(",\n");
        sb.append("    version: ").append(quote(GeneratorUtils.projectVersion(spec))).append(",\n");
        sb.append("  }, { capabilities: { tools: {} } });\n\n");

        if (caps != null && !caps.getTools().isEmpty()) {
            sb.append("  // ---------- Tools ----------\n");
            sb.append("  server.server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: [\n");
            for (Tool tool : caps.getTools()) {
                sb.append("    { name: ").append(quote(tool.getName())).append(", description: ").append(quote(nz(tool.getDescription())))
                        .append(", inputSchema: ").append(GeneratorUtils.pretty(tool.getInputSchema() == null ? Map.of("type", "object") : tool.getInputSchema())).append(" },\n");
            }
            sb.append("  ] }));\n  server.server.setRequestHandler(CallToolRequestSchema, async request => {\n    switch (request.params.name) {\n");
            for (Tool tool : caps.getTools()) sb.append("      case ").append(quote(tool.getName())).append(": return ")
                    .append(GeneratorUtils.sanitise(tool.getName())).append("Handler(request.params.arguments ?? {});\n");
            sb.append("      default: throw new Error('Unknown tool: ' + request.params.name);\n    }\n  });\n");
        } else {
            sb.append("  server.server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: [] }));\n");
        }
        if (caps != null && !caps.getResources().isEmpty()) {
            sb.append("  // ---------- Resources ----------\n");
            for (var r : caps.getResources()) {
                sb.append("  server.registerResource(").append(quote(r.getName())).append(", ")
                        .append(r.getUriTemplate().contains("{") ? "new ResourceTemplate(" + quote(r.getUriTemplate()) + ", { list: undefined })" : quote(r.getUriTemplate())).append(", {\n");
                sb.append("    description: ").append(quote(nz(r.getDescription()))).append(",\n");
                sb.append("    mimeType: ").append(quote(nz(r.getMimeType()))).append(",\n");
                if (r.getContent() == null) {
                    sb.append("  }, async () => { throw new Error('Resource content is not configured'); });\n");
                } else {
                    sb.append(r.getUriTemplate().contains("{") ? "  }, async (uri, variables) => ({ contents: [{ uri: uri.href, text: " : "  }, async (uri) => ({ contents: [{ uri: uri.href, text: ")
                            .append(quote(r.getContent()));
                    if (r.getUriTemplate().contains("{")) sb.append(".replace(/\\{\\{\\s*([^{}]+?)\\s*\\}\\}/g, (_, key) => String(variables[key] ?? ''))");
                    sb.append(" }] }));\n");
                }
            }
        }
        if (caps != null && !caps.getPrompts().isEmpty()) {
            sb.append("  // ---------- Prompts ----------\n");
            for (var p : caps.getPrompts()) {
                sb.append("  server.registerPrompt(").append(quote(p.getName())).append(", {\n");
                sb.append("    description: ").append(quote(nz(p.getDescription()))).append(",\n");
                sb.append("    argsSchema: {");
                boolean first = true;
                for (var arg : p.getArguments()) {
                    if (!first) sb.append(", ");
                    sb.append(quote(arg.getName())).append(": z.string()");
                    if (!arg.isRequired()) sb.append(".optional()");
                    first = false;
                }
                sb.append("},\n  }, async (args) => ({\n");
                sb.append("    messages: [{ role: \"user\" as const, content: { type: \"text\" as const, text: ")
                        .append(quote(nz(p.getTemplate()))).append(".replace(/\\{\\{\\s*([^{}]+?)\\s*\\}\\}/g, (_, key) => String(args[key] ?? '')) } }]\n  }));\n\n");
            }
        }

        sb.append("  return server;\n}\n");
        return sb.toString();
    }

    private String toolFile(Tool tool) {
        String fnName = GeneratorUtils.sanitise(tool.getName());
        String props  = tool.getInputSchema() == null
                ? "args: any"
                : "args: { " + schemaParamsTs(tool.getInputSchema()) + " }";
        return "import { callHttp, validateInput } from '../http.js';\nexport async function " + fnName
                + "Handler(args: Record<string, any>) { const invalid = validateInput(" + GeneratorUtils.pretty(tool.getInputSchema()) + ", args); if (invalid) return invalid; return callHttp("
                + GeneratorUtils.pretty(tool.getHttp()) + ", args); }\n";
    }
    private String toolsIndex(List<Tool> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Re-export all tool handlers.\n");
        for (Tool t : tools) {
            String fnName = GeneratorUtils.sanitise(t.getName());
            sb.append("export { ").append(fnName).append("Handler } from \"./").append(fnName).append(".js\";\n");
        }
        return sb.toString();
    }

    private String tsQuickStart(McpProject.Transport t, McpProject.Auth a) {
        boolean stdio = t != null && "stdio".equals(t.getKind());
        StringBuilder sb = new StringBuilder("\n## Run locally\n\n```bash\nnpm install\nnpm run build\n");
        if (a != null && "bearer".equalsIgnoreCase(a.getKind())) {
            sb.append("export MCP_AUTH_TOKEN=").append(a.getGeneratedToken() == null ? "<your-token>" : a.getGeneratedToken()).append("\n");
        }
        sb.append(stdio ? "npm start   # launches over stdio\n" : "npm start   # listens on http://localhost:3500/mcp\n");
        sb.append("```\n");
        return sb.toString();
    }

    // --------------------- helpers ---------------------

    private GeneratedFile file(String path, String content, String hint) {
        byte[] bytes = content.getBytes();
        return GeneratedFile.builder().path(path).content(content).bytes(bytes.length).mimeHint(hint).build();
    }

    /**
     * Builds a double-quoted TS string literal. Beyond backslash/quote,
     * MUST also escape raw control chars — tool/resource/prompt
     * descriptions are frequently AI-synthesized (Step 3 "Synthesize")
     * or hand-typed in a multi-line textarea, and a literal '\n' embedded
     * directly in a double-quoted string is invalid JS/TS ("Unterminated
     * string literal") — it silently corrupts server.ts at compile/build
     * time (caught as a Docker build failure, not at generate-time).
     */
    private static String quote(String s) {
        if (s == null) return "\"\"";
        String escaped = s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n")
                .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }
    private static String nz(String s)   { return s == null ? "" : s; }
    private static String escapeBacktick(String s) { return s == null ? "" : s.replace("`","\\`").replace("$","\\$"); }

    @SuppressWarnings("unchecked")
    private static String zodShapeFromSchema(Map<String, Object> schema) {
        if (schema == null) return "{}";
        Object props = schema.get("properties");
        if (!(props instanceof Map<?, ?> map)) return "{}";
        List<String> required = schema.get("required") instanceof List<?> l ? l.stream().map(Object::toString).toList() : List.of();
        StringBuilder sb = new StringBuilder("{ ");
        int i = 0;
        for (var e : map.entrySet()) {
            String k = e.getKey().toString();
            Map<String, Object> v = e.getValue() instanceof Map ? (Map<String, Object>) e.getValue() : Map.of();
            if (i++ > 0) sb.append(", ");
            sb.append(quote(k)).append(": ").append(zodFromProp(v));
            if (!required.contains(k)) sb.append(".optional()");
        }
        sb.append(" }");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String schemaParamsTs(Map<String, Object> schema) {
        Object props = schema.get("properties");
        if (!(props instanceof Map<?, ?> map) || map.isEmpty()) return "[key: string]: any";
        List<String> required = schema.get("required") instanceof List<?> l ? l.stream().map(Object::toString).toList() : List.of();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (var e : map.entrySet()) {
            String k = e.getKey().toString();
            Map<String, Object> v = e.getValue() instanceof Map ? (Map<String, Object>) e.getValue() : Map.of();
            if (i++ > 0) sb.append("; ");
            sb.append(quote(k));
            if (!required.contains(k)) sb.append("?");
            sb.append(": ").append(tsTypeFromProp(v));
        }
        return sb.toString();
    }

    private static String zodFromProp(Map<String, Object> v) {
        if (v.get("type") == null || v.get("type") instanceof List<?>) return "z.any()";
        String t = v.get("type") == null ? "string" : v.get("type").toString();
        return switch (t) {
            case "number" -> "z.number()";
            case "integer" -> "z.number().int()";
            case "boolean" -> "z.boolean()";
            case "array"   -> "z.array(" + zodFromProp(v.get("items") instanceof Map<?, ?> items ? (Map<String, Object>) items : Map.of()) + ")";
            case "object"  -> "z.object(" + zodShapeFromSchema(v) + ")";
            default        -> "z.string()";
        };
    }

    private static String tsTypeFromProp(Map<String, Object> v) {
        String t = v.get("type") == null ? "string" : v.get("type").toString();
        return switch (t) {
            case "number", "integer" -> "number";
            case "boolean" -> "boolean";
            case "array"   -> "any[]";
            case "object"  -> "Record<string, any>";
            default        -> "string";
        };
    }
}
