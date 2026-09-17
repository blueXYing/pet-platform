package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/** Merchant-owned supplement-23 binding; protected canonical bytes are the equality authority. */
public final class MerchantCommandBindingEntity {
    private Long id;
    private String canonicalVersion;
    private String paramsSha256;
    private byte[] paramsCanonical;
    private String status;
    private String receiptJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCanonicalVersion() { return canonicalVersion; }
    public void setCanonicalVersion(String canonicalVersion) { this.canonicalVersion = canonicalVersion; }
    public String getParamsSha256() { return paramsSha256; }
    public void setParamsSha256(String paramsSha256) { this.paramsSha256 = paramsSha256; }
    public byte[] getParamsCanonical() { return paramsCanonical; }
    public void setParamsCanonical(byte[] paramsCanonical) { this.paramsCanonical = paramsCanonical; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReceiptJson() { return receiptJson; }
    public void setReceiptJson(String receiptJson) { this.receiptJson = receiptJson; }
}
