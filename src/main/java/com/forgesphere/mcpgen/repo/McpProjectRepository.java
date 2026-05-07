package com.forgesphere.mcpgen.repo;

import com.forgesphere.mcpgen.model.McpProject;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface McpProjectRepository extends MongoRepository<McpProject, String> {
    List<McpProject> findByOwnerEmailOrderByCreatedAtDesc(String ownerEmail);
    List<McpProject> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
    Optional<McpProject> findByWorkspaceIdAndIdentitySlug(String workspaceId, String slug);
}
