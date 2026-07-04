package com.forgesphere.mcpgen.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class McpMockRuntimeService {

    private final MongoTemplate mongoTemplate;

    public Object handleRequest(String mockUrl, String method, Object params, Object id) {
        // 1. Find the mock server
        Query serverQuery = new Query(Criteria.where("mockUrl").is(mockUrl));
        Map<String, Object> mock = mongoTemplate.findOne(serverQuery, Map.class, "mcp_mock_servers");
        if (mock == null) {
            return errorResponse(id, -32000, "Mock server not found: " + mockUrl);
        }
        String mockServerId = (String) mock.get("_id");

        // 2. Route to the appropriate MCP method
        return switch (method) {
            case "initialize" -> handleInitialize(id);
            case "tools/list" -> handleToolsList(mockServerId, id);
            case "tools/call" -> handleToolsCall(mockServerId, params, id);
            case "resources/list" -> handleResourcesList(mockServerId, id);
            case "resources/read" -> handleResourcesRead(mockServerId, params, id);
            case "prompts/list" -> handlePromptsList(mockServerId, id);
            case "prompts/get" -> handlePromptsGet(mockServerId, params, id);
            case "ping" -> handlePing(id);
            default -> errorResponse(id, -32601, "Method not found: " + method);
        };
    }

    // ---------- MCP Handlers ----------

    private Object handleInitialize(Object id) {
        Map<String, Object> result = Map.of(
                "protocolVersion", "2024-11-05",
                "serverInfo", Map.of("name", "MCP Mock Server", "version", "1.0.0")
        );
        return successResponse(id, result);
    }

    private Object handleToolsList(String mockServerId, Object id) {
        Query query = new Query(Criteria.where("mockServerId").is(mockServerId));
        List<Map> tools = mongoTemplate.find(query, Map.class, "mcp_mock_tools");
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (Map t : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", t.get("toolName"));
            item.put("description", t.get("toolDescription"));
            item.put("inputSchema", t.get("inputSchema"));
            toolList.add(item);
        }
        return successResponse(id, Map.of("tools", toolList));
    }

    private Object handleToolsCall(String mockServerId, Object params, Object id) {
        if (params == null) {
            return errorResponse(id, -32602, "Missing params");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) params;
        String toolName = (String) p.get("name");

        Query query = new Query(Criteria.where("mockServerId").is(mockServerId)
                .and("toolName").is(toolName));
        Map tool = mongoTemplate.findOne(query, Map.class, "mcp_mock_tools");
        if (tool == null) {
            return errorResponse(id, -32602, "Tool not found: " + toolName);
        }

        Map<String, Object> content = Map.of(
                "content", tool.get("mockResponse"),
                "isError", false
        );
        return successResponse(id, content);
    }

    private Object handleResourcesList(String mockServerId, Object id) {
        Query query = new Query(Criteria.where("mockServerId").is(mockServerId));
        List<Map> resources = mongoTemplate.find(query, Map.class, "mcp_mock_resources");
        List<Map<String, Object>> resourceList = new ArrayList<>();
        for (Map r : resources) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", r.get("resourceName"));
            item.put("description", r.get("resourceDescription"));
            item.put("mimeType", r.get("mimeType"));
            resourceList.add(item);
        }
        return successResponse(id, Map.of("resources", resourceList));
    }

    private Object handleResourcesRead(String mockServerId, Object params, Object id) {
        if (params == null) {
            return errorResponse(id, -32602, "Missing params");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) params;
        String resourceName = (String) p.get("name");

        Query query = new Query(Criteria.where("mockServerId").is(mockServerId)
                .and("resourceName").is(resourceName));
        Map resource = mongoTemplate.findOne(query, Map.class, "mcp_mock_resources");
        if (resource == null) {
            return errorResponse(id, -32602, "Resource not found: " + resourceName);
        }

        Map<String, Object> contents = Map.of(
                "contents", List.of(Map.of(
                        "name", resource.get("resourceName"),
                        "mimeType", resource.get("mimeType"),
                        "data", resource.get("mockData")
                ))
        );
        return successResponse(id, contents);
    }

    private Object handlePromptsList(String mockServerId, Object id) {
        Query query = new Query(Criteria.where("mockServerId").is(mockServerId));
        List<Map> prompts = mongoTemplate.find(query, Map.class, "mcp_mock_prompts");
        List<Map<String, Object>> promptList = new ArrayList<>();
        for (Map p : prompts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", p.get("promptName"));
            item.put("description", p.get("promptDescription"));
            promptList.add(item);
        }
        return successResponse(id, Map.of("prompts", promptList));
    }

    private Object handlePromptsGet(String mockServerId, Object params, Object id) {
        if (params == null) {
            return errorResponse(id, -32602, "Missing params");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) params;
        String promptName = (String) p.get("name");

        Query query = new Query(Criteria.where("mockServerId").is(mockServerId)
                .and("promptName").is(promptName));
        Map prompt = mongoTemplate.findOne(query, Map.class, "mcp_mock_prompts");
        if (prompt == null) {
            return errorResponse(id, -32602, "Prompt not found: " + promptName);
        }

        Map<String, Object> result = Map.of(
                "messages", List.of(Map.of(
                        "role", "assistant",
                        "content", Map.of(
                                "type", "text",
                                "text", prompt.get("mockTemplate")
                        )
                ))
        );
        return successResponse(id, result);
    }

    private Object handlePing(Object id) {
        return successResponse(id, Map.of("ping", "pong"));
    }

    // ---------- Response Builders ----------

    private Map<String, Object> successResponse(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> errorResponse(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }
}