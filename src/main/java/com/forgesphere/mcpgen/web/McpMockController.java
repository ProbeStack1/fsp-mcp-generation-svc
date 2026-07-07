package com.forgesphere.mcpgen.web;

import com.forgesphere.mcpgen.dto.Dtos.Envelope;
import com.forgesphere.mcpgen.model.McpMockServer;
import com.forgesphere.mcpgen.service.McpMockService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/projects/{projectId}/mock")
@RequiredArgsConstructor
public class McpMockController {

    private final McpMockService mockService;

    @PostMapping
    public Envelope<Map<String, Object>> generateMock(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail) {

        String transport = payload != null && payload.containsKey("transport")
                ? payload.get("transport").toString()
                : "http";

        McpMockServer mock = mockService.createMockFromProject(projectId, transport, userEmail);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mockServerId", mock.getId());
        response.put("mockServerUrl", mock.getMockServerUrl());
        response.put("transport", mock.getTransport());
        response.put("generatedAt", mock.getGeneratedAt());
        response.put("generatedBy", mock.getGeneratedBy());

        return Envelope.ok(response);
    }

    @GetMapping
    public Envelope<McpMockServer> getMock(@PathVariable String projectId) {
        McpMockServer mock = mockService.getByProjectId(projectId);
        if (mock == null) {
            // Return 404 with null data – frontend will treat as "no mock"
            return Envelope.fail("Mock server not found");
        }
        return Envelope.ok(mock);
    }

    @DeleteMapping
    public Envelope<Void> deleteMock(@PathVariable String projectId) {
        mockService.deleteByProjectId(projectId);
        return Envelope.ok(null);
    }

    @PostMapping("/regenerate")
    public Envelope<Map<String, Object>> regenerate(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail) {
        mockService.deleteByProjectId(projectId);
        return generateMock(projectId, payload, userEmail);
    }

    @GetMapping("/download")
    public ResponseEntity<ByteArrayResource> downloadMock(@PathVariable String projectId) {
        byte[] zip = mockService.generateMockZip(projectId);
        ByteArrayResource resource = new ByteArrayResource(zip);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"mcp-mock-" + projectId + ".zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(zip.length)
                .body(resource);
    }
}