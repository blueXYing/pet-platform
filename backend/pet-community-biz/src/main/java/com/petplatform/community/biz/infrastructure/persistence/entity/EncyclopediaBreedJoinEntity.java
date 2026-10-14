package com.petplatform.community.biz.infrastructure.persistence.entity;

/**
 * encyclopedia_breed row joined with its enabled category (read-side projection, SQL 62).
 * The join columns feed card/detail bodies; the kernel rejects rows missing the projection.
 */
public class EncyclopediaBreedJoinEntity extends EncyclopediaBreedEntity {
    private String categoryCode;
    private String categoryName;

    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
}
