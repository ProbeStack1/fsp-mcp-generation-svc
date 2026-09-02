package com.forgesphere.mcpgen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Persisted GitHub Actions step logs for ONE failed job of a deploy run.
 *
 * <p>Only written for deploys that conclude non-{@code success} — the reaper
 * grabs the logs the moment it observes the terminal failure, because GitHub
 * purges Actions logs after ~90 days and "why did it break" is exactly what
 * you want kept. Successful deploys are never stored; their step logs are
 * live-fetched from GitHub on demand (nobody opens an old green run).</p>
 *
 * <p>Bounded on purpose: only the first failing step and the steps after it
 * are kept, each tail-truncated, with a hard ~500 KB ceiling across the whole
 * run. A 365-day TTL on {@link #createdAt} self-cleans the collection.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "mcp_deploy_step_logs")
@CompoundIndex(name = "project_run_job", def = "{'projectId': 1, 'runId': 1, 'jobId': 1}", unique = true)
public class McpDeployStepLog {

    @Id
    private String id;

    @Indexed
    private String projectId;

    @Indexed
    private String runId;

    private String jobId;
    private String jobName;
    private String conclusion;   // failure | cancelled | timed_out | ...

    /** First failing step + every step after it. Earlier green steps are dropped. */
    private List<StepLog> steps;

    private int totalBytes;
    private boolean truncated;

    /** 365-day TTL — Mongo drops the row automatically after this. */
    @Indexed(expireAfterSeconds = 31_536_000)
    private Instant createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepLog {
        private Integer number;
        private String name;
        private String conclusion;
        private String log;
    }
}
