package com.petplatform.user.biz.infrastructure.persistence;

import com.petplatform.user.biz.infrastructure.persistence.entity.FootprintEntity;
import com.petplatform.user.biz.infrastructure.persistence.mapper.FootprintMapper;
import java.util.List;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;

/** user_browse_footprint plus the user_account guard read (Contract 58). */
public final class FootprintStore {

    private final SqlSessionTemplate template;

    public FootprintStore(DataSource dataSource) {
        this.template = UserMybatis.template(dataSource);
    }

    public <T> T read(Function<FootprintMapper, T> work) {
        return work.apply(template.getMapper(FootprintMapper.class));
    }

    public String userStatus(long userId) {
        return read(mapper -> mapper.selectUserStatus(userId));
    }

    public FootprintEntity find(long userId, String targetType, long targetId) {
        return read(mapper -> mapper.selectFootprint(userId, targetType, targetId));
    }

    public FootprintEntity findById(long footprintId, long userId) {
        return read(mapper -> mapper.selectFootprintById(footprintId, userId));
    }

    public void insert(FootprintEntity row) {
        int changed = read(mapper -> mapper.insertFootprint(row));
        if (changed != 1) {
            throw new IllegalStateException("Footprint insert failed; transaction rolled back");
        }
    }

    /** Refreshes last_viewed_at so the target bubbles back to the head of the fixed ordering. */
    public void touch(long footprintId, long userId) {
        int changed = read(mapper -> mapper.touchFootprint(footprintId, userId));
        if (changed != 1) {
            throw new IllegalStateException("Footprint touch failed; transaction rolled back");
        }
    }

    public boolean delete(long footprintId, long userId) {
        return read(mapper -> mapper.deleteFootprint(footprintId, userId)) == 1;
    }

    public long count(long userId) {
        return read(mapper -> mapper.countFootprints(userId));
    }

    public List<FootprintEntity> page(long userId, int limit, int offset) {
        return read(mapper -> mapper.selectFootprintPage(userId, limit, offset));
    }

    public static FootprintEntity row(long id, long userId, String targetType, long targetId) {
        FootprintEntity entity = new FootprintEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        return entity;
    }
}
