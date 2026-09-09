package com.forgesphere.mcpgen.service;

import com.forgesphere.mcpgen.model.*;
import com.forgesphere.mcpgen.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class McpMockService {

    private final McpProjectRepository projectRepository;
    private final McpMockServerRepository mockServerRepository;
    private final McpMockToolRepository mockToolRepository;
    private final McpMockResourceRepository mockResourceRepository;
    private final McpMockPromptRepository mockPromptRepository;

    @Value("${mock.service.base-url:https://forgesphere.probestack.io}")
    private String mockServiceBaseUrl;

    /**
     * Create or update a mock server from the project's capabilities (UPSERT).
     * If a mock already exists for this project, it is replaced.
     */
    public McpMockServer createMockFromProject(String projectId, String transport, String userEmail) {
        // 1. Fetch project
        McpProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        // 2. Validate capabilities
        if (project.getCapabilities() == null ||
                (project.getCapabilities().getTools().isEmpty() &&
                 project.getCapabilities().getResources().isEmpty() &&
                 project.getCapabilities().getPrompts().isEmpty())) {
            throw new IllegalArgumentException(
                    "No capabilities found. Define Tools, Resources, or Prompts first."
            );
        }

        // 3. Check existing mock
        McpMockServer existing = mockServerRepository.findByProjectId(projectId).orElse(null);

        McpMockServer mockServer;
        if (existing != null) {
            // Update existing mock
            mockServer = existing;
            // Clean old tools/resources/prompts
            mockToolRepository.deleteByMockServerId(mockServer.getId());
            mockResourceRepository.deleteByMockServerId(mockServer.getId());
            mockPromptRepository.deleteByMockServerId(mockServer.getId());
        } else {
            // Create new mock – generate unique mock URL
            String mockUrl = generateMockUrl();
            while (mockServerRepository.existsByMockUrl(mockUrl)) {
                mockUrl = generateMockUrl();
            }
            mockServer = McpMockServer.builder()
                    .id(UUID.randomUUID().toString())
                    .projectId(projectId)
                    .name(project.getIdentity().getDisplayName() + "-mock")
                    .mockUrl(mockUrl)
                    .createdAt(Instant.now())
                    .build();
        }

        // 4. Update common fields
        String mockUrl = mockServer.getMockUrl();
        mockServer.setTransport(transport);
        mockServer.setGeneratedBy(userEmail != null ? userEmail : "system");
        mockServer.setGeneratedAt(Instant.now());
        mockServer.setUpdatedAt(Instant.now());

        if ("http".equals(transport)) {
            mockServer.setMockServerUrl(buildMockServerUrl(mockUrl));
            mockServer.setDownloadUrl(null);
        } else {
            // Stdio: no HTTP URL; download URL will be resolved when requested
            mockServer.setMockServerUrl(null);
            mockServer.setDownloadUrl(null); // Will be set by the download endpoint if needed
        }

        // Save mock server
        mockServer = mockServerRepository.save(mockServer);

        // 5. Create mock tools, resources, prompts
        createMockTools(mockServer.getId(), project.getCapabilities().getTools());
        createMockResources(mockServer.getId(), project.getCapabilities().getResources());
        createMockPrompts(mockServer.getId(), project.getCapabilities().getPrompts());

        // 6. Save mockServerId on the project
        project.setMockServerId(mockServer.getId());
        project.setUpdatedAt(Instant.now());
        if (userEmail != null && !userEmail.isBlank()) {
            project.setUpdatedBy(userEmail);
        }
        projectRepository.save(project);

        log.info("MCP mock server {} (transport={}) for project {}",
                mockServer.getId(), transport, projectId);

        return mockServer;
    }

    private void createMockTools(String mockServerId, List<McpProject.Tool> tools) {
        for (McpProject.Tool tool : tools) {
            McpMockTool mockTool = McpMockTool.builder()
                    .id(UUID.randomUUID().toString())
                    .mockServerId(mockServerId)
                    .toolName(tool.getName())
                    .toolDescription(tool.getDescription())
                    .inputSchema(tool.getInputSchema())
                    .mockRequest(generateMockRequest(tool))
                    .mockResponse(generateMockResponse(tool))
                    .isActive(true)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            mockToolRepository.save(mockTool);
        }
    }

    private void createMockResources(String mockServerId, List<McpProject.Resource> resources) {
        for (McpProject.Resource resource : resources) {
            McpMockResource mockResource = McpMockResource.builder()
                    .id(UUID.randomUUID().toString())
                    .mockServerId(mockServerId)
                    .resourceName(resource.getName())
                    .uriTemplate(resource.getUriTemplate())
                    .resourceDescription(resource.getDescription())
                    .mimeType(resource.getMimeType())
                    .mockData(generateMockResourceData(resource))
                    .isActive(true)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            mockResourceRepository.save(mockResource);
        }
    }

    private void createMockPrompts(String mockServerId, List<McpProject.Prompt> prompts) {
        for (McpProject.Prompt prompt : prompts) {
            McpMockPrompt mockPrompt = McpMockPrompt.builder()
                    .id(UUID.randomUUID().toString())
                    .mockServerId(mockServerId)
                    .promptName(prompt.getName())
                    .promptDescription(prompt.getDescription())
                    .arguments(prompt.getArguments())
                    .mockArguments(generateMockArguments(prompt))
                    .mockTemplate(generateMockTemplate(prompt))
                    .isActive(true)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            mockPromptRepository.save(mockPrompt);
        }
    }

    // ---------- Mock Data Generators ----------

    private Map<String, Object> generateMockRequest(McpProject.Tool tool) {
        Map<String, Object> mock = new LinkedHashMap<>();
        Map<String, Object> schema = tool.getInputSchema() != null
                ? tool.getInputSchema() : Map.of();

        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> prop = (Map<String, Object>) entry.getValue();
            String type = (String) prop.getOrDefault("type", "string");

            if (prop.containsKey("example")) {
                mock.put(key, prop.get("example"));
            } else {
                mock.put(key, generateSampleValue(type));
            }
        }

        return mock;
    }

    private Map<String, Object> generateMockResponse(McpProject.Tool tool) {
        Map<String, Object> mock = new LinkedHashMap<>();
        mock.put("id", "mock-" + UUID.randomUUID().toString().substring(0, 8));
        mock.put("status", "success");
        mock.put("message", "Mock response for " + tool.getName());
        mock.put("timestamp", Instant.now().toString());
        return mock;
    }

    private Map<String, Object> generateMockResourceData(McpProject.Resource resource) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", "mock-res-" + UUID.randomUUID().toString().substring(0, 8));
        data.put("name", resource.getName());
        data.put("description", resource.getDescription());
        data.put("mimeType", resource.getMimeType());
        data.put("content", "Mock content for " + resource.getName());
        return data;
    }

    private Map<String, Object> generateMockArguments(McpProject.Prompt prompt) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (prompt.getArguments() != null) {
            for (McpProject.PromptArg arg : prompt.getArguments()) {
                args.put(arg.getName(), "mock-" + arg.getName());
            }
        }
        return args;
    }

    private String generateMockTemplate(McpProject.Prompt prompt) {
        if (prompt.getTemplate() != null) {
            return prompt.getTemplate();
        }
        return "This is a mock prompt template for " + prompt.getName();
    }

    private Object generateSampleValue(String type) {
        return switch (type) {
            case "string" -> "sample-string";
            case "number", "integer" -> 42;
            case "boolean" -> true;
            case "array" -> List.of();
            case "object" -> Map.of("key", "value");
            default -> "sample-value";
        };
    }

    // ---------- Helpers ----------

    private String generateMockUrl() {
        return "mcp-mock-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String buildMockServerUrl(String mockUrl) {
        String base = mockServiceBaseUrl != null ? mockServiceBaseUrl.trim() : "";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/mcp-generate/v1/api/mocks/" + mockUrl + "/mcp";
    }

    public McpMockServer getByProjectId(String projectId) {
        return mockServerRepository.findByProjectId(projectId).orElse(null);
    }

    public void deleteByProjectId(String projectId) {
        McpMockServer mock = getByProjectId(projectId);
        if (mock != null) {
            String mockId = mock.getId();
            mockToolRepository.deleteByMockServerId(mockId);
            mockResourceRepository.deleteByMockServerId(mockId);
            mockPromptRepository.deleteByMockServerId(mockId);
            mockServerRepository.deleteById(mockId);
            log.info("Deleted MCP mock server: projectId={}, mockId={}", projectId, mockId);
        }
    }

    /**
     * Generate a zip file containing a stdio mock server.
     */
    public byte[] generateMockZip(String projectId) {
        McpMockServer mock = mockServerRepository.findByProjectId(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Mock server not found for project: " + projectId));

        if (!"stdio".equals(mock.getTransport())) {
            throw new IllegalStateException("Mock transport is not stdio, cannot generate zip.");
        }

        McpProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        List<McpProject.Tool> tools = project.getCapabilities().getTools();
        List<McpProject.Resource> resources = project.getCapabilities().getResources();
        List<McpProject.Prompt> prompts = project.getCapabilities().getPrompts();

        Map<String, Object> mockSpec = new LinkedHashMap<>();
        mockSpec.put("tools", tools); mockSpec.put("resources", resources); mockSpec.put("prompts", prompts);
        Map<String, Object> responses = new LinkedHashMap<>();
        for (var tool : tools) responses.put(tool.getName(), generateMockResponse(tool));
        Map<String, Object> resourceData = new LinkedHashMap<>();
        for (var resource : resources) resourceData.put(resource.getName(), generateMockResourceData(resource));
        mockSpec.put("responses", responses); mockSpec.put("resourceData", resourceData);
        StringBuilder indexJs = new StringBuilder(com.forgesphere.mcpgen.generator.GeneratorUtils.template("mock-stdio.cjs")
                .replace("__SPEC__", com.forgesphere.mcpgen.generator.GeneratorUtils.pretty(mockSpec)));
        // package.json
        String packageJson = "{\n" +
                "  \"name\": \"mcp-mock\",\n" +
                "  \"version\": \"1.0.0\",\n" +
                "  \"description\": \"MCP Mock Server\",\n" +
                "  \"main\": \"index.js\",\n" +
                "  \"scripts\": {\n" +
                "    \"start\": \"node index.js\"\n" +
                "  },\n" +
                "  \"dependencies\": {\n" +
                "    \"@modelcontextprotocol/sdk\": \"^1.12.0\", \"ajv\": \"^8.17.1\"\n" +
                "  }\n" +
                "}\n";

        // README
        String readme = "# MCP Mock Server (Stdio)\n\n" +
                "This is a mock MCP server for project " + projectId + ".\n\n" +
                "## Setup\n\n" +
                "```bash\nnpm install\n```\n\n" +
                "## Run\n\n" +
                "```bash\nnpm start\n```\n\n" +
                "The server will run over stdio and respond to tools/list and tools/call with mock data.\n";

        // Build zip
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // index.js
            ZipEntry entry1 = new ZipEntry("index.js");
            zos.putNextEntry(entry1);
            zos.write(indexJs.toString().getBytes());
            zos.closeEntry();

            // package.json
            ZipEntry entry2 = new ZipEntry("package.json");
            zos.putNextEntry(entry2);
            zos.write(packageJson.getBytes());
            zos.closeEntry();

            // README.md
            ZipEntry entry3 = new ZipEntry("README.md");
            zos.putNextEntry(entry3);
            zos.write(readme.getBytes());
            zos.closeEntry();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create zip", e);
        }
        return baos.toByteArray();
    }

    private String generateMockResponseJson(McpProject.Tool tool) {
        Map<String, Object> mock = new LinkedHashMap<>();
        mock.put("id", "mock-" + UUID.randomUUID().toString().substring(0, 8));
        mock.put("status", "success");
        mock.put("data", Map.of("message", "Mock response for " + tool.getName()));
        mock.put("timestamp", Instant.now().toString());
        return mock.toString();
    }
}
