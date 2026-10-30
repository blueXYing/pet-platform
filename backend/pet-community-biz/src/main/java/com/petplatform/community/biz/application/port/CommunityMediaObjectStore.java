package com.petplatform.community.biz.application.port;

/** OSS-first community image bytes; a disabled store means the DB BLOB fallback is used. */
public interface CommunityMediaObjectStore {

    boolean enabled();

    void put(String objectKey, byte[] content, String contentType);

    byte[] get(String objectKey);
}
