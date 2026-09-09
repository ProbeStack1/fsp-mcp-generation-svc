package com.forgesphere.mcpgen.web;

import com.forgesphere.mcpgen.service.McpMockRuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/mocks/{mockUrl}")
@RequiredArgsConstructor
public class McpMockRuntimeController {

    private final McpMockRuntimeService runtimeService;

    @PostMapping("/mcp")
    public ResponseEntity<Object> handleMcpRequest(
            @PathVariable String mockUrl,
            @RequestBody Map<String, Object> request) {

        String method = request.get("method") instanceof String name ? name : null;
        Object params = request.get("params");
        Object id = request.get("id");
        if (!request.containsKey("id") && method != null && method.startsWith("notifications/")) {
            return ResponseEntity.accepted().build();
        }

        try {
            if (!"2.0".equals(request.get("jsonrpc")) || method == null) throw new IllegalArgumentException("Invalid JSON-RPC request");
            Object result = runtimeService.handleRequest(mockUrl, method, params, id);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("MCP mock error: {}", e.getMessage());
            Map<String, Object> error = new java.util.LinkedHashMap<>();
            error.put("jsonrpc", "2.0"); error.put("id", id);
            error.put("error", Map.of("code", -32600, "message", "Invalid MCP request"));
            return ResponseEntity.badRequest().body(error);
        }
    }
}
