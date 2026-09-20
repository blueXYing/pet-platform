package com.petplatform.admin.biz.infrastructure.persistence;

import com.petplatform.admin.biz.application.AdminAuthFailure;
import com.petplatform.admin.biz.infrastructure.persistence.mapper.AdminAuthMapper;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Only this Owner's tables, accessed through {@link AdminAuthMapper}. Reads run REPEATABLE READ,
 * writes READ_COMMITTED, each transaction opened with the UTC session statements and a 5-second
 * per-statement timeout; a lost commit acknowledgement still surfaces as {@link CommitUnknown} so
 * the recovery flows can re-read the committed result.
 */
public final class AdminAuthStore {
  @FunctionalInterface
  public interface Work<T> {
    T apply(Tx tx);
  }

  public static final class CommitUnknown extends AdminAuthFailure {
    public CommitUnknown() {
      super(503, "COMMON_DEPENDENCY_UNAVAILABLE");
    }
  }

  /** Transaction-scoped mapper facade; the database clock comes from the same connection. */
  public static final class Tx {
    private final AdminAuthMapper mapper;

    Tx(AdminAuthMapper mapper) {
      this.mapper = mapper;
    }

    public AdminAuthMapper auth() {
      return mapper;
    }

    public Instant now() {
      return mapper.selectUtcClock();
    }
  }

  private final SqlSessionTemplate template;
  private final TransactionTemplate readTransaction;
  private final TransactionTemplate writeTransaction;

  private final TransactionTemplate currentAuthorizationTransaction;
  private final TransactionTemplate isolatedCurrentAuthorizationTransaction;

  public AdminAuthStore(DataSource source) {
    Objects.requireNonNull(source);
    this.template = AdminMybatis.template(source);
    var manager = new DataSourceTransactionManager(source);
    this.readTransaction = new TransactionTemplate(manager);
    readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.writeTransaction = new TransactionTemplate(manager);
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  this.currentAuthorizationTransaction = new TransactionTemplate(manager);
    currentAuthorizationTransaction.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRED);
    currentAuthorizationTransaction.setIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.isolatedCurrentAuthorizationTransaction = new TransactionTemplate(manager);
    isolatedCurrentAuthorizationTransaction.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    isolatedCurrentAuthorizationTransaction.setIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public <T> T read(Work<T> work) {
    return run(work, readTransaction);
  }

  public <T> T write(Work<T> work) {
    return run(work, writeTransaction);
  }

  /**
   * Uses locking current reads. Execute checks join a caller transaction on the same DataSource;
   * result-read checks use an isolated read/write transaction so they remain valid when their
   * caller is read-only.
   */
  public <T> T currentAuthorization(Work<T> work, boolean joinCaller) {
    return run(
        work,
        joinCaller ? currentAuthorizationTransaction : isolatedCurrentAuthorizationTransaction);
  }

  private <T> T run(Work<T> work, TransactionTemplate transaction) {
    try {
      return transaction.execute(
          status -> {
            AdminAuthMapper mapper = template.getMapper(AdminAuthMapper.class);
            mapper.setTimeZoneUtc();
            mapper.setLockWaitTimeout2Seconds();
            return work.apply(new Tx(mapper));
          });
    } catch (AdminAuthFailure failure) {
      throw failure;
    } catch (org.springframework.transaction.TransactionSystemException commitLost) {
      // The work itself did not fail; the commit acknowledgement did (recovery re-reads).
      throw new CommitUnknown();
    } catch (RuntimeException unavailable) {
      throw AdminAuthFailure.unavailable();
    }
  }
}
