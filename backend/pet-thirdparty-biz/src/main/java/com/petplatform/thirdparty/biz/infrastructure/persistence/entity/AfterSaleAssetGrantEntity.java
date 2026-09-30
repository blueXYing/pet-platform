package com.petplatform.thirdparty.biz.infrastructure.persistence.entity;

import java.time.Instant;

/** THIRD_PARTY-owned grant row, never exposed through a public API. */
public final class AfterSaleAssetGrantEntity {
    public Long id,afterSaleId,batchId,assetId,actorId;
    public String actorType,requestId,keyVersion,status;
    public byte[] tokenDigest,requestHash,proofHash,reasonCipher;
    public Instant issuedAt,expiresAt;
    public Boolean expired;
}
