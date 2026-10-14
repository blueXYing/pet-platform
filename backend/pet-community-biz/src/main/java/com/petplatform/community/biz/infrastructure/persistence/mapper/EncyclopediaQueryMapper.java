package com.petplatform.community.biz.infrastructure.persistence.mapper;

import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaBreedEntity;
import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaBreedJoinEntity;
import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaCategoryEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * encyclopedia_category / encyclopedia_breed read face only (contract 62).
 * SQL lives in resources/mapper/EncyclopediaQueryMapper.xml (22号 MyBatis ruling).
 */
public interface EncyclopediaQueryMapper {

    List<EncyclopediaCategoryEntity> selectEnabledCategories();

    EncyclopediaCategoryEntity selectEnabledCategoryByCode(@Param("categoryCode") String categoryCode);

    List<EncyclopediaBreedJoinEntity> selectPublishedBreeds(
            @Param("categoryId") Long categoryId,
            @Param("keywordPattern") String keywordPattern,
            @Param("tag") String tag,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countPublishedBreeds(
            @Param("categoryId") Long categoryId,
            @Param("keywordPattern") String keywordPattern,
            @Param("tag") String tag);

    EncyclopediaBreedJoinEntity selectPublishedBreedById(@Param("breedId") long breedId);

    List<EncyclopediaBreedJoinEntity> selectRelatedPublishedBreeds(
            @Param("categoryId") Long categoryId,
            @Param("excludeBreedId") long excludeBreedId,
            @Param("limit") int limit);
}
