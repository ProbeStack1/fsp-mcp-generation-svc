package com.forgesphere.mcpgen.web;

import com.forgesphere.mcpgen.dto.Dtos.Envelope;
import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.AuditTrail;
import com.forgesphere.mcpgen.model.McpProject.DeployEntry;
import com.forgesphere.mcpgen.service.AuditService;
import com.forgesphere.mcpgen.service.McpGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Read-only audit endpoints + rollback marker.
 *
 *   GET  /projects/{id}/audit            → full audit trail (create + edits + pushes + deploys)
 *   GET  /projects/{id}/deployments      → just the deploy history (Dashboard table)
 *   POST /projects/{id}/rollback?toRunId=&actorEmail=&actorName=
 *                                        → record a rollback marker pointing at a prior deploy
 *
 * Rollback here is "marker only" — we record an audit entry pointing at
 * the prior runId + restore the project's `deployedServiceUrl` /
 * `latestRunId` to that prior deploy. The actual GCP rollout is
 * performed via the existing Cloud Run revision pinning (out-of-band).
 */
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class McpAuditController {

    private final McpGenerationService svc;
    private final AuditService audit;

    @GetMapping("/{id}/audit")
    public Envelope<AuditTrail> audit(@PathVariable String id) {
        McpProject p = svc.get(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        return Envelope.ok(audit.ensure(p));
    }

    @GetMapping("/{id}/deployments")
    public Envelope<Map<String, Object>> deployments(@PathVariable String id) {
        McpProject p = svc.get(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        AuditTrail a = audit.ensure(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total",         a.getTotalDeploys());
        out.put("totalSuccess",  a.getTotalDeploysSuccess());
        out.put("totalFailed",   a.getTotalDeploysFailed());
        out.put("history",       new ArrayList<>(a.getDeployHistory()).stream()
                .sorted(Comparator.comparing(
                        (DeployEntry e) -> e.getBy() == null ? null : e.getBy().getTimestamp(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList()));
        out.put("latestRunId",            p.getLatestRunId());
        out.put("latestRunUrl",           p.getLatestRunUrl());
        out.put("latestRunStatus",        p.getLatestRunStatus());
        out.put("latestRunConclusion",    p.getLatestRunConclusion());
        out.put("deployedServiceUrl",     p.getDeployedServiceUrl());
        out.put("deployedAt",             p.getDeployedAt());
        out.put("pushedCommitSha",        p.getPushedCommitSha());
        out.put("pushedAt",               p.getPushedAt());
        return Envelope.ok(out);
    }

    @PostMapping("/{id}/rollback")
    public Envelope<Map<String, Object>> rollback(@PathVariable String id,
                                                  @RequestParam String toRunId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        McpProject p = svc.get(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        AuditTrail a = audit.ensure(p);

        DeployEntry target = a.getDeployHistory().stream()
                .filter(e -> e.getRunId() != null && e.getRunId().equals(toRunId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no deploy entry with runId=" + toRunId));

        String actorEmail = body == null ? null : (String) body.get("updatedBy");
        if (actorEmail == null && body != null) actorEmail = (String) body.get("createdBy");
        var actor = (actorEmail == null || actorEmail.isBlank())
                ? audit.fallbackActor(p)
                : audit.actor(actorEmail, null);

        // Mark a rollback entry: status=completed, conclusion=success,
        // but `rolledBackFrom` carries the runId we replaced. The
        // catalog UI uses the presence of `rolledBackFrom` to render
        // the "↺" badge on this entry.
        audit.recordDeploy(p, actor,
                target.getRunId(), target.getRunUrl(),
                "completed", "success",
                target.getDeployedUrl(), null,
                null, null,
                p.getLatestRunId(),
                target.getCommitSha());

        // Re-point the project's "current" deploy snapshot at the target.
        p.setLatestRunId(target.getRunId());
        p.setLatestRunUrl(target.getRunUrl());
        p.setLatestRunStatus("completed");
        p.setLatestRunConclusion("success");
        p.setDeployedServiceUrl(target.getDeployedUrl());
        if (actorEmail != null && !actorEmail.isBlank()) p.setUpdatedBy(actorEmail);

        audit.save(p);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", p.getId());
        out.put("rolledBackTo", target.getRunId());
        out.put("deployedServiceUrl", target.getDeployedUrl());
        return Envelope.ok(out);
    }
}
