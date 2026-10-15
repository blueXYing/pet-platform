package com.petplatform.schedule.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleAdminViewMapper;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only store behind the contract-64 schedule view (06/37 schedule tables). */
public final class ScheduleAdminViewStore {
    private final org.mybatis.spring.SqlSessionTemplate template;
    private final TransactionTemplate transaction;

    public ScheduleAdminViewStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource is required");
        this.template = ScheduleMybatis.template(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(true);
        transaction.setTimeout(10);
    }

    public <T> T read(Function<ScheduleAdminViewMapper, T> work) {
        Objects.requireNonNull(work, "work is required");
        try {
            return transaction.execute(status -> work.apply(template.getMapper(ScheduleAdminViewMapper.class)));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "schedule view dependency unavailable");
        }
    }
}
