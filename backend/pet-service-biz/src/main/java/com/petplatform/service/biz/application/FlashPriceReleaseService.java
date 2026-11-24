package com.petplatform.service.biz.application;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.service.biz.infrastructure.persistence.FlashPriceStore;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.ReservationRow;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Contract 77 §6.3 expiry release round (durable task FLASH_PRICE_RELEASE_EXPIRED, owner=SERVICE).
 * One round scans at most {@value #BATCH} RESERVED rows with expire_at &lt; now-5min, releases
 * each with a row lock + version CAS and gives the quota back (sold_count-1), then enqueues the
 * deterministic next-minute successor inside the SAME transaction. It never reads order tables:
 * both domains settle on their own authoritative rows with the 5-minute tolerance.
 */
public final class FlashPriceReleaseService {

    private static final System.Logger LOG =
            System.getLogger(FlashPriceReleaseService.class.getName());

    public static final String TASK_TYPE = "FLASH_PRICE_RELEASE_EXPIRED";
    public static final String OWNER_MODULE = "SERVICE";
    public static final String RETRY_POLICY = "SVC_FLASH_RELEASE";
    public static final int MAX_RETRIES = 3;
    public static final int BATCH = 500;
    public static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final DateTimeFormatter SLOT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC);

    private final FlashPriceStore store;
    private final JdbcAsyncTaskSubmitter tasks;
    private final Clock clock;

    public FlashPriceReleaseService(FlashPriceStore store, JdbcAsyncTaskSubmitter tasks, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.tasks = Objects.requireNonNull(tasks, "task submitter is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Composition entry for the boot assembler; no persistence type crosses the module edge. */
    public FlashPriceReleaseService(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            JdbcAsyncTaskSubmitter tasks,
            Clock clock) {
        this(new FlashPriceStore(dataSource, ids), tasks, clock);
    }

    /** Deterministic per-minute task key: handler and watchdog converge on the same successor. */
    public static String taskKey(OffsetDateTime slot) {
        return "svc-flash-release:" + SLOT_FORMAT.format(slot);
    }

    /** Deterministic payload JSON bound to the slot (byte-identical from every enqueuer). */
    public static String payloadJson(OffsetDateTime slot) {
        return "{\"slot\":\"" + slot.withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES) + "\"}";
    }

    /** Next whole-minute slot strictly after the given instant. */
    public static OffsetDateTime nextSlot(Clock clock) {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        return now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusMinutes(1);
    }

    /**
     * Enqueues the round for {@code slot} on the caller's business transaction. Content (key,
     * payload, availableAt) is fully deterministic, so racing enqueuers collapse onto one row.
     */
    public void enqueueRound(OffsetDateTime slot) {
        store.execute(mapper -> {
            mapper.setTimeZoneUtc();
            tasks.enqueueAt(
                    taskKey(slot),
                    OWNER_MODULE,
                    TASK_TYPE,
                    "SERVICE_FLASH_RELEASE",
                    slot.toInstant().toEpochMilli() / 60_000L,
                    null,
                    payloadJson(slot),
                    MAX_RETRIES,
                    RETRY_POLICY,
                    slot);
            return null;
        });
    }

    /** Releases up to {@value #BATCH} expired reservations and schedules the successor round. */
    public int runRound() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int released = store.execute(mapper -> {
            mapper.setTimeZoneUtc();
            List<ReservationRow> expired =
                    mapper.selectExpiredReservations(now.minus(TOLERANCE), BATCH);
            int count = 0;
            for (ReservationRow row : expired) {
                if (row.id == null) continue;
                ReservationRow locked = mapper.selectReservationByIdForUpdate(row.id);
                if (locked == null || !"RESERVED".equals(locked.status)) continue;
                if (mapper.releaseReservation(locked.id, locked.version, now) != 1) continue;
                if (locked.flashPriceId != null
                        && mapper.decrementSoldCount(locked.flashPriceId, now) != 1) {
                    // The counter floor (sold_count>0) has nothing to give back: the release CAS
                    // already succeeded, so the ledger stays authoritative and the round moves
                    // on instead of letting one damaged row block every later release.
                    LOG.log(System.Logger.Level.WARNING,
                            "flash-price sold_count floor reached while releasing reservation {0}",
                            Long.toUnsignedString(locked.id));
                }
                count++;
            }
            OffsetDateTime slot = nextSlot(clock);
            tasks.enqueueAt(
                    taskKey(slot),
                    OWNER_MODULE,
                    TASK_TYPE,
                    "SERVICE_FLASH_RELEASE",
                    slot.toInstant().toEpochMilli() / 60_000L,
                    null,
                    payloadJson(slot),
                    MAX_RETRIES,
                    RETRY_POLICY,
                    slot);
            return count;
        });
        return released;
    }
}
