package com.forgesphere.mcpgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgesphere.mcpgen.model.McpProject;
import java.util.*;

final class PostmanMcpSupport {
    private PostmanMcpSupport() {}
    static void prepare(Map<String, Object> collection, List<Map<String, Object>> items, McpProject project) {
        String url = project.getTransport() == null ? null : project.getTransport().getBaseUrl();
        if (url == null || url.isBlank()) url = "http://localhost:" +
                (project.getRuntime() != null && "typescript".equals(project.getRuntime().getLanguage()) ? "3500" : "8080") + "/mcp";
        java.net.URI uri = java.net.URI.create(url);
        if (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath())) url = uri.resolve("/mcp").toString();
        collection.put("variable", List.of(Map.of("key", "mcpUrl", "value", url), Map.of("key", "authToken", "value", ""),
                Map.of("key", "mcpSessionId", "value", ""), Map.of("key", "mcpProtocolVersion", "value", "2025-03-26")));
        collection.put("event", List.of(event("prerequest", """
                const sid = pm.collectionVariables.get('mcpSessionId');
                if (sid) pm.request.headers.upsert({key: 'Mcp-Session-Id', value: sid});
                else pm.request.headers.remove('Mcp-Session-Id');
                if (pm.request.name === 'initialize') {
                  pm.collectionVariables.unset('mcpSessionId');
                  pm.request.headers.remove('Mcp-Session-Id');
                }
                """)));
        items.add(0, request("notifications/initialized", Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), project));
        Map<String, Object> initialize = request("initialize", Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params",
                Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(), "clientInfo", Map.of("name", "Postman", "version", "1.0"))), project);
        initialize.put("event", List.of(event("test", parseScript() + """
                pm.test('Initialize succeeded', () => { pm.expect(pm.response.code).to.eql(200); pm.expect(rpc.error).to.be.undefined; pm.expect(rpc.result.serverInfo.name).to.be.a('string'); });
                if (rpc.result) pm.collectionVariables.set('mcpProtocolVersion', rpc.result.protocolVersion);
                const sid = pm.response.headers.get('Mcp-Session-Id');
                if (sid) pm.collectionVariables.set('mcpSessionId', sid);
                """)));
        items.add(0, initialize);
        for (String method : List.of("tools/list", "resources/list", "resources/templates/list", "prompts/list")) {
            if (method.startsWith("resources") && (project.getCapabilities() == null || project.getCapabilities().getResources().isEmpty())) continue;
            if (method.startsWith("prompts") && (project.getCapabilities() == null || project.getCapabilities().getPrompts().isEmpty())) continue;
            items.add(request(method, Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", method), project));
        }
        if (project.getCapabilities() != null) {
            for (var prompt : project.getCapabilities().getPrompts()) {
                Map<String, String> arguments = new LinkedHashMap<>();
                for (var arg : prompt.getArguments()) arguments.put(arg.getName(), "sample");
                items.add(request("prompts/get: " + prompt.getName(), Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", "prompts/get", "params", Map.of("name", prompt.getName(), "arguments", arguments)), project));
            }
            for (var resource : project.getCapabilities().getResources()) items.add(request("resources/read: " + resource.getName(), Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", "resources/read", "params", Map.of("uri", resource.getUriTemplate().replaceAll("\\{[^}]+}", "sample"))), project));
        }
        for (Map<String, Object> item : items) {
            @SuppressWarnings("unchecked") Map<String, Object> req = (Map<String, Object>) item.get("request");
            req.put("url", "{{mcpUrl}}");
            @SuppressWarnings("unchecked") List<Map<String, String>> existing = (List<Map<String, String>>) req.getOrDefault("header", List.of());
            List<Map<String, String>> headers = new ArrayList<>(existing);
            headers.removeIf(h -> Set.of("Accept", "MCP-Protocol-Version").contains(h.get("key")));
            headers.add(Map.of("key", "Accept", "value", "application/json, text/event-stream"));
            headers.add(Map.of("key", "MCP-Protocol-Version", "value", "{{mcpProtocolVersion}}"));
            if (project.getAuth() == null || "none".equals(project.getAuth().getKind())) headers.removeIf(h -> "Authorization".equals(h.get("key")));
            if (project.getAuth() != null && "api-key".equals(project.getAuth().getKind())) {
                boolean authenticated = headers.removeIf(h -> "Authorization".equalsIgnoreCase(h.get("key")));
                String key = project.getAuth().getHeaderName();
                if (key == null || key.isBlank()) key = "X-API-Key";
                if (authenticated) headers.add(Map.of("key", key, "value", "{{authToken}}"));
            }
            req.put("header", headers);
            if (item.containsKey("event")) continue;
            String name = item.get("name").toString();
            String assertion;
            if (name.equals("notifications/initialized")) assertion = "pm.test('Notification accepted', () => pm.expect(pm.response.code).to.be.oneOf([202,204]));";
            else if (name.contains("Security (missing auth)")) assertion = "pm.test('Missing authentication rejected', () => pm.expect(pm.response.code).to.eql(401));";
            else assertion = parseScript() + (name.contains("Negative")
                    ? "pm.test('Invalid input rejected', () => { pm.expect(pm.response.code).to.be.oneOf([200,400]); pm.expect(Boolean(rpc.error || rpc.result?.isError)).to.eql(true); });"
                    : "pm.test('MCP request succeeded', () => { pm.expect(pm.response.code).to.eql(200); pm.expect(rpc.error).to.be.undefined; pm.expect(rpc.result).to.exist; pm.expect(rpc.result.isError).not.to.eql(true); });");
            if (name.contains("Performance")) assertion += "\npm.test('Latency under 2 seconds', () => pm.expect(pm.response.responseTime).to.be.below(2000));";
            item.put("event", List.of(event("test", assertion)));
        }
        collection.put("item", items);
    }
    private static Map<String, Object> request(String name, Map<String, Object> body, McpProject p) {
        try {
            Map<String, Object> request = new LinkedHashMap<>(); request.put("method", "POST");
            request.put("header", List.of(Map.of("key", "Content-Type", "value", "application/json"), Map.of("key", "Authorization", "value", "Bearer {{authToken}}")));
            request.put("body", Map.of("mode", "raw", "raw", new ObjectMapper().writeValueAsString(body)));
            Map<String, Object> item = new LinkedHashMap<>(); item.put("name", name); item.put("request", request); return item;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static Map<String, Object> event(String listen, String code) { return Map.of("listen", listen, "script", Map.of("type", "text/javascript", "exec", List.of(code.split("\n")))); }
    private static String parseScript() { return """
            const text = pm.response.text();
            const frames = text.trim().startsWith('{') ? [JSON.parse(text)] : text.split(/\\r?\\n\\r?\\n/).map(f => f.split(/\\r?\\n/).filter(l => l.startsWith('data:')).map(l => l.slice(5).trimStart()).join('\\n')).filter(Boolean).map(JSON.parse);
            const rpc = frames.find(f => f.result !== undefined || f.error !== undefined) || {};
            """; }
}
