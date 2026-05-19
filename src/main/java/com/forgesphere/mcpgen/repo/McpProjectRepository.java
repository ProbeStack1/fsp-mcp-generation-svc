package com.forgesphere.mcpgen.repo;

import com.forgesphere.mcpgen.model.McpProject;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface McpProjectRepository extends MongoRepository<McpProject, String> {
    List<McpProject> findByOwnerEmailOrderByCreatedAtDesc(String ownerEmail);
    List<McpProject> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
    Optional<McpProject> findByWorkspaceIdAndIdentitySlug(String workspaceId, String slug);

    /**
     * Projects with a recorded `pushedCommitSha` are the only candidates
     * for background workflow polling — we can't ask GitHub about a run
     * for a repo we never pushed to. We additionally filter by `pushedAt`
     * within the last 24 h (handled in service code) to keep the working
     * set bounded.
     */
    List<McpProject> findByPushedCommitShaNotNull();
}
