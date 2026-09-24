package com.petplatform.schedule.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleReadMapper;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Joins the availability window/occupancy RR snapshot; standalone callers start their own. */
public final class ScheduleStaffFactsReadStore {
    private final SqlSessionTemplate template;
    private final TransactionTemplate transaction;

    public ScheduleStaffFactsReadStore(DataSource source) {
        Objects.requireNonNull(source, "source is required");
        template = ScheduleMybatis.template(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(10);
    }

    public <T> T read(Function<ScheduleReadMapper, T> work) {
        Objects.requireNonNull(work, "work is required");
        try {
            return transaction.execute(status -> work.apply(template.getMapper(ScheduleReadMapper.class)));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "schedule staff facts unavailable");
        }
    }
}
