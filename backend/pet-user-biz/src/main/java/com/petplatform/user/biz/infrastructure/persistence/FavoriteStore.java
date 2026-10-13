package com.petplatform.user.biz.infrastructure.persistence;

import com.petplatform.user.biz.infrastructure.persistence.entity.FavoriteEntity;
import com.petplatform.user.biz.infrastructure.persistence.mapper.FavoriteMapper;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;

/** user_favorite plus the user_account guard read (Contract 58); only this Owner's tables. */
public final class FavoriteStore {

    private final SqlSessionTemplate template;

    public FavoriteStore(DataSource dataSource) {
        this.template = UserMybatis.template(dataSource);
    }

    public <T> T read(Function<FavoriteMapper, T> work) {
        return work.apply(template.getMapper(FavoriteMapper.class));
    }

    public String userStatus(long userId) {
        return read(mapper -> mapper.selectUserStatus(userId));
    }

    public FavoriteEntity find(long userId, String targetType, long targetId) {
        return read(mapper -> mapper.selectFavorite(userId, targetType, targetId));
    }

    public FavoriteEntity findForUpdate(long userId, String targetType, long targetId) {
        return read(mapper -> mapper.selectFavoriteForUpdate(userId, targetType, targetId));
    }

    public void insert(FavoriteEntity row) {
        int changed = read(mapper -> mapper.insertFavorite(row));
        if (changed != 1) {
            throw new IllegalStateException("Favorite insert failed; transaction rolled back");
        }
    }

    /** True when a row was removed; false means the pair was not favorited (still a success). */
    public boolean delete(long userId, String targetType, long targetId) {
        return read(mapper -> mapper.deleteFavorite(userId, targetType, targetId)) == 1;
    }

    public long count(long userId, String targetType) {
        return read(mapper -> mapper.countFavorites(userId, targetType));
    }

    public List<FavoriteEntity> page(long userId, String targetType, int limit, int offset) {
        return read(mapper -> mapper.selectFavoritePage(userId, targetType, limit, offset));
    }

    public static FavoriteEntity row(long id, long userId, String targetType, long targetId) {
        FavoriteEntity entity = new FavoriteEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        return entity;
    }

    public static String unsigned(long value) {
        return Objects.requireNonNull(Long.toUnsignedString(value));
    }
}
