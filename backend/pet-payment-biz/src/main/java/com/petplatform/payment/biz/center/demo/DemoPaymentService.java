package com.petplatform.payment.biz.center.demo;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.api.command.PaymentCenterApi;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CenterPayment;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CreateCenterPaymentCommand;
import com.petplatform.payment.biz.center.PaymentCenterBizPolicy;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentDemoStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentDemoStore.DemoRow;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 75 §7 test-only business owner. Creates its own order and asks the payment center to collect it.
 * No HTTP is registered; it exists only to prove the "pay → notify/query → confirm" loop in SIT/tests.
 * The deadline is derived from the stored creation time so a replay of the same requestId binds identically.
 */
public final class DemoPaymentService {
    static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final SnowflakeIdGenerator ids;
    private final PaymentCenterApi center;
    private final PaymentDemoStore store;
    private final TransactionTemplate tx;

    public DemoPaymentService(DataSource source, SnowflakeIdGenerator ids, PaymentCenterApi center) {
        this.ids = Objects.requireNonNull(ids);
        this.center = Objects.requireNonNull(center);
        this.store = new PaymentDemoStore(Objects.requireNonNull(source));
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
    }

    public CenterPayment start(CommandContext context, BigDecimal amount) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
        if (context == null || context.operatorType() != OperatorType.USER)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "DEMO payment requires USER");
        long userId;
        try {
            PublicContractChecks.requireCommandRequestId(context);
            userId = IDS.fromApi(context.operatorId());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "invalid DEMO payment request");
        }
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2)
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "invalid DEMO amount");
        byte[] key = ("payment.demo.start/USER/" + context.operatorId() + "/" + context.requestId())
                .getBytes(StandardCharsets.UTF_8);
        DemoRow row;
        try {
            row = tx.execute(status -> {
                store.mapper.insertDemo(PaymentFoundationStore.values("id", nextId(), "demoNo", nextId(),
                        "key", key, "userId", userId, "amount", amount));
                DemoRow bound = store.mapper.selectDemoByRequestForUpdate(key);
                if (bound == null) throw unavailable();
                if (bound.userId() != userId || bound.amount().compareTo(amount) != 0)
                    throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "DEMO requestId already bound");
                return bound;
            });
        } catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable(); }
        OffsetDateTime expireAt = row.createdAt().atOffset(ZoneOffset.UTC).plus(LIFETIME);
        return center.create(new CreateCenterPaymentCommand(context, PaymentCenterBizPolicy.DEMO,
                Long.toString(row.demoNo()), row.amount(), expireAt));
    }

    private long nextId() {
        long value = ids.nextId();
        if (value <= 0) throw unavailable();
        return value;
    }
    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "DEMO payment unavailable");
    }
}
