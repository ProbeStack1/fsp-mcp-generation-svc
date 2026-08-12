package com.forgesphere.mcpgen.generator;

import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.GeneratedFile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Python MCP server generator. Uses the official `mcp` PyPI package.
 * Emits a minimal but runnable project with a single `server.py` that
 * registers all tools / resources / prompts; each tool is a stub
 * function for the user to fill in.
 */
@Component
public class PythonGenerator implements CodeGenerator {

    @Override public String language() { return "python"; }

    @Override
    public List<GeneratedFile> generate(McpProject spec) {
        var id   = spec.getIdentity();
        var caps = spec.getCapabilities();
        var t    = spec.getTransport();
        var a    = spec.getAuth();
        boolean stdio = t != null && "stdio".equals(t.getKind());

        List<GeneratedFile> files = new ArrayList<>();

        files.add(file("requirements.txt",
                "mcp>=1.2.0\nuvicorn>=0.25.0\nstarlette>=0.36.0\n",
                "plaintext"));
        files.add(file("pyproject.toml", """
                [project]
                name = "%s"
                version = "0.1.0"
                description = "%s"
                requires-python = ">=3.10"
                """.formatted(id == null ? "mcp-server" : id.getSlug(), id == null ? "" : id.getSummary()), "toml"));

        // server.py
        StringBuilder py = new StringBuilder();
        py.append("""
                \"\"\"Auto-generated MCP server. Fill in the tool bodies marked TODO.\"\"\"
                from mcp.server.fastmcp import FastMCP
                import os

                mcp = FastMCP(%s)

                """.formatted(quote(id == null ? "mcp-server" : id.getDisplayName())));

        if (caps != null) {
            for (var tool : caps.getTools()) {
                String fnName = GeneratorUtils.sanitise(tool.getName());
                py.append("@mcp.tool()\n");
                py.append("def ").append(fnName).append("(").append(pythonParams(tool.getInputSchema())).append(") -> str:\n");
                py.append("    \"\"\"").append(nz(tool.getDescription())).append("\"\"\"\n");
                py.append("    # TODO: implement\n");
                py.append("    return f\"TODO implement ").append(tool.getName()).append(": {locals()}\"\n\n");
            }
            for (var r : caps.getResources()) {
                py.append("@mcp.resource(").append(quote(r.getUriTemplate())).append(")\n");
                py.append("def ").append(GeneratorUtils.sanitise(r.getName())).append("() -> str:\n");
                py.append("    \"\"\"").append(nz(r.getDescription())).append("\"\"\"\n");
                py.append("    return \"TODO: return resource contents\"\n\n");
            }
            for (var p : caps.getPrompts()) {
                py.append("@mcp.prompt()\n");
                py.append("def ").append(GeneratorUtils.sanitise(p.getName())).append("(");
                int i = 0;
                for (var arg : p.getArguments()) {
                    if (i++ > 0) py.append(", ");
                    py.append(arg.getName());
                    if (!arg.isRequired()) py.append(" = \"\"");
                    else py.append(": str");
                }
                py.append(") -> str:\n");
                py.append("    \"\"\"").append(nz(p.getDescription())).append("\"\"\"\n");
                py.append("    return f\"\"\"").append(nz(p.getTemplate())).append("\"\"\"\n\n");
            }
        }

        py.append("if __name__ == \"__main__\":\n");
        if (stdio) py.append("    mcp.run()\n");
        else {
            // For streamable-http we wrap the FastMCP app inside a tiny
            // Starlette app so we can add a `/healthz` route alongside
            // the MCP endpoint. Cloud Run's deploy pipeline curls
            // `/healthz` to confirm the rollout — without this route
            // the deploy is marked failed.
            //
            // `/docs` returns the wizard manifest (mcp.json) as JSON so
            // an LLM-aware client can discover the tools/resources
            // without speaking JSON-RPC first.
            py.append("    from starlette.applications import Starlette\n");
            py.append("    from starlette.responses import JSONResponse, FileResponse\n");
            py.append("    from starlette.routing import Route, Mount\n");
            py.append("    import uvicorn, json, pathlib\n\n");
            py.append("    async def healthz(_request):\n");
            py.append("        return JSONResponse({\"status\": \"ok\", \"service\": ")
              .append(quote(id == null ? "mcp-server" : id.getDisplayName()))
              .append("})\n\n");
            py.append("    async def docs(_request):\n");
            py.append("        path = pathlib.Path(__file__).parent / \"mcp.json\"\n");
            py.append("        if path.exists():\n");
            py.append("            return FileResponse(path, media_type=\"application/json\")\n");
            py.append("        return JSONResponse({\"error\": \"mcp.json not found\"}, status_code=404)\n\n");
            py.append("    app = Starlette(routes=[\n");
            py.append("        Route(\"/healthz\", healthz),\n");
            py.append("        Route(\"/readyz\", healthz),\n");
            py.append("        Route(\"/docs\", docs),\n");
            py.append("        Mount(\"/\", app=mcp.streamable_http_app()),\n");
            py.append("    ])\n");
            py.append("    port = int(os.environ.get(\"PORT\", 8080))\n");
            py.append("    uvicorn.run(app, host=\"0.0.0.0\", port=port)\n");
        }
        files.add(file("server.py", py.toString(), "python"));

        files.add(file(".env.example", GeneratorUtils.envExample(spec), "dotenv"));
        files.add(file("Dockerfile", GeneratorUtils.dockerfile(spec), "docker"));
        files.add(file("mcp.json", GeneratorUtils.pretty(GeneratorUtils.manifest(spec)), "json"));
        files.add(file("README.md", GeneratorUtils.commonReadme(spec) +
                "\n## Run locally\n\n```bash\npip install -r requirements.txt\n" +
                (a != null && "bearer".equalsIgnoreCase(a.getKind()) ? "export MCP_AUTH_TOKEN=" + (a.getGeneratedToken() == null ? "<token>" : a.getGeneratedToken()) + "\n" : "") +
                "python server.py\n```\n", "markdown"));
        files.add(file(".gitignore", "__pycache__/\n*.pyc\n.env\n.venv/\n", "gitignore"));

        // pytest stubs, one per tool
        if (caps != null && !caps.getTools().isEmpty()) {
            StringBuilder pt = new StringBuilder("\"\"\"Auto-generated pytest suite. Fill in real assertions.\"\"\"\nimport pytest\nfrom server import mcp\n\n");
            for (var tool : caps.getTools()) {
                String fn = GeneratorUtils.sanitise(tool.getName());
                pt.append("def test_").append(fn).append("():\n");
                pt.append("    # TODO: call ").append(tool.getName()).append(" and assert output shape\n");
                pt.append("    assert mcp is not None\n\n");
            }
            files.add(file("tests/test_tools.py", pt.toString(), "python"));
        }


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

    @SuppressWarnings("unchecked")
    private static String pythonParams(java.util.Map<String, Object> schema) {
        if (schema == null) return "";
        Object props = schema.get("properties");
        if (!(props instanceof java.util.Map<?, ?> map) || map.isEmpty()) return "";
        List<String> required = schema.get("required") instanceof List<?> l ? l.stream().map(Object::toString).toList() : List.of();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (var e : map.entrySet()) {
            String k = e.getKey().toString();
            java.util.Map<String, Object> v = e.getValue() instanceof java.util.Map ? (java.util.Map<String, Object>) e.getValue() : java.util.Map.of();
            if (i++ > 0) sb.append(", ");
            sb.append(k).append(": ").append(pyType(v));
            if (!required.contains(k)) sb.append(" = None");
        }
        return sb.toString();
    }

    private static String pyType(java.util.Map<String, Object> v) {
        String t = v.get("type") == null ? "string" : v.get("type").toString();
        return switch (t) {
            case "number"  -> "float";
            case "integer" -> "int";
            case "boolean" -> "bool";
            case "array"   -> "list";
            case "object"  -> "dict";
            default        -> "str";
        };
    }

    private GeneratedFile file(String path, String content, String hint) {
        return GeneratedFile.builder().path(path).content(content).bytes(content.getBytes().length).mimeHint(hint).build();
    }

    /**
     * Builds a double-quoted Python string literal. Same bug class as
     * TypeScriptGenerator's quote(): missing backslash escaping (an
     * unescaped trailing '\' can eat the closing quote) AND missing
     * control-char escaping (a raw newline in an AI-synthesized or
     * multi-line description breaks the single-quoted-string across
     * lines — invalid Python syntax at build/run time).
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
    private static String nz(String s) { return s == null ? "" : s; }
}
