package com.petplatform.coupon.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.command.CouponCommandApi;
import com.petplatform.coupon.api.command.CouponCommandApi.CouponConsumeCommand;
import com.petplatform.coupon.api.command.CouponCommandApi.CouponConsumeResult;
import com.petplatform.coupon.api.command.CouponCommandApi.CouponFreezeCommand;
import com.petplatform.coupon.api.command.CouponCommandApi.CouponFreezeResult;
import com.petplatform.coupon.api.command.CouponCommandApi.CouponReleaseCommand;
import com.petplatform.coupon.api.command.CouponCommandApi.CouponReleaseResult;
import com.petplatform.coupon.biz.domain.service.CouponRule;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponLifecycleStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponLifecycleInstanceEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.mapper.CouponLifecycleMapper;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;

/**
 * Contract 61 coupon lifecycle (CPN-001): freeze/consume/release as guarded CAS mutations that
 * join the caller's ORDER transaction. Every action appends its coupon_ledger row under a
 * deterministic per-order request id (uk_coupon_ledger_request), so a committed action can never
 * be half-repeated; an unexpected duplicate is fail-closed 503 — the caller's transaction rolls
 * back and the durable replay path resolves the original outcome. Authorization: the caller must
 * already hold the store guard (requireHeld), mirror of BookingCouponExposureApiImpl.
 */
