package com.forgesphere.mcpgen.web;

import com.forgesphere.mcpgen.dto.Dtos.*;
import com.forgesphere.mcpgen.service.McpProbeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

/**
 * Live helpers — probe any MCP URL, call one tool on an MCP URL, or
 * generate a secure random bearer token for the wizard. All endpoints
 * support `mock=true` so the UI stays responsive even without network.
 */
@RestController
@RequiredArgsConstructor
public class McpLiveController {

    private final McpProbeService probeSvc;
    private final SecureRandom random = new SecureRandom();

    @PostMapping("/probe")
    public Envelope<ProbeResponse> probe(@RequestBody ProbeRequest req) {
        return Envelope.ok(probeSvc.probe(req));
    }

    @PostMapping("/call")
    public Envelope<CallResponse> call(@RequestBody CallRequest req) {
        return Envelope.ok(probeSvc.call(req));
    }

    /** 32-byte hex token — the wizard's "Generate" button calls this. */
    @GetMapping("/token")
    public Envelope<Map<String, String>> token() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return Envelope.ok(Map.of("token", HexFormat.of().formatHex(buf)));
    }
}
