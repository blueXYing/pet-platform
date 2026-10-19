package com.petplatform.user.biz.infrastructure.persistence.entity;

/** user_pet_health_record_audit row (SQL68); metadata only, never health content. */
public class PetHealthRecordAuditEntity {
    private Long id;
    private Long actorId;
    private String actionCode;
    private Long recordId;
    private Long petId;
    private String recordType;
    private byte[] requestKey;
    private byte[] requestId;
    private String traceId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getActionCode() { return actionCode; }
    public void setActionCode(String actionCode) { this.actionCode = actionCode; }
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public Long getPetId() { return petId; }
    public void setPetId(Long petId) { this.petId = petId; }
    public String getRecordType() { return recordType; }
    public void setRecordType(String recordType) { this.recordType = recordType; }
    public byte[] getRequestKey() { return requestKey; }
    public void setRequestKey(byte[] requestKey) { this.requestKey = requestKey; }
    public byte[] getRequestId() { return requestId; }
    public void setRequestId(byte[] requestId) { this.requestId = requestId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
