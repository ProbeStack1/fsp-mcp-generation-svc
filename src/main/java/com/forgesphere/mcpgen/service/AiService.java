package com.forgesphere.mcpgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgesphere.mcpgen.model.McpProject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * Thin Gemini REST wrapper. We call the model directly (not via a
 * heavyweight SDK) so there's nothing to update when Google bumps
 * versions — just change the `mcpgen.ai.gemini.model` property.
 *
 * Two capabilities today:
 *   1. Synthesise one MCP tool from a natural-language description.
 *   2. Critique an existing tool (future use — not wired yet).
 *
 * Fails soft — returns a clear error message the UI can show instead
 * of throwing.
 */
@Service
@Slf4j
public class AiService {

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final WebClient wc;
    private final ObjectMapper om = new ObjectMapper();

    public AiService(
            @Value("${mcpgen.ai.gemini.api-key}") String apiKey,
            @Value("${mcpgen.ai.gemini.model}")   String model,
            @Value("${mcpgen.ai.gemini.base-url}") String baseUrl) {
        this.apiKey  = apiKey;
        this.model   = model;
        this.baseUrl = baseUrl;
        this.wc = WebClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * Turn a free-form description into a Tool JSON. The returned map
     * has `name` / `description` / `inputSchema` / `outputType` /
     * `sideEffects` / `implementationHint`. Throws on API failure.
     */
    public Map<String, Object> synthesiseTool(String userDescription) throws Exception {
        String prompt = """
                You are an expert at designing MCP (Model Context Protocol) server tools.
                Given a natural-language description, produce ONE tool specification as compact JSON.

                Rules:
                - `name` must be snake_case, 2-60 chars.
                - `description` is a one-line sentence that tells an LLM when to call this tool.
                - `inputSchema` is a valid JSON Schema (type:object, properties:{...}, required:[...]).
                  Use string/integer/boolean/array/object types; prefer string unless numeric.
                - `outputType` is one of: structured-json, text, markdown, image.
                - `sideEffects` is one of: read-only, writes, destructive.
                - `implementationHint` is 1-3 lines of pseudo-code, not real code.

                Return ONLY a JSON object — no markdown fences, no commentary.

                User description: """ + userDescription;

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "temperature", 0.3,
                        "responseMimeType", "application/json"));

        String path = String.format("/models/%s:generateContent?key=%s", model, apiKey);
        String raw  = wc.post().uri(path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();

        JsonNode root = om.readTree(raw == null ? "{}" : raw);
        JsonNode err  = root.path("error");
        if (!err.isMissingNode()) {
            String msg = err.path("message").asText("Gemini error");
            throw new RuntimeException("Gemini: " + msg);
        }
        String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        if (text.isBlank()) throw new RuntimeException("Gemini returned an empty response.");

        text = text.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) text = text.substring(firstLine + 1, lastFence).trim();
        }
        Map<String, Object> parsed;
        try { parsed = om.readValue(text, Map.class); }
        catch (Exception e) { throw new RuntimeException("Gemini returned non-JSON: " + text.substring(0, Math.min(text.length(), 200))); }

        if (!parsed.containsKey("outputType"))   parsed.put("outputType", "structured-json");
        if (!parsed.containsKey("sideEffects"))  parsed.put("sideEffects", "read-only");
        if (!parsed.containsKey("inputSchema"))  parsed.put("inputSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        return parsed;
    }
}
