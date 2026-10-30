package com.petplatform.marketing.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.marketing.biz.application.MarketingCanonical;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.BindingRow;
import com.petplatform.marketing.biz.infrastructure.persistence.mapper.MarketingMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Marketing tables + the requestId idempotency binding. Reads run in a repeatable-read snapshot;
 * writes run in a READ_COMMITTED transaction. SQL errors never escape with bound values.
 */
public final class MarketingStore {

    public static final class CommitUnknown extends RuntimeException {
        public CommitUnknown(Throwable cause) {
            super("marketing commit acknowledgement is unknown", cause);
        }
    }

    public record Binding(String status, String receiptJson, String paramsSha256, byte[] paramsCanonical,
            boolean fresh) {
        public Binding {
            paramsCanonical = paramsCanonical == null ? null : paramsCanonical.clone();
        }

        @Override
        public byte[] paramsCanonical() {
            return paramsCanonical == null ? null : paramsCanonical.clone();
        }
    }

    public interface Work<T> {
        T run(MarketingMapper mapper);
    }

    private final SqlSessionTemplate template;
    private final TransactionTemplate read;
    private final TransactionTemplate execute;
    private final SnowflakeIdGenerator ids;

    public MarketingStore(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ID provider is required");
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(Objects.requireNonNull(dataSource, "dataSource is required"));
        try {
            factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/MarketingMapper.xml"));
            var factory = Objects.requireNonNull(factoryBean.getObject());
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            factory.getConfiguration().setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            this.template = new SqlSessionTemplate(factory);
        } catch (Exception failure) {
            throw new IllegalStateException("marketing mapper initialization failed", failure);
        }
        var manager = new DataSourceTransactionManager(dataSource);
        read = tx(manager, TransactionDefinition.ISOLATION_REPEATABLE_READ, true);
        execute = tx(manager, TransactionDefinition.ISOLATION_READ_COMMITTED, false);
    }

    private static TransactionTemplate tx(DataSourceTransactionManager manager, int isolation, boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setIsolationLevel(isolation);
        template.setReadOnly(readOnly);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    public long nextId() {
        long id = ids.nextId();
        if (id <= 0) unavailable("ID provider returned invalid value");
        return id;
    }

    public <T> T read(Work<T> work) {
        return run(work, read, false);
    }

    public <T> T execute(Work<T> work) {
        return run(work, execute, true);
    }

    public Binding admit(String requestKey, String commandType, MarketingCanonical canonical) {
        return run(m -> {
            BindingRow row = m.selectBinding(requestKey);
            if (row != null) return binding(row);
            try {
                if (m.insertBinding(nextId(), requestKey, commandType, canonical.sha256(),
                        canonical.bytes(), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)) != 1) {
                    unavailable("idempotency reservation failed");
                }
            } catch (DuplicateKeyException duplicate) {
                row = m.selectBinding(requestKey);
                if (row == null) unavailable("idempotency binding is missing");
                return binding(row);
            }
            row = m.selectBinding(requestKey);
            if (row == null) unavailable("idempotency binding is missing");
            Binding existing = binding(row);
            return new Binding(existing.status(), existing.receiptJson(), existing.paramsSha256(),
                    existing.paramsCanonical(), true);
        }, execute, true);
    }

    public void markSucceeded(String requestKey, String receiptJson) {
        run(m -> m.markBindingSucceeded(requestKey, receiptJson,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)), execute, true);
    }

    public static void requireSame(Binding binding, MarketingCanonical canonical) {
        if (!canonical.matches(binding.paramsCanonical(), binding.paramsSha256())) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    "marketing idempotency conflict");
        }
    }

    private static Binding binding(BindingRow row) {
        return new Binding(row.status, row.receiptJson, row.paramsSha256, row.paramsCanonical, false);
    }

    private <T> T run(Work<T> work, TransactionTemplate transaction, boolean commitUnknown) {
        try {
            return transaction.execute(status -> work.run(template.getMapper(MarketingMapper.class)));
        } catch (CommitUnknown rethrown) {
            throw rethrown;
        } catch (RuntimeException failure) {
            if (commitUnknown && isCommitBoundary(failure)) throw new CommitUnknown(failure);
            throw failure;
        }
    }

    private static boolean isCommitBoundary(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.transaction.TransactionException) return true;
        }
        return false;
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
