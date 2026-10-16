package com.petplatform.community.biz.infrastructure.persistence.mapper;

import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaAdminEntities.AdminBreedEntity;
import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaAdminEntities.AdminCategoryEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * encyclopedia_category / encyclopedia_breed admin face (contract 65 §3).
 * SQL lives in resources/mapper/EncyclopediaAdminMapper.xml (22号 MyBatis ruling).
 */
public interface EncyclopediaAdminMapper {

    List<AdminCategoryEntity> selectCategories(@Param("limit") int limit, @Param("offset") int offset);

    long countCategories();

    AdminCategoryEntity selectCategoryByCode(@Param("categoryCode") String categoryCode);

    List<AdminBreedEntity> selectBreeds(
            @Param("status") String status,
            @Param("categoryId") Long categoryId,
            @Param("keywordPattern") String keywordPattern,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countBreeds(
            @Param("status") String status,
            @Param("categoryId") Long categoryId,
            @Param("keywordPattern") String keywordPattern);

    AdminBreedEntity selectBreedById(@Param("breedId") long breedId);

    long countBreedsByCategory(@Param("categoryId") Long categoryId);

    long countPublishedBreedsByCategory(@Param("categoryId") Long categoryId);

    int updateCategory(
            @Param("categoryCode") String categoryCode,
            @Param("categoryName") String categoryName,
            @Param("sortNo") Integer sortNo,
            @Param("status") String status,
            @Param("fromStatus") String fromStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") java.time.LocalDateTime now);

    int updateBreedFields(
            @Param("breedId") long breedId,
            @Param("summary") String summary,
            @Param("coverUrl") String coverUrl,
            @Param("bodyType") String bodyType,
            @Param("aliases") String aliases,
            @Param("tags") String tags,
            @Param("sortNo") int sortNo,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") java.time.LocalDateTime now);

    int updateBreedStatus(
            @Param("breedId") long breedId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") java.time.LocalDateTime now);

    int insertBinding(
            @Param("id") long id,
            @Param("requestKey") String requestKey,
            @Param("canonicalVersion") String canonicalVersion,
            @Param("paramsSha256") String paramsSha256,
            @Param("paramsCanonical") byte[] paramsCanonical);

    com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaAdminBindingEntity
            selectBindingForUpdate(@Param("requestKey") String requestKey);

    int markBindingSucceeded(
            @Param("requestKey") String requestKey,
            @Param("receiptJson") String receiptJson);
}
