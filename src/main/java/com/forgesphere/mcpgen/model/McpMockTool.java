package com.forgesphere.mcpgen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "mcp_mock_tools")
public class McpMockTool {

    @Id
    private String id;

    @Indexed
    private String mockServerId;

    private String toolName;
    private String toolDescription;
    private Map<String, Object> inputSchema;

    // Mock data
    private Map<String, Object> mockRequest;
    private Map<String, Object> mockResponse;

    private Boolean isActive;
    private Instant createdAt;
    private Instant updatedAt;
}