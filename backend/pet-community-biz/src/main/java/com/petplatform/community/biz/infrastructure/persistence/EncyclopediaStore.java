package com.petplatform.community.biz.infrastructure.persistence;

import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaBreedEntity;
import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaBreedJoinEntity;
import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaCategoryEntity;
import com.petplatform.community.biz.infrastructure.persistence.mapper.EncyclopediaQueryMapper;
import java.util.List;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;

/** Encyclopedia read-only tables (SQL 62). SQL errors never escape with bound values. */
public final class EncyclopediaStore {

    private final SqlSessionTemplate template;

    public EncyclopediaStore(DataSource dataSource) {
        this.template = CommunityMybatis.template(dataSource);
    }

    public List<EncyclopediaCategoryEntity> listEnabledCategories() {
        return mapper().selectEnabledCategories();
    }

    public EncyclopediaCategoryEntity findEnabledCategoryByCode(String categoryCode) {
        return mapper().selectEnabledCategoryByCode(categoryCode);
    }

    public List<EncyclopediaBreedJoinEntity> pagePublishedBreeds(
            Long categoryId, String keywordPattern, String tag, int limit, int offset) {
        return mapper().selectPublishedBreeds(categoryId, keywordPattern, tag, limit, offset);
    }

    public long countPublishedBreeds(Long categoryId, String keywordPattern, String tag) {
        return mapper().countPublishedBreeds(categoryId, keywordPattern, tag);
    }

    public EncyclopediaBreedJoinEntity findPublishedBreedById(long breedId) {
        return mapper().selectPublishedBreedById(breedId);
    }

    public List<EncyclopediaBreedJoinEntity> listRelatedPublishedBreeds(Long categoryId, long excludeBreedId, int limit) {
        return mapper().selectRelatedPublishedBreeds(categoryId, excludeBreedId, limit);
    }

    private EncyclopediaQueryMapper mapper() {
        return template.getMapper(EncyclopediaQueryMapper.class);
    }
}
