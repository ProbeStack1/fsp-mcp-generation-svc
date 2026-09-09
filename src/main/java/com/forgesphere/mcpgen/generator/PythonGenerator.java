package com.forgesphere.mcpgen.generator;

import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.GeneratedFile;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class PythonGenerator implements CodeGenerator {
    @Override public String language() { return "python"; }
    @Override public List<GeneratedFile> generate(McpProject spec) {
        List<GeneratedFile> files = new ArrayList<>();
        files.add(file("server.py", GeneratorUtils.template("server.py"), "python"));
        files.add(file("server-spec.json", GeneratorUtils.runtimeSpec(spec), "json"));
        files.add(file("requirements.txt", "mcp>=1.12.0,<2\nuvicorn>=0.25,<1\nstarlette>=0.36,<1\nhttpx>=0.27,<1\njsonschema>=4.23,<5\npython-dotenv>=1,<2\npytest>=8,<9\nruff>=0.11,<1\n", "plaintext"));
        files.add(file(".env.example", GeneratorUtils.envExample(spec), "dotenv"));
        String readyEnv = GeneratorUtils.envReady(spec);
        if (readyEnv != null) files.add(file(".env", readyEnv, "dotenv"));
        files.add(file("Dockerfile", GeneratorUtils.dockerfile(spec), "docker"));
        files.add(file("mcp.json", GeneratorUtils.pretty(GeneratorUtils.manifest(spec)), "json"));
        files.add(file("README.md", GeneratorUtils.commonReadme(spec) + "\nRun: pip install -r requirements.txt && python server.py\nHTTP tools use UPSTREAM_BASE_URL and optional UPSTREAM_AUTHORIZATION. Tools without a binding return an explicit MCP error.\n", "markdown"));
        files.add(file(".gitignore", "__pycache__/\n*.pyc\n.env\n.venv/\n", "gitignore"));
        files.add(file(".github/workflows/mcp.yml", GeneratorUtils.buildGithubWorkflow(spec), "yaml"));
        return files;
    }
    private GeneratedFile file(String path, String content, String hint) {
        return GeneratedFile.builder().path(path).content(content).bytes(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).mimeHint(hint).build();
    }
}
