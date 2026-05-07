package com.forgesphere.mcpgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Universal collection parser — sniffs the format of any pasted
 * blob and converts it into an MCP-tools array.
 *
 * Supported formats:
 *   - OpenAPI 3.x             (has `openapi`/`swagger` + `paths`)
 *   - Postman v2.1 collection (has `info.schema` containing "postman")
 *   - Insomnia v4 export      (has `_type: "export"`, resources[])
 *   - ForgeQ collection       (has `source: "FORGEQ"` or `forgeq`)
 *   - Raw endpoint list       (array of { method, path|url, name? })
 *
 * Returns both the detected format (so the UI can show it to the user)
 * and the parsed tools.
 */
@Service
public class CollectionParserService {

    private final ObjectMapper om = new ObjectMapper();

    public record ParseResult(String detectedFormat, String description,
                              int totalEndpoints, List<Map<String, Object>> tools) {}

    public ParseResult parse(String raw) throws Exception {
        if (raw == null || raw.isBlank()) throw new RuntimeException("Empty input.");
        JsonNode root;
        try { root = om.readTree(raw); }
        catch (Exception e) { throw new RuntimeException("Not valid JSON. YAML is not supported — convert first."); }

        // ---- Detect format --------------------------------------------------
        String fmt = detect(root);

        // ---- Dispatch --------------------------------------------------------
        List<Map<String, Object>> tools;
        switch (fmt) {
            case "openapi"  -> tools = fromOpenApi(root);
            case "postman"  -> tools = fromPostman(root);
            case "insomnia" -> tools = fromInsomnia(root);
            case "forgeq"   -> tools = fromForgeQ(root);
            case "endpoint-list" -> tools = fromEndpointList(root);
            default -> throw new RuntimeException("Unrecognised format. Supported: OpenAPI 3, Postman v2, Insomnia v4, ForgeQ, or a raw array of {method, path}.");
        }
        if (tools.isEmpty()) throw new RuntimeException("Parsed the collection but found zero endpoints.");

        return new ParseResult(fmt, describe(fmt), tools.size(), tools);
    }

    private String detect(JsonNode r) {
        if (r.has("openapi") || r.has("swagger")) return "openapi";
        if (r.has("info") && r.path("info").path("schema").asText("").contains("postman")) return "postman";
        if (r.has("item") && r.has("info")) return "postman";                 // permissive fallback
        if ("export".equals(r.path("_type").asText())) return "insomnia";
        if (r.has("resources") && r.path("__export_format").asInt(0) > 0) return "insomnia";
        if ("FORGEQ".equalsIgnoreCase(r.path("source").asText()) || r.has("forgeq")) return "forgeq";
        if (r.isArray() && r.size() > 0 && r.get(0).has("method")) return "endpoint-list";
        return "unknown";
    }

    private String describe(String fmt) {
        return switch (fmt) {
            case "openapi"       -> "OpenAPI 3.x / Swagger — converts each path × method to a tool.";
            case "postman"       -> "Postman v2 collection — converts each request to a tool.";
            case "insomnia"      -> "Insomnia v4 export — converts each request resource to a tool.";
            case "forgeq"        -> "ForgeQ collection — converts each endpoint to a tool.";
            case "endpoint-list" -> "Raw endpoint list — one tool per entry.";
            default              -> "Unknown";
        };
    }

