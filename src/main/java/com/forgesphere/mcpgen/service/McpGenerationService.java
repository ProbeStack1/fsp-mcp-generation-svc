package com.forgesphere.mcpgen.service;

import com.forgesphere.mcpgen.generator.CodeGenerator;
import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.Generated;
import com.forgesphere.mcpgen.repo.McpProjectRepository;
import com.forgesphere.mcpgen.storage.StorageClient;
import com.forgesphere.mcpgen.storage.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Orchestrator for everything the controller layer doesn't do itself:
 * CRUD, generation (delegates to language-specific `CodeGenerator`),
 * zip streaming/persistence, and client-config snippet assembly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpGenerationService {

    private final McpProjectRepository repo;
    private final List<CodeGenerator> generators;
    private final StorageClient storage;

    // ------------------------------------------------------------- CRUD
    public McpProject create(McpProject p) {
        p.setId(UUID.randomUUID().toString());
        Instant now = Instant.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        if (p.getIdentity() == null)    p.setIdentity(McpProject.Identity.builder().build());
        if (p.getCapabilities() == null) p.setCapabilities(McpProject.Capabilities.builder().build());
        if (p.getRuntime() == null)     p.setRuntime(McpProject.Runtime.builder().language("typescript").sdkVersion("^1.0.0").bundler("tsx").build());
        if (p.getTransport() == null)   p.setTransport(McpProject.Transport.builder().kind("streamable-http").baseUrl("http://localhost:3500/mcp").build());
        if (p.getAuth() == null)        p.setAuth(McpProject.Auth.builder().kind("bearer").headerName("Authorization").build());
        if (p.getAdvanced() == null)    p.setAdvanced(McpProject.Advanced.builder().build());
        return repo.save(p);
    }

    public List<McpProject> list(String ownerEmail, String workspaceId) {
        if (ownerEmail != null && !ownerEmail.isBlank())
            return repo.findByOwnerEmailOrderByCreatedAtDesc(ownerEmail);
        if (workspaceId != null && !workspaceId.isBlank())
            return repo.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
        return repo.findAll();
    }

    public Optional<McpProject> get(String id) { return repo.findById(id); }

    public McpProject update(String id, McpProject patch) {
        McpProject cur = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        if (patch.getIdentity()     != null) cur.setIdentity(patch.getIdentity());
        if (patch.getCapabilities() != null) cur.setCapabilities(patch.getCapabilities());
        if (patch.getRuntime()      != null) cur.setRuntime(patch.getRuntime());
        if (patch.getTransport()    != null) cur.setTransport(patch.getTransport());
        if (patch.getAuth()         != null) cur.setAuth(patch.getAuth());
        if (patch.getAdvanced()     != null) cur.setAdvanced(patch.getAdvanced());
        if (patch.getOwnerEmail()   != null) cur.setOwnerEmail(patch.getOwnerEmail());
        if (patch.getWorkspaceId()  != null) cur.setWorkspaceId(patch.getWorkspaceId());
        if (patch.getOnboardingId() != null) cur.setOnboardingId(patch.getOnboardingId());
        if (patch.getConnectorId()  != null) cur.setConnectorId(patch.getConnectorId());
        if (patch.getOnboarding()   != null) cur.setOnboarding(patch.getOnboarding());
        if (patch.getSource()       != null) cur.setSource(patch.getSource());
        cur.setUpdatedAt(Instant.now());
        return repo.save(cur);
    }

    public void delete(String id) { repo.deleteById(id); }

    // --------------------------------------------------------- Generate
    public McpProject generate(String id) {
        McpProject p = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id));
        return generateInto(p);
    }

    /** Generate directly from a spec without persisting — used for the live preview in Step 6. */
    public McpProject generateInline(McpProject spec) {
        if (spec.getId() == null) spec.setId(UUID.randomUUID().toString());
        return generateInto(spec);
    }

    private McpProject generateInto(McpProject p) {
        String lang = p.getRuntime() == null ? "typescript" : p.getRuntime().getLanguage();
        CodeGenerator gen = generators.stream()
                .filter(g -> g.language().equalsIgnoreCase(lang))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported language: " + lang));
        var files = gen.generate(p);
        int total = files.stream().mapToInt(f -> f.getBytes()).sum();
        p.setGenerated(Generated.builder()
                .files(files).totalBytes(total).generatedAt(Instant.now()).build());
        // Invalidate any cached zip — the in-memory files have just
        // changed and the previously-uploaded archive (if any) is now
        // stale. Leaving it pointed at the old object meant downloads
        // kept handing the user yesterday's bytes even after a
        // backend code change or a "Generate again" click.
        p.setZipObjectPath(null);
        p.setZipBytes(null);
        p.setZipContentType(null);
        p.setZipUploadedAt(null);
        p.setUpdatedAt(Instant.now());
        if (p.getId() != null && repo.existsById(p.getId())) repo.save(p);
        return p;
    }

    // -------------------------------------------------------------- Zip
    public void streamZip(McpProject p, OutputStream out) throws Exception {
        if (p.getGenerated() == null || p.getGenerated().getFiles().isEmpty()) generateInto(p);

        // Write the zip to a buffer so we can persist + stream simultaneously.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            for (var f : p.getGenerated().getFiles()) {
                ZipEntry e = new ZipEntry(f.getPath());
                zip.putNextEntry(e);
                zip.write(f.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        byte[] bytes = buf.toByteArray();

        // Persist to object storage so the user can re-download from
        // anywhere — first download wins; subsequent downloads stream
        // straight from the saved object.
        if (p.getId() != null && repo.existsById(p.getId())) {
            try {
                if (p.getZipObjectPath() == null) {
                    String path = "mcp-zips/" + p.getId() + "/" + slugForZip(p) + ".zip";
                    StoredObject so = storage.upload(path, bytes, "application/zip");
                    p.setZipObjectPath(so.getObjectPath());
                    p.setZipBytes(so.getBytes());
                    p.setZipContentType(so.getContentType());
                    p.setZipUploadedAt(so.getUploadedAt());
                }
                p.setLastDownloadedAt(Instant.now());
                repo.save(p);
            } catch (Exception ex) {
                log.warn("[zip] storage upload failed (continuing with stream-only): {}", ex.getMessage());
            }
        }

        out.write(bytes);
    }

    /** Re-stream a previously-stored zip without regenerating. */
    public byte[] downloadStoredZip(McpProject p) {
        if (p.getZipObjectPath() == null) return null;
        try {
            byte[] bytes = storage.download(p.getZipObjectPath());
            p.setLastDownloadedAt(Instant.now());
            repo.save(p);
            return bytes;
        } catch (Exception ex) {
            log.warn("[zip] stored fetch failed ({}): {}", p.getZipObjectPath(), ex.getMessage());
            return null;
        }
    }

    private String slugForZip(McpProject p) {
        String slug = p.getIdentity() != null && p.getIdentity().getSlug() != null && !p.getIdentity().getSlug().isBlank()
                ? p.getIdentity().getSlug()
                : "mcp-server";
        return slug.replaceAll("[^a-z0-9-]+", "-");
    }

    // --------------------------------------------------- Client configs
    public Map<String, Object> clientConfigs(McpProject p) {
        String slug = p.getIdentity() == null ? "mcp-server" : p.getIdentity().getSlug();
        String baseUrl = p.getTransport() == null || p.getTransport().getBaseUrl() == null
                ? "http://localhost:3500/mcp" : p.getTransport().getBaseUrl();
        String transport = p.getTransport() == null ? "streamable-http" : p.getTransport().getKind();
        boolean bearer = p.getAuth() != null && "bearer".equalsIgnoreCase(p.getAuth().getKind());
        String token = bearer && p.getAuth().getGeneratedToken() != null ? p.getAuth().getGeneratedToken() : "<your-token>";

        Map<String, Object> serverEntry = new LinkedHashMap<>();
        serverEntry.put("url", baseUrl);
        serverEntry.put("transport", transport);
        if (bearer) serverEntry.put("headers", Map.of("Authorization", "Bearer " + token));

        Map<String, Object> claude = Map.of("mcpServers", Map.of(slug, serverEntry));
        Map<String, Object> cursor = Map.of("mcpServers", Map.of(slug, serverEntry));
        Map<String, Object> forgeq = GeneratorUtilsProxy.manifest(p);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("claudeDesktop", claude);
        out.put("cursor", cursor);
        out.put("forgeq", forgeq);
        return out;
    }

    /** Tiny bridge so the service can reuse the manifest builder in GeneratorUtils. */
    static final class GeneratorUtilsProxy {
        static Map<String, Object> manifest(McpProject p) {
            return com.forgesphere.mcpgen.generator.GeneratorUtils.manifest(p);
        }
    }
}
