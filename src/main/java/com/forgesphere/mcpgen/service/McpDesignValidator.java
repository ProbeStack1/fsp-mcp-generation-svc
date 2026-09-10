package com.forgesphere.mcpgen.service;

import com.forgesphere.mcpgen.model.McpProject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure validation rules for the MCP capability spec the user authors in
 * Step 3. Step 4 of the wizard calls this and renders the resulting
 * issue list. Each issue carries a {@code severity}, a {@code code} the
 * UI can map to a tooltip, the {@code path} that failed, and a
 * human-friendly {@code message}.
 *
 * <p>The validator is deliberately stateless and does not look at any
 * Mongo data — the controller hands it the {@link McpProject} fresh
 * from the repo so the same instance is safely shared across requests.</p>
 */
@Service
public class McpDesignValidator {

    /**
     * Tool / prompt names must look like {@code get_issue}: lowercase,
     * underscores, digits allowed but never at the start. This matches the
     * convention used by every official MCP server in the reference
     * implementations and is the same rule the SDKs enforce at runtime.
     */
    private static final Pattern SNAKE_CASE = Pattern.compile("^[a-z][a-z0-9_]*$");

    /**
     * RFC 6570 URI templates allow {@code {name}}, {@code {+name}},
     * {@code {/name}}, {@code {?name}} etc. We accept the common ones the
     * spec ships with and flag anything else as a warning so a typo in
     * the operator doesn't silently break a resource at runtime.
     */
    private static final Pattern URI_TEMPLATE_VAR =
            Pattern.compile("\\{([+#./;?&]?[a-zA-Z_][a-zA-Z0-9_,]*)\\}");

    /**
     * Run every rule and return the aggregated report. The order of
     * issues is stable — tools first, then resources, then prompts, then
     * cross-cutting checks — so the UI can render a deterministic list.
     */
    public Result validate(McpProject project) {
        Result r = new Result();
        if (project == null) {
            r.addError("project.missing", "$", "No project supplied to validator.");
            return r;
        }
        if (project.getIdentity() == null
                || project.getIdentity().getSlug() == null
                || project.getIdentity().getSlug().isBlank()) {
            r.addError("identity.slug.missing", "$.identity.slug",
                    "Identity slug is required so the generated package can be named.");
        }
        if (project.getCapabilities() == null) {
            r.addError("capabilities.missing", "$.capabilities",
                    "No tools / resources / prompts defined.");
            return r;
        }
        validateTools(project.getCapabilities().getTools(), r);
        validateResources(project.getCapabilities().getResources(), r);
        validatePrompts(project.getCapabilities().getPrompts(), r);
        validateTransport(project, r);
        return r;
    }

    // ─────────── Tools ─────────────────────────────────────────────────

    private void validateTools(List<McpProject.Tool> tools, Result r) {
        if (tools == null || tools.isEmpty()) {
            r.addWarning("tools.empty", "$.capabilities.tools",
                    "An MCP server with zero tools is valid but rarely useful — add at least one tool.");
            return;
        }
        Set<String> seenNames = new HashSet<>();
        for (int i = 0; i < tools.size(); i++) {
            McpProject.Tool t = tools.get(i);
            String basePath = "$.capabilities.tools[" + i + "]";
            if (t.getName() == null || t.getName().isBlank()) {
                r.addError("tool.name.missing", basePath + ".name", "Tool name is required.");
                continue;
            }
            if (!SNAKE_CASE.matcher(t.getName()).matches()) {
                r.addError("tool.name.shape", basePath + ".name",
                        "Tool name must be snake_case (e.g. " + suggestSnake(t.getName()) + ").");
            }
            if (!seenNames.add(t.getName())) {
                r.addError("tool.name.duplicate", basePath + ".name",
                        "Tool name '" + t.getName() + "' is duplicated. Tool names must be unique.");
            }
            if (t.getDescription() == null || t.getDescription().isBlank()) {
                r.addWarning("tool.description.missing", basePath + ".description",
                        "Add a description — MCP clients use it to decide whether to call the tool.");
            }
            validateInputSchema(t.getInputSchema(), basePath + ".inputSchema", r);
        }
    }

