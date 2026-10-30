package com.petplatform.service.biz.infrastructure.persistence.entity;

/**
 * service_category dictionary row projected for the C catalog read (contract 74 §3.1,
 * CCR-SVC-CAT-001). {@code parentCode} is resolved through a self-join on parent_id; the row also
 * carries the stored status so the availability query and the categoryCode resolver can apply the
 * approved enabled/disabled split without a second read.
 */
public final class CatalogCategoryReadEntity {
    private Long id;
    private String categoryCode;
    private String parentCode;
    private String categoryName;
    private String iconUrl;
    private String description;
    private Integer sortNo;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
    public String getParentCode() { return parentCode; }
    public void setParentCode(String parentCode) { this.parentCode = parentCode; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
