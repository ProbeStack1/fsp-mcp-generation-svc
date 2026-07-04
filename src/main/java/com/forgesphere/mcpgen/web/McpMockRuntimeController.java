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

        String method = (String) request.get("method");
        Object params = request.get("params");
        Object id = request.get("id");

        try {
            Object result = runtimeService.handleRequest(mockUrl, method, params, id);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("MCP mock error: {}", e.getMessage());
            Map<String, Object> error = Map.of(
                    "jsonrpc", "2.0",
                    "error", Map.of("code", -32000, "message", e.getMessage()),
                    "id", id
            );
            return ResponseEntity.badRequest().body(error);
        }
    }
}