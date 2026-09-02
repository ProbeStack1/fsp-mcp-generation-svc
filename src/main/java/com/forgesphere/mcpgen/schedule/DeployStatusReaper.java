/**
 * DeployStatusReaper — backend-side scheduled poller for in-flight
 * GitHub workflow runs.
 *
 * Problem this solves:
 *   Audit trail updates (`auditTrail.totalDeploys`, deployHistory[])
 *   used to depend on the FRONTEND polling `/projects/{id}/workflow-runs/latest`
 *   while the user kept a browser tab open. Anyone who pushed and
 *   navigated away (or closed the tab) before the workflow finished
 *   ended up with `totalDeploys = 0` in Mongo despite GitHub clearly
 *   having run their workflow. User-visible symptom: "5-10 baar deploy
 *   kiya, abhi bhi count zero hai".
 *
 * What this does:
 *   Every 30 s a single Spring task wakes up, finds every project that
 *   has a `pushedCommitSha` set and whose last push was within the
 *   last 24 h (working set is small — the long tail of stale projects
 *   doesn't bloat the loop), and calls the same `getLatestWorkflowRun`
 *   that the controller exposes. The audit dedup logic in
 *   `McpProjectController` (don't append the same runId twice) is
 *   re-used by inlining the same recording snippet here — so a run
 *   is written exactly once whether the polling came from the UI or
 *   from this scheduler.
 *
 * Safety:
 *   - All exceptions swallowed per-project so a single bad doc can't
 *     stall the loop.
 *   - Polls in single thread; if GitHub is slow we just skip the next
 *     tick rather than stack work.
 *   - Skips projects whose newest deploy entry already matches the
 *     latest GitHub runId (i.e. nothing to write).
 */
package com.forgesphere.mcpgen.schedule;

