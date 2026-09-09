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

    @GetMapping("/health")
    public ResponseEntity<Object> health(@PathVariable String mockUrl) {
        try {
            Object ping = runtimeService.handleRequest(mockUrl, "ping", Map.of(), "health");
            if (!(ping instanceof Map<?, ?> result) || result.containsKey("error"))
                return ResponseEntity.status(404).header("Cache-Control", "no-store").body(Map.of("status", "NOT_FOUND"));
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of(
                    "status", "UP", "transport", "streamable-http", "checkedAt", java.time.Instant.now().toString(),
                    "check", "Mock configuration lookup and MCP ping", "mock", true));
        } catch (Exception error) {
            return ResponseEntity.status(503).header("Cache-Control", "no-store").body(Map.of("status", "DOWN", "error", "Mock storage is unavailable"));
        }
    }

    @GetMapping("/mcp")
    public ResponseEntity<Object> browserHelp(@PathVariable String mockUrl,
            @RequestHeader(value = "Accept", defaultValue = "") String accept) {
        // This runtime has no standalone SSE stream. Browser navigation gets help,
        // while MCP streaming clients still receive the protocol's 405 response.
        if (!accept.contains("text/html")) return ResponseEntity.status(405).header("Allow", "POST").build();
        Object ping = runtimeService.handleRequest(mockUrl, "ping", Map.of(), 1);
        if (ping instanceof Map<?, ?> result && result.containsKey("error"))
            return ResponseEntity.status(404).body(Map.of("error", "Mock server not found"));
        return ResponseEntity.ok().header("Content-Type", "text/html; charset=UTF-8").body("""
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>ForgeFuzz MCP mock</title>
            <body style="margin:0;background:#0e172a;color:#e5e7eb;font:16px system-ui;line-height:1.7">
            <main style="max-width:720px;margin:10vh auto;padding:32px;border:1px solid #232942;border-radius:12px">
            <p style="color:#ff5b1f">ForgeFuzz / MCP mock</p><h1>Your mock endpoint is available</h1>
            <p id="health" role="status">Checking health...</p><button id="refresh" type="button">Refresh health</button>
            <script>
            async function checkHealth() {
              const label = document.getElementById('health');
              label.textContent = 'Checking health...';
              try {
                const url = new URL(location.href); url.pathname = url.pathname.replace(/mcp$/, 'health');
                const response = await fetch(url, { cache: 'no-store' }); const data = await response.json();
                label.textContent = 'Status: ' + data.status + (data.checkedAt ? ' | Checked: ' + new Date(data.checkedAt).toLocaleTimeString() : '');
              } catch { label.textContent = 'Status: unavailable. Could not reach the health endpoint.'; }
            }
            document.getElementById('refresh').addEventListener('click', checkHealth); checkHealth();
            </script>
            <p>Health checks mock configuration storage and MCP ping. It does not test an upstream API.</p>
            <p>This address is an MCP protocol endpoint. Opening it in a browser does not invoke a tool.</p>
            <ol><li>Copy this page's URL.</li><li>Open ForgeFuzz MCP Test and connect a server using Streamable HTTP.</li>
            <li>Paste the URL, connect, then select a tool and run it.</li></ol>
            <p>Resources and prompts are available through the inspector. Mock results use your configured fixtures.</p>
            <p>For Postman, send JSON-RPC with POST and Content-Type: application/json. Start with:</p>
            <pre style="overflow:auto;padding:16px;background:#15192b">{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"postman","version":"1.0"}}}</pre>
            </main></body></html>
            """);
    }

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
