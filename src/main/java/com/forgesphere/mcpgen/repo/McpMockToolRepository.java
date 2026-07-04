package com.forgesphere.mcpgen.repo;

import com.forgesphere.mcpgen.model.McpMockTool;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface McpMockToolRepository extends MongoRepository<McpMockTool, String> {
    List<McpMockTool> findByMockServerId(String mockServerId);
    void deleteByMockServerId(String mockServerId);
}