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

    // ---------------- AI batch capability synthesis ----------------
    /**
     * User describes the whole MCP server in 1-2 sentences and picks
     * how many tools / resources / prompts they want generated. Each
     * count is clamped to 0..10 server-side. Front-end's
     * `AISynthesizeToolModal` calls this when the user toggles batch
     * mode and selects the per-capability checkboxes + quantity.
     */
    public record SynthesiseCapsReq(String description,
                                    Integer toolCount,
                                    Integer resourceCount,
                                    Integer promptCount) {}

    @PostMapping("/ai/synthesize-capabilities")
    public Envelope<Object> synthesizeCapabilities(@RequestBody SynthesiseCapsReq req) {
        if (req == null || req.description() == null || req.description().isBlank())
            return Envelope.fail("description is required");
        int t = req.toolCount()     == null ? 3 : req.toolCount();
        int r = req.resourceCount() == null ? 0 : req.resourceCount();
        int p = req.promptCount()   == null ? 0 : req.promptCount();
        try { return Envelope.ok(ai.synthesiseCapabilities(req.description(), t, r, p)); }
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
