package com.petplatform.review.biz.infrastructure.persistence.entity;

/** 14号 command_idempotency row for one five-tuple review-create binding. */
public final class ReviewCommandBindingEntity {
    private Long id;
    private String canonicalVersion;
    private String paramsSha256;
    private byte[] paramsCanonical;
    private String status;
    private String receiptJson;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getCanonicalVersion() { return canonicalVersion; }
    public void setCanonicalVersion(String value) { canonicalVersion = value; }
    public String getParamsSha256() { return paramsSha256; }
    public void setParamsSha256(String value) { paramsSha256 = value; }
    public byte[] getParamsCanonical() { return paramsCanonical; }
    public void setParamsCanonical(byte[] value) { paramsCanonical = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getReceiptJson() { return receiptJson; }
    public void setReceiptJson(String value) { receiptJson = value; }
}
