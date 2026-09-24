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

/**
 * Executes every schedule read unit in one read-only MySQL repeatable-read snapshot (the window
 * rows and their reservation occupancy counts share it). Service-domain eligibility is consumed
 * through pet-service-api before this snapshot opens - two consecutive snapshots, drift between
 * them stays display-only because the authoritative re-check belongs to the SCH-003 hold.
 */
public final class ScheduleReadStore {
    private final SqlSessionTemplate template;
    private final TransactionTemplate transaction;

    public ScheduleReadStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource is required");
        this.template = ScheduleMybatis.template(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        } catch (RuntimeException unavailable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "schedule read dependency unavailable");
        }
    }
}
