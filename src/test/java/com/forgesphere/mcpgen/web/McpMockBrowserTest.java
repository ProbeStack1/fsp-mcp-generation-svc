package com.forgesphere.mcpgen.web;

import com.forgesphere.mcpgen.service.McpMockRuntimeService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpMockBrowserTest {
    @Test void healthReportsLookupSuccessMissingMockAndStorageFailure() {
        var service = mock(McpMockRuntimeService.class);
        var controller = new McpMockRuntimeController(service);
        when(service.handleRequest("demo", "ping", Map.of(), "health")).thenReturn(Map.of("result", Map.of()));
        var up = controller.health("demo");
        assertEquals(200, up.getStatusCode().value());
        assertEquals("UP", ((Map<?, ?>) up.getBody()).get("status"));
        when(service.handleRequest("demo", "ping", Map.of(), "health")).thenReturn(Map.of("error", Map.of()));
        assertEquals(404, controller.health("demo").getStatusCode().value());
        when(service.handleRequest("demo", "ping", Map.of(), "health")).thenThrow(new RuntimeException("database offline"));
        assertEquals(503, controller.health("demo").getStatusCode().value());
    }
    @Test void browserGetsHelpButSseDoesNotPretendToConnect() {
        var service = mock(McpMockRuntimeService.class);
        when(service.handleRequest("demo", "ping", Map.of(), 1)).thenReturn(Map.of("result", Map.of()));
        var controller = new McpMockRuntimeController(service);
        var browser = controller.browserHelp("demo", "text/html,application/xhtml+xml");
        assertEquals(200, browser.getStatusCode().value());
        assertTrue(browser.getBody().toString().contains("Streamable HTTP"));
        var stream = controller.browserHelp("demo", "text/event-stream");
        assertEquals(405, stream.getStatusCode().value());
        assertEquals("POST", stream.getHeaders().getFirst("Allow"));
    }
    @Test void missingMockIsNotAdvertisedAsAvailable() {
        var service = mock(McpMockRuntimeService.class);
        when(service.handleRequest("missing", "ping", Map.of(), 1)).thenReturn(Map.of("error", Map.of()));
        assertEquals(404, new McpMockRuntimeController(service).browserHelp("missing", "text/html").getStatusCode().value());
    }
}
