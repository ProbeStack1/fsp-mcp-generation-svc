package com.forgesphere.mcpgen.service;

import com.forgesphere.mcpgen.model.McpProject;
import com.forgesphere.mcpgen.repo.McpProjectRepository;
import com.forgesphere.mcpgen.storage.StorageClient;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpLifecycleTest {
    private final McpProjectRepository repo = mock(McpProjectRepository.class);
    private final MicroserviceBridgeService bridge = mock(MicroserviceBridgeService.class);
    private final McpGenerationService service = new McpGenerationService(repo, List.of(), mock(StorageClient.class), new GenerationPostProcessor(), mock(MongoTemplate.class));
    private McpProject source() {
        ReflectionTestUtils.setField(service, "bridgeService", bridge);
        var p = McpProject.builder().id("original").workspaceId("workspace")
                .identity(McpProject.Identity.builder().slug("example").displayName("Example").build())
                .capabilities(McpProject.Capabilities.builder().tools(new ArrayList<>(List.of(McpProject.Tool.builder().name("read").http(new LinkedHashMap<>(Map.of("path", "/items"))).build()))).build())
                .versionNumber("1.0.0").mockServerId("old-mock").testCollectionObjectKey("old-tests")
                .generated(McpProject.Generated.builder().files(List.of()).build()).deployedServiceUrl("https://old.test")
                .zipObjectPath("old.zip").deploymentId("old-deployment").testRunResults(Map.of("old", "passed")).build();
        when(repo.findById("original")).thenReturn(Optional.of(p));
        when(repo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(bridge.createMicroserviceOnly(any())).thenReturn("new-mirror");
        return p;
    }
    private void assertFresh(McpProject copy) {
        assertNull(copy.getMockServerId()); assertNull(copy.getTestCollectionObjectKey()); assertNull(copy.getGenerated());
        assertNull(copy.getZipObjectPath()); assertNull(copy.getDeploymentId()); assertNull(copy.getDeployedServiceUrl());
        assertNull(copy.getTestRunResults()); assertEquals("new-mirror", copy.getMicroserviceMirrorId());
    }
    @Test void newProjectsAndDefaultClonesStartAtOneWithoutRewritingExistingVersions() {
        var original = source(); original.setVersionNumber("0.1.0");
        var created = service.create(McpProject.builder().workspaceId("workspace").identity(McpProject.Identity.builder().slug("new-server").build()).build());
        assertEquals("1.0.0", created.getVersionNumber());
        assertEquals("1.0.0", service.clone("original", null, null).getVersionNumber());
        assertEquals("0.1.0", original.getVersionNumber());
    }
    @Test void cloneDeepCopiesConfigurationAndClearsEveryRuntimePointer() {
        var original = source(); var copy = service.clone("original", null, null);
        assertFresh(copy); assertEquals("original", copy.getCloneOf()); assertNull(copy.getVersionOf());
        assertEquals("example-copy", copy.getIdentity().getSlug());
        copy.getCapabilities().getTools().get(0).getHttp().put("path", "/changed");
        assertEquals("/items", original.getCapabilities().getTools().get(0).getHttp().get("path"));
        assertEquals("old-mock", original.getMockServerId());
    }
    @Test void newVersionBumpsLatestSiblingAndDoesNotCopyResults() {
        source();
        when(repo.findByWorkspaceIdAndIdentitySlugOrderByCreatedAtDesc("workspace", "example"))
                .thenReturn(List.of(McpProject.builder().versionNumber("1.0.5").build()));
        var copy = service.version("original", null, null); assertFresh(copy);
        assertEquals("1.0.6", copy.getVersionNumber()); assertEquals("original", copy.getVersionOf());
        assertEquals("workspace|example|1.0.6", copy.getVersionKey());
        assertThrows(IllegalArgumentException.class, () -> service.version("original", null, "1.0.5"));
        assertThrows(IllegalArgumentException.class, () -> service.version("original", null, "banana"));
    }
    @Test void diffSectionsAndUpdateReportOnlyGenuinelyChangedSections() {
        source();
        // Patch re-sends every section but only changes `auth`.
        var patch = McpProject.builder()
                .identity(McpProject.Identity.builder().slug("example").displayName("Example").build())
                .capabilities(McpProject.Capabilities.builder()
                        .tools(new ArrayList<>(List.of(McpProject.Tool.builder().name("read")
                                .http(new LinkedHashMap<>(Map.of("path", "/items"))).build()))).build())
                .versionNumber("1.0.0")
                .auth(McpProject.Auth.builder().kind("bearer").generatedToken("abc123").build())
                .build();
        assertEquals(List.of("auth"), service.diffSections("original", patch));

        // A patch that changes nothing is a true no-op — no persist.
        clearInvocations(repo);
        var noop = McpProject.builder()
                .identity(McpProject.Identity.builder().slug("example").displayName("Example").build())
                .versionNumber("1.0.0").build();
        assertTrue(service.diffSections("original", noop).isEmpty());
        service.update("original", noop);
        verify(repo, never()).save(any());
    }
    @Test void cloneHonorsRequestedNameVersionAndSlugWithoutChangingSource() {
        var original = source();
        var copy = service.clone("original", null, "independent-server", "2.3.4", "Independent Server");
        assertEquals("Independent Server", copy.getIdentity().getDisplayName());
        assertEquals("2.3.4", copy.getVersionNumber()); assertEquals("workspace|independent-server|2.3.4", copy.getVersionKey());
        assertEquals("1.0.0", original.getVersionNumber()); assertEquals("example", original.getIdentity().getSlug());
        assertThrows(IllegalArgumentException.class, () -> service.clone("original", null, "other", "invalid", null));
    }
}
