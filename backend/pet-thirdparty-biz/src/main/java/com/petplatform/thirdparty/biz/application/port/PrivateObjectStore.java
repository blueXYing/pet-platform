package com.petplatform.thirdparty.biz.application.port;

import java.util.Optional;

/**
 * Immutable private-object operations. Implementations must enforce the merchant-materials prefix.
 */
public interface PrivateObjectStore {
  StoredObject putIfAbsent(String objectKey, byte[] content, String mediaType, String sha256);

  StoredContent get(String objectKey, String versionRef);

  Optional<StoredObject> head(String objectKey);

  record StoredObject(String versionRef, String sha256, long bytes, String mediaType) {}

  record StoredContent(byte[] content, String mediaType, String sha256) {
    public StoredContent {
      content = content == null ? null : content.clone();
    }

    @Override
    public byte[] content() {
      return content == null ? null : content.clone();
    }
  }
}
