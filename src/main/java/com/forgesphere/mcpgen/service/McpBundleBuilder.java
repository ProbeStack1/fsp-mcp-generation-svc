package com.forgesphere.mcpgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.model.McpProject.Tool;
import com.forgesphere.mcpgen.storage.StorageClient;
import com.forgesphere.mcpgen.storage.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Composes the "everything" bundle the user downloads at Step 11. The
 * shape is intentionally similar across MCP servers so a consumer can
 * write a single import script:
 * <pre>
 * mcp-&lt;slug&gt;-bundle.zip
 *   code/             ← every file the code generator produced
 *   spec/             ← mcp-spec.json (+ generated openapi.yaml for HTTP)
 *   postman/          ← collection.json (one request per tool)
 *   tests/            ← stub test files keyed by tool name
 *   client-configs/   ← claude-desktop.json, cursor-mcp.json, forgeq.json
 *   README.md         ← table of contents + quick-start
 * </pre>
 *
 * <p>The bundle is built once on first request and cached in the object
 * store under {@code bundles/&lt;projectId&gt;/&lt;timestamp&gt;.zip}; the
 * controller is responsible for invalidating the cache when the user
 * regenerates.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpBundleBuilder {

    private final McpGenerationService genSvc;
    private final StorageClient storage;
    private final MongoTemplate mongoTemplate;
    private final TestCollectionGenerator testCollectionGenerator; // NEW

    @Value("${mcp.bundle.prefix:bundles}")
    private String bundlePrefix;

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Result of {@link #buildAndStore} so the controller can stream from
     * the cached object on the next download instead of rebuilding.
     */
    public record Bundle(byte[] content, String objectKey, String fileName, int fileCount, long sizeBytes) {}

    /**
     * Build the zip in-memory, upload it to the storage client, and
     * return both the bytes (so the controller can stream them directly)
     * and the object key (so the controller can persist a pointer on
     * the project document).
     */
    public Bundle buildAndStore(McpProject project) {
        if (project.getGenerated() == null) {
            // Reuse the canonical generation pipeline so the bundle is
            // always in sync with the code preview.
            genSvc.generate(project.getId());
            project = genSvc.get(project.getId()).orElse(project);
        }
        String slug = safeSlug(project);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int fileCount;
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            fileCount = writeCode(zip, project);
            fileCount += writeSpec(zip, project);
            fileCount += writePostman(zip, project);
            fileCount += writeTests(zip, project);
            fileCount += writeClientConfigs(zip, project);
            fileCount += writeReadme(zip, project);
            // ---- NEW: Write test collection (scenario-based) ----
            fileCount += writeTestCollection(zip, project);
            // ---- NEW: Write mock responses ----
            fileCount += writeMockResponses(zip, project);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to assemble MCP bundle for " + project.getId(), ex);
        }
        byte[] bytes = buf.toByteArray();
        String fileName = "mcp-" + slug + "-bundle.zip";
        String objectKey = bundlePrefix + "/" + project.getId() + "/"
                + DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-")
                + "_" + UUID.randomUUID().toString().substring(0, 8) + ".zip";
        StoredObject stored = storage.upload(objectKey, bytes, "application/zip");
        log.info("Bundle stored for project={} bytes={} files={} key={}",
                project.getId(), bytes.length, fileCount, stored.getObjectPath());

        // ---- NEW: Upload test collection as a separate JSON artifact ----
        uploadTestCollection(project);

        // ──────────────────────────────────────────────────────────
        // Mirror the freshly-uploaded zip into `codegen_results` so
        // senior's contract-testing-svc / analysis-svc / peer-review-svc
        // BundleDownloadService can locate the MCP archive via the
        // same query path it uses for microservice archives. We upsert
        // under both the MCP project id and the legacy mirror id (if
        // set) so any of the ids the UI may pass downstream resolves.
        // ──────────────────────────────────────────────────────────
        mirrorIntoCodegenResults(project, stored.getObjectPath(), fileName);
        return new Bundle(bytes, stored.getObjectPath(), fileName, fileCount, bytes.length);
    }

    /**
     * Builds + uploads JUST the scenario-based test collection (Postman
     * collection + scenario metadata) and stamps the resulting signed
     * URL onto {@code project.generated.testCollectionUrl}. Split out of
     * {@link #buildAndStore} so Step 8 (Test Cases) can get a URL right
     * after Step 7's plain {@code /generate} call — it used to only ever
     * get built as a side-effect of downloading the FULL "everything"
     * bundle at Step 11, which nothing in the wizard actually triggered
     * before that step, so Step 8 always saw "no test collection URL".
     * Best-effort: returns the existing URL (or null) on any failure so
     * a hiccup here never breaks generation.
     */
    public String uploadTestCollection(McpProject project) {
        try {
            String testCollectionKey = "test-collections/" + project.getId() + "/" + UUID.randomUUID() + ".json";
            // testCollectionGenerator.generate() returns each field as an
            // ALREADY JSON-STRINGIFIED string (that's what writeTestCollection()
            // needs — it writes them out as standalone .json files in the
            // full bundle). Putting those strings straight into `combined`
            // and then writeValueAsString()-ing the whole map double-encodes
            // them: the stored artifact ended up as
            // {"scenarioMetadata": "[{...}]"} (a STRING containing JSON
            // text) instead of {"scenarioMetadata": [{...}]} (a real
            // array) — exactly what made the frontend's
            // `scenarioMetadata.map()` blow up with "is not a function".
            // Parse them back into real trees before combining.
            Map<String, String> testData = testCollectionGenerator.generate(project);
            Map<String, Object> combined = new LinkedHashMap<>();
            try {
                combined.put("postmanCollection", json.readTree(testData.get("postmanCollection")));
                combined.put("scenarioMetadata", json.readTree(testData.get("scenarioMetadata")));
            } catch (Exception parseEx) {
                log.warn("Failed to parse generated test data as JSON for project {}: {}",
                        project.getId(), parseEx.getMessage());
                combined.put("postmanCollection", Map.of());
                combined.put("scenarioMetadata", List.of());
            }
            String combinedJson;
            try {
                combinedJson = json.writeValueAsString(combined);
            } catch (Exception e) {
                combinedJson = "{}";
            }
            storage.upload(testCollectionKey, combinedJson.getBytes(StandardCharsets.UTF_8), "application/json");
            URL testUrl = storage.signedDownloadUrl(testCollectionKey, Duration.ofDays(7));
            String testCollectionUrl = testUrl != null ? testUrl.toString() : null;
            if (project.getGenerated() == null) {
                project.setGenerated(McpProject.Generated.builder().build());
            }
            if (testCollectionUrl != null) {
                project.getGenerated().setTestCollectionUrl(testCollectionUrl);
            }
            // Persisted (non-transient) pointer — this is what
            // GET /projects/{id}/test-collection reads on every future
            // request, regardless of session, so Step 8 never depends on
            // the signed URL above surviving a reload.
            project.setTestCollectionObjectKey(testCollectionKey);
            genSvc.save(project);
            return testCollectionUrl;
        } catch (Exception e) {
            log.warn("Failed to build test collection for project {}: {}", project.getId(), e.getMessage());
            return project.getGenerated() != null ? project.getGenerated().getTestCollectionUrl() : null;
        }
    }

    /**
     * Downloads the raw test-collection JSON bytes for a project —
     * builds one fresh first if it's never had one. Backs
     * {@code GET /projects/{id}/test-collection}, which exists so the
     * browser never has to fetch the GCS signed URL directly: that
     * bucket has no CORS rule for our frontend's origin, so a direct
     * `fetch(signedUrl)` from the browser always failed with a bare
     * "Failed to fetch" even though the URL itself was valid (200 in
     * the Network tab — the browser just refused to hand the response
     * to JS). A same-origin call to our own API has no such problem.
     */
    public byte[] downloadTestCollection(McpProject project) {
        if (project.getTestCollectionObjectKey() == null || project.getTestCollectionObjectKey().isBlank()) {
            uploadTestCollection(project);
        }
        if (project.getTestCollectionObjectKey() == null || project.getTestCollectionObjectKey().isBlank()) {
            return null;
        }
        return storage.download(project.getTestCollectionObjectKey());
    }

    /**
     * Upserts senior's microservice-keyed catalogue row so that the
     * analysis / contract-testing / peer-review services find the MCP
     * bundle without any senior-side code change.
     */
    private void mirrorIntoCodegenResults(McpProject project, String gcsPath, String fileName) {
        if (mongoTemplate == null) return;
        try {
            Update u = new Update()
                    .set("microserviceId",  project.getId())
                    .set("gcsArchivePath",  gcsPath)
                    .set("archiveFileName", fileName)
                    .set("targetType",      "MCP_SERVER")
                    .set("updatedAt",       Instant.now());
            mongoTemplate.upsert(
                    Query.query(Criteria.where("microserviceId").is(project.getId())),
                    u, "codegen_results");
            String mirror = project.getMicroserviceMirrorId();
            if (mirror != null && !mirror.isBlank() && !mirror.equals(project.getId())) {
                mongoTemplate.upsert(
                        Query.query(Criteria.where("microserviceId").is(mirror)),
                        new Update()
                                .set("microserviceId",  mirror)
                                .set("gcsArchivePath",  gcsPath)
                                .set("archiveFileName", fileName)
                                .set("targetType",      "MCP_SERVER")
                                .set("updatedAt",       Instant.now()),
                        "codegen_results");
            }
            log.info("[codegen_results] mirrored MCP {} → {}", project.getId(), gcsPath);
        } catch (Exception ex) {
            log.warn("[codegen_results] mirror failed for project={}: {}",
                    project.getId(), ex.getMessage());
        }
    }

    // ─────────── per-folder writers ───────────────────────────────────

    /** Mirror the generated source tree under {@code code/}. */
    private int writeCode(ZipOutputStream zip, McpProject p) throws Exception {
        if (p.getGenerated() == null || p.getGenerated().getFiles() == null) return 0;
        int count = 0;
        for (McpProject.GeneratedFile f : p.getGenerated().getFiles()) {
            putEntry(zip, "code/" + f.getPath(), f.getContent());
            count++;
        }
        return count;
    }

    /** Persist the original MCP design + an OpenAPI sketch for HTTP transports. */
    private int writeSpec(ZipOutputStream zip, McpProject p) throws Exception {
        int count = 0;
        Map<String, Object> mcpSpec = new LinkedHashMap<>();
        mcpSpec.put("identity",     p.getIdentity());
        mcpSpec.put("capabilities", p.getCapabilities());
        mcpSpec.put("runtime",      p.getRuntime());
        mcpSpec.put("transport",    p.getTransport());
        mcpSpec.put("auth",         p.getAuth());
        mcpSpec.put("advanced",     p.getAdvanced());
        putEntry(zip, "spec/mcp-spec.json", json.writeValueAsString(mcpSpec));
        count++;
        // OpenAPI surface only makes sense for the HTTP transports; for
        // stdio we'd be inventing endpoints the server doesn't expose.
        if (p.getTransport() != null
                && p.getTransport().getKind() != null
                && p.getTransport().getKind().toLowerCase().contains("http")) {
            putEntry(zip, "spec/openapi.yaml", buildOpenApiSketch(p));
            count++;
        }
        return count;
    }

    /** One Postman request per tool, plus the {@code initialize} bootstrap call. */
    private int writePostman(ZipOutputStream zip, McpProject p) throws Exception {
        Map<String, Object> coll = new LinkedHashMap<>();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", (p.getIdentity() == null ? "MCP Server" : p.getIdentity().getDisplayName()));
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        info.put("_postman_id", UUID.randomUUID().toString());
        coll.put("info", info);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(postmanItem("initialize", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", p));
        if (p.getCapabilities() != null && p.getCapabilities().getTools() != null) {
            int id = 2;
            for (McpProject.Tool t : p.getCapabilities().getTools()) {
                Map<String, Object> rpc = new LinkedHashMap<>();
                rpc.put("jsonrpc", "2.0");
                rpc.put("id",      id++);
                rpc.put("method",  "tools/call");
                rpc.put("params",  Map.of("name", t.getName(), "arguments", Map.of()));
                items.add(postmanItem("tools/call · " + t.getName(),
                        json.writeValueAsString(rpc), p));
            }
        }
        coll.put("item", items);
        putEntry(zip, "postman/collection.json", json.writeValueAsString(coll));
        return 1;
    }

    private Map<String, Object> postmanItem(String name, String body, McpProject p) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("method", "POST");
        String url = p.getTransport() != null && p.getTransport().getBaseUrl() != null
                ? p.getTransport().getBaseUrl()
                : "http://localhost:3500/mcp";
        req.put("url", Map.of("raw", url));
        req.put("header", List.of(Map.of("key", "Content-Type", "value", "application/json")));
        req.put("body", Map.of("mode", "raw", "raw", body));
        item.put("request", req);
        return item;
    }

    /** Tool-shaped test stubs that the test-case service can pick up. */
    private int writeTests(ZipOutputStream zip, McpProject p) throws Exception {
        int count = 0;
        putEntry(zip, "tests/README.md",
                "Test stubs generated from the MCP spec. Each tool gets a `tools/call` "
                        + "happy-path JSON-RPC envelope you can run against any conformant client.\n");
        count++;
        if (p.getCapabilities() != null && p.getCapabilities().getTools() != null) {
            for (McpProject.Tool t : p.getCapabilities().getTools()) {
                Map<String, Object> stub = new LinkedHashMap<>();
                stub.put("name",        "happy_path__" + t.getName());
                stub.put("description", "Smoke test for tool `" + t.getName() + "`.");
                stub.put("request", Map.of(
                        "jsonrpc", "2.0",
                        "id",      1,
                        "method",  "tools/call",
                        "params",  Map.of("name", t.getName(), "arguments", Map.of())));
                stub.put("expectations", List.of(
                        Map.of("path", "result.isError", "equals", false),
                        Map.of("path", "result.content", "exists", true)));
                putEntry(zip, "tests/" + t.getName() + ".json", json.writeValueAsString(stub));
                count++;
            }
        }
        return count;
    }

    /** Client config snippets for the popular MCP clients. */
    private int writeClientConfigs(ZipOutputStream zip, McpProject p) throws Exception {
        String slug = safeSlug(p);
        String cmd  = clientCommand(p);
        // Claude Desktop / Cursor share the same shape: a mcpServers map.
        Map<String, Object> claude = Map.of("mcpServers",
                Map.of(slug, Map.of("command", cmd, "args", List.of())));
        Map<String, Object> cursor = Map.of("mcpServers",
                Map.of(slug, Map.of("command", cmd, "args", List.of())));
        // ForgeQ takes the more verbose connector shape so future fields
        // (audit toggles, custom headers) can be added without breaking
        // older client builds.
        Map<String, Object> forgeq = new LinkedHashMap<>();
        forgeq.put("name", slug);
        forgeq.put("transport", p.getTransport() == null ? Map.of("kind", "stdio") : p.getTransport());
        forgeq.put("auth", p.getAuth() == null ? Map.of("kind", "none") : p.getAuth());
        forgeq.put("command", cmd);
        putEntry(zip, "client-configs/claude-desktop.json", json.writeValueAsString(claude));
        putEntry(zip, "client-configs/cursor-mcp.json",     json.writeValueAsString(cursor));
        putEntry(zip, "client-configs/forgeq.json",         json.writeValueAsString(forgeq));
        return 3;
    }

    private int writeReadme(ZipOutputStream zip, McpProject p) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(p.getIdentity() == null ? "MCP Server" : p.getIdentity().getDisplayName()).append('\n');
        if (p.getIdentity() != null && p.getIdentity().getSummary() != null) {
            sb.append('\n').append(p.getIdentity().getSummary()).append('\n');
        }
        sb.append("\n## Bundle contents\n\n")
          .append("- `code/` — generated server source\n")
          .append("- `spec/mcp-spec.json` — MCP capability definition\n")
          .append("- `postman/collection.json` — copy-paste JSON-RPC examples\n")
          .append("- `tests/` — test stubs keyed by tool name\n")
          .append("- `client-configs/` — drop-in snippets for Claude Desktop, Cursor, ForgeQ\n")
          .append("- `test-collection/` — scenario-based Postman collection + metadata\n")
          .append("- `mock-responses/` — sample mock responses for each tool\n");
        putEntry(zip, "README.md", sb.toString());
        return 1;
    }

    // ---- NEW method to write test collection folder in the bundle ----
    private int writeTestCollection(ZipOutputStream zip, McpProject p) throws Exception {
        Map<String, String> testData = testCollectionGenerator.generate(p);
        String postmanColl = testData.get("postmanCollection");
        String scenarioMeta = testData.get("scenarioMetadata");
        putEntry(zip, "test-collection/postman-collection.json", postmanColl);
        putEntry(zip, "test-collection/scenario-metadata.json", scenarioMeta);
        return 2;
    }

    // ---- NEW method to write mock responses ----
    private int writeMockResponses(ZipOutputStream zip, McpProject p) throws Exception {
        // Currently we don't have a dedicated mock response store; we can use the tool simulator to generate sample responses.
        // We'll generate a simple JSON with mock responses for each tool.
        Map<String, Object> mockMap = new LinkedHashMap<>();
        if (p.getCapabilities() != null && p.getCapabilities().getTools() != null) {
            for (Tool tool : p.getCapabilities().getTools()) {
                // Use the same logic as simulator to produce a plausible response
                Map<String, Object> mock = new LinkedHashMap<>();
                mock.put("name", tool.getName());
                mock.put("description", tool.getDescription());
                mock.put("sampleResponse", Map.of(
                        "content", List.of(Map.of("type", "text", "text", "Mock response for " + tool.getName())),
                        "isError", false
                ));
                mockMap.put(tool.getName(), mock);
            }
        }
        String mockJson;
        try {
            mockJson = json.writeValueAsString(mockMap);
        } catch (Exception e) {
            mockJson = "{}";
        }
        putEntry(zip, "mock-responses/mock-data.json", mockJson);
        return 1;
    }

    // ─────────── helpers ──────────────────────────────────────────────

    private static void putEntry(ZipOutputStream zip, String path, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        if (content != null) zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String safeSlug(McpProject p) {
        if (p.getIdentity() == null || p.getIdentity().getSlug() == null || p.getIdentity().getSlug().isBlank())
            return "mcp-server";
        return p.getIdentity().getSlug();
    }

    /**
     * Best-guess invocation command for the bundled client configs. For
     * stdio servers this is what Claude Desktop will spawn directly.
     */
    private static String clientCommand(McpProject p) {
        String lang = p.getRuntime() == null ? "typescript" : String.valueOf(p.getRuntime().getLanguage()).toLowerCase();
        return switch (lang) {
            case "python" -> "python -m " + safeSlug(p).replace('-', '_');
            case "java"   -> "java -jar " + safeSlug(p) + ".jar";
            default       -> "npx " + safeSlug(p);
        };
    }

    /**
     * Minimal OpenAPI 3.1 stub generated only for HTTP-transport servers.
     * Each tool becomes a POST endpoint under {@code /tools/{name}}; the
     * shape lets the senior team's mock-api service ingest the same file
     * without changes.
     */
    private String buildOpenApiSketch(McpProject p) {
        StringBuilder sb = new StringBuilder();
        sb.append("openapi: 3.1.0\n");
        sb.append("info:\n");
        sb.append("  title: ").append(safeSlug(p)).append('\n');
        sb.append("  version: \"").append(p.getVersionNumber() == null ? "1.0.0" : p.getVersionNumber()).append("\"\n");
        sb.append("paths:\n");
        if (p.getCapabilities() != null && p.getCapabilities().getTools() != null) {
            for (McpProject.Tool t : p.getCapabilities().getTools()) {
                sb.append("  /tools/").append(t.getName()).append(":\n");
                sb.append("    post:\n");
                sb.append("      summary: ").append(t.getDescription() == null ? t.getName() : t.getDescription()).append('\n');
                sb.append("      responses:\n");
                sb.append("        '200':\n");
                sb.append("          description: OK\n");
            }
        }
        return sb.toString();
    }
}
