package com.forgesphere.mcpgen.service;

import com.forgesphere.mcpgen.dto.Dtos.CallRequest;
import com.forgesphere.mcpgen.dto.Dtos.CallResponse;
import com.forgesphere.mcpgen.dto.Dtos.ProbeRequest;
import com.forgesphere.mcpgen.dto.Dtos.ProbeResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * Thin proxy that talks to a running MCP server on the user's behalf.
 * Two modes:
 *   - mock=true  → no network, returns a plausible shape so the user can
 *                   click through the wizard even before their server is
 *                   deployed.
 *   - mock=false → real JSON-RPC call over HTTP (works for any public
 *                   streamable-http / http-sse endpoint). Localhost is
 *                   unreachable from this pod — we surface a helpful
 *                   error if the user tries.
 */
@Service
public class McpProbeService {

    private final WebClient wc = WebClient.builder()
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/event-stream")
            .build();

    // ---------------------------------------------------------- Probe
    public ProbeResponse probe(ProbeRequest req) {
        if (req.mock()) return mockProbe(req);
        if (req.url() == null || req.url().isBlank())
            return new ProbeResponse(false, 0, "url is required", null, null, null, null, false);
        if (req.url().contains("localhost") || req.url().contains("127.0.0.1")) {
            return new ProbeResponse(false, 0,
                    "localhost URLs are unreachable from this service. Deploy the server (Cloud Run / Vercel / ngrok) and probe the public URL, or enable mock mode to skip the network call.",
                    null, null, null, null, false);
        }
        long start = System.currentTimeMillis();
        try {
            // 1) initialize
            Map<String, Object> initReq = Map.of(
                    "jsonrpc", "2.0", "id", 1, "method", "initialize",
                    "params", Map.of("protocolVersion", "2024-11-05",
                            "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "forgeq-mcp-generation-svc", "version", "1.0.0")));
            Map<?, ?> initRes = callRpc(req, initReq);

            // 2) tools/list
            Map<String, Object> listReq = Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list");
            Map<?, ?> listRes = callRpc(req, listReq);

            long ms = System.currentTimeMillis() - start;
            Map<String, Object> serverInfo = initRes != null && initRes.get("result") instanceof Map<?, ?> r
                    && r.get("serverInfo") instanceof Map<?, ?> si
                    ? new LinkedHashMap<>((Map<String, Object>) si) : Map.of();
            List<Map<String, Object>> tools = extractList(listRes, "tools");

            return new ProbeResponse(true, ms, null, serverInfo, tools, List.of(), List.of(), false);
        } catch (Exception e) {
            return new ProbeResponse(false, System.currentTimeMillis() - start,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    null, null, null, null, false);
        }
    }

    // --------------------------------------------------------- Call
    public CallResponse call(CallRequest req) {
        if (req.mock()) return mockCall(req);
        if (req.url() == null || req.url().isBlank())
            return new CallResponse(false, 0, "url is required", null, false);
        if (req.url().contains("localhost") || req.url().contains("127.0.0.1")) {
            return new CallResponse(false, 0,
                    "localhost URLs are unreachable from this service. Enable mock mode or deploy the server first.",
                    null, false);
        }
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = Map.of(
                    "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                    "params", Map.of("name", req.toolName(),
                            "arguments", req.arguments() == null ? Map.of() : req.arguments()));
            ProbeRequest asProbe = new ProbeRequest(req.url(), req.transport(), req.authHeader(), false, null, null);
            Map<?, ?> res = callRpc(asProbe, body);
            long ms = System.currentTimeMillis() - start;
            Object content = res == null ? null : ((Map<?, ?>) res).get("result");
            return new CallResponse(true, ms, null, content, false);
        } catch (Exception e) {
            return new CallResponse(false, System.currentTimeMillis() - start,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    null, false);
        }
    }

