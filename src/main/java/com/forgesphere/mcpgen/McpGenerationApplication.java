package com.forgesphere.mcpgen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for forgeq-mcp-generation-svc.
 *
 * Pure generator service. Given a wizard spec — server identity, tools,
 * resources, prompts, runtime, transport, auth — it produces a runnable
 * MCP server codebase (TypeScript, Python or Java) and a zip of the
 * bundle. Also offers a thin proxy to probe a live MCP server (so the
 * user can verify their generated server from inside the wizard).
 *
 * Deliberately decoupled:
 *  - No baked-in auth / dev-bypass. The platform that mounts this
 *    service puts its own filter in front (OAuth, API key, whatever).
 *  - No platform-specific shared modules. Just Spring Boot + Mongo.
 */
@SpringBootApplication
public class McpGenerationApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpGenerationApplication.class, args);
    }
}