    // ---------------- OpenAPI -----------------------------------------------
    private List<Map<String, Object>> fromOpenApi(JsonNode doc) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode paths = doc.path("paths");
        Iterator<String> it = paths.fieldNames();
        String[] methods = { "get", "post", "put", "patch", "delete" };
        while (it.hasNext()) {
            String path = it.next();
            JsonNode item = paths.path(path);
            for (String m : methods) {
                JsonNode op = item.path(m);
                if (op.isMissingNode()) continue;
                Map<String, Object> props = new LinkedHashMap<>();
                List<String> required = new ArrayList<>();
                op.path("parameters").forEach(p -> {
                    String n = p.path("name").asText(); if (n.isEmpty()) return;
                    props.put(n, Map.of("type", p.path("schema").path("type").asText("string"),
                            "description", p.path("description").asText("")));
                    if (p.path("required").asBoolean(false)) required.add(n);
                });
                JsonNode body = op.path("requestBody").path("content").path("application/json").path("schema");
                if (!body.isMissingNode() && body.has("properties")) {
                    body.path("properties").fields().forEachRemaining(e ->
                            props.put(e.getKey(), Map.of("type", e.getValue().path("type").asText("string"),
                                    "description", e.getValue().path("description").asText(""))));
                    body.path("required").forEach(r -> { if (!required.contains(r.asText())) required.add(r.asText()); });
                }
                out.add(tool(
                        snake(op.path("operationId").asText(m + "_" + lastSeg(path))),
                        firstNonBlank(op.path("summary").asText(""), op.path("description").asText(""), m.toUpperCase() + " " + path),
                        props, required, m,
                        "// " + m.toUpperCase() + " " + path));
            }
        }
        return out;
    }

    // ---------------- Postman -----------------------------------------------
    private List<Map<String, Object>> fromPostman(JsonNode root) {
        List<Map<String, Object>> out = new ArrayList<>();
        walkPostmanItems(root.path("item"), out);
        return out;
    }

    private void walkPostmanItems(JsonNode items, List<Map<String, Object>> out) {
        if (!items.isArray()) return;
        for (JsonNode it : items) {
            if (it.has("item")) { walkPostmanItems(it.path("item"), out); continue; }
            JsonNode req = it.path("request"); if (req.isMissingNode()) continue;
            String method = req.path("method").asText("GET").toLowerCase();
            String urlRaw = req.path("url").isTextual() ? req.path("url").asText()
                    : req.path("url").path("raw").asText("");
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            // path variables
            req.path("url").path("variable").forEach(v -> {
                String n = v.path("key").asText(); if (n.isEmpty()) return;
                props.put(n, Map.of("type", "string", "description", v.path("description").asText("")));
                required.add(n);
            });
            // query
            req.path("url").path("query").forEach(q -> {
                String n = q.path("key").asText(); if (n.isEmpty()) return;
                props.put(n, Map.of("type", "string", "description", q.path("description").asText("")));
            });
            // body (raw JSON, best-effort)
            JsonNode bodyRaw = req.path("body").path("raw");
            if (bodyRaw.isTextual()) {
                try {
                    JsonNode parsed = om.readTree(bodyRaw.asText());
                    if (parsed.isObject()) parsed.fields().forEachRemaining(e ->
                            props.put(e.getKey(), Map.of("type", e.getValue().isNumber() ? "number" : e.getValue().isBoolean() ? "boolean" : "string", "description", "")));
                } catch (Exception ignored) {}
            }
            out.add(tool(snake(it.path("name").asText(method + "_" + lastSeg(urlRaw))),
                    firstNonBlank(it.path("name").asText(""), method.toUpperCase() + " " + urlRaw),
                    props, required, method, "// " + method.toUpperCase() + " " + urlRaw));
        }
    }

    // ---------------- Insomnia ----------------------------------------------
    private List<Map<String, Object>> fromInsomnia(JsonNode root) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode r : root.path("resources")) {
            if (!"request".equals(r.path("_type").asText())) continue;
            String method = r.path("method").asText("GET").toLowerCase();
            String url    = r.path("url").asText("");
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (JsonNode p : r.path("parameters")) {
                String n = p.path("name").asText(); if (n.isEmpty()) continue;
                props.put(n, Map.of("type", "string", "description", ""));
            }
            out.add(tool(snake(r.path("name").asText(method + "_" + lastSeg(url))),
                    firstNonBlank(r.path("name").asText(""), r.path("description").asText(""), method.toUpperCase() + " " + url),
                    props, required, method, "// " + method.toUpperCase() + " " + url));
        }
        return out;
    }

    // ---------------- ForgeQ collection -------------------------------------
    private List<Map<String, Object>> fromForgeQ(JsonNode root) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode ep : root.path("endpoints")) {
            String method = ep.path("method").asText("GET").toLowerCase();
            String path   = ep.path("path").asText("");
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            ep.path("defaultParams").forEach(p -> {
                String k = p.path("key").asText(); if (k.isEmpty()) return;
                props.put(k, Map.of("type", "string", "description", ""));
            });
            out.add(tool(snake(ep.path("name").asText(method + "_" + lastSeg(path))),
                    firstNonBlank(ep.path("name").asText(""), ep.path("description").asText(""), method.toUpperCase() + " " + path),
                    props, required, method, "// " + method.toUpperCase() + " " + path));
        }
        return out;
    }

    // ---------------- Raw endpoint list -------------------------------------
    private List<Map<String, Object>> fromEndpointList(JsonNode arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode e : arr) {
            String method = e.path("method").asText("GET").toLowerCase();
            String path   = firstNonBlank(e.path("path").asText(""), e.path("url").asText(""), "/");
            out.add(tool(snake(firstNonBlank(e.path("name").asText(""), method + "_" + lastSeg(path))),
                    firstNonBlank(e.path("name").asText(""), method.toUpperCase() + " " + path),
                    Map.of(), List.of(), method, "// " + method.toUpperCase() + " " + path));
        }
        return out;
    }

    // ---------------- helpers -----------------------------------------------
    private Map<String, Object> tool(String name, String desc, Map<String, Object> props, List<String> req,
                                     String method, String hint) {
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", props);
        inputSchema.put("required", req);
        return Map.of(
                "name", name,
                "description", desc,
                "inputSchema", inputSchema,
                "outputType", "structured-json",
                "sideEffects", "delete".equals(method) ? "destructive" : ("get".equals(method) ? "read-only" : "writes"),
                "implementationHint", hint);
    }

    private static String snake(String s) {
        if (s == null || s.isBlank()) return "op";
        return s.trim()
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("[^a-zA-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toLowerCase();
    }
    private static String lastSeg(String p) {
        if (p == null) return "op";
        String[] parts = p.split("/");
        for (int i = parts.length - 1; i >= 0; i--)
            if (!parts[i].isBlank() && !parts[i].startsWith("{") && !parts[i].startsWith(":"))
                return parts[i];
        return "op";
    }
    private static String firstNonBlank(String... xs) {
        for (String x : xs) if (x != null && !x.isBlank()) return x;
        return "";
    }
}
