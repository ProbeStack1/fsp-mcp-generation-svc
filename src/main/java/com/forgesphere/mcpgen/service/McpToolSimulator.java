package com.forgesphere.mcpgen.service;

import com.forgesphere.mcpgen.model.McpProject;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-process JSON-RPC stand-in used by Step 5 ("Mock Service") whenever
 * the project's transport is {@code stdio} — for which the upstream
 * REST {@code mockApiService} from the senior team obviously doesn't
 * apply. Each call returns a deterministic, schema-aware fake response
 * so the wizard can light up the "test a tool" panel without spawning a
 * real MCP server.
 *
 * <p>The simulator does <strong>not</strong> execute any user-supplied
 * code — it only inspects the {@code inputSchema} on the chosen tool to
 * synthesise a believable result. Side-effecting tools (sideEffects
 * other than {@code read-only}) are echoed back with an explicit
 * {@code simulated=true} marker so a confused dev never confuses a mock
 * response for a real one.</p>
 */
@Service
public class McpToolSimulator {

    /**
     * Simulate the result of invoking {@code toolName} on {@code project}
     * with the supplied arguments. Returns a stable JSON-RPC 2.0 shaped
     * envelope so the UI can render it like a real MCP response.
     */
    public Map<String, Object> simulate(McpProject project, String toolName, Map<String, Object> args) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc",  "2.0");
        envelope.put("id",       UUID.randomUUID().toString());
        envelope.put("simulated", true);
        envelope.put("calledAt",  Instant.now().toString());

        if (project == null) {
            envelope.put("error", error(-32602, "Project not found."));
            return envelope;
        }
        McpProject.Tool tool = findTool(project, toolName);
        if (tool == null) {
            envelope.put("error", error(-32601, "Tool '" + toolName + "' is not declared on this MCP server."));
            return envelope;
        }

        Instant started = Instant.now();
        List<String> validationErrors = validateArgs(tool.getInputSchema(), args);
        if (!validationErrors.isEmpty()) {
            envelope.put("error", error(-32602,
                    "Invalid params for `" + toolName + "`: " + String.join("; ", validationErrors)));
            envelope.put("durationMs", Duration.between(started, Instant.now()).toMillis());
            return envelope;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(synthesise(tool, args)));
        result.put("isError", false);
        envelope.put("result", result);
        envelope.put("durationMs", Duration.between(started, Instant.now()).toMillis());
        return envelope;
    }

    private static McpProject.Tool findTool(McpProject p, String name) {
        if (p.getCapabilities() == null || p.getCapabilities().getTools() == null) return null;
        return p.getCapabilities().getTools().stream()
                .filter(t -> name != null && name.equals(t.getName()))
                .findFirst().orElse(null);
    }

    /**
     * Lightweight shape check — required vs supplied, plus type tags on
     * each property if the schema declares them. We deliberately don't
     * validate constraints like {@code minLength} so the wizard's "try
     * a tool" panel feels permissive; the real generated server uses
     * strict validation at runtime.
     */
    @SuppressWarnings("unchecked")
    private static List<String> validateArgs(Map<String, Object> schema, Map<String, Object> args) {
        List<String> errs = new ArrayList<>();
        if (schema == null) return errs;
        Object req = schema.get("required");
        if (req instanceof List<?> reqList) {
            for (Object key : reqList) {
                if (args == null || !args.containsKey(String.valueOf(key))) {
                    errs.add("Missing required argument `" + key + "`");
                }
            }
        }
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> propMap && args != null) {
            for (Map.Entry<String, Object> e : args.entrySet()) {
                Object def = propMap.get(e.getKey());
                if (!(def instanceof Map<?, ?> defMap)) continue;
                String declaredType = String.valueOf(defMap.get("type"));
                String actualType   = actualType(e.getValue());
                if (declaredType != null
                        && !"null".equals(declaredType)
                        && !declaredType.equals("any")
                        && !declaredType.equals(actualType)) {
                    errs.add("Argument `" + e.getKey() + "` expected " + declaredType
                            + " but got " + actualType);
                }
            }
        }
        return errs;
    }

    private static String actualType(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Integer || v instanceof Long) return "integer";
        if (v instanceof Number) return "number";
        if (v instanceof String) return "string";
        if (v instanceof List<?>) return "array";
        if (v instanceof Map<?, ?>) return "object";
        return "object";
    }

    /**
     * Build a believable payload based on the tool's declared output
     * type. We aim for the shape a developer would expect during a quick
     * sanity check — not a perfect emulation of the real upstream.
     */
    private static Map<String, Object> synthesise(McpProject.Tool tool, Map<String, Object> args) {
        String out = tool.getOutputType() == null ? "structured-json" : tool.getOutputType();
        Map<String, Object> body = new LinkedHashMap<>();
        switch (out) {
            case "text":
                body.put("type", "text");
                body.put("text", "Simulated result for `" + tool.getName() + "`. "
                        + "Arguments echoed: " + safeEcho(args));
                break;
            case "markdown":
                body.put("type", "text");
                body.put("text", "**Simulated** call to `" + tool.getName() + "`\n\n"
                        + "- arguments: `" + safeEcho(args) + "`\n"
                        + "- side effects: `" + nz(tool.getSideEffects(), "read-only") + "`");
                break;
            case "image":
                body.put("type", "image");
                body.put("mimeType", "image/png");
                body.put("data", "");                       // empty payload, just shape
                body.put("note", "Simulated image response — replace with a real result at runtime.");
                break;
            default:
                body.put("type", "text");
                body.put("text", "Simulated structured-json response.");
                body.put("data", Map.of(
                        "tool",       tool.getName(),
                        "args",       args == null ? Map.of() : args,
                        "echoedAt",   Instant.now().toString(),
                        "simulated",  true
                ));
        }
        return body;
    }

    private static String safeEcho(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "(none)";
        return args.toString();
    }

    private static String nz(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static Map<String, Object> error(int code, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code",    code);
        e.put("message", message);
        return e;
    }
}
