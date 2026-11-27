package com.petplatform.order.biz.infrastructure.persistence.entity;

/**
 * Read-only creation-time snapshot-name row ({@code order_service_snapshot}) behind the HTTP
 * contract 10 §3.7 name supplement. Both names are null when the own order carries no snapshot
 * row; the row is absent altogether for a foreign or unknown order.
 */
public final class OrderNameFactsEntity {
    private String storeName;
    private String serviceName;

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
}