    /**
     * We don't ship a full JSON Schema validator (Spectral would pull in
     * a 5 MB dependency for a handful of rules). Instead we cover the
     * surface that matters in practice for tool inputs:
     *   - {@code type} should be present and either {@code object} or a
     *     primitive recognised by JSON Schema draft-7,
     *   - {@code object} schemas must declare {@code properties}, and
     *   - each property must have a {@code type}.
     * Anything more exotic is left alone so the user can hand-author
     * schemas the SDK accepts.
     */
    @SuppressWarnings("unchecked")
    private void validateInputSchema(Map<String, Object> schema, String path, Result r) {
        if (schema == null || schema.isEmpty()) {
            r.addWarning("tool.inputSchema.missing", path,
                    "No input schema — clients will not get parameter hints.");
            return;
        }
        Object type = schema.get("type");
        if (type == null) {
            r.addError("tool.inputSchema.type.missing", path + ".type",
                    "Input schema must declare a top-level `type` (usually `object`).");
            return;
        }
        Set<String> allowed = Set.of("object", "string", "number", "integer", "boolean", "array", "null");
        if (!allowed.contains(String.valueOf(type))) {
            r.addError("tool.inputSchema.type.invalid", path + ".type",
                    "Unknown JSON Schema type `" + type + "`.");
            return;
        }
        if (!"object".equals(type)) return; // primitives need no further checks
        Object props = schema.get("properties");
        if (!(props instanceof Map<?, ?> propsMap) || propsMap.isEmpty()) {
            r.addWarning("tool.inputSchema.properties.empty", path + ".properties",
                    "Object schema has no properties — tool will accept arbitrary input at runtime.");
            return;
        }
        for (Map.Entry<?, ?> e : propsMap.entrySet()) {
            String propPath = path + ".properties." + e.getKey();
            Object propDef = e.getValue();
            if (!(propDef instanceof Map<?, ?> propMap)) {
                r.addError("tool.inputSchema.property.shape", propPath,
                        "Property definitions must be JSON objects.");
                continue;
            }
            if (!propMap.containsKey("type")) {
                r.addWarning("tool.inputSchema.property.type.missing", propPath + ".type",
                        "Add a `type` so clients render the right input control.");
            }
        }
    }

    // ─────────── Resources ────────────────────────────────────────────

    private void validateResources(List<McpProject.Resource> resources, Result r) {
        if (resources == null) return;
        Set<String> seenUris = new HashSet<>();
        for (int i = 0; i < resources.size(); i++) {
            McpProject.Resource res = resources.get(i);
            String basePath = "$.capabilities.resources[" + i + "]";
            if (res.getUriTemplate() == null || res.getUriTemplate().isBlank()) {
                // Non-blocking: generation backfills a default
                // `resource://<name>` URI (GeneratorUtils.validateResources)
                // and flags it for review, so this no longer fails design
                // validation outright.
                r.addWarning("resource.uri.missing", basePath + ".uriTemplate",
                        "Resource has no URI — a default will be generated. Set one in Design → Resources to override.");
                continue;
            }
            if (!seenUris.add(res.getUriTemplate())) {
                r.addWarning("resource.uri.duplicate", basePath + ".uriTemplate",
                        "URI template `" + res.getUriTemplate() + "` is used by another resource.");
            }
            // Catch operators we don't yet generate handlers for. The
            // URI is still valid RFC 6570, just unsupported by the
            // scaffold — a warning lets the user proceed if they're
            // hand-editing the generated code.
            var matcher = URI_TEMPLATE_VAR.matcher(res.getUriTemplate());
            while (matcher.find()) {
                String op = matcher.group(1).substring(0, 1);
                if ("#?&".indexOf(op) >= 0) {
                    r.addWarning("resource.uri.operator.unsupported",
                            basePath + ".uriTemplate",
                            "URI operator `" + op + "` in `" + res.getUriTemplate()
                                    + "` is parsed but not scaffolded — you'll need to wire the handler manually.");
                    break;
                }
            }
            if (res.getMimeType() == null || res.getMimeType().isBlank()) {
                r.addWarning("resource.mimeType.missing", basePath + ".mimeType",
                        "Add a mime type so clients can render the resource correctly.");
            }
        }
    }

    // ─────────── Prompts ──────────────────────────────────────────────

