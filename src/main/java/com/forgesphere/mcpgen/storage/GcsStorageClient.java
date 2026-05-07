package com.forgesphere.mcpgen.storage;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * GCS-backed implementation. Mirrors the same contract used by the
 * forgeq mgmt services (see
 * {@code forgeq-api-documentation-mgmt-svc/.../GcsStorageClient.java}).
 *
 * Credentials are passed as a Base64-encoded service-account JSON in
 * {@link StorageProperties#getCredentialsEncoded()}. If empty we fall
 * back to {@link GoogleCredentials#getApplicationDefault()}.
 */
public class GcsStorageClient implements StorageClient {

    private final Storage storage;
    private final String  bucket;

    public GcsStorageClient(Storage storage, String bucket) {
        this.storage = storage;
        this.bucket  = bucket;
    }

    public static GcsStorageClient build(StorageProperties props) {
        try {
            StorageOptions.Builder b = StorageOptions.newBuilder();
            if (props.getProjectId() != null && !props.getProjectId().isBlank()) {
                b.setProjectId(props.getProjectId());
            }
            String enc = props.getCredentialsEncoded();
            GoogleCredentials creds;
            if (enc != null && !enc.isBlank()) {
                byte[] json = Base64.getDecoder().decode(enc.getBytes(StandardCharsets.UTF_8));
                creds = ServiceAccountCredentials.fromStream(new ByteArrayInputStream(json));
            } else {
                creds = GoogleCredentials.getApplicationDefault();
            }
            b.setCredentials(creds);
            return new GcsStorageClient(b.build().getService(), props.getBucket());
        } catch (IOException e) {
            throw new IllegalStateException("Could not build GCS client: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ ops

    @Override public StoredObject upload(String objectPath, byte[] bytes, String contentType) {
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectPath))
                .setContentType(contentType != null ? contentType : "application/octet-stream")
                .build();
        Blob b = storage.create(info, bytes);
        return StoredObject.builder()
                .bucket(bucket).objectPath(objectPath).bytes(b.getSize())
                .contentType(b.getContentType()).uploadedAt(Instant.now())
                .build();
    }

    @Override public byte[] download(String objectPath) {
        Blob b = storage.get(BlobId.of(bucket, objectPath));
        if (b == null) throw new IllegalArgumentException("object not found: " + objectPath);
        return b.getContent();
    }

    @Override public InputStream openStream(String objectPath) {
        return new ByteArrayInputStream(download(objectPath));
    }

    @Override public URL signedDownloadUrl(String objectPath, Duration ttl) {
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectPath)).build();
        return storage.signUrl(info, ttl.toMinutes(), TimeUnit.MINUTES,
                Storage.SignUrlOption.withV4Signature());
    }

    @Override public String providerId() { return "gcs"; }
}
