package com.forgesphere.mcpgen.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;

/**
 * Local-disk fallback used when GCS credentials are missing — keeps
 * dev pods functional without a real bucket. Object paths translate
 * directly to filesystem paths under {@link StorageProperties#getFallbackDir()}.
 */
@Slf4j
@RequiredArgsConstructor
public class LocalDiskStorageClient implements StorageClient {

    private final Path root;

    public static LocalDiskStorageClient build(StorageProperties props) {
        Path p = Path.of(props.getFallbackDir());
        try { Files.createDirectories(p); } catch (IOException ignored) {}
        log.warn("[storage] GCS not configured — falling back to local-disk at {}", p);
        return new LocalDiskStorageClient(p);
    }

    @Override public StoredObject upload(String objectPath, byte[] bytes, String contentType) {
        try {
            Path target = root.resolve(objectPath).normalize();
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return StoredObject.builder()
                    .bucket("local-disk").objectPath(objectPath).bytes(bytes.length)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .uploadedAt(Instant.now()).build();
        } catch (IOException e) {
            throw new RuntimeException("local-disk upload failed: " + e.getMessage(), e);
        }
    }

    @Override public byte[] download(String objectPath) {
        try { return Files.readAllBytes(root.resolve(objectPath).normalize()); }
        catch (IOException e) { throw new IllegalArgumentException("object not found: " + objectPath); }
    }

    @Override public InputStream openStream(String objectPath) {
        try { return Files.newInputStream(root.resolve(objectPath).normalize()); }
        catch (IOException e) { throw new IllegalArgumentException("object not found: " + objectPath); }
    }

    @Override public URL signedDownloadUrl(String objectPath, Duration ttl) {
        // Local fallback can't sign — caller streams via the API.
        return null;
    }

    @Override public String providerId() { return "local-disk"; }
}