    private void validatePrompts(List<McpProject.Prompt> prompts, Result r) {
        if (prompts == null) return;
        Set<String> seenNames = new HashSet<>();
        for (int i = 0; i < prompts.size(); i++) {
            McpProject.Prompt p = prompts.get(i);
            String basePath = "$.capabilities.prompts[" + i + "]";
            if (p.getName() == null || p.getName().isBlank()) {
                r.addError("prompt.name.missing", basePath + ".name", "Prompt name is required.");
                continue;
            }
            if (!SNAKE_CASE.matcher(p.getName()).matches()) {
                r.addError("prompt.name.shape", basePath + ".name",
                        "Prompt name must be snake_case.");
            }
            if (!seenNames.add(p.getName())) {
                r.addError("prompt.name.duplicate", basePath + ".name",
                        "Prompt name '" + p.getName() + "' is duplicated.");
            }
            if (p.getTemplate() == null || p.getTemplate().isBlank()) {
                r.addError("prompt.template.missing", basePath + ".template",
                        "Prompt template body is empty.");
                continue;
            }
            // Cross-check: every {{placeholder}} in the template must be
            // declared as an argument, and vice versa. This is the single
            // most common authoring mistake in the prompt editor.
            Set<String> declared = new HashSet<>();
            if (p.getArguments() != null) {
                p.getArguments().forEach(a -> { if (a.getName() != null) declared.add(a.getName()); });
            }
            Set<String> usedInBody = extractPlaceholders(p.getTemplate());
            for (String used : usedInBody) {
                if (!declared.contains(used)) {
                    r.addError("prompt.template.unknownArgument", basePath + ".template",
                            "Template references `{{" + used + "}}` but no matching argument is declared.");
                }
            }
            for (String dec : declared) {
                if (!usedInBody.contains(dec)) {
                    r.addWarning("prompt.argument.unused", basePath + ".arguments",
                            "Argument `" + dec + "` is declared but never used in the template body.");
                }
            }
        }
    }

    private static Set<String> extractPlaceholders(String body) {
        Set<String> out = new HashSet<>();
        var m = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\}\\}").matcher(body);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    // ─────────── Transport cross-checks ───────────────────────────────

    /**
     * Some combinations of transport + auth don't make sense — flag them
     * so the user fixes them now instead of after deploy. Examples:
     *   - {@code stdio} transport cannot do bearer auth at the HTTP layer
     *     (no HTTP layer), so we warn,
     *   - {@code streamable-http} with {@code none} auth is technically
     *     allowed but rarely what the user wants in production.
     */
    private void validateTransport(McpProject p, Result r) {
        McpProject.Transport t = p.getTransport();
        McpProject.Auth a = p.getAuth();
        if (t == null || a == null) return;
        String kind = t.getKind();
        String authKind = a.getKind();
        if ("stdio".equals(kind) && authKind != null && !"none".equals(authKind)) {
            r.addWarning("transport.stdio.auth.ignored", "$.auth",
                    "Stdio transport ignores HTTP auth — `" + authKind
                            + "` will not be enforced. Switch to streamable-http if you need auth.");
        }
        if ("streamable-http".equals(kind) && "none".equals(authKind)) {
            r.addWarning("transport.http.auth.open", "$.auth",
                    "HTTP transport with no auth exposes every tool to anyone who can reach the URL.");
        }
    }

    private static String suggestSnake(String s) {
        return s.trim()
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("[^a-zA-Z0-9]+", "_")
                .toLowerCase();
    }

    // ─────────── Result envelope ──────────────────────────────────────

    /**
     * Wrapper passed back to the controller. {@code ok} is {@code true}
     * if and only if no {@code error}-level issues were recorded —
     * warnings do not block step completion.
     */
    public static class Result {
        private final List<Issue> issues = new ArrayList<>();
        public List<Issue> getIssues() { return issues; }
        public boolean isOk() { return issues.stream().noneMatch(i -> "error".equals(i.severity)); }
        public int getErrorCount()   { return (int) issues.stream().filter(i -> "error".equals(i.severity)).count(); }
        public int getWarningCount() { return (int) issues.stream().filter(i -> "warning".equals(i.severity)).count(); }

        void addError(String code, String path, String message) {
            issues.add(new Issue("error", code, path, message));
        }
        void addWarning(String code, String path, String message) {
            issues.add(new Issue("warning", code, path, message));
        }

        /** Stable JSON shape for the frontend. */
        public Map<String, Object> toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", isOk());
            out.put("errorCount",   getErrorCount());
            out.put("warningCount", getWarningCount());
            out.put("issues", issues);
            return out;
        }
    }

    public static class Issue {
        public final String severity;   // error | warning
        public final String code;
        public final String path;
        public final String message;
        public Issue(String severity, String code, String path, String message) {
            this.severity = severity;
            this.code = code;
            this.path = path;
            this.message = message;
        }
        public String getSeverity() { return severity; }
        public String getCode()     { return code; }
        public String getPath()     { return path; }
        public String getMessage()  { return message; }
    }
}
