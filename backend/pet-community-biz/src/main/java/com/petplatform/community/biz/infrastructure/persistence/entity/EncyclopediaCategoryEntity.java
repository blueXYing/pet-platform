package com.petplatform.community.biz.infrastructure.persistence.entity;

/** encyclopedia_category row (SQL 62); enabled dictionary per PRD 5.1.4. */
public class EncyclopediaCategoryEntity {
    private Long id;
    private String categoryCode;
    private String categoryName;
    private String status;
    private Integer sortNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
}
