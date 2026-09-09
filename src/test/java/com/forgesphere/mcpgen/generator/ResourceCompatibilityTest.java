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

    @Test void everyGeneratorRejectsMissingUriWithActionableError() throws Exception {
        var spec = new ObjectMapper().readValue("{\"capabilities\":{\"resources\":[{\"name\":\"broken-guide\"}]}}", McpProject.class);
        for (var generator : List.of(new TypeScriptGenerator(), new PythonGenerator(), new JavaSpringGenerator(), new RawSpecGenerator())) {
            var error = assertThrows(IllegalArgumentException.class, () -> generator.generate(spec));
            assertTrue(error.getMessage().contains("broken-guide"));
            assertTrue(error.getMessage().contains("missing its URI"));
        }
    }
}
