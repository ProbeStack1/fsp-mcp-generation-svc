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

    /** Persist & return for caller chaining. */
    public McpProject save(McpProject p) { return repo.save(p); }
}
