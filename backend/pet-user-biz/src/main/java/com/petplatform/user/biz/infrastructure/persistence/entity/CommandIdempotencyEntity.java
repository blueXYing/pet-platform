package com.petplatform.user.biz.infrastructure.persistence.entity;

/** command_idempotency row (SQL 14, supplement 23 §5 binding record). */
public class CommandIdempotencyEntity {
    private Long id;
    private String requestKey;
    private String canonicalVersion;
    private String paramsSha256;
    private byte[] paramsCanonical;
    private String status;
    private String receiptJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String requestKey) { this.requestKey = requestKey; }
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
