package com.petplatform.merchant.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.biz.application.MerchantCanonicalParams;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantCommandBindingEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantAgreementMapper;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Merchant-owned agreement persistence and supplement-23 transaction boundaries. */
public final class MerchantAgreementStore {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    @FunctionalInterface
    public interface Work<T> {
        T apply(MerchantAgreementMapper mapper);
    }

    public record Binding(String canonicalVersion, String paramsSha256, byte[] paramsCanonical,
                          String status, String receiptJson) {
        public Binding {
            paramsCanonical = paramsCanonical == null ? null : paramsCanonical.clone();
        }

        @Override
        public byte[] paramsCanonical() {
            return paramsCanonical == null ? null : paramsCanonical.clone();
        }
    }

    public static final class CommitUnknown extends RuntimeException {
        public CommitUnknown(Throwable cause) {
            super("agreement transaction commit acknowledgement is unknown", cause);
        }
    }

    private final SqlSessionTemplate template;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate admissionTransaction;
    private final TransactionTemplate executionTransaction;
    private final SnowflakeIdGenerator ids;
    private final DataSource dataSource;

    public MerchantAgreementStore(DataSource dataSource) {
        this(dataSource, null);
    }

    public MerchantAgreementStore(DataSource dataSource, SnowflakeIdGenerator ids) {
        Objects.requireNonNull(dataSource, "dataSource is required");
        this.dataSource = dataSource;
        this.template = MerchantMybatis.template(dataSource);
        this.ids = ids;
        var manager = new DataSourceTransactionManager(dataSource);
        this.readTransaction = transaction(manager, TransactionDefinition.ISOLATION_REPEATABLE_READ, true);
        this.admissionTransaction = transaction(manager, TransactionDefinition.ISOLATION_READ_COMMITTED, false);
        this.executionTransaction = transaction(manager, TransactionDefinition.ISOLATION_READ_COMMITTED, false);
    }

    public long nextId() {
        if (ids == null) unavailable("PLAT-002 ID provider is not configured for agreement writes");
        long id = ids.nextId();
        if (id <= 0) unavailable("ID provider returned an invalid value");
        return id;
    }

    public <T> T read(Work<T> work) {
        return run(work, readTransaction, false);
    }

    /**
     * Binds the original protected parameters in an independent transaction. A duplicate request
     * key is locked and compared by both digest and canonical bytes; failed executions never
     * release or overwrite this binding.
     */
    public Binding admit(
            byte[] requestKey,
            MerchantCanonicalParams.Canonical canonical,
            String traceId
    ) {
        return run(mapper -> {
            MerchantCommandBindingEntity found = mapper.selectBindingForUpdate(requestKey);
            if (found != null) {
                Binding existing = binding(found);
                requireSameParams(existing, canonical);
                return existing;
            }
            try {
                int changed = mapper.insertBinding(nextId(), requestKey, canonical.version(),
                        canonical.sha256(), canonical.bytes(), traceId);
                if (changed != 1) unavailable("idempotency reservation failed");
            } catch (DuplicateKeyException duplicate) {
                Binding existing = requireBinding(mapper, requestKey);
                requireSameParams(existing, canonical);
                return existing;
            }
            return requireBinding(mapper, requestKey);
        }, admissionTransaction, true);
    }

    public <T> T execute(Work<T> work) {
        return run(work, executionTransaction, true);
    }

    /** Joins an already active transaction, used by the S2 eligibility composition adapter. */
    public <T> T joinCurrentTransaction(Function<MerchantAgreementMapper, T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            unavailable("agreement facts require an active transaction on the merchant DataSource");
        }
        try {
            return work.apply(template.getMapper(MerchantAgreementMapper.class));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            unavailable("agreement facts are unavailable");
            return null;
        }
    }

    public static Binding requireBinding(MerchantAgreementMapper mapper, byte[] requestKey) {
        MerchantCommandBindingEntity row = mapper.selectBindingForUpdate(requestKey);
        if (row == null) unavailable("idempotency binding is missing or damaged");
        return binding(row);
    }

    private static Binding binding(MerchantCommandBindingEntity row) {
        if (row.getId() == null || row.getId() <= 0
                || row.getCanonicalVersion() == null || row.getParamsSha256() == null
                || row.getParamsCanonical() == null || row.getStatus() == null) {
            unavailable("idempotency binding is missing or damaged");
        }
        if (!SHA256.matcher(row.getParamsSha256()).matches()
                || row.getParamsCanonical().length < 1 || row.getParamsCanonical().length > 65_536) {
            unavailable("idempotency binding parameters are damaged");
        }
        if (!MerchantCanonicalParams.sha256Hex(row.getParamsCanonical()).equals(row.getParamsSha256())) {
            unavailable("idempotency binding digest is damaged");
        }
        if (!"RESERVED".equals(row.getStatus()) && !"SUCCEEDED".equals(row.getStatus())) {
            unavailable("idempotency binding status is unknown");
        }
        if ("SUCCEEDED".equals(row.getStatus()) && row.getReceiptJson() == null) {
            unavailable("successful idempotency receipt is missing");
        }
        return new Binding(row.getCanonicalVersion(), row.getParamsSha256(),
                row.getParamsCanonical(), row.getStatus(), row.getReceiptJson());
    }

    public static void requireSameParams(Binding binding, MerchantCanonicalParams.Canonical canonical) {
        if (!Objects.equals(binding.canonicalVersion(), canonical.version())) {
            unavailable("idempotency canonical version is unsupported");
        }
        if (!Objects.equals(binding.paramsSha256(), canonical.sha256())
                || !Arrays.equals(binding.paramsCanonical(), canonical.bytes())) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    "the requestId is already bound to different parameters");
        }
    }

    private <T> T run(Work<T> work, TransactionTemplate transaction, boolean commitCanBeUnknown) {
        Objects.requireNonNull(work, "work is required");
        try {
            return transaction.execute(status -> {
                MerchantAgreementMapper mapper = template.getMapper(MerchantAgreementMapper.class);
                mapper.setTimeZoneUtc();
                mapper.setLockWaitTimeout2Seconds();
                return work.apply(mapper);
            });
        } catch (ApiException known) {
            throw known;
        } catch (PessimisticLockingFailureException busy) {
            throw new ApiException(CommonApiCodes.CONFLICT,
                    "the same request is busy; retry later with the original requestId");
        } catch (TransactionSystemException commitFailure) {
            if (commitCanBeUnknown) throw new CommitUnknown(commitFailure);
            unavailable("idempotency admission commit is unknown");
            return null;
        } catch (RuntimeException failure) {
            unavailable("agreement persistence is unavailable");
            return null;
        }
    }

    private static TransactionTemplate transaction(
            DataSourceTransactionManager manager,
            int isolation,
            boolean readOnly
    ) {
        TransactionTemplate result = new TransactionTemplate(manager);
        result.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        result.setIsolationLevel(isolation);
        result.setReadOnly(readOnly);
        result.setTimeout(10);
        return result;
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
