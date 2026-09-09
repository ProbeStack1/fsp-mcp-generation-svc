package com.forgesphere.mcpgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpMockRuntimeTest {
    @Test void hostedMockValidatesArgumentsAndReturnsMcpCapabilities() {
        var mongo = mock(MongoTemplate.class);
        when(mongo.findOne(any(Query.class), eq(Map.class), eq("mcp_mock_servers"))).thenReturn(Map.of("_id", "mock"));
        Map<String, Object> tool = Map.of("toolName", "read", "inputSchema", Map.of("type", "object", "properties", Map.of("id", Map.of("type", "integer")), "required", List.of("id")), "mockResponse", Map.of("ok", true));
        when(mongo.findOne(any(Query.class), eq(Map.class), eq("mcp_mock_tools"))).thenReturn(tool);
        when(mongo.find(any(Query.class), eq(Map.class), eq("mcp_mock_tools"))).thenReturn(List.of(tool));
        Map<String, Object> arg = new LinkedHashMap<>(); arg.put("name", "who"); arg.put("description", null); arg.put("required", true);
        Map<String, Object> prompt = Map.of("promptName", "greet", "arguments", List.of(arg), "mockTemplate", "Hello {{who}}");
        when(mongo.findOne(any(Query.class), eq(Map.class), eq("mcp_mock_prompts"))).thenReturn(prompt);
        when(mongo.find(any(Query.class), eq(Map.class), eq("mcp_mock_prompts"))).thenReturn(List.of(prompt));
        var runtime = new McpMockRuntimeService(mongo); var json = new ObjectMapper();
        var init = json.valueToTree(runtime.handleRequest("url", "initialize", Map.of(), 1));
        assertTrue(init.path("result").path("capabilities").has("prompts"));
        var call = json.valueToTree(runtime.handleRequest("url", "tools/call", Map.of("name", "read", "arguments", Map.of("id", 1)), 2));
        assertEquals("text", call.path("result").path("content").path(0).path("type").asText());
        var invalid = json.valueToTree(runtime.handleRequest("url", "tools/call", Map.of("name", "read", "arguments", Map.of("id", "wrong")), 3));
        assertEquals(-32602, invalid.path("error").path("code").asInt());
        var catalog = json.valueToTree(runtime.handleRequest("url", "prompts/list", Map.of(), 4));
        assertFalse(catalog.path("result").path("prompts").path(0).path("arguments").path(0).has("description"));
        var rendered = json.valueToTree(runtime.handleRequest("url", "prompts/get", Map.of("name", "greet", "arguments", Map.of("who", "Ada")), 5));
        assertEquals("Hello Ada", rendered.path("result").path("messages").path(0).path("content").path("text").asText());
        var missing = json.valueToTree(runtime.handleRequest("url", "prompts/get", Map.of("name", "greet"), 6));
        assertEquals(-32602, missing.path("error").path("code").asInt());
    }
}
