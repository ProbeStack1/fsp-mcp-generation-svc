package com.forgesphere.mcpgen.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.service.CollectionParserService;
import com.forgesphere.mcpgen.service.GenerationPostProcessor;
import com.forgesphere.mcpgen.service.TestCollectionGenerator;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpRuntimeGenerationTest {
    private final ObjectMapper json = new ObjectMapper();
    private McpProject fixture(String language) throws Exception {
        return json.readValue("""
            {"identity":{"slug":"mcp-fixture","displayName":"MCP fixture"},
             "runtime":{"language":"%s"},"transport":{"kind":"streamable-http"},"auth":{"kind":"none"},
             "capabilities":{"tools":[{"name":"get_item","description":"Read item","inputSchema":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]},
               "http":{"method":"GET","baseUrl":"http://localhost:18990","path":"/items/{id}","parameters":[{"name":"id","in":"path","argument":"id"}]}}],
               "resources":[{"name":"guide","uriTemplate":"docs://guide","mimeType":"text/plain","content":"Hello guide"},{"name":"item","uriTemplate":"docs://items/{id}","mimeType":"text/plain","content":"Item {{id}}"}],
               "prompts":[{"name":"greet","arguments":[{"name":"who","required":true}],"template":"Hello {{who}}"}]}}
            """.formatted(language), McpProject.class);
    }
    @Test void generateRunnableFixturesAndCompletePostman() throws Exception {
        for (CodeGenerator generator : List.of(new TypeScriptGenerator(), new PythonGenerator(), new JavaSpringGenerator())) {
            McpProject p = fixture(generator.language());
            var files = new GenerationPostProcessor().apply(p, new ArrayList<>(generator.generate(p)));
            Path root = Path.of("target", "runtime-verification", generator.language()).toAbsolutePath();
            // Remove the obsolete fixture from earlier generator revisions, preserving installed dependencies.
            Files.deleteIfExists(root.resolve("tests/tools.test.ts"));
            if (generator.language().equals("java")) Files.deleteIfExists(root.resolve("src/main/java/com/forgesphere/generated/mcpfixture/HealthController.java"));
            Set<String> names = new HashSet<>();
            for (var file : files) {
                assertTrue(names.add(file.getPath()), "Duplicate artifact: " + file.getPath());
                assertFalse(file.getContent().contains("expect(true).toBe(true)"));
                assertFalse(file.getContent().contains("assertTrue(true)"));
                Path target = root.resolve(file.getPath()).normalize(); assertTrue(target.startsWith(root));
                Files.createDirectories(target.getParent()); Files.writeString(target, file.getContent());
            }
            var collection = json.readTree(files.stream().filter(f -> f.getPath().equals("postman/collection.json")).findFirst().orElseThrow().getContent());
            assertEquals("initialize", collection.path("item").path(0).path("name").asText());
            assertEquals("notifications/initialized", collection.path("item").path(1).path("name").asText());
            assertTrue(collection.toString().contains("Mcp-Session-Id"));
            var scenarios = json.readTree(new TestCollectionGenerator().generate(p).get("scenarioMetadata"));
            Set<String> ids = new HashSet<>(); for (var scenario : scenarios) assertTrue(ids.add(scenario.path("id").asText()));
        }
    }
    @Test void importPreservesBindingsReferencesAndOperationOverrides() throws Exception {
        var parsed = new CollectionParserService().parse("""
            {"openapi":"3.0.3","servers":[{"url":"https://api.example.test/v1"}],"components":{"schemas":{"Payload":{"type":"object","properties":{"count":{"type":"integer"}},"required":["count"]}}},
             "paths":{"/items/{id}":{"parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"string"}}],
             "post":{"operationId":"updateItem","requestBody":{"required":true,"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Payload"}}}}}}}}
            """);
        var tool = json.valueToTree(parsed.tools().get(0));
        assertEquals("/items/{id}", tool.path("http").path("path").asText());
        assertEquals("id", tool.path("http").path("parameters").path(0).path("argument").asText());
        assertEquals("integer", tool.path("inputSchema").path("properties").path("body").path("properties").path("count").path("type").asText());
    }
    @Test void disabledHelpersAreAbsent() throws Exception {
        McpProject p = fixture("typescript");
        var onboarding = new McpProject.Onboarding();
        onboarding.setGenerationOptions(Map.of("helpers", Map.of("dockerfile", false, "githubWorkflows", false)));
        p.setOnboarding(onboarding);
        var files = new GenerationPostProcessor().apply(p, new ArrayList<>(new TypeScriptGenerator().generate(p)));
        assertTrue(files.stream().noneMatch(f -> f.getPath().equals("Dockerfile") || f.getPath().startsWith(".github/workflows/")));
    }

    @Test void mockZipContainsExecutableJsonAndAllCapabilities() throws Exception {
        var projects = org.mockito.Mockito.mock(com.forgesphere.mcpgen.repo.McpProjectRepository.class);
        var mocks = org.mockito.Mockito.mock(com.forgesphere.mcpgen.repo.McpMockServerRepository.class);
        var mock = new com.forgesphere.mcpgen.model.McpMockServer(); mock.setTransport("stdio");
        org.mockito.Mockito.when(projects.findById("fixture")).thenReturn(Optional.of(fixture("typescript")));
        org.mockito.Mockito.when(mocks.findByProjectId("fixture")).thenReturn(Optional.of(mock));
        var service = new com.forgesphere.mcpgen.service.McpMockService(projects, mocks,
                org.mockito.Mockito.mock(com.forgesphere.mcpgen.repo.McpMockToolRepository.class),
                org.mockito.Mockito.mock(com.forgesphere.mcpgen.repo.McpMockResourceRepository.class),
                org.mockito.Mockito.mock(com.forgesphere.mcpgen.repo.McpMockPromptRepository.class));
        Path root = Path.of("target/runtime-verification/typescript/mock").toAbsolutePath();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(service.generateMockZip("fixture")))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path path = root.resolve(entry.getName()).normalize(); assertTrue(path.startsWith(root));
                Files.createDirectories(path.getParent()); Files.write(path, zip.readAllBytes());
            }
        }
        String source = Files.readString(root.resolve("index.js"));
        assertFalse(source.contains("__SPEC__"));
        assertTrue(source.contains("ReadResourceRequestSchema"));
        assertTrue(source.contains("GetPromptRequestSchema"));
    }
    @Test void authenticatedFixturesHonorHttpOptions() throws Exception {
        for (CodeGenerator generator : List.of(new TypeScriptGenerator(), new PythonGenerator(), new JavaSpringGenerator())) {
            McpProject project = fixture(generator.language());
            project.setAuth(McpProject.Auth.builder().kind("api-key").headerName("X-Fixture-Key").build());
            project.setAdvanced(json.readValue("""
                {"cors":{"enabled":true,"allowedOrigins":"https://client.example"},"rateLimit":{"enabled":true,"requestsPerMinute":100},
                "metrics":{"enabled":true,"path":"/metrics"},"healthCheck":{"enabled":true,"path":"/healthz"},"logging":{"enabled":true}}
                """, McpProject.Advanced.class));
            Path root = Path.of("target/runtime-verification", generator.language(), "auth-fixture").toAbsolutePath();
            for (var file : new GenerationPostProcessor().apply(project, new ArrayList<>(generator.generate(project)))) {
                Path path = root.resolve(file.getPath()).normalize(); assertTrue(path.startsWith(root));
                Files.createDirectories(path.getParent()); Files.writeString(path, file.getContent());
                if (file.getPath().startsWith(".github/workflows")) assertFalse(file.getContent().contains("|| true"));
            }
        }
    }
    @Test void stdioFixturesUseProcessTransportAndDoNotPretendToDeployHttp() throws Exception {
        for (CodeGenerator generator : List.of(new TypeScriptGenerator(), new PythonGenerator())) {
            McpProject project = fixture(generator.language()); project.getTransport().setKind("stdio");
            Path root = Path.of("target/runtime-verification", generator.language(), "stdio-fixture").toAbsolutePath();
            var files = new GenerationPostProcessor().apply(project, new ArrayList<>(generator.generate(project)));
            assertFalse(files.stream().anyMatch(f -> f.getPath().equals("postman/collection.json")));
            for (var file : files) {
                Path path = root.resolve(file.getPath()).normalize(); assertTrue(path.startsWith(root));
                Files.createDirectories(path.getParent()); Files.writeString(path, file.getContent());
                if (file.getPath().startsWith(".github/workflows/")) assertFalse(file.getContent().contains("gcloud run"));
            }
        }
    }
}
