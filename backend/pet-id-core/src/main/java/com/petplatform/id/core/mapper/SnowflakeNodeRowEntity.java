package com.petplatform.id.core.mapper;

import java.time.LocalDateTime;

/** snowflake_worker_state row; owner_incarnation is the raw 16-byte grant owner. */
public class SnowflakeNodeRowEntity {
    private Integer nodeId;
    private String formatIdentity;
    private Boolean enabled;
    private String initializationRef;
    private byte[] ownerIncarnation;
    private Long fence;
    private Long reservedThrough;
    private Long grantStart;
    private Long grantThrough;
    private LocalDateTime leaseUntil;

    public Integer getNodeId() { return nodeId; }
    public void setNodeId(Integer nodeId) { this.nodeId = nodeId; }
    public String getFormatIdentity() { return formatIdentity; }
    public void setFormatIdentity(String formatIdentity) { this.formatIdentity = formatIdentity; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getInitializationRef() { return initializationRef; }
    public void setInitializationRef(String initializationRef) { this.initializationRef = initializationRef; }
    public byte[] getOwnerIncarnation() { return ownerIncarnation; }
    public void setOwnerIncarnation(byte[] ownerIncarnation) { this.ownerIncarnation = ownerIncarnation; }
    public Long getFence() { return fence; }
    public void setFence(Long fence) { this.fence = fence; }
    public Long getReservedThrough() { return reservedThrough; }
    public void setReservedThrough(Long reservedThrough) { this.reservedThrough = reservedThrough; }
    public Long getGrantStart() { return grantStart; }
    public void setGrantStart(Long grantStart) { this.grantStart = grantStart; }
    public Long getGrantThrough() { return grantThrough; }
    public void setGrantThrough(Long grantThrough) { this.grantThrough = grantThrough; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
}
