package com.petplatform.community.biz.infrastructure.persistence.entity;

/**
 * encyclopedia_category / encyclopedia_breed admin rows (SQL 62, contract 65 admin face).
 * Full-column projections including version and audit timestamps that the C-end read entities
 * do not map. JSON columns stay String at the persistence boundary and are decoded in the
 * community kernel only.
 */
public class EncyclopediaAdminEntities {

    /** All-status category row. */
    public static class AdminCategoryEntity {
        private Long id;
        private String categoryCode;
        private String categoryName;
        private String status;
        private Integer sortNo;
        private Long version;
        private java.time.LocalDateTime createdAt;
        private java.time.LocalDateTime updatedAt;

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
        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        public java.time.LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
        public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(java.time.LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }

    /** All-status breed row (flat; category facts resolved by join in the admin queries). */
    public static class AdminBreedEntity {
        private Long id;
        private Long categoryId;
        private String categoryCode;
        private String categoryName;
        private String name;
        private String aliases;
        private String tags;
        private String summary;
        private String coverUrl;
        private String bodyType;
        private String descriptionHtml;
        private String careAdviceHtml;
        private String healthTipsHtml;
        private String status;
        private Integer sortNo;
        private Long version;
        private java.time.LocalDateTime createdAt;
        private java.time.LocalDateTime updatedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getCategoryId() { return categoryId; }
        public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
        public String getCategoryCode() { return categoryCode; }
        public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
        public String getCategoryName() { return categoryName; }
        public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAliases() { return aliases; }
        public void setAliases(String aliases) { this.aliases = aliases; }
        public String getTags() { return tags; }
        public void setTags(String tags) { this.tags = tags; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public String getCoverUrl() { return coverUrl; }
        public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
        public String getBodyType() { return bodyType; }
        public void setBodyType(String bodyType) { this.bodyType = bodyType; }
        public String getDescriptionHtml() { return descriptionHtml; }
        public void setDescriptionHtml(String descriptionHtml) { this.descriptionHtml = descriptionHtml; }
        public String getCareAdviceHtml() { return careAdviceHtml; }
        public void setCareAdviceHtml(String careAdviceHtml) { this.careAdviceHtml = careAdviceHtml; }
        public String getHealthTipsHtml() { return healthTipsHtml; }
        public void setHealthTipsHtml(String healthTipsHtml) { this.healthTipsHtml = healthTipsHtml; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Integer getSortNo() { return sortNo; }
        public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        public java.time.LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
        public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(java.time.LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }

    private EncyclopediaAdminEntities() {}
}
