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
@Document(collection = "mcp_mock_prompts")
public class McpMockPrompt {

    @Id
    private String id;

    @Indexed
    private String mockServerId;

    private String promptName;
    private String promptDescription;
    private java.util.List<McpProject.PromptArg> arguments;
    private Map<String, Object> mockArguments;
    private String mockTemplate;

    private Boolean isActive;
    private Instant createdAt;
    private Instant updatedAt;
}
