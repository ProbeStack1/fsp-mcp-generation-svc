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

    /**
     * Multi-capability batch synthesis — the user describes the whole MCP
     * server in one sentence and selects how many tools / resources /
     * prompts they want generated. Returns a single map keyed by
     * {@code tools}, {@code resources}, {@code prompts} (each a list).
     *
     * Each requested count must be in 0..10. We cap at 10 so the prompt
     * stays focused — bigger servers should be authored via the OpenAPI
     * importer, not the AI brainstorm path.
     */
    public Map<String, Object> synthesiseCapabilities(String userDescription,
                                                      int toolCount,
                                                      int resourceCount,
                                                      int promptCount) throws Exception {
        int tn = Math.max(0, Math.min(10, toolCount));
        int rn = Math.max(0, Math.min(10, resourceCount));
        int pn = Math.max(0, Math.min(10, promptCount));
        if (tn + rn + pn == 0) {
            return Map.of("tools", List.of(), "resources", List.of(), "prompts", List.of());
        }

        String prompt = """
                You are an expert at designing MCP (Model Context Protocol) servers.
                Given a one-sentence description of a server, propose a coherent set of capabilities.

                Produce ONE JSON object with exactly these top-level keys: "tools", "resources", "prompts".
                Each is an array. Generate:
                  - %d tools
                  - %d resources
                  - %d prompts

                Rules per tool:
                  - name: snake_case, 2-60 chars.
                  - description: one short sentence.
                  - inputSchema: valid JSON Schema (type:object, properties:{...}, required:[...]).
                  - outputType: one of structured-json | text | markdown | image.
                  - sideEffects: one of read-only | writes | destructive.
                  - implementationHint: 1-3 lines of pseudo-code.

                Rules per resource:
                  - name: short kebab-case label.
                  - uriTemplate: e.g. "repo://{owner}/{name}/issues".
                  - description: one sentence.
                  - mimeType: application/json | text/plain | text/markdown.
                  - mode: static | dynamic.

                Rules per prompt:
                  - name: snake_case.
                  - description: one sentence.
                  - arguments: array of { name, description, required:boolean }.
                  - template: markdown body with {{placeholders}} matching arguments.

                Return ONLY the JSON object — no markdown fences, no commentary.

                Server description: """.formatted(tn, rn, pn) + userDescription;

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "temperature", 0.4,
                        "responseMimeType", "application/json"));

        String path = String.format("/models/%s:generateContent?key=%s", model, apiKey);
        String raw  = wc.post().uri(path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(45))
                .block();

        JsonNode root = om.readTree(raw == null ? "{}" : raw);
        JsonNode err  = root.path("error");
        if (!err.isMissingNode()) throw new RuntimeException("Gemini: " + err.path("message").asText("error"));
        String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        if (text.isBlank()) throw new RuntimeException("Gemini returned an empty response.");
        text = text.trim();
        if (text.startsWith("```")) {
            int nl = text.indexOf('\n');
            int en = text.lastIndexOf("```");
            if (nl >= 0 && en > nl) text = text.substring(nl + 1, en).trim();
        }
        Map<String, Object> parsed;
        try { parsed = om.readValue(text, Map.class); }
        catch (Exception e) { throw new RuntimeException("Gemini returned non-JSON: " + text.substring(0, Math.min(text.length(), 200))); }

        // Defensive defaults so the FE never has to null-guard.
        parsed.putIfAbsent("tools",     List.of());
        parsed.putIfAbsent("resources", List.of());
        parsed.putIfAbsent("prompts",   List.of());
        return parsed;
    }
}
