package com.petplatform.order.biz.infrastructure.persistence.entity;

/**
 * One grouped sales count row for the public service-catalog facts (74号 §6.2 /
 * CCR-SVC-CARD-001 §3.1): the requested store/service coordinate and the number of effective
 * orders behind it. Deliberately carries no order, user, amount or timestamp field — the
 * surface opens no enumeration or PII face.
 */
public final class ServiceCatalogSalesCountEntity {
    private Long id;
    private Long orderCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderCount() { return orderCount; }
    public void setOrderCount(Long orderCount) { this.orderCount = orderCount; }
}
