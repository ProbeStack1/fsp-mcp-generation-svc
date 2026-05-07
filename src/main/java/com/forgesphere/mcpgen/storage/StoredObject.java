package com.forgesphere.mcpgen.storage;

import lombok.Builder;
import lombok.Value;
import java.time.Instant;

/** Provider-agnostic pointer returned from {@link StorageClient#upload}. */
@Value @Builder
public class StoredObject {
    String  bucket;
    String  objectPath;
    long    bytes;
    String  contentType;
    Instant uploadedAt;
}
