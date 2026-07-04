package com.forgesphere.mcpgen.repo;

import com.forgesphere.mcpgen.model.McpMockServer;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface McpMockServerRepository extends MongoRepository<McpMockServer, String> {
    Optional<McpMockServer> findByProjectId(String projectId);
    boolean existsByMockUrl(String mockUrl);
}