package com.forgesphere.mcpgen.repo;

import com.forgesphere.mcpgen.model.McpDeployStepLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface McpDeployStepLogRepository extends MongoRepository<McpDeployStepLog, String> {

    boolean existsByProjectIdAndRunId(String projectId, String runId);

    List<McpDeployStepLog> findByProjectIdAndRunId(String projectId, String runId);

    Optional<McpDeployStepLog> findByProjectIdAndRunIdAndJobId(String projectId, String runId, String jobId);
}
