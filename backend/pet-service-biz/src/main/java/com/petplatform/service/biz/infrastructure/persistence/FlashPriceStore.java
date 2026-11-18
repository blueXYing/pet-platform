package com.petplatform.service.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.service.biz.infrastructure.persistence.mapper.FlashPriceMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract-77 flash-price persistence shell. Merchant/admin commands run in their own
 * READ_COMMITTED transactions (admission and business work); reads take a repeatable-read
 * snapshot. The reserve ledger and the read-side effective-flash lookup instead JOIN the caller's
 * already-open Spring transaction on the same DataSource ({@link #join}) — the order kernel owns
 * that transaction and the reservation must commit or roll back with it (77 §6.1).
 */
public final class FlashPriceStore {

    private static final System.Logger LOG = System.getLogger(FlashPriceStore.class.getName());

    @FunctionalInterface
    public interface Work<T> {
        T apply(FlashPriceMapper mapper);
    }

    public static final class CommitUnknown extends RuntimeException {
        public CommitUnknown(Throwable cause) {
            super("flash-price command commit acknowledgement is unknown", cause);
        }
    }

    private final DataSource source;
    private final SqlSessionTemplate template;
    private final TransactionTemplate read;
    private final TransactionTemplate execute;
    private final SnowflakeIdGenerator ids;

    public FlashPriceStore(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.source = Objects.requireNonNull(dataSource, "dataSource is required");
        this.ids = Objects.requireNonNull(ids, "ID provider is required");
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        try {
            factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/FlashPriceMapper.xml"));
            var factory = Objects.requireNonNull(factoryBean.getObject());
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            factory.getConfiguration().setLocalCacheScope(LocalCacheScope.STATEMENT);
            this.template = new SqlSessionTemplate(factory);
        } catch (Exception failure) {
            throw new IllegalStateException("flash-price mapper initialization failed", failure);
        }
        var manager = new DataSourceTransactionManager(dataSource);
        read = tx(manager, TransactionDefinition.ISOLATION_REPEATABLE_READ, true);
        execute = tx(manager, TransactionDefinition.ISOLATION_READ_COMMITTED, false);
    }

    public long nextId() {
        long id = ids.nextId();
        if (id <= 0) unavailable("ID provider returned invalid value");
        return id;
    }

    public DataSource dataSource() {
        return source;
    }

    /** Repeatable-read snapshot for list reads. */
    public <T> T read(Work<T> work) {
        return run(work, read, false);
    }

    /** Own READ_COMMITTED write transaction for terminal commands and the consumer/task. */
    public <T> T execute(Work<T> work) {
        return run(work, execute, true);
    }

    /**
     * Runs on the caller's open Spring transaction (reserve / effective-flash / booking facts).
     * Without one the reservation ledger would auto-commit per statement — fail closed instead.
     */
    public <T> T join(Work<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !(TransactionSynchronizationManager.getResource(source)
                        instanceof org.springframework.jdbc.datasource.ConnectionHolder holder)
                || !holder.isSynchronizedWithTransaction()) {
            unavailable("flash-price join work requires the caller transaction");
        }
        try {
            FlashPriceMapper mapper = template.getMapper(FlashPriceMapper.class);
            mapper.setTimeZoneUtc();
            return work.apply(mapper);
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            unavailable("flash-price persistence is unavailable");
            return null;
        }
    }

    private <T> T run(Work<T> work, TransactionTemplate transaction, boolean commitUnknown) {
        try {
            return transaction.execute(status -> {
                FlashPriceMapper mapper = template.getMapper(FlashPriceMapper.class);
                mapper.setTimeZoneUtc();
                mapper.setLockWaitTimeout2Seconds();
                return work.apply(mapper);
            });
        } catch (CommitUnknown rethrown) {
            throw rethrown;
        } catch (ApiException known) {
            throw known;
        } catch (PessimisticLockingFailureException busy) {
            throw new ApiException(CommonApiCodes.CONFLICT,
                    "the same resource is busy; retry with the original requestId");
        } catch (TransactionSystemException commitBoundary) {
            if (commitUnknown) throw new CommitUnknown(commitBoundary);
            unavailable("flash-price commit result is unknown");
            return null;
        } catch (RuntimeException failure) {
            // Class names only: raw messages may carry SQL statements or bound values.
            StringBuilder chain = new StringBuilder(failure.getClass().getName());
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                chain.append(" -> ").append(cause.getClass().getName());
            }
            LOG.log(System.Logger.Level.WARNING, "flash-price store failure: {0}", chain);
            unavailable("flash-price persistence is unavailable");
            return null;
        }
    }

    private static TransactionTemplate tx(
            DataSourceTransactionManager manager, int isolation, boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setIsolationLevel(isolation);
        template.setReadOnly(readOnly);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(10);
        return template;
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
