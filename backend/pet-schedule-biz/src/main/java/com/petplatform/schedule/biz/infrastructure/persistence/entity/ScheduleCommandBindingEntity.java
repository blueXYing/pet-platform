package com.petplatform.schedule.biz.infrastructure.persistence.entity;

/** Binding row of command_idempotency (14号 shared physical table; schedule request-key-v1). */
public final class ScheduleCommandBindingEntity {
    private Long id;
    private String canonicalVersion;
    private String paramsSha256;
    private byte[] paramsCanonical;
    private String status;
    private String receiptJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
