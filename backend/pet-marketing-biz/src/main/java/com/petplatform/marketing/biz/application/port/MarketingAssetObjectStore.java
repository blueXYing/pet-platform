package com.petplatform.marketing.biz.application.port;

/**
 * CCR-MKT-002 storage port. V1 can fall back to the DB-backed asset row; when the local
 * OSS_* configuration is present, artwork bytes live in the private object bucket and the
 * public asset endpoint serves them through the application (no bucket ACL change).
 */
public interface MarketingAssetObjectStore {

    boolean enabled();

    void put(String objectKey, byte[] content, String contentType);

    byte[] get(String objectKey);
}
