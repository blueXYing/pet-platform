package com.petplatform.community.biz.infrastructure.persistence;

import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaAdminBindingEntity;
import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaAdminEntities.AdminBreedEntity;
import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaAdminEntities.AdminCategoryEntity;
import com.petplatform.community.biz.infrastructure.persistence.mapper.EncyclopediaAdminMapper;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.community.biz.application.CommunityAdminCanonicalParams;
import java.util.Objects;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * encyclopedia_category / encyclopedia_breed admin tables (SQL 62) plus the command_idempotency
 * binding (14号 shared physical table) for the contract-65 maintenance writes. SQL errors never
 * escape with bound values; reads run REPEATABLE_READ, the idempotency admission and the
 * execution run READ_COMMITTED with commit-unknown recovery (points store convention).
 */
public final class EncyclopediaAdminStore {

    /** Thrown when the commit acknowledgement stays unknown after one recovery attempt. */
    public static final class CommitUnknown extends RuntimeException {
        public CommitUnknown(Throwable cause) {
            super("encyclopedia admin commit acknowledgement is unknown", cause);
        }
    }

    public record Binding(
            String status, String receiptJson, String paramsSha256, byte[] paramsCanonical) {
        public Binding {
            paramsCanonical = paramsCanonical == null ? null : paramsCanonical.clone();
        }

        @Override
        public byte[] paramsCanonical() {
            return paramsCanonical == null ? null : paramsCanonical.clone();
        }
    }

    public interface Work<T> {
        T run(EncyclopediaAdminMapper mapper);
    }

    private final SqlSessionTemplate template;
    private final TransactionTemplate read, admission, execution;
    private final SnowflakeIdGenerator ids;

    public EncyclopediaAdminStore(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ID provider is required");
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(Objects.requireNonNull(dataSource, "dataSource is required"));
        try {
            factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/EncyclopediaAdminMapper.xml"));
            var factory = Objects.requireNonNull(factoryBean.getObject());
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            factory.getConfiguration().setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            this.template = new SqlSessionTemplate(factory);
        } catch (Exception failure) {
            throw new IllegalStateException("encyclopedia admin mapper initialization failed", failure);
        }
        var manager = new DataSourceTransactionManager(dataSource);
        read = tx(manager, TransactionDefinition.ISOLATION_REPEATABLE_READ, true);
        admission = tx(manager, TransactionDefinition.ISOLATION_READ_COMMITTED, false);
        execution = tx(manager, TransactionDefinition.ISOLATION_READ_COMMITTED, false);
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
        return run(work, execution, true);
    }

    public Binding admit(String requestKey, CommunityAdminCanonicalParams.Canonical canonical) {
        return run(m -> {
            EncyclopediaAdminBindingEntity row = m.selectBindingForUpdate(requestKey);
            if (row != null) return binding(row);
            try {
                if (m.insertBinding(nextId(), requestKey, canonical.version(), canonical.sha256(),
                        canonical.bytes()) != 1) {
                    unavailable("idempotency reservation failed");
                }
            } catch (DuplicateKeyException duplicate) {
                return binding(require(m, requestKey));
            }
            return binding(require(m, requestKey));
        }, admission, true);
    }

    public static EncyclopediaAdminBindingEntity require(EncyclopediaAdminMapper m, String requestKey) {
        EncyclopediaAdminBindingEntity row = m.selectBindingForUpdate(requestKey);
        if (row == null) unavailable("idempotency binding is missing");
        return row;
    }

    public static Binding binding(EncyclopediaAdminBindingEntity row) {
        return new Binding(row.getStatus(), row.getReceiptJson(), row.getParamsSha256(),
                row.getParamsCanonical());
    }

    public static void same(Binding b, CommunityAdminCanonicalParams.Canonical c) {
        if (b.paramsCanonical() == null || b.paramsSha256() == null
                || !b.paramsSha256().equals(c.sha256())
                || !java.util.Arrays.equals(b.paramsCanonical(), c.bytes())) {
            throw conflict();
        }
    }

    private <T> T run(Work<T> work, TransactionTemplate transaction, boolean commitUnknown) {
        try {
            return transaction.execute(status -> work.run(template.getMapper(EncyclopediaAdminMapper.class)));
        } catch (CommitUnknown rethrown) {
            throw rethrown;
        } catch (RuntimeException failure) {
            if (commitUnknown && template != null && isCommitBoundary(failure)) {
                throw new CommitUnknown(failure);
            }
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
        throw new com.petplatform.common.ApiException(
                com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }

    public static com.petplatform.common.ApiException conflict() {
        return new com.petplatform.common.ApiException(
                com.petplatform.common.CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                "encyclopedia admin idempotency conflict");
    }
}
