package com.petplatform.schedule.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleUtilizationMapper;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Utilization read unit (contract 70 §2 排期利用率): one read-only MySQL repeatable-read
 * snapshot over the two aggregate statements so the OPEN-window capacity and the CONFIRMED
 * reservation occupancy cannot drift mid-read. Same discipline as {@link ScheduleReadStore}.
 */
public final class ScheduleUtilizationStore {
    private final SqlSessionTemplate template;
    private final TransactionTemplate transaction;

    public ScheduleUtilizationStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource is required");
        this.template = ScheduleMybatis.template(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(10);
    }

    public <T> T read(Function<ScheduleUtilizationMapper, T> work) {
        Objects.requireNonNull(work, "work is required");
        try {
            return transaction.execute(status -> work.apply(template.getMapper(ScheduleUtilizationMapper.class)));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "schedule utilization facts unavailable");
        }
    }
}
