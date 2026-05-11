package com.forgesphere.mcpgen.bridge;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Thin GCS client dedicated to the bridge upload — distinct from
 * {@link com.forgesphere.mcpgen.storage.GcsStorageClient} (which writes
 * to our own per-project zip cache) because:
 *
 *   • a different bucket / SA may be needed for the shared bucket, and
 *   • we want a single-purpose {@link #upload(String, byte[])} surface
 *     that returns the gs:// path the senior pipeline expects.
 *
 * Construction lifecycle is handled by {@link BridgeAutoConfiguration}.
 */
public class BridgeGcsClient {

    private final Storage storage;
    private final String  bucket;
    private final String  pathPrefix;
    private final String  contentType;

    public BridgeGcsClient(Storage storage, String bucket, String pathPrefix, String contentType) {
        this.storage     = storage;
        this.bucket      = bucket;
        this.pathPrefix  = pathPrefix == null ? "" : pathPrefix;
        this.contentType = contentType == null ? "application/octet-stream" : contentType;
    }

    /** Build using the same Base64-SA pattern as the rest of the codebase. */
    public static BridgeGcsClient build(BridgeProperties.Gcs props) {
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
            return new BridgeGcsClient(b.build().getService(), props.getBucket(),
                    props.getPathPrefix(), props.getContentType());
        } catch (IOException e) {
            throw new IllegalStateException("Could not build Bridge GCS client: " + e.getMessage(), e);
        }
    }

    /**
     * Uploads {@code bytes} to {@code <bucket>/<pathPrefix><objectKey>}.
     *
     * @param objectKey relative key (NO leading slash, NO bucket prefix)
     * @param bytes     raw zip payload
     * @return the absolute object path inside the bucket (i.e. what
     *         {@code codegen_results.gcsArchivePath} should store).
     *         Note: returned value does NOT include the bucket name —
     *         it's a relative path the senior pipeline can append to
     *         their bucket reference.
     */
    public String upload(String objectKey, byte[] bytes) {
        String fullPath = (pathPrefix + objectKey).replaceAll("^/+", "");
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, fullPath))
                .setContentType(contentType)
                .build();
        storage.create(info, bytes);
        return fullPath;
    }

    /**
     * Generates a V4-signed download URL for {@code objectKey} valid for
     * {@code ttlMinutes}. Returns the full {@code https://storage.googleapis.com/...}
     * URL the same way senior's pipeline writes {@code archiveDownloadUrl}.
     *
     * @param fullObjectPath the absolute path inside the bucket (the value
     *                       returned by {@link #upload(String, byte[])})
     * @param ttlMinutes     expiry in minutes (senior uses 60)
     */
    public String signedUrl(String fullObjectPath, long ttlMinutes) {
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, fullObjectPath)).build();
        return storage.signUrl(info, ttlMinutes, java.util.concurrent.TimeUnit.MINUTES,
                com.google.cloud.storage.Storage.SignUrlOption.withV4Signature()).toString();
    }

    public String getBucket() { return bucket; }
}