import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.repo.McpProjectRepository;
import com.forgesphere.mcpgen.service.AuditService;
import com.forgesphere.mcpgen.service.MicroserviceBridgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeployStatusReaper {

    private final McpProjectRepository       projects;
    private final MicroserviceBridgeService  bridge;
    private final AuditService               audit;

    /**
     * Cron-style: every 30 seconds. `fixedDelay` (not `fixedRate`) so a
     * slow GitHub tick can't trigger a parallel run.
     */
    @Scheduled(fixedDelay = 15_000L, initialDelay = 10_000L)
    public void tick() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        // Two candidate sets, de-duplicated by id:
        //   • pipeline deploys  → pipelineRepoFullName set at dispatch time
        //   • legacy direct push → pushedCommitSha set after a Git Data push
        java.util.Map<String, McpProject> candidates = new java.util.LinkedHashMap<>();
        try {
            for (McpProject p : projects.findByPipelineRepoFullNameNotNull()) {
                if (p.getId() != null) candidates.put(p.getId(), p);
            }
            for (McpProject p : projects.findByPushedCommitShaNotNull()) {
                if (p.getId() != null) candidates.putIfAbsent(p.getId(), p);
            }
        } catch (Exception ex) {
            log.warn("[reaper] candidate query failed: {}", ex.getMessage());
            return;
        }
        int polled = 0, recorded = 0;
        for (McpProject p : candidates.values()) {
            // Bound the working set: skip projects whose last deploy trigger /
            // push is older than 24 h — UNLESS they still carry an in-flight
            // (non-terminal) deploy entry that nobody has resolved yet (e.g. a
            // queued row seeded at dispatch that never got its GitHub runId).
            // Re-opening such a project also triggers an on-demand poll via the
            // UI's `/workflow-runs/latest` GET.
            Instant last = p.getDeployTriggeredAt() != null ? p.getDeployTriggeredAt() : p.getPushedAt();
            if (last != null && last.isBefore(cutoff) && !hasInFlightDeploy(p)) continue;
            try {
                polled++;
                if (pollOne(p)) recorded++;
            } catch (Exception ex) {
                log.debug("[reaper] {} failed: {}", p.getId(), ex.getMessage());
            }
        }
        if (recorded > 0) {
            log.info("[reaper] polled={} new_audit_rows={}", polled, recorded);
        }
    }

    /** True if the project has a deploy entry that hasn't reached a terminal state yet. */
    private static boolean hasInFlightDeploy(McpProject p) {
        if (p.getAuditTrail() == null || p.getAuditTrail().getDeployHistory() == null) return false;
        for (McpProject.DeployEntry de : p.getAuditTrail().getDeployHistory()) {
            if (de.getRolledBackFrom() != null) continue;
            boolean terminal = "completed".equalsIgnoreCase(de.getStatus())
                    && de.getConclusion() != null && !de.getConclusion().isBlank();
            if (!terminal) return true;
        }
        return false;
    }

    /**
     * Public alias for the controller's on-demand reconcile endpoint.
     * Same audit-write semantics as the scheduled tick (idempotent,
     * dedup-by-runId, failure-attribution included).
     */
    public boolean pollOneForController(McpProject p) {
        try { return pollOne(p); }
        catch (Exception ex) { log.warn("[reaper] on-demand poll for {} failed: {}", p.getId(), ex.getMessage()); return false; }
    }

    /**
     * Returns `true` if a deploy entry was appended to the audit trail
     * (i.e. we observed a NEW terminal status). False otherwise.
     *
     * Mirrors `McpProjectController.latestWorkflowRun`'s dedup +
     * failure-attribution logic, just driven by the scheduler instead
     * of the UI. Kept inline rather than extracted to avoid spreading
     * audit-write logic across two services — when the controller is
     * eventually refactored both paths should call the same helper.
     */
    private boolean pollOne(McpProject p) {
        Map<String, Object> out = bridge.getLatestWorkflowRun(p);
        if (out == null) return false;
        String runId      = (String) out.get("runId");
        String status     = (String) out.get("status");
        String conclusion = (String) out.get("conclusion");
        String runUrl     = (String) out.get("htmlUrl");
        if (runId == null || runId.isBlank()) return false;

        // Snapshot the trail BEFORE upsert so we can return whether a
        // meaningful change happened (used by the manual reconcile
        // endpoint's `wroteNewEntry` field).
        var trailBefore = audit.ensure(p);
        int beforeCount = trailBefore.getDeployHistory().size();
        int beforeSuccess = trailBefore.getTotalDeploysSuccess();
        int beforeFailed  = trailBefore.getTotalDeploysFailed();

        // Failure attribution — only meaningful at terminal time.
        String failedStep   = null;
        String failedReason = null;
        boolean terminalFailure = "completed".equalsIgnoreCase(status)
                && conclusion != null && !"success".equalsIgnoreCase(conclusion);
        if (terminalFailure) {
            try {
                Map<String, Object> steps = bridge.getWorkflowRunSteps(p, runId);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> jobs =
                        (List<Map<String, Object>>) steps.getOrDefault("jobs", List.of());
                outer:
                for (Map<String, Object> job : jobs) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> sList =
                            (List<Map<String, Object>>) job.getOrDefault("steps", List.of());
                    for (Map<String, Object> step : sList) {
                        String sConcl = String.valueOf(step.getOrDefault("conclusion", ""));
                        if ("failure".equalsIgnoreCase(sConcl)
                                || "cancelled".equalsIgnoreCase(sConcl)
                                || "timed_out".equalsIgnoreCase(sConcl)) {
                            failedStep   = String.valueOf(step.getOrDefault("name", "(unknown)"));
                            failedReason = "Step \"" + failedStep + "\" reported "
                                    + sConcl.toLowerCase()
                                    + " in job \"" + job.getOrDefault("name", "?") + "\". "
                                    + "Open the workflow run on GitHub for the full byte-by-byte log.";
                            break outer;
                        }
                    }
                }
            } catch (Exception ignore) { /* best-effort */ }
            if (failedStep == null) {
                failedStep   = "deploy-to-cloud-run";
                failedReason = "Workflow concluded \"" + conclusion + "\". Open the run on GitHub for details.";
            }
        }

        var actor = trailBefore.getLastUpdatedBy();
        if (actor == null) {
            actor = McpProject.AuditActor.builder()
                    .email(Objects.toString(p.getUpdatedBy(), "system"))
                    .name("background-reaper")
                    .timestamp(Instant.now()).build();
        }

        audit.upsertDeployFromPoll(p, actor,
                runId, runUrl, status, conclusion,
                p.getDeployedServiceUrl(), null,
                failedStep, failedReason, p.getPushedCommitSha());
        audit.save(p);

        var trailAfter = audit.ensure(p);
        boolean appended = trailAfter.getDeployHistory().size() > beforeCount;
        boolean terminalTransition = trailAfter.getTotalDeploysSuccess() > beforeSuccess
                || trailAfter.getTotalDeploysFailed() > beforeFailed;
        boolean changed = appended || terminalTransition;
        if (changed) {
            log.debug("[reaper] {} {} runId={} status={} conclusion={}",
                    p.getId(),
                    appended ? "inserted" : "updated",
                    runId, status, conclusion);
        }
        return changed;
    }
}
