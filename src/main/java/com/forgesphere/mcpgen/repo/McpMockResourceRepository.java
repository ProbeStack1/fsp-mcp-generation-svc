package com.forgesphere.mcpgen.repo;

import com.forgesphere.mcpgen.model.McpMockResource;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface McpMockResourceRepository extends MongoRepository<McpMockResource, String> {
    List<McpMockResource> findByMockServerId(String mockServerId);
    void deleteByMockServerId(String mockServerId);
}