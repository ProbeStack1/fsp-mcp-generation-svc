package com.forgesphere.mcpgen.generator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.GeneratedFile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generator for the {@code raw} language option — emits ONLY the
 * MCP manifest + a README. Lets a user describe the protocol surface in
 * the wizard and bring their own implementation language. Step 4 already
 * markets this card as "Generates the mcp.json manifest only — bring
 * your own language."
 *
 * Without this generator, picking {@code raw} would throw
 * {@code unsupported language} from {@link com.forgesphere.mcpgen.service.McpGenerationService}.
 */
@Component
public class RawSpecGenerator implements CodeGenerator {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override public String language() { return "raw"; }

    @Override
    public List<GeneratedFile> generate(McpProject spec) {
        GeneratorUtils.validateResources(spec);
        List<GeneratedFile> out = new ArrayList<>();

        // 1) mcp.json manifest — single source of truth for the protocol surface.
        out.add(GeneratedFile.builder()
                .path("mcp.json")
                .content(buildManifest(spec))
                .mimeHint("application/json")
                .build());

        // 2) README — explains how to consume the manifest in any language.
        out.add(GeneratedFile.builder()
                .path("README.md")
                .content(buildReadme(spec))
                .mimeHint("text/markdown")
                .build());

        // 3) .gitignore — small but useful starter.
        out.add(GeneratedFile.builder()
                .path(".gitignore")
                .content(".DS_Store\n.idea/\n.vscode/\nnode_modules/\n*.pyc\n__pycache__/\ntarget/\n")
                .mimeHint("text/plain")
                .build());

        // Senior dev's pipeline picks this workflow up — even raw bundles
        // get a manifest-only validation workflow so push-to-deploy still
        // produces a green check-mark in the platform UI.
        {
            String workflowYml = GeneratorUtils.buildGithubWorkflow(spec);
            out.add(GeneratedFile.builder()
                    .path(".github/workflows/mcp.yml")
                    .content(workflowYml)
                    .bytes(workflowYml == null ? 0 : workflowYml.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .mimeHint("text/yaml")
                    .build());
        }

        return out;
    }

    private String buildManifest(McpProject p) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> server = new LinkedHashMap<>();
        if (p.getIdentity() != null) {
            server.put("name",        p.getIdentity().getDisplayName());
            server.put("slug",        p.getIdentity().getSlug());
            server.put("summary",     p.getIdentity().getSummary());
            server.put("description", p.getIdentity().getDescription());
            server.put("category",    p.getIdentity().getCategory());
            server.put("license",     p.getIdentity().getLicense());
        }
        root.put("server", server);

        if (p.getTransport() != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("kind",    p.getTransport().getKind());
            t.put("baseUrl", p.getTransport().getBaseUrl());
            root.put("transport", t);
        }
        if (p.getAuth() != null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("kind", p.getAuth().getKind());
            root.put("auth", a);
        }
        if (p.getCapabilities() != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("tools",     p.getCapabilities().getTools()     != null ? p.getCapabilities().getTools()     : List.of());
            c.put("resources", p.getCapabilities().getResources() != null ? p.getCapabilities().getResources() : List.of());
            c.put("prompts",   p.getCapabilities().getPrompts()   != null ? p.getCapabilities().getPrompts()   : List.of());
            root.put("capabilities", c);
        }
        try { return JSON.writeValueAsString(root); }
        catch (JsonProcessingException e) { return "{}"; }
    }

    private String buildReadme(McpProject p) {
        String name = p.getIdentity() != null && p.getIdentity().getDisplayName() != null
                ? p.getIdentity().getDisplayName() : "MCP Server";
        return "# " + name + "\n\n"
                + "This bundle contains a language-agnostic MCP manifest (`mcp.json`)\n"
                + "describing the tools, resources, and prompts your server exposes.\n\n"
                + "## How to use\n\n"
                + "1. Pick your language and an MCP SDK / shim.\n"
                + "2. Implement each tool/resource/prompt declared in `mcp.json`.\n"
                + "3. Wire your transport (stdio, streamable-http, or SSE) to match the manifest.\n"
                + "4. Validate against the official MCP test suite.\n\n"
                + "Generated by ForgeSphere MCP Generation.\n";
    }
}
