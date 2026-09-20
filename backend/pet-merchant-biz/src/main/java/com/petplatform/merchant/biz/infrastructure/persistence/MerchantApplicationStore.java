package com.petplatform.merchant.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.biz.application.MerchantCanonicalParams;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantCommandBindingEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantApplicationMapper;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class MerchantApplicationStore {
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  @FunctionalInterface
  public interface Work<T> {
    T apply(MerchantApplicationMapper mapper);
  }

  public record Binding(
      String canonicalVersion,
      String paramsSha256,
      byte[] paramsCanonical,
      String status,
      String receiptJson) {
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
      super("application transaction commit acknowledgement is unknown", cause);
    }
  }

  private final SqlSessionTemplate template;
  private final TransactionTemplate read, admission, execution;
  private final SnowflakeIdGenerator ids;
  private final DataSource dataSource;

  public MerchantApplicationStore(DataSource dataSource, SnowflakeIdGenerator ids) {
    this.ids = Objects.requireNonNull(ids, "ID provider is required");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
    template = MerchantMybatis.template(dataSource);
    var manager = new DataSourceTransactionManager(dataSource);
    read = tx(manager, TransactionDefinition.ISOLATION_REPEATABLE_READ, true);
    admission = tx(manager, TransactionDefinition.ISOLATION_READ_COMMITTED, false);
    execution = tx(manager, TransactionDefinition.ISOLATION_READ_COMMITTED, false);
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

  public <T> T joinCurrentTransaction(Work<T> work) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.hasResource(dataSource))
      unavailable("application facts require the caller's merchant transaction");
    try {
      return work.apply(template.getMapper(MerchantApplicationMapper.class));
    } catch (ApiException known) {
      throw known;
    } catch (RuntimeException failure) {
      unavailable("application facts are unavailable");
      return null;
    }
  }

  public Binding admit(byte[] key, MerchantCanonicalParams.Canonical canonical, String traceId) {
    return run(
        m -> {
          MerchantCommandBindingEntity row = m.selectBindingForUpdate(key);
          if (row != null) {
            Binding b = binding(row);
            same(b, canonical);
            return b;
          }
          try {
            if (m.insertBinding(
                    nextId(),
                    key,
                    canonical.version(),
                    canonical.sha256(),
                    canonical.bytes(),
                    traceId)
                != 1) unavailable("idempotency reservation failed");
          } catch (DuplicateKeyException duplicate) {
            Binding b = require(m, key);
            same(b, canonical);
            return b;
          }
          return require(m, key);
        },
        admission,
        true);
  }

  public static Binding require(MerchantApplicationMapper m, byte[] key) {
    MerchantCommandBindingEntity row = m.selectBindingForUpdate(key);
    if (row == null) unavailable("idempotency binding is missing");
    return binding(row);
  }

  public static void same(Binding b, MerchantCanonicalParams.Canonical c) {
    if (!Objects.equals(b.canonicalVersion(), c.version()))
      unavailable("idempotency canonical version is unsupported");
    if (!Objects.equals(b.paramsSha256(), c.sha256())
        || !Arrays.equals(b.paramsCanonical(), c.bytes()))
      throw new ApiException(
          CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "requestId is bound to different parameters");
  }

  private static Binding binding(MerchantCommandBindingEntity r) {
    if (r.getId() == null
        || r.getId() <= 0
        || r.getCanonicalVersion() == null
        || r.getParamsSha256() == null
        || r.getParamsCanonical() == null
        || r.getStatus() == null
        || !SHA256.matcher(r.getParamsSha256()).matches()
        || !MerchantCanonicalParams.sha256Hex(r.getParamsCanonical()).equals(r.getParamsSha256())
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
            MerchantApplicationMapper m = template.getMapper(MerchantApplicationMapper.class);
            m.setTimeZoneUtc();
            m.setLockWaitTimeout2Seconds();
            return w.apply(m);
          });
    } catch (ApiException e) {
      throw e;
    } catch (PessimisticLockingFailureException busy) {
      throw new ApiException(
          CommonApiCodes.CONFLICT, "the same resource is busy; retry with the original requestId");
    } catch (TransactionSystemException e) {
      if (commitUnknown) throw new CommitUnknown(e);
      unavailable("idempotency admission commit is unknown");
      return null;
    } catch (RuntimeException e) {
      unavailable("merchant application persistence is unavailable");
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
}
