package com.petplatform.thirdparty.biz.infrastructure.oss;

/** Storage operations the sync and future admin upload rely on; faked in tests. */
public interface OssAssetClient {

    boolean exists(String objectKey);

    void put(String objectKey, byte[] content, String contentType);

    default String publicUrl(String objectKey) {
        return connection().publicUrl(objectKey);
    }

    OssConnection connection();
}
