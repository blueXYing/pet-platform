package com.petplatform.thirdparty.biz;

import com.petplatform.thirdparty.biz.infrastructure.oss.OssAssetClient;
import com.petplatform.thirdparty.biz.infrastructure.oss.OssConnection;
import java.util.HashMap;
import java.util.Map;

/** In-memory storage fake: CI and unit runs never touch a real bucket (CCR-OSS-001 §5). */
final class FakeOssAssetClient implements OssAssetClient {
    final Map<String, byte[]> store = new HashMap<>();
    private final OssConnection connection = new OssConnection(
            "https://oss-cn-test.aliyuncs.com", "cn-test", "test-bucket", "ak", "sk", null);

    @Override public boolean exists(String objectKey) {
        return store.containsKey(objectKey);
    }

    @Override public void put(String objectKey, byte[] content, String contentType) {
        store.put(objectKey, content.clone());
    }

    @Override public OssConnection connection() {
        return connection;
    }
}
