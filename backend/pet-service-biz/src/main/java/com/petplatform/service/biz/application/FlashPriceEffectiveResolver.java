package com.petplatform.service.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.service.biz.infrastructure.persistence.FlashPriceStore;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.FlashPriceRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Contract 77 §5 effective-flash resolution shared by the C read side (snapshot/page/detail) and
 * the booking facts kernel. Enabled means a real store; the DISABLED constant answers LIST
 * without touching the flash tables, which is exactly the switch-off contract (zero impact).
 *
 * <p>The lookup joins the CALLER's transaction (read snapshot or the order guard transaction);
 * it never opens its own. A live row whose flash price no longer beats the service price answers
 * LIST (77 §5: never block the service read); more than one live row is damaged facts and fails
 * closed as 503.
 */
public final class FlashPriceEffectiveResolver {

    /** priceSource LIST view: every flash field null (77 §5). */
    public record Effective(
            String priceSource,
            BigDecimal flashPrice,
            BigDecimal listPrice,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            Integer remaining,
            String flashPriceId) {
        static final Effective LIST_VIEW = new Effective("LIST", null, null, null, null, null, null);
    }

    public static final FlashPriceEffectiveResolver DISABLED =
            new FlashPriceEffectiveResolver(null, null);

    private final FlashPriceStore store;
    private final Clock clock;

    public FlashPriceEffectiveResolver(FlashPriceStore store, Clock clock) {
        this.store = store;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Composition entry for the boot assembler; no persistence type crosses the module edge. */
    public FlashPriceEffectiveResolver(
            DataSource dataSource, SnowflakeIdGenerator ids, Clock clock) {
        this(new FlashPriceStore(dataSource, ids), clock);
    }

    public boolean enabled() {
        return store != null;
    }

    /**
     * @param serviceId numeric service id
     * @param currentPrice the locked/read service price of the same transaction
     */
    public Effective resolve(long serviceId, BigDecimal currentPrice) {
        if (store == null) return Effective.LIST_VIEW;
        Objects.requireNonNull(currentPrice, "currentPrice");
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<FlashPriceRow> rows = store.join(mapper -> mapper.selectEffectiveFlash(serviceId, now));
        if (rows.isEmpty()) return Effective.LIST_VIEW;
        if (rows.size() > 1) unavailable("active flash-price windows overlap");
        FlashPriceRow row = rows.get(0);
        FlashPriceSupport.requireRowSanity(row);
        if (row.flashPrice == null || row.flashPrice.signum() <= 0
                || row.flashPrice.compareTo(currentPrice) >= 0) {
            // Live window but no longer a discount: LIST, no flash fields (77 §5).
            return Effective.LIST_VIEW;
        }
        Integer remaining = row.totalQuota == null
                ? null
                : Math.max(0, row.totalQuota - row.soldCount);
        return new Effective("FLASH", row.flashPrice, currentPrice,
                row.startAt.atOffset(ZoneOffset.UTC), row.endAt.atOffset(ZoneOffset.UTC),
                remaining, Long.toUnsignedString(row.id));
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
