package com.forgesphere.mcpgen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "mcp_mock_servers")
public class McpMockServer {

    @Id
    private String id;

    @Indexed
    private String projectId;           // Reference to McpProject

    private String name;
    private String mockUrl;             // Unique routing key (e.g., mcp-mock-abc)
    private String mockServerUrl;       // Full base URL for MCP endpoint
    private String transport;           // "http" or "stdio"

    private String generatedBy;         // User email
    private Instant generatedAt;

    private Long requestCount;
    private Instant createdAt;
    private Instant updatedAt;
}