package com.forgesphere.mcpgen.repo;

import com.forgesphere.mcpgen.model.McpProject;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface McpProjectRepository extends MongoRepository<McpProject, String> {

    /* ----- default catalog reads (hide soft-deleted) ----- */

    List<McpProject> findBySoftDeletedFalseOrderByCreatedAtDesc();

    List<McpProject> findByOwnerEmailAndSoftDeletedFalseOrderByCreatedAtDesc(String ownerEmail);

    List<McpProject> findByWorkspaceIdAndSoftDeletedFalseOrderByCreatedAtDesc(String workspaceId);

    /* ----- legacy unfiltered reads (kept for back-compat) ----- */

    List<McpProject> findByOwnerEmailOrderByCreatedAtDesc(String ownerEmail);
    List<McpProject> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

    /* ----- slug lookups ----- */

    Optional<McpProject> findByWorkspaceIdAndIdentitySlug(String workspaceId, String slug);

    /**
     * Find every project that shares the same slug within a workspace,
     * regardless of soft-delete state. Used by the version endpoint to
     * compute the next version number and prevent collisions.
     */
    List<McpProject> findByWorkspaceIdAndIdentitySlugOrderByCreatedAtDesc(String workspaceId, String slug);

    /**
     * Projects with a recorded `pushedCommitSha` are the only candidates
     * for background workflow polling — we can't ask GitHub about a run
     * for a repo we never pushed to. We additionally filter by `pushedAt`
     * within the last 24 h (handled in service code) to keep the working
     * set bounded.
     *
     * (Legacy direct-push path — kept so a repo pushed via Git Data API
     * still gets polled.)
     */
    List<McpProject> findByPushedCommitShaNotNull();

    /**
     * Pipeline-deploy path candidates: the onboarding pipeline creates the
     * repo, so `pushedCommitSha` is never set by us — instead we record
     * `pipelineRepoFullName` at dispatch time. Same 24 h `deployTriggeredAt`
     * bound is applied in service code.
     */
    List<McpProject> findByPipelineRepoFullNameNotNull();
}
