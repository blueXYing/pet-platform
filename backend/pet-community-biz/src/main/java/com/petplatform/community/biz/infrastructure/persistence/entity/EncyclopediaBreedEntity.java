package com.petplatform.community.biz.infrastructure.persistence.entity;

/**
 * encyclopedia_breed row (SQL 62). JSON columns (aliases/tags/facts/radar) stay String at the
 * persistence boundary and are decoded in the community kernel only.
 */
public class EncyclopediaBreedEntity {
    private Long id;
    private Long categoryId;
    private String name;
    private String aliases;
    private String tags;
    private String summary;
    private String coverUrl;
    private String bodyType;
    private String facts;
    private String radar;
    private String descriptionHtml;
    private String careAdviceHtml;
    private String healthTipsHtml;
    private String status;
    private Integer sortNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
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
    public String getFacts() { return facts; }
    public void setFacts(String facts) { this.facts = facts; }
    public String getRadar() { return radar; }
    public void setRadar(String radar) { this.radar = radar; }
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
}
