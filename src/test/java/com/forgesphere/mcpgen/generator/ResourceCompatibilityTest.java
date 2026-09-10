package com.forgesphere.mcpgen.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgesphere.mcpgen.model.McpProject;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ResourceCompatibilityTest {
    @Test void staticWireUriIsAvailableToGenerators() throws Exception {
        var resource = new ObjectMapper().readValue("{\"name\":\"guide\",\"uri\":\"docs://guide\"}", McpProject.Resource.class);
        assertEquals("docs://guide", resource.getUriTemplate());
        resource.setUriTemplate("docs://preferred");
        assertEquals("docs://preferred", resource.getUriTemplate());
    }

    @Test void everyGeneratorBackfillsAMissingUriInsteadOfFailing() throws Exception {
        for (var generator : List.of(new TypeScriptGenerator(), new PythonGenerator(), new JavaSpringGenerator(), new RawSpecGenerator())) {
            var spec = new ObjectMapper().readValue("{\"capabilities\":{\"resources\":[{\"name\":\"broken-guide\"}]}}", McpProject.class);
            assertDoesNotThrow(() -> generator.generate(spec));
            assertEquals("resource://broken_guide",
                    spec.getCapabilities().getResources().get(0).getUriTemplate());
        }
    }

    @Test void validateResourcesReturnsAReviewWarningPerBackfilledResource() throws Exception {
        var spec = new ObjectMapper().readValue("{\"capabilities\":{\"resources\":[{\"name\":\"broken-guide\"}]}}", McpProject.class);
        var warnings = GeneratorUtils.validateResources(spec);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("broken-guide"));
        assertTrue(warnings.get(0).contains("resource://broken_guide"));
    }
}
