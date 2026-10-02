package com.petplatform.schedule.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.schedule.biz.application.ScheduleCanonicalParams;
import com.petplatform.schedule.biz.infrastructure.persistence.entity.ScheduleCommandBindingEntity;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleWriteMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction shells for the merchant schedule write side: the supplement-23 admission pair
 * (binding RESERVED in a short READ_COMMITTED transaction) plus ONE top-level local execution
 * transaction on the shared main DataSource where the per-store
 * ScheduleCapacityGuardApi lock, the protected writes, the append-only audit and the first
 * receipt commit together (34号 §2 SCHC-1). Fail-closed: any persistence failure is surfaced
 * as COMMON_DEPENDENCY_UNAVAILABLE, never swallowed as a business no-op.
 */
public final class ScheduleWriteStore {
    private static final System.Logger LOG =
            System.getLogger(ScheduleWriteStore.class.getName());
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    @FunctionalInterface
    public interface Work<T> {
        T apply(ScheduleWriteMapper mapper);
    }

    public record Binding(
            String canonicalVersion, String paramsSha256, byte[] paramsCanonical,
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
            super("schedule command commit acknowledgement is unknown", cause);
        }
    }

    private final SqlSessionTemplate template;
    private final TransactionTemplate admission, execution;
    private final SnowflakeIdGenerator ids;

    public ScheduleWriteStore(DataSource dataSource, SnowflakeIdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ID provider is required");
        this.template = ScheduleMybatis.template(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        admission = tx(manager, TransactionDefinition.ISOLATION_READ_COMMITTED, false);
        execution = tx(manager, TransactionDefinition.ISOLATION_READ_COMMITTED, false);
    }

    public long nextId() {
        long id = ids.nextId();
        if (id <= 0) unavailable("ID provider returned invalid value");
        return id;
    }

    public <T> T execute(Work<T> work) {
        return run(work, execution, true);
    }

    public Binding admit(String requestKey, ScheduleCanonicalParams.Canonical canonical) {
        return run(
                m -> {
                    ScheduleCommandBindingEntity row = m.selectBindingForUpdate(requestKey);
                    if (row != null) {
                        return binding(row);
                    }
                    try {
                        if (m.insertBinding(
                                        nextId(),
                                        requestKey,
                                        canonical.version(),
                                        canonical.sha256(),
                                        canonical.bytes())
                                != 1) unavailable("idempotency reservation failed");
                    } catch (DuplicateKeyException duplicate) {
                        return require(m, requestKey);
                    }
                    return require(m, requestKey);
                },
                admission,
                true);
    }

    public static Binding require(ScheduleWriteMapper m, String requestKey) {
        ScheduleCommandBindingEntity row = m.selectBindingForUpdate(requestKey);
        if (row == null) unavailable("idempotency binding is missing");
        return binding(row);
    }

    public static void same(Binding b, ScheduleCanonicalParams.Canonical c) {
        if (!Objects.equals(b.canonicalVersion(), c.version()))
            unavailable("idempotency canonical version is unsupported");
        if (!Objects.equals(b.paramsSha256(), c.sha256())
                || !Arrays.equals(b.paramsCanonical(), c.bytes()))
            throw new ApiException(
                    CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    "requestId is bound to different parameters");
    }

    private static Binding binding(ScheduleCommandBindingEntity r) {
        if (r.getId() == null
                || r.getId() <= 0
                || r.getCanonicalVersion() == null
                || r.getParamsSha256() == null
                || r.getParamsCanonical() == null
                || r.getStatus() == null
                || !SHA256.matcher(r.getParamsSha256()).matches()
                || !ScheduleCanonicalParams.sha256Hex(r.getParamsCanonical())
                        .equals(r.getParamsSha256())
                || (!"RESERVED".equals(r.getStatus()) && !"SUCCEEDED".equals(r.getStatus()))
                || ("SUCCEEDED".equals(r.getStatus()) && r.getReceiptJson() == null))
            unavailable("idempotency binding is damaged");
        return new Binding(
                r.getCanonicalVersion(),
                r.getParamsSha256(),
                r.getParamsCanonical(),
                r.getStatus(),
                r.getReceiptJson());
    }

    private <T> T run(Work<T> w, TransactionTemplate t, boolean commitUnknown) {
        try {
            return t.execute(
                    s -> {
                        ScheduleWriteMapper m = template.getMapper(ScheduleWriteMapper.class);
                        m.setTimeZoneUtc();
                        m.setLockWaitTimeout2Seconds();
                        return w.apply(m);
                    });
        } catch (ApiException e) {
            throw e;
        } catch (PessimisticLockingFailureException busy) {
            throw new ApiException(
                    CommonApiCodes.CONFLICT,
                    "the same resource is busy; retry with the original requestId");
        } catch (TransactionSystemException e) {
            if (commitUnknown) throw new CommitUnknown(e);
            unavailable("schedule command commit is unknown");
            return null;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "schedule write store failure: {0}",
                    e.getClass().getName() + ": " + e.getMessage());
            unavailable("schedule persistence is unavailable");
            return null;
        }
    }

    private static TransactionTemplate tx(
            DataSourceTransactionManager m, int isolation, boolean readOnly) {
        TransactionTemplate t = new TransactionTemplate(m);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.setIsolationLevel(isolation);
        t.setReadOnly(readOnly);
        t.setTimeout(10);
        return t;
    }

    private static void unavailable(String m) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, m);
    }

    /** UTF-8 string form of the supplement-23 logical tuple (fits the 14号 VARCHAR(512) key). */
    public static String requestKey(
            String namespace, String actorType, String actorId, String authorityScope,
            String requestId) {
        String key =
                "request-key-v1|"
                        + namespace.length() + ":" + namespace + "|"
                        + actorType.length() + ":" + actorType + "|"
                        + actorId.length() + ":" + actorId + "|"
                        + authorityScope.length() + ":" + authorityScope + "|"
                        + requestId.length() + ":" + requestId + "|";
        if (key.length() > 512 || key.getBytes(StandardCharsets.UTF_8).length > 512 * 4 / 3)
            throw new IllegalArgumentException("request key exceeds storage boundary");
        for (String part : new String[] {namespace, actorType, actorId, authorityScope, requestId}) {
            if (part == null || part.isBlank())
                throw new IllegalArgumentException("request key component is required");
        }
        return key;
    }
}
