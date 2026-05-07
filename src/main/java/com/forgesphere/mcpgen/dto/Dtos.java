package com.forgesphere.mcpgen.dto;

import java.util.List;
import java.util.Map;

/**
 * All DTOs in one file — keeps the controller tidy.
 *
 * The wire format mirrors the `McpProject` document, minus internals
 * (`id`, `createdAt`, `updatedAt`, `generated`) on the *write* side,
 * plus wrappers for `generate` / `probe` / `call`.
 */
public final class Dtos {
    private Dtos() {}

    public record ApiError(String code, String message, Object details) {}

    public record Envelope<T>(boolean success, T data, String error) {
        public static <T> Envelope<T> ok(T data) { return new Envelope<>(true, data, null); }
        public static <T> Envelope<T> fail(String error) { return new Envelope<>(false, null, error); }
    }

    // ---------- Project CRUD ----------
    public record CreateProjectRequest(
            String ownerEmail, String workspaceId,
            Object identity, Object capabilities,
            Object runtime, Object transport, Object auth) {}

    // ---------- Generate ----------
    public record GenerateResponse(
            String projectId, int fileCount, int totalBytes,
            List<FileSummary> files, String generatedAt) {}

    public record FileSummary(String path, int bytes, String mimeHint) {}

    // ---------- Probe ----------
    public record ProbeRequest(
            String url,                 // e.g. "https://my-server.com/mcp"
            String transport,           // "streamable-http" | "http-sse" | "stdio"
            String authHeader,          // optional "Bearer xxx"
            boolean mock) {}            // true → skip network, return a plausible mock

    public record ProbeResponse(
            boolean ok, long ms, String error,
            Map<String, Object> serverInfo,
            List<Map<String, Object>> tools,
            List<Map<String, Object>> resources,
            List<Map<String, Object>> prompts,
            boolean mocked) {}

    // ---------- Tool call ----------
    public record CallRequest(
            String url, String transport, String authHeader,
            String toolName, Map<String, Object> arguments,
            boolean mock) {}

    public record CallResponse(
            boolean ok, long ms, String error,
            Object content, boolean mocked) {}

    // ---------- Client config ----------
    public record ClientConfigResponse(
            Map<String, Object> claudeDesktop,
            Map<String, Object> cursor,
            Map<String, Object> forgeq) {}
}