public final class CouponCommandApiImpl implements CouponCommandApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final SnowflakeIdGenerator ids;
    private final CouponLifecycleStore store;

    public CouponCommandApiImpl(DataSource source, ScheduleCapacityGuardApi guard,
            SnowflakeIdGenerator ids) {
        this.source = Objects.requireNonNull(source, "source is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.ids = Objects.requireNonNull(ids, "ids is required");
        this.store = CouponLifecycleStore.joining(source);
    }

    @Override public CouponFreezeResult freeze(CouponFreezeCommand command) {
        requireCommand(command == null ? null : command.context());
        long orderId = id(command.orderId(), "orderId");
        long couponId = id(command.couponInstanceId(), "couponInstanceId");
        long userId = id(command.userId(), "userId");
        String storeId = requireStore(command.storeId());
        BigDecimal orderAmount = amount(command.orderAmount(), "orderAmount");
        LocalDateTime deadline = utc(command.freezeExpireAt(), "freezeExpireAt");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        guard.requireHeld(storeId, source);
        return store.work(mapper -> {
            // Defense in depth: one order freezes at most one coupon ever.
            if (mapper.countInstancesByOrder(orderId) != 0) {
                throw unavailable("coupon freeze: order already binds a coupon");
            }
            CouponLifecycleInstanceEntity row = mapper.lockInstance(couponId);
            if (row == null || row.getUserId() == null || row.getUserId() != userId
                    || !"AVAILABLE".equals(row.getStatus())) {
                throw notAvailable();
            }
            // Eligibility per contract 61 section 3: instance and template still valid, with a
            // usable frozen-vocabulary rule (amountOff positive, threshold met, payable > 0).
            if (row.getExpireAt() == null || !row.getExpireAt().isAfter(now)) throw notAvailable();
            if (!"ACTIVE".equals(row.getTemplateStatus())
                    || row.getTemplateValidStartAt() == null
                    || row.getTemplateValidEndAt() == null
                    || row.getTemplateValidStartAt().isAfter(now)
                    || !row.getTemplateValidEndAt().isAfter(now)) {
                throw notAvailable();
            }
            CouponRule rule = CouponRule.parse(row.getRuleJson());
            if (rule == null) throw notAvailable();
            BigDecimal discount = rule.amountOff();
            BigDecimal threshold = rule.thresholdAmount();
            if (threshold != null && orderAmount.compareTo(threshold) < 0) throw notAvailable();
            // A payable order must stay positive: the 40/41 channel cannot settle zero amounts.
            if (discount.compareTo(orderAmount) >= 0) throw notAvailable();
            if (mapper.freezeInstance(couponId, userId, orderId, deadline, now) != 1) {
                // Lost the row-level race: another order froze this instance first (CPN-004).
                throw notAvailable();
            }
            ledger(mapper, couponId, userId, orderId, "FREEZE", "AVAILABLE", "FROZEN",
                    "CPN:FREEZE:" + orderId, "booking freeze", now);
            return new CouponFreezeResult(IDS.toApi(couponId), discount);
        });
    }

    @Override public CouponConsumeResult consume(CouponConsumeCommand command) {
        requireCommand(command == null ? null : command.context());
        long orderId = id(command.orderId(), "orderId");
        String storeId = requireStore(command.storeId());
        LocalDateTime usedAt = utc(command.consumedAt(), "consumedAt");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        guard.requireHeld(storeId, source);
        return store.work(mapper -> {
            CouponLifecycleInstanceEntity row = requireOrderBinding(mapper, orderId);
            if (!"FROZEN".equals(row.getStatus())) {
                throw unavailable("coupon consume: order binding is not frozen");
            }
            long couponId = row.getId();
            if (mapper.consumeInstance(couponId, orderId, usedAt, now) != 1) {
                throw unavailable("coupon consume compare-and-set failed");
            }
            ledger(mapper, couponId, row.getUserId(), orderId, "CONSUME", "FROZEN", "USED",
                    "CPN:CONSUME:" + orderId, "payment success", now);
            return new CouponConsumeResult(IDS.toApi(couponId));
        });
    }

    @Override public CouponReleaseResult release(CouponReleaseCommand command) {
        requireCommand(command == null ? null : command.context());
        long orderId = id(command.orderId(), "orderId");
        String storeId = requireStore(command.storeId());
        LocalDateTime closedAt = utc(command.closedAt(), "closedAt");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        guard.requireHeld(storeId, source);
        return store.work(mapper -> {
            CouponLifecycleInstanceEntity row = requireOrderBinding(mapper, orderId);
            if (!"FROZEN".equals(row.getStatus())) {
                throw unavailable("coupon release: order binding is not frozen");
            }
            long couponId = row.getId();
            if (mapper.releaseInstance(couponId, orderId, closedAt, now) != 1) {
                throw unavailable("coupon release compare-and-set failed");
            }
            String landing = mapper.selectStatus(couponId);
            if (!"AVAILABLE".equals(landing) && !"EXPIRED".equals(landing)) {
                throw unavailable("coupon release landing status is unknown");
            }
            ledger(mapper, couponId, row.getUserId(), orderId, "RELEASE", "FROZEN", landing,
                    "CPN:RELEASE:" + orderId, "payment timeout close", now);
            return new CouponReleaseResult(IDS.toApi(couponId), landing);
        });
    }

    /** Shared freeze readback: exactly one binding row for the order, else the ledger diverged. */
    private static CouponLifecycleInstanceEntity requireOrderBinding(CouponLifecycleMapper mapper,
            long orderId) {
        CouponLifecycleInstanceEntity row = mapper.lockInstanceByOrder(orderId);
        if (row == null || row.getId() == null || row.getUserId() == null) {
            throw unavailable("coupon lifecycle: order binds no coupon");
        }
        return row;
    }

    private void ledger(CouponLifecycleMapper mapper, long couponId, long userId, long orderId,
            String action, String fromStatus, String toStatus, String requestId, String remark,
            LocalDateTime now) {
        long ledgerId = ids.nextId();
        if (ledgerId <= 0) throw unavailable("coupon ledger ID provider unavailable");
        try {
            mapper.insertLedger(ledgerId, couponId, userId, orderId, action, fromStatus, toStatus,
                    requestId, remark, now);
        } catch (DuplicateKeyException replayed) {
            // Unreachable behind the caller's one-shot transaction discipline; never guess.
            throw unavailable("coupon ledger request already exists: " + requestId);
        }
    }

    /**
     * The in-process authorization is the held store guard; the context must still carry a real
     * internal requestId so the action is attributable in traces.
     */
    private static void requireCommand(CommandContext context) {
        if (context == null) throw unavailable("coupon command context is required");
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException malformed) {
            throw unavailable("coupon command requestId is invalid");
        }
    }

    private static ApiException notAvailable() {
        return new ApiException("COUPON_NOT_AVAILABLE", "优惠券不可用");
    }

    private static ApiException unavailable(String detail) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, detail);
    }

    private static long id(String value, String field) {
        try {
            return IDS.fromApi(Objects.requireNonNull(value, field + " is required"));
        } catch (RuntimeException malformed) {
            throw unavailable("coupon " + field + " is invalid");
        }
    }

    private static String requireStore(String storeId) {
        if (storeId == null || storeId.isBlank()) throw unavailable("coupon storeId is required");
        id(storeId, "storeId");
        return storeId;
    }

    private static BigDecimal amount(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0 || value.scale() > 2) {
            throw unavailable("coupon " + field + " is invalid");
        }
        return value;
    }

    private static LocalDateTime utc(OffsetDateTime value, String field) {
        if (value == null) throw unavailable("coupon " + field + " is required");
        try {
            PublicContractChecks.requireMillisecondPrecision(value);
        } catch (IllegalArgumentException malformed) {
            throw unavailable("coupon " + field + " precision is invalid");
        }
        return LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }
}
