package com.forgesphere.mcpgen.storage;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;

/**
 * Minimal object-store abstraction. Default implementation is GCS
 * ({@link GcsStorageClient}); a {@link LocalDiskStorageClient} kicks in
 * when GCS credentials are not configured so dev pods keep working.
 *
 * Patterned after the same {@code StorageClient} interface used in the
 * forgeq microservices so business code can switch providers freely.
 */
public interface StorageClient {

    /** Upload raw bytes. Returns provider-agnostic pointer. */
    StoredObject upload(String objectPath, byte[] bytes, String contentType);

    /** Download to a byte array. Throws on not-found. */
    byte[] download(String objectPath);

    /** Stream open (caller closes). */
    InputStream openStream(String objectPath);

    /**
     * Optional pre-signed URL for direct-from-storage downloads — null
     * if backend can't sign (e.g. local-disk fallback).
     */
    URL signedDownloadUrl(String objectPath, Duration ttl);

    /** Provider id, e.g. {@code gcs} or {@code local-disk}. */
    String providerId();
}
