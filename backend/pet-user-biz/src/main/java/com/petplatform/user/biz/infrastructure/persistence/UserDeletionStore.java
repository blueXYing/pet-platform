package com.petplatform.user.biz.infrastructure.persistence;

import com.petplatform.user.biz.infrastructure.persistence.mapper.UserDeletionMapper;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;

/** Store for the contract-66 deletion audit rows and the guarded account status flip. */
public final class UserDeletionStore {
    private final SqlSessionTemplate template;

    public UserDeletionStore(DataSource dataSource) {
        this.template = UserMybatis.template(Objects.requireNonNull(dataSource));
    }

    public <T> T work(Function<UserDeletionMapper, T> statements) {
        return statements.apply(template.getMapper(UserDeletionMapper.class));
    }
}
