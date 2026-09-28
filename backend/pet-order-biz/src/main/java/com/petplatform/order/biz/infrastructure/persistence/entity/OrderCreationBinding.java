package com.petplatform.order.biz.infrastructure.persistence.entity;

/** Immutable parameters and first success receipt from SQL38. */
public class OrderCreationBinding {
    private long id;
    private String canonicalVersion;
    private String paramsSha256;
    private byte[] paramsCanonical;
    private String status;
    private String receiptJson;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
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
