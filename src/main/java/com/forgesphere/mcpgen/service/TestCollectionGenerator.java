package com.forgesphere.mcpgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.Tool;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generates scenario-based test collections (Postman format + scenario metadata)
 * from the MCP project's tools. Each tool gets a set of test cases:
 *   - Positive (happy path)
 *   - Negative (missing required args, invalid types)
 *   - Security (missing auth header)
 *   - Performance (measured response latency)
 *
 * The output is a Postman collection with a test script for each scenario,
 * plus a JSON metadata file that the Step 8 UI can parse to render the table.
 */
@Service
public class TestCollectionGenerator {

    private final ObjectMapper json = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Generates a Postman collection and a scenario metadata file.
     * Returns a map with two keys: "postmanCollection" (string) and "scenarioMetadata" (string).
     */
    public Map<String, String> generate(McpProject project) {
        List<Tool> tools = project.getCapabilities() == null ? List.of() : project.getCapabilities().getTools();
        String serverUrl = project.getTransport() != null && project.getTransport().getBaseUrl() != null
                ? project.getTransport().getBaseUrl()
                : "http://localhost:3500/mcp";

        // Build Postman collection
        Map<String, Object> collection = new LinkedHashMap<>();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", (project.getIdentity() == null ? "MCP Server" : project.getIdentity().getDisplayName()) + " - Test Collection");
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        collection.put("info", info);

        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> scenarioMetadata = new ArrayList<>();

        for (Tool tool : tools) {
            String toolName = tool.getName();
            String desc = tool.getDescription() == null ? "" : tool.getDescription();

            // --- 1. Positive scenario ---
            Map<String, Object> validArgs = generateValidArgs(tool);
            Map<String, Object> positiveReq = buildRequest(serverUrl, toolName, validArgs, true);
            items.add(createItem(toolName + " - Positive", positiveReq));
            scenarioMetadata.add(createMeta(toolName, "POSITIVE", "Happy path with valid arguments", "200", validArgs, true));

            // --- 2. Negative: missing required ---
            if (hasRequired(tool)) {
                Map<String, Object> missingArgs = generateMissingArgs(tool);
                Map<String, Object> missingReq = buildRequest(serverUrl, toolName, missingArgs, true);
                items.add(createItem(toolName + " - Negative (missing required)", missingReq));
                scenarioMetadata.add(createMeta(toolName, "NEGATIVE", "Missing required arguments", "400", missingArgs, true));
            }

            // --- 3. Negative: invalid type ---
            Map<String, Object> invalidArgs = generateInvalidTypeArgs(tool);
            if (invalidArgs != null) {
                Map<String, Object> invalidReq = buildRequest(serverUrl, toolName, invalidArgs, true);
                items.add(createItem(toolName + " - Negative (invalid type)", invalidReq));
                scenarioMetadata.add(createMeta(toolName, "NEGATIVE", "Invalid argument type", "400", invalidArgs, true));
            }

            // --- 4. Security: missing auth (if auth is enabled) ---
            if (project.getAuth() != null && !"none".equalsIgnoreCase(project.getAuth().getKind())) {
                Map<String, Object> noAuthReq = buildRequest(serverUrl, toolName, validArgs, false);
                items.add(createItem(toolName + " - Security (missing auth)", noAuthReq));
                scenarioMetadata.add(createMeta(toolName, "SECURITY", "Missing Authorization header", "401", validArgs, false));
            }

            // --- 5. Performance: the runner measures and enforces the latency threshold. ---
            Map<String, Object> perfReq = buildRequest(serverUrl, toolName, validArgs, true);
            items.add(createItem(toolName + " - Performance (latency test)", perfReq));
            scenarioMetadata.add(createMeta(toolName, "PERFORMANCE", "Response time threshold test", "200", validArgs, true));
        }

        PostmanMcpSupport.prepare(collection, items, project);
        String postmanCollection;
        try {
            postmanCollection = json.writeValueAsString(collection);
        } catch (Exception e) {
            postmanCollection = "{}";
        }

        String scenarioMetadataJson;
        try {
            scenarioMetadataJson = json.writeValueAsString(scenarioMetadata);
        } catch (Exception e) {
            scenarioMetadataJson = "[]";
        }

        Map<String, String> result = new LinkedHashMap<>();
        result.put("postmanCollection", postmanCollection);
        result.put("scenarioMetadata", scenarioMetadataJson);
        return result;
    }

