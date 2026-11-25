package com.petplatform.service.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.service.api.command.ServiceFlashPriceReservationApi.ReserveCommand;
import com.petplatform.service.api.command.ServiceFlashPriceReservationApi.ReservationResult;
import com.petplatform.service.api.error.ServiceFlashPriceApiCodes;
import com.petplatform.service.biz.infrastructure.persistence.FlashPriceStore;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.FlashPriceRow;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.ReservationRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;

/**
 * Contract 77 §6.1 quota reservation. Runs INSIDE the order-creation transaction: every ledger
 * write joins the caller's DataSource transaction via {@link FlashPriceStore#join} — there is no
 * inner REQUIRES_NEW here, so any failure rolls the reservation away with the order. The flash
 * row is locked FOR UPDATE for the whole check-then-increment sequence; the UNIQUE(order_id)
 * binding makes an orderId replay idempotent.
 */
public final class FlashPriceReservationService {

    private final FlashPriceStore store;
    private final Clock clock;

    public FlashPriceReservationService(FlashPriceStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public ReservationResult reserve(ReserveCommand command) {
        if (command == null) invalid("command is required");
        if (command.context() == null || command.context().requestId() == null
                || command.context().requestId().isBlank()) {
            invalid("command context is required");
        }
        long flashPriceId = numeric(command.flashPriceId(), "flashPriceId");
        long orderId = numeric(command.orderId(), "orderId");
        long userId = numeric(command.userId(), "userId");
        if (command.expireAt() == null) invalid("expireAt is required");
        LocalDateTime expireAt =
                LocalDateTime.ofInstant(command.expireAt().toInstant(), ZoneOffset.UTC);

        return store.join(mapper -> {
            // Same orderId again (order retry): the surviving ledger row decides.
            ReservationRow existing = mapper.selectReservationByOrderId(orderId);
            if (existing != null) {
                return replay(mapper, existing, flashPriceId);
            }
            FlashPriceRow flash = mapper.selectFlashForUpdate(flashPriceId);
            if (flash == null || flash.id == null) {
                throw new ApiException(ServiceFlashPriceApiCodes.FLASH_PRICE_NOT_FOUND,
                        "秒杀记录不存在");
            }
            FlashPriceSupport.requireRowSanity(flash);
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (!Set.of("SCHEDULED", "ACTIVE").contains(flash.status)
                    || now.isBefore(flash.startAt) || !now.isBefore(flash.endAt)) {
                throw new ApiException(ServiceFlashPriceApiCodes.FLASH_PRICE_STATE_NOT_ALLOWED,
                        "秒杀不在可预约的生效窗口内");
            }
            if (flash.totalQuota != null && flash.soldCount >= flash.totalQuota) {
                throw new ApiException(ServiceFlashPriceApiCodes.FLASH_PRICE_QUOTA_EXCEEDED,
                        "秒杀名额已售罄");
            }
            if (flash.perUserLimit != null
                    && mapper.countActiveByUser(flashPriceId, userId) >= flash.perUserLimit) {
                throw new ApiException(ServiceFlashPriceApiCodes.FLASH_PRICE_LIMIT_REACHED,
                        "已达到每人限领数量");
            }
            if (mapper.incrementSoldCount(flashPriceId, flash.version, now) != 1) {
                throw new ApiException(CommonApiCodes.CONFLICT,
                        "秒杀名额状态已变化，请重试原请求");
            }
            mapper.insertReservation(store.nextId(), flashPriceId, orderId, userId,
                    flash.flashPrice, expireAt, now);
            return result(flashPriceId, flash.flashPrice, flash.listPriceSnapshot);
        });
    }

    private static ReservationResult replay(
            com.petplatform.service.biz.infrastructure.persistence.mapper.FlashPriceMapper mapper,
            ReservationRow existing, long flashPriceId) {
        if (existing.flashPriceId != null && existing.flashPriceId == flashPriceId
                && "RESERVED".equals(existing.status)) {
            FlashPriceRow flash = mapper.selectById(flashPriceId);
            return result(flashPriceId, existing.reservedPrice,
                    flash == null ? null : flash.listPriceSnapshot);
        }
        // CONSUMED orders cannot re-create; RELEASED (late expiry) cannot resurrect either.
        throw new ApiException(ServiceFlashPriceApiCodes.FLASH_PRICE_STATE_NOT_ALLOWED,
                "该订单的秒杀预占已失效");
    }

    private static ReservationResult result(
            long flashPriceId, BigDecimal reservedPrice, BigDecimal listPrice) {
        BigDecimal price = reservedPrice.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal list = listPrice == null ? null : listPrice.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal discount = list == null ? BigDecimal.ZERO.setScale(2)
                : list.subtract(price).setScale(2, RoundingMode.UNNECESSARY);
        return new ReservationResult(Long.toUnsignedString(flashPriceId), price, list, discount,
                "RESERVED");
    }

    private static long numeric(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) invalid(field + " is invalid");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException tooLarge) {
            invalid(field + " is too large");
            return 0;
        }
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }
}
