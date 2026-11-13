package com.petplatform.schedule.biz.infrastructure.persistence;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleSelectionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** A single read-only RR view of SCH unified slots and original reservation claims for display. */
public final class SelectionReadStore {
    private final SqlSessionTemplate template;
    private final TransactionTemplate transaction;

    public SelectionReadStore(DataSource source) {
        Objects.requireNonNull(source, "source is required");
        template = ScheduleMybatis.template(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(10);
    }

    public <T> T read(Function<ScheduleSelectionMapper, T> work) {
        try {
            return transaction.execute(status -> work.apply(
                    template.getMapper(ScheduleSelectionMapper.class)));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "schedule selection facts are unavailable");
        }
    }

    /** Store-wide unified slots (CCR-SCH-SLOT-001): no service/kind filter on the window table. */
    public List<WindowRow> windows(ScheduleSelectionMapper connection, long storeId,
            LocalDateTime from, LocalDateTime to) {
        return connection.windows(storeId, from, to).stream().map(row -> new WindowRow(
                ScheduleSqlRows.number(row, "id"), ScheduleSqlRows.number(row, "merchant_id"),
                ScheduleSqlRows.number(row, "store_id"),
                ScheduleSqlRows.dateTime(row, "start_at"),
                ScheduleSqlRows.dateTime(row, "end_at"),
                (int) ScheduleSqlRows.number(row, "configured_capacity"),
                ScheduleSqlRows.text(row, "status"), ScheduleSqlRows.number(row, "version"))).toList();
    }

    /** All reservation claims touching the store's slots in range — single-pool occupancy facts. */
    public List<ClaimRow> claims(ScheduleSelectionMapper connection, long storeId,
            LocalDateTime from, LocalDateTime to) {
        return connection.claims(storeId, from, to).stream().map(row -> new ClaimRow(
                ScheduleSqlRows.number(row, "reservation_id"), ScheduleSqlRows.number(row, "merchant_id"),
                ScheduleSqlRows.number(row, "store_id"), ScheduleSqlRows.number(row, "service_id"),
                ScheduleSqlRows.text(row, "fulfillment_type"), ScheduleSqlRows.dateTime(row, "start_at"),
                ScheduleSqlRows.dateTime(row, "end_at"), ScheduleSqlRows.dateTime(row, "pickup_start_at"),
                ScheduleSqlRows.dateTime(row, "return_start_at"), ScheduleSqlRows.text(row, "status"),
                ScheduleSqlRows.nullableNumber(row, "claim_id"), ScheduleSqlRows.nullableNumber(row, "window_id"),
                ScheduleSqlRows.nullableNumber(row, "claim_store_id"),
                ScheduleSqlRows.nullableNumber(row, "claim_service_id"), ScheduleSqlRows.text(row, "claim_kind"),
                ScheduleSqlRows.dateTime(row, "claim_start_at"), ScheduleSqlRows.dateTime(row, "claim_end_at"),
                ScheduleSqlRows.nullableNumber(row, "original_merchant_id"),
                ScheduleSqlRows.nullableNumber(row, "original_store_id"),
                ScheduleSqlRows.dateTime(row, "original_start_at"),
                ScheduleSqlRows.dateTime(row, "original_end_at"),
                ScheduleSqlRows.text(row, "original_status"))).toList();
    }

    /** Claims of this store's slots must have an intact parent and owner. */
    public boolean hasBrokenClaimLinks(ScheduleSelectionMapper connection, long storeId,
            LocalDateTime from, LocalDateTime to) {
        Integer broken = connection.brokenClaimLinks(storeId, from, to);
        return broken == null || broken > 0;
    }

    public record WindowRow(long id, long merchantId, long storeId,
            LocalDateTime start, LocalDateTime end, int configuredCapacity, String status,
            long version) {}

    public record ClaimRow(long reservationId, long merchantId, long storeId, long serviceId,
            String fulfillmentType, LocalDateTime start, LocalDateTime end,
            LocalDateTime pickupStart, LocalDateTime returnStart, String status,
            Long claimId, Long windowId, Long claimStoreId, Long claimServiceId, String claimKind,
            LocalDateTime claimStart, LocalDateTime claimEnd, Long originalMerchantId,
            Long originalStoreId,
            LocalDateTime originalStart, LocalDateTime originalEnd, String originalStatus) {}
}
