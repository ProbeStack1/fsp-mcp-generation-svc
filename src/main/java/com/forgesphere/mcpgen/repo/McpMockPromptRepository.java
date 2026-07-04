package com.forgesphere.mcpgen.repo;

import com.forgesphere.mcpgen.model.McpMockPrompt;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface McpMockPromptRepository extends MongoRepository<McpMockPrompt, String> {
    List<McpMockPrompt> findByMockServerId(String mockServerId);
    void deleteByMockServerId(String mockServerId);
}