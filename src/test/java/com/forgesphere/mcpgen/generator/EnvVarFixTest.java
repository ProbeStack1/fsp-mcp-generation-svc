package com.forgesphere.mcpgen.generator;

import com.forgesphere.mcpgen.model.McpProject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnvVarFixTest {

    private McpProject proj(String lang, String authKind, String token) {
        McpProject p = new McpProject();
        McpProject.Identity id = new McpProject.Identity();
        id.setSlug("recipe-manager");
        p.setIdentity(id);
        McpProject.Runtime rt = new McpProject.Runtime();
        rt.setLanguage(lang);
        p.setRuntime(rt);
        if (authKind != null) {
            McpProject.Auth a = new McpProject.Auth();
            a.setKind(authKind);
            a.setGeneratedToken(token);
            p.setAuth(a);
        }
        return p;
    }

    @Test
    void bearerAuthTokenReachesCloudRunEnv() {
        String wf = GeneratorUtils.buildGithubWorkflow(proj("typescript", "bearer", "tok_ABC123"));
        assertNotNull(wf);
        assertTrue(wf.contains("MCP_AUTH_TOKEN=tok_ABC123"),
            "bearer token must be in --set-env-vars\n" + slice(wf));
    }

    @Test
    void apiKeyTokenReachesCloudRunEnv() {
        String wf = GeneratorUtils.buildGithubWorkflow(proj("java", "api-key", "key_XYZ789"));
        assertNotNull(wf);
        assertTrue(wf.contains("MCP_API_KEY=key_XYZ789"), "api-key token must be in --set-env-vars\n" + slice(wf));
    }

    @Test
    void noAuthLeavesEnvUnchanged() {
        String wf = GeneratorUtils.buildGithubWorkflow(proj("python", "none", null));
        assertNotNull(wf);
        assertFalse(wf.contains("MCP_AUTH_TOKEN"), "no-auth project must not add MCP_AUTH_TOKEN");
        assertFalse(wf.contains("MCP_API_KEY"), "no-auth project must not add MCP_API_KEY");
    }

    private String slice(String wf) {
        int i = wf.indexOf("set-env-vars");
        return i < 0 ? "(no set-env-vars line)" : wf.substring(Math.max(0, i - 20), Math.min(wf.length(), i + 200));
    }
}
