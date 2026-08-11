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
 *   - Performance (simulated latency)
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
            if (hasProperties(tool)) {
                Map<String, Object> invalidArgs = generateInvalidTypeArgs(tool);
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

            // --- 5. Performance: simulate latency (no actual delay, just marker) ---
            Map<String, Object> perfReq = buildRequest(serverUrl, toolName, validArgs, true);
            items.add(createItem(toolName + " - Performance (latency test)", perfReq));
            scenarioMetadata.add(createMeta(toolName, "PERFORMANCE", "Response time threshold test", "200", validArgs, true));
        }

        collection.put("item", items);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> generateValidArgs(Tool tool) {
        Map<String, Object> schema = tool.getInputSchema();
        if (schema == null) return Map.of();
        Object props = schema.get("properties");
        if (!(props instanceof Map)) return Map.of();
        Map<String, Object> propMap = (Map<String, Object>) props;
        Map<String, Object> args = new LinkedHashMap<>();
        for (String key : propMap.keySet()) {
            Map<String, Object> prop = (Map<String, Object>) propMap.get(key);
            String type = (String) prop.getOrDefault("type", "string");
            Object value = switch (type) {
                case "number" -> 42;
                case "integer" -> 42;
                case "boolean" -> true;
                case "array" -> List.of("sample");
                case "object" -> Map.of("key", "value");
                default -> "sample-" + key;
            };
            args.put(key, value);
        }
        return args;
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
        if (schema == null) return Map.of();
        Object props = schema.get("properties");
        if (!(props instanceof Map)) return Map.of();
        Map<String, Object> propMap = (Map<String, Object>) props;
        Map<String, Object> args = new LinkedHashMap<>();
        for (String key : propMap.keySet()) {
            // swap type: string → number, number → string, etc.
            Map<String, Object> prop = (Map<String, Object>) propMap.get(key);
            String type = (String) prop.getOrDefault("type", "string");
            Object value = switch (type) {
                case "number" -> "not-a-number";
                case "integer" -> "not-an-integer";
                case "boolean" -> "not-a-boolean";
                case "array" -> "not-an-array";
                case "object" -> "not-an-object";
                default -> 123; // string -> number
            };
            args.put(key, value);
        }
        return args;
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