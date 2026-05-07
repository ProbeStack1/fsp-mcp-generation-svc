package com.forgesphere.mcpgen.generator;

import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.GeneratedFile;

import java.util.List;

/**
 * Contract for language-specific MCP server code generators. Each
 * implementation turns a validated `McpProject` into a list of
 * content-in-memory files ready for zipping or previewing.
 *
 * Implementations must be pure (no I/O, no network) so the service is
 * safe to run repeatedly — the controller/service layer handles
 * persistence + zip streaming.
 */
public interface CodeGenerator {

    /** @return the `runtime.language` value this generator handles. */
    String language();

    /**
     * Produce all project files for the given spec. The returned list
     * must be ordered so that the UI file-tree is stable (package.json,
     * then src/, then tools/, …). Files are UTF-8 text only; binary
     * assets are out of scope.
     */
    List<GeneratedFile> generate(McpProject spec);
}