    // ---- Helper methods ----

    private Map<String, Object> buildRequest(String url, String toolName, Map<String, Object> args, boolean withAuth) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", "POST");
        request.put("url", Map.of("raw", url));
        List<Map<String, String>> headers = new ArrayList<>();
        headers.add(Map.of("key", "Content-Type", "value", "application/json"));
        if (!withAuth) {
            // intentionally omit Authorization
        } else {
            // we can add a placeholder for auth header; the test script will replace
            headers.add(Map.of("key", "Authorization", "value", "Bearer {{authToken}}"));
        }
        request.put("header", headers);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", UUID.randomUUID().toString());
        body.put("method", "tools/call");
        body.put("params", Map.of("name", toolName, "arguments", args));
        try {
            request.put("body", Map.of("mode", "raw", "raw", json.writeValueAsString(body)));
        } catch (Exception e) {
            request.put("body", Map.of("mode", "raw", "raw", "{}"));
        }
        return request;
    }

    private Map<String, Object> createItem(String name, Map<String, Object> request) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("request", request);
        return item;
    }

    private Map<String, Object> createMeta(String toolName, String category, String description, String expectedStatus,
                                            Map<String, Object> arguments, boolean withAuth) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("toolName", toolName);
        meta.put("id", toolName + ":" + category + ":" + description);
        if ("PERFORMANCE".equals(category)) meta.put("maxLatencyMs", 2000);
        meta.put("category", category);
        meta.put("description", description);
        meta.put("expectedStatus", expectedStatus);
        // Carried straight through so the wizard's "Run" button can call the
        // real tool with the exact arguments this scenario was built for,
        // instead of guessing / sending an empty payload.
        meta.put("arguments", arguments == null ? Map.of() : arguments);
        meta.put("withAuth", withAuth);
        return meta;
    }

    /**
     * Generates a plausible value for one JSON-Schema property. Recurses
     * into nested "object" properties and "array of object" items instead
     * of the old flat {@code {"key": "value"}} placeholder — tools like
     * MCP's createNote/updateNote/shareNote wrap their real payload in a
     * nested "body" object (per the OpenAPI-to-MCP parser's requestBody
     * convention), so a flat placeholder there produced a positive-path
     * test whose "positive" request would actually fail validation in the
     * generated handler (body.title/body.content missing). Prefers the
     * schema's own "enum"/"default" when present so samples read like
     * real data instead of generic filler.
     */
    @SuppressWarnings("unchecked")
    private Object generateValueForProp(Map<String, Object> prop, String key) {
        if (prop == null) return "sample-" + key;
        if (prop.containsKey("example")) return prop.get("example");
        if (prop.get("examples") instanceof List<?> examples && !examples.isEmpty()) return examples.get(0);
        if (prop.containsKey("default")) return prop.get("default");
        if (prop.get("enum") instanceof List<?> enumVals && !enumVals.isEmpty()) return enumVals.get(0);
        Object declaredType = prop.getOrDefault("type", "string");
        String type = declaredType instanceof List<?> types ? types.stream().filter(t -> !"null".equals(t)).map(String::valueOf).findFirst().orElse("null") : String.valueOf(declaredType);
        return switch (type) {
            case "number", "integer" -> {
                double value = prop.get("minimum") instanceof Number n ? n.doubleValue() : 42;
                if (prop.get("maximum") instanceof Number n) value = Math.min(value, n.doubleValue());
                yield "integer".equals(type) ? (Object) (long) Math.ceil(value) : value;
            }
            case "boolean" -> true;
            case "array" -> {
                Object items = prop.get("items");
                if (items instanceof Map) {
                    Map<String, Object> itemSchema = (Map<String, Object>) items;
                    yield Collections.singletonList(generateValueForProp(itemSchema, key));
                }
                yield List.of("sample");
            }
            case "object" -> generateValidArgsFromSchema(prop);
            case "null" -> null;
            default -> {
                String value = switch (String.valueOf(prop.get("format"))) {
                    case "email" -> "test@example.com";
                    case "uuid" -> "00000000-0000-4000-8000-000000000001";
                    case "date" -> "2026-01-01";
                    case "date-time" -> "2026-01-01T00:00:00Z";
                    case "uri", "url" -> "https://example.com";
                    default -> "sample-" + key;
                };
                if (prop.get("minLength") instanceof Number n && value.length() < n.intValue()) value += "x".repeat(Math.min(10000, n.intValue() - value.length()));
                if (prop.get("maxLength") instanceof Number n && n.intValue() >= 0 && value.length() > n.intValue()) value = value.substring(0, n.intValue());
                yield value;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> generateValidArgsFromSchema(Map<String, Object> schema) {
        if (schema == null) return Map.of();
        Object props = schema.get("properties");
        if (!(props instanceof Map)) return Map.of();
        Map<String, Object> propMap = (Map<String, Object>) props;
        Map<String, Object> args = new LinkedHashMap<>();
        for (String key : propMap.keySet()) {
            args.put(key, generateValueForProp((Map<String, Object>) propMap.get(key), key));
        }
        return args;
    }

    private Map<String, Object> generateValidArgs(Tool tool) {
        return generateValidArgsFromSchema(tool.getInputSchema());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> generateMissingArgs(Tool tool) {
        Map<String, Object> schema = tool.getInputSchema();
        if (schema == null) return Map.of();
        Object req = schema.get("required");
        if (!(req instanceof List)) return Map.of();
        List<String> required = (List<String>) req;
        if (required.isEmpty()) return Map.of();
        // omit the first required field
        String omit = required.get(0);
        Map<String, Object> args = generateValidArgs(tool);
        args.remove(omit);
        return args;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> generateInvalidTypeArgs(Tool tool) {
        Map<String, Object> schema = tool.getInputSchema();
        if (schema == null) return null;
        Object props = schema.get("properties");
        if (!(props instanceof Map)) return null;
        Map<String, Object> propMap = (Map<String, Object>) props;
        Map<String, Object> args = new LinkedHashMap<>(generateValidArgs(tool));
        for (String key : propMap.keySet()) {
            if (!(propMap.get(key) instanceof Map<?, ?> prop) || prop.get("type") == null) continue;
            Object declared = prop.get("type");
            List<?> allowed = declared instanceof List<?> list ? list : List.of(declared);
            Map<String, Object> candidates = new LinkedHashMap<>();
            candidates.put("string", "invalid-type"); candidates.put("number", 123.5);
            candidates.put("boolean", true); candidates.put("array", List.of()); candidates.put("object", Map.of()); candidates.put("null", null);
            for (var candidate : candidates.entrySet()) if (!allowed.contains(candidate.getKey())) {
                args.put(key, candidate.getValue()); return args;
            }
        }
        return null;
    }

    private boolean hasRequired(Tool tool) {
        if (tool.getInputSchema() == null) return false;
        Object req = tool.getInputSchema().get("required");
        return req instanceof List && !((List<?>) req).isEmpty();
    }

    private boolean hasProperties(Tool tool) {
        if (tool.getInputSchema() == null) return false;
        Object props = tool.getInputSchema().get("properties");
        return props instanceof Map && !((Map<?, ?>) props).isEmpty();
    }
}