    // -------------------------------------------------- internals
    private Map<?, ?> callRpc(ProbeRequest req, Map<String, Object> body) {
        var spec = wc.post().uri(req.url())
                .headers(h -> {
                    if (req.authHeader() != null && !req.authHeader().isBlank())
                        h.set(HttpHeaders.AUTHORIZATION,
                                req.authHeader().startsWith("Bearer ") ? req.authHeader() : "Bearer " + req.authHeader());
                })
                .bodyValue(body);
        String raw = spec.retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(15))
                .onErrorReturn("{}")
                .block();
        try {
            if (raw == null || raw.isBlank()) return Map.of();
            // Streamable HTTP may return either a single JSON or SSE lines.
            if (raw.trim().startsWith("{")) {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
            }
            // Find the last `data:` line and parse.
            String[] lines = raw.split("\\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                if (lines[i].startsWith("data:")) {
                    String payload = lines[i].substring(5).trim();
                    if (!payload.isEmpty()) {
                        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(payload, Map.class);
                    }
                }
            }
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractList(Map<?, ?> rpcResponse, String key) {
        if (rpcResponse == null || !(rpcResponse.get("result") instanceof Map<?, ?> r)) return List.of();
        Object v = r.get(key);
        if (!(v instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) if (o instanceof Map<?, ?> m) out.add(new LinkedHashMap<>((Map<String, Object>) m));
        return out;
    }

    // -------------------------------------------------- mocks
    private ProbeResponse mockProbe(ProbeRequest req) {
        // Prefer the tools the caller authored — that's the whole reason
        // the wizard sends them along. Fall back to a stable two-tool
        // sample only when the user hasn't built any tools yet.
        List<Map<String, Object>> toolsOut;
        if (req != null && req.tools() != null && !req.tools().isEmpty()) {
            toolsOut = new ArrayList<>(req.tools().size());
            for (Map<String, Object> t : req.tools()) {
                Map<String, Object> shaped = new LinkedHashMap<>();
                shaped.put("name",        t.get("name"));
                shaped.put("description", t.getOrDefault("description", ""));
                Object schema = t.get("inputSchema");
                shaped.put("inputSchema", schema != null ? schema
                        : Map.of("type", "object", "properties", Map.of()));
                toolsOut.add(shaped);
            }
        } else {
            toolsOut = List.of(
                    Map.of("name", "get_item", "description", "Fetch a sample item", "inputSchema",
                            Map.of("type", "object",
                                    "properties", Map.of("id", Map.of("type", "string")),
                                    "required", List.of("id"))),
                    Map.of("name", "list_items", "description", "List sample items", "inputSchema",
                            Map.of("type", "object", "properties", Map.of())));
        }
        Map<String, Object> serverInfo;
        if (req != null && req.serverInfo() != null && !req.serverInfo().isEmpty()) {
            serverInfo = req.serverInfo();
        } else {
            serverInfo = Map.of("name", "mock-mcp-server", "version", "0.1.0", "protocolVersion", "2024-11-05");
        }
        return new ProbeResponse(true, 120, null, serverInfo, toolsOut, List.of(), List.of(), true);
    }

    private CallResponse mockCall(CallRequest req) {
        String outputType = null;
        if (req.toolSpec() != null && req.toolSpec().get("outputType") instanceof String s) {
            outputType = s;
        }
        Object content;
        if ("structured-json".equalsIgnoreCase(outputType)) {
            content = Map.of("content", List.of(Map.of(
                    "type", "json",
                    "json", Map.of(
                            "tool", req.toolName(),
                            "arguments", req.arguments() == null ? Map.of() : req.arguments(),
                            "_mock", true))));
        } else if ("markdown".equalsIgnoreCase(outputType)) {
            content = Map.of("content", List.of(Map.of(
                    "type", "text",
                    "text", "**MOCK** result for `" + req.toolName() + "`\n\nargs: `" +
                            (req.arguments() == null ? "{}" : req.arguments()) + "`")));
        } else {
            content = Map.of("content", List.of(Map.of(
                    "type", "text",
                    "text", "MOCK result for " + req.toolName() + " with args " +
                            (req.arguments() == null ? "{}" : req.arguments()))));
        }
        return new CallResponse(true, 80, null, content, true);
    }
}
