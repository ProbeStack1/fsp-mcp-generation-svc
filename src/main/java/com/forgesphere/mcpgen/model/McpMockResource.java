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
@Document(collection = "mcp_mock_resources")
public class McpMockResource {

    @Id
    private String id;

    @Indexed
    private String mockServerId;

    private String resourceName;
    private String uriTemplate;
    private String resourceDescription;
    private String mimeType;
    private Map<String, Object> mockData;

    private Boolean isActive;
    private Instant createdAt;
    private Instant updatedAt;
}
