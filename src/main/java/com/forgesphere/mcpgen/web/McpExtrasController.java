package com.forgesphere.mcpgen.web;

import com.forgesphere.mcpgen.dto.Dtos.Envelope;
import com.forgesphere.mcpgen.service.AiService;
import com.forgesphere.mcpgen.service.CollectionParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Extra endpoints:
 *   POST /ai/synthesize-tool     — natural-language → one MCP tool
 *   POST /collections/parse      — auto-detect OpenAPI/Postman/Insomnia/ForgeQ/raw
 *
 * Cloud Run deployment is intentionally NOT exposed here — the platform
 * has its own Git-driven CI/CD pipeline that owns deployments.
 */
@RestController
@RequiredArgsConstructor
public class McpExtrasController {

    private final AiService ai;
    private final CollectionParserService parser;

    // ---------------- AI tool synthesis ----------------
    public record SynthesiseReq(String description) {}

    @PostMapping("/ai/synthesize-tool")
    public Envelope<Object> synthesize(@RequestBody SynthesiseReq req) {
        if (req == null || req.description() == null || req.description().isBlank())
            return Envelope.fail("description is required");
        try { return Envelope.ok(ai.synthesiseTool(req.description())); }
        catch (Exception e) { return Envelope.fail(e.getMessage()); }
    }

    // ---------------- Universal parser ----------------
    public record ParseReq(String raw) {}

    @PostMapping("/collections/parse")
    public Envelope<Object> parse(@RequestBody ParseReq req) {
        if (req == null || req.raw() == null || req.raw().isBlank())
            return Envelope.fail("raw is required");
        try {
            var out = parser.parse(req.raw());
            return Envelope.ok(Map.of(
                    "detectedFormat", out.detectedFormat(),
                    "description",    out.description(),
                    "totalEndpoints", out.totalEndpoints(),
                    "tools",          out.tools()));
        } catch (Exception e) { return Envelope.fail(e.getMessage()); }
    }
}
