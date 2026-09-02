package com.forgesphere.mcpgen.service;

import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.*;
import com.forgesphere.mcpgen.repo.McpProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Audit / activity recorder.
 *
 * One source of truth for "who did what, when" across the MCP project
 * lifecycle. Every mutating service method routes through here so the
 * `auditTrail` substructure stays consistent and the Catalog cards +
 * the in-wizard "previously deployed" detection can rely on it.
 *
 * Senior team's convention reused intact: an actor is `{email, name,
 * timestamp}` — same shape that powers their onboarding / microservice
 * activity feeds — so the front-end activity panel renders uniformly
 * across pages without extra adapters.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final McpProjectRepository repo;

    /** Build an actor from raw caller-supplied identity. */
    public AuditActor actor(String email, String name) {
        return AuditActor.builder()
                .email(email == null ? "" : email)
                .name(name == null ? "" : name)
                .timestamp(Instant.now())
                .build();
    }

    /** Best-effort actor derived from the project itself when the caller
     *  forgot to send identity headers (e.g. internal service-to-service
     *  push). Falls back to the project owner's onboarding info. */
    public AuditActor fallbackActor(McpProject p) {
        String email = p.getOwnerEmail();
        String name  = null;
        if (p.getOnboarding() != null) {
            if (email == null || email.isBlank()) email = p.getOnboarding().getOwnerEmail();
            name = p.getOnboarding().getProjectOwner();
        }
        return actor(email, name);
    }

    /** Ensure the audit substructure exists with non-null lists/counters. */
    public AuditTrail ensure(McpProject p) {
        if (p.getAuditTrail() == null) {
            p.setAuditTrail(AuditTrail.builder().build());
        }
        AuditTrail a = p.getAuditTrail();
        if (a.getEditHistory()   == null) a.setEditHistory(new ArrayList<>());
        if (a.getPushHistory()   == null) a.setPushHistory(new ArrayList<>());
        if (a.getDeployHistory() == null) a.setDeployHistory(new ArrayList<>());
        if (a.getTotalEdits()          == null) a.setTotalEdits(0);
        if (a.getTotalPushes()         == null) a.setTotalPushes(0);
        if (a.getTotalDeploys()        == null) a.setTotalDeploys(0);
        if (a.getTotalDeploysSuccess() == null) a.setTotalDeploysSuccess(0);
        if (a.getTotalDeploysFailed()  == null) a.setTotalDeploysFailed(0);
        return a;
    }

    public void recordCreate(McpProject p, AuditActor actor) {
        AuditTrail a = ensure(p);
        a.setCreatedBy(actor);
        a.setLastUpdatedBy(actor);
    }

    public void recordEdit(McpProject p, AuditActor actor, List<String> fieldsChanged, String note) {
        AuditTrail a = ensure(p);
        a.setLastUpdatedBy(actor);
        a.getEditHistory().add(EditEntry.builder()
                .by(actor).fieldsChanged(fieldsChanged == null ? List.of() : fieldsChanged).note(note).build());
        a.setTotalEdits(a.getEditHistory().size());
    }

    public PushEntry recordPush(McpProject p, AuditActor actor,
                                String repoFullName, String repoUrl,
                                String branch, String commitSha,
                                Integer fileCount, String status, String error) {
        AuditTrail a = ensure(p);
        PushEntry e = PushEntry.builder().by(actor)
                .repoFullName(repoFullName).repoUrl(repoUrl).branch(branch)
                .commitSha(commitSha).fileCount(fileCount)
                .status(status).errorMessage(error).build();
        a.getPushHistory().add(e);
        a.setTotalPushes(a.getPushHistory().size());
        a.setLastUpdatedBy(actor);
        return e;
    }

    public DeployEntry recordDeploy(McpProject p, AuditActor actor,
                                    String runId, String runUrl,
                                    String status, String conclusion,
                                    String deployedUrl, Long durationMs,
                                    String failedStep, String failedReason,
                                    String rolledBackFrom, String commitSha) {
        AuditTrail a = ensure(p);
        DeployEntry e = DeployEntry.builder().by(actor)
                .runId(runId).runUrl(runUrl)
                .status(status).conclusion(conclusion)
                .deployedUrl(deployedUrl).durationMs(durationMs)
                .failedStep(failedStep).failedReason(failedReason)
                .rolledBackFrom(rolledBackFrom).commitSha(commitSha)
                .build();
        a.getDeployHistory().add(e);
        a.setTotalDeploys(a.getDeployHistory().size());
        if ("success".equalsIgnoreCase(conclusion))      a.setTotalDeploysSuccess(a.getTotalDeploysSuccess() + 1);
        else if (conclusion != null && !conclusion.isBlank())
            a.setTotalDeploysFailed(a.getTotalDeploysFailed() + 1);
        a.setLastUpdatedBy(actor);
        return e;
    }

    /**
     * Seed a {@code queued} deploy entry the MOMENT a pipeline deploy is
     * dispatched — so the timeline (and {@code totalDeploys}) reflects the
     * deploy immediately, exactly like the microservice flow's
     * {@code deployment_history} row. The scheduled reaper / on-demand poll
     * later ADOPTS this pending entry (fills in the real GitHub {@code runId}
     * and terminal status) via {@link #upsertDeployFromPoll}, so no duplicate
     * row is ever created.
     *
     * <p>Idempotent: if a non-terminal entry with no {@code runId} is already
     * in flight for this project, this is a no-op (a double-dispatch of the
     * same commit must not add a second row).</p>
     */
    public DeployEntry recordDeployDispatch(McpProject p, AuditActor actor,
                                            String commitSha, String deployedUrl) {
        AuditTrail a = ensure(p);
        for (DeployEntry de : a.getDeployHistory()) {
            if ((de.getRunId() == null || de.getRunId().isBlank())
                    && de.getRolledBackFrom() == null && !isTerminal(de)) {
                return de;
            }
        }
        DeployEntry e = DeployEntry.builder().by(actor)
                .status("queued")
                .commitSha(commitSha).deployedUrl(deployedUrl)
                .build();
        a.getDeployHistory().add(e);
        a.setTotalDeploys(a.getDeployHistory().size());
        a.setLastUpdatedBy(actor);
        return e;
    }

    private static boolean isTerminal(DeployEntry de) {
        return "completed".equalsIgnoreCase(de.getStatus())
                && de.getConclusion() != null && !de.getConclusion().isBlank();
    }

    /**
     * Self-heal a pending ({@code queued}, no {@code runId}, or mid-flight)
     * deploy entry from the project doc's OWN {@code latestRun*} /
     * {@code deployedServiceUrl} fields — which the wizard's push/deploy poll
     * already populates. This closes the gap where a deploy finished but the
     * reaper never got a fresh GitHub sighting to adopt the seeded entry, so
     * the timeline stayed stuck on "Pending" and rollback stayed unavailable.
     *
     * <p>No network call — purely reconciles two places on the same document.
     * Returns {@code true} if it changed anything (caller should persist).</p>
     */
    public boolean resolvePendingDeploy(McpProject p, AuditActor actor) {
        AuditTrail a = ensure(p);

        DeployEntry pending = null;
        for (DeployEntry de : a.getDeployHistory()) {
            if (de.getRolledBackFrom() == null && !isTerminal(de)) pending = de; // newest non-terminal
        }
        if (pending == null) return false;

        String runId      = p.getLatestRunId();
        String runUrl     = p.getLatestRunUrl();
        String runStatus  = p.getLatestRunStatus();
        String conclusion = p.getLatestRunConclusion();
        String deployedUrl = p.getDeployedServiceUrl();

        // Nothing new to pull from the doc.
        if ((runId == null || runId.isBlank())
                && (conclusion == null || conclusion.isBlank())
                && (deployedUrl == null || deployedUrl.isBlank())) {
            return false;
        }

        // Infer a terminal state when GitHub's own conclusion wasn't captured
        // but the service is clearly live (URL present, not still running).
        boolean running = "queued".equalsIgnoreCase(runStatus) || "in_progress".equalsIgnoreCase(runStatus);
        if ((conclusion == null || conclusion.isBlank()) && deployedUrl != null && !deployedUrl.isBlank() && !running) {
            conclusion = "success";
            runStatus  = "completed";
        }

        boolean wasTerminal = isTerminal(pending);
        boolean changed = false;

        if (runId != null && !runId.isBlank() && !runId.equals(pending.getRunId())) {
            pending.setRunId(runId); changed = true;
        }
        if (runUrl != null && !runUrl.isBlank() && !runUrl.equals(pending.getRunUrl())) {
            pending.setRunUrl(runUrl); changed = true;
        }
        if (runStatus != null && !runStatus.isBlank() && !runStatus.equalsIgnoreCase(pending.getStatus())) {
            pending.setStatus(runStatus); changed = true;
        }
        if (conclusion != null && !conclusion.isBlank() && !conclusion.equalsIgnoreCase(pending.getConclusion())) {
            pending.setConclusion(conclusion); changed = true;
        }
        if (deployedUrl != null && !deployedUrl.isBlank() && !deployedUrl.equals(pending.getDeployedUrl())) {
            pending.setDeployedUrl(deployedUrl); changed = true;
        }
        if (pending.getCommitSha() == null && p.getPushedCommitSha() != null) {
            pending.setCommitSha(p.getPushedCommitSha()); changed = true;
        }

        if (!wasTerminal && isTerminal(pending)) {
            bumpTerminalCounter(a, pending.getConclusion());
            changed = true;
        }
        if (changed && actor != null) a.setLastUpdatedBy(actor);
        return changed;
    }

    /**
     * Upsert-by-runId variant used by polling paths (controller +
     * scheduled reaper). Semantics that match the user's mental model:
     *
     *   First time we observe a runId (any status — queued / in_progress
     *   / completed) we INSERT a new entry and bump `totalDeploys` so
     *   the UI counter increments the moment a deploy *starts*.
     *
     *   Subsequent polls for the same runId UPDATE the existing entry's
     *   status/conclusion/deployedUrl/failedStep/failedReason without
     *   appending a duplicate row.
     *
     *   When status TRANSITIONS to terminal (completed with a non-null
     *   conclusion) we increment `totalDeploysSuccess` or
     *   `totalDeploysFailed` exactly once.
     *
     * Rollback markers continue to use the append-only `recordDeploy`
     * above — they intentionally re-record the target runId with
     * `rolledBackFrom` set, so we filter those out when matching here.
     */
    public DeployEntry upsertDeployFromPoll(McpProject p, AuditActor actor,
                                            String runId, String runUrl,
                                            String status, String conclusion,
                                            String deployedUrl, Long durationMs,
                                            String failedStep, String failedReason,
                                            String commitSha) {
        if (runId == null || runId.isBlank()) return null;
        AuditTrail a = ensure(p);

        // Find existing non-rollback entry for this runId.
        DeployEntry existing = null;
        for (DeployEntry de : a.getDeployHistory()) {
            if (runId.equals(de.getRunId()) && de.getRolledBackFrom() == null) {
                existing = de;
                break;
            }
        }

        boolean isTerminal = "completed".equalsIgnoreCase(status)
                && conclusion != null && !conclusion.isBlank();

        if (existing == null) {
            // Before inserting a fresh row, see if there's a PENDING entry
            // seeded by recordDeployDispatch (queued, no runId yet). If so,
            // ADOPT it — this poll is the first real GitHub sighting of the
            // deploy that dispatch already counted, so we must not add a
            // second row or re-bump totalDeploys.
            DeployEntry pending = null;
            for (DeployEntry de : a.getDeployHistory()) {
                if ((de.getRunId() == null || de.getRunId().isBlank())
                        && de.getRolledBackFrom() == null && !isTerminal(de)) {
                    pending = de; // newest wins if there were somehow several
                }
            }
            if (pending != null) {
                pending.setRunId(runId);
                pending.setRunUrl(runUrl != null ? runUrl : pending.getRunUrl());
                pending.setStatus(status != null ? status : pending.getStatus());
                if (conclusion != null && !conclusion.isBlank()) pending.setConclusion(conclusion);
                if (deployedUrl != null && !deployedUrl.isBlank()) pending.setDeployedUrl(deployedUrl);
                if (durationMs != null)   pending.setDurationMs(durationMs);
                if (failedStep != null)   pending.setFailedStep(failedStep);
                if (failedReason != null) pending.setFailedReason(failedReason);
                if (commitSha != null && !commitSha.isBlank()) pending.setCommitSha(commitSha);
                if (isTerminal) bumpTerminalCounter(a, conclusion);
                a.setLastUpdatedBy(actor);
                return pending;
            }

            // First sighting → INSERT. Counter +1 for the started run.
            DeployEntry e = DeployEntry.builder().by(actor)
                    .runId(runId).runUrl(runUrl)
                    .status(status).conclusion(conclusion)
                    .deployedUrl(deployedUrl).durationMs(durationMs)
                    .failedStep(failedStep).failedReason(failedReason)
                    .commitSha(commitSha)
                    .build();
            a.getDeployHistory().add(e);
            a.setTotalDeploys(a.getDeployHistory().size());
            // If we somehow saw a terminal status on first sighting
            // (race where polling caught the very end), still credit
            // the success/failed bucket so totals stay consistent.
            if (isTerminal) bumpTerminalCounter(a, conclusion);
            a.setLastUpdatedBy(actor);
            return e;
        }

        // Existing entry → UPDATE. Bump success/failed counter ONLY
        // when we just transitioned from non-terminal to terminal.
        boolean wasTerminal = "completed".equalsIgnoreCase(existing.getStatus())
                && existing.getConclusion() != null && !existing.getConclusion().isBlank();

        existing.setRunUrl(runUrl != null ? runUrl : existing.getRunUrl());
        existing.setStatus(status != null ? status : existing.getStatus());
        if (conclusion != null && !conclusion.isBlank()) existing.setConclusion(conclusion);
        if (deployedUrl != null && !deployedUrl.isBlank()) existing.setDeployedUrl(deployedUrl);
        if (durationMs != null)   existing.setDurationMs(durationMs);
        if (failedStep != null)   existing.setFailedStep(failedStep);
        if (failedReason != null) existing.setFailedReason(failedReason);
        if (commitSha != null && !commitSha.isBlank()) existing.setCommitSha(commitSha);

        if (!wasTerminal && isTerminal) bumpTerminalCounter(a, conclusion);
        a.setLastUpdatedBy(actor);
        return existing;
    }

    private void bumpTerminalCounter(AuditTrail a, String conclusion) {
        if ("success".equalsIgnoreCase(conclusion)) {
            a.setTotalDeploysSuccess(a.getTotalDeploysSuccess() + 1);
        } else {
            // Any non-success terminal conclusion counts as failed
            // (failure / cancelled / timed_out / action_required / etc).
            a.setTotalDeploysFailed(a.getTotalDeploysFailed() + 1);
        }
    }

    /** Persist & return for caller chaining. */
    public McpProject save(McpProject p) { return repo.save(p); }
}
