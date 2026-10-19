package com.petplatform.user.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.merchant.api.query.MerchantStoreDisplayQuery;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.user.api.command.FavoriteCommandApi.AddOutcome;
import com.petplatform.user.api.command.FavoriteCommandApi.CancelOutcome;
import com.petplatform.user.api.command.FavoriteCommands.Add;
import com.petplatform.user.api.command.FavoriteCommands.Cancel;
import com.petplatform.user.api.command.FootprintCommandApi.DeleteOutcome;
import com.petplatform.user.api.command.FootprintCommandApi.RecordOutcome;
import com.petplatform.user.api.command.FootprintCommands.Delete;
import com.petplatform.user.api.command.FootprintCommands.Record;
import com.petplatform.user.api.dto.FavoriteItemDTO;
import com.petplatform.user.api.dto.FootprintItemDTO;
import com.petplatform.user.api.query.FavoriteQueryApi.FavoriteStatus;
import com.petplatform.user.api.query.FavoriteQueryApi.FavoriteStatusQuery;
import com.petplatform.user.api.query.FavoriteQueryApi.MyFavoriteListQuery;
import com.petplatform.user.api.query.FootprintQueryApi.MyFootprintListQuery;
import com.petplatform.user.biz.apiimpl.FavoriteApiImpl;
import com.petplatform.user.biz.apiimpl.FootprintApiImpl;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract 58 kernel acceptance over isolated real MySQL (SQL06 user tables + SQL14): requestId
 * replay (supplement 23 §5), business duplicate collapse onto the existing favorite row,
 * cancel-as-delete idempotency, the (user, target) footprint upsert dedup with the fixed
 * last_viewed_at DESC ordering, per-subject isolation, and target admission through the C
 * catalog visibility APIs (hidden → uniform NOT_FOUND; facts failure → fail-closed 503).
 */
class FavoriteFootprintMySqlTest {

    private static final long OWNER = 9_400_000_000_000_801L;
    private static final long OTHER = 9_400_000_000_000_802L;
    private static final long FROZEN = 9_400_000_000_000_803L;
    private static final long SERVICE_1 = 9_400_000_000_000_811L;
    private static final long SERVICE_HIDDEN = 9_400_000_000_000_812L;
    private static final long SERVICE_BROKEN = 9_400_000_000_000_813L;
    private static final long STORE_1 = 9_400_000_000_000_821L;
    private static final long STORE_2 = 9_400_000_000_000_822L;

    private final AtomicLong ids = new AtomicLong(9_400_000_000_100_000L);
    private MySqlUserDomainTestDatabase database;
    private FavoriteApiImpl favorites;
    private FootprintApiImpl footprints;

    @BeforeEach
    void start() throws Exception {
        database = new MySqlUserDomainTestDatabase();
        database.seedUser(OWNER, "13800000001", "ACTIVE");
        database.seedUser(OTHER, "13800000002", "ACTIVE");
        database.seedUser(FROZEN, "13800000003", "FROZEN");
        FakeCatalog catalog = new FakeCatalog();
        favorites = new FavoriteApiImpl(database.dataSource(), ids::incrementAndGet,
                catalog, catalog);
        footprints = new FootprintApiImpl(database.dataSource(), ids::incrementAndGet,
                catalog, catalog);
    }

    @AfterEach
    void stop() {
        if (database != null) {
            database.close();
        }
    }

    /** The two catalog *-api surfaces the kernel is allowed to see; nothing else. */
    private static final class FakeCatalog implements ServiceQueryApi, MerchantStoreDisplayApi {
        @Override
        public ServiceSnapshotDTO getVisibleService(ServiceSnapshotQuery query) {
            long id = Long.parseLong(query.serviceId());
            if (id == SERVICE_BROKEN) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "facts down");
            }
            if (id == SERVICE_HIDDEN) {
                throw new ApiException("SERVICE_NOT_FOUND", "service resource not found");
            }
            return new ServiceSnapshotDTO(query.serviceId(), "9400000000000831",
                    "9400000000000821", "宠物洗澡美容全套", "9400000000000841", "宠物美容",
                    new BigDecimal("80.00"), 60, null, null, null, "https://cover/s1.jpg",
                    null);
        }

        @Override
        public ServiceSnapshotDTO getServiceSnapshot(ServiceSnapshotQuery query) {
            throw new UnsupportedOperationException("not part of the Contract 58 slice");
        }

        @Override
        public com.petplatform.service.api.dto.ServiceSnapshotPageDTO searchVisibleServices(
                com.petplatform.service.api.query.CatalogServiceSearchQuery query) {
            throw new UnsupportedOperationException("not part of the Contract 58 slice");
        }

        @Override
        public com.petplatform.service.api.dto.ServiceBookabilityDTO checkBookable(
                com.petplatform.service.api.query.ServiceBookabilityQuery query) {
            throw new UnsupportedOperationException("not part of the Contract 58 slice");
        }

        @Override
        public com.petplatform.service.api.dto.ServiceSnapshotPageDTO getStoreServiceSnapshots(
                com.petplatform.service.api.query.StoreServiceSnapshotQuery query) {
            throw new UnsupportedOperationException("not part of the Contract 58 slice");
        }

        @Override
        public MerchantStoreDisplayDTO getDisplayStore(MerchantStoreDisplayQuery query) {
            long id = Long.parseLong(query.storeId());
            if (id == STORE_2) {
                throw new ApiException(CommonApiCodes.NOT_FOUND, "store resource not found");
            }
            return new MerchantStoreDisplayDTO("9400000000000831", query.storeId(),
                    "萌宠之家", "萌宠之家宠物店", "上海市浦东新区世纪大道100号", "121.5", "31.2",
                    "021****1234", "shanghai");
        }

        @Override
        public com.petplatform.merchant.api.dto.MerchantStoreDisplayPageDTO pageDisplayStores(
                com.petplatform.merchant.api.query.MerchantStoreDisplayPageQuery query) {
            throw new UnsupportedOperationException("not part of the Contract 58 slice");
        }
    }

    private static CommandContext writeContext(long userId) {
        return new CommandContext(UUID.randomUUID().toString(), "trace", OperatorType.USER,
                Long.toString(userId), "MINIAPP");
    }

    private static CommandContext writeContext(long userId, String requestId) {
        return new CommandContext(requestId, "trace", OperatorType.USER,
                Long.toString(userId), "MINIAPP");
    }

    private static QueryContext readContext(long userId) {
        return new QueryContext("trace", OperatorType.USER, Long.toString(userId));
    }

    // --- favorites ---

    @Test
    void addCreatesThenReplaysThenCollapsesBusinessDuplicates() {
        String requestId = UUID.randomUUID().toString();
        AddOutcome first = favorites.addFavorite(
                new Add("SERVICE", Long.toString(SERVICE_1), writeContext(OWNER, requestId)));
        assertTrue(first.created());
        assertTrue(first.receipt().created());
        assertNotNull(first.receipt().favoriteId());

        // Same requestId, same params: the first receipt replays and the answer is 200-class.
        AddOutcome replay = favorites.addFavorite(
                new Add("SERVICE", Long.toString(SERVICE_1), writeContext(OWNER, requestId)));
        assertFalse(replay.created());
        assertEquals(first.receipt().favoriteId(), replay.receipt().favoriteId());
        assertEquals(first.receipt().favoritedAt(), replay.receipt().favoritedAt());

        // Different requestId for the same target: business duplicate collapses (created=false).
        AddOutcome duplicate = favorites.addFavorite(
                new Add("SERVICE", Long.toString(SERVICE_1), writeContext(OWNER)));
        assertFalse(duplicate.created());
        assertEquals(first.receipt().favoriteId(), duplicate.receipt().favoriteId());
        PageResult<FavoriteItemDTO> page =
                favorites.listMyFavorites(new MyFavoriteListQuery(null, 1, 20, readContext(OWNER)));
        assertEquals(1, page.total());
    }

    @Test
    void sameRequestIdWithDifferentParametersIsIdempotencyConflict() {
        favorites.addFavorite(new Add("SERVICE", Long.toString(SERVICE_1), writeContext(OWNER)));
        String reused = UUID.randomUUID().toString();
        favorites.addFavorite(new Add("SERVICE", Long.toString(SERVICE_1),
                writeContext(OWNER, reused)));
        ApiException conflict = assertThrows(ApiException.class, () -> favorites.addFavorite(
                new Add("STORE", Long.toString(STORE_1), writeContext(OWNER, reused))));
        assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());
    }

    @Test
    void hiddenOrMissingTargetsAreUniformNotFoundAndFactsFailuresStay503() {
        ApiException hidden = assertThrows(ApiException.class, () -> favorites.addFavorite(
                new Add("SERVICE", Long.toString(SERVICE_HIDDEN), writeContext(OWNER))));
        assertEquals("SERVICE_NOT_FOUND", hidden.code());
        ApiException absentStore = assertThrows(ApiException.class, () -> favorites.addFavorite(
                new Add("STORE", Long.toString(STORE_2), writeContext(OWNER))));
        assertEquals("STORE_NOT_FOUND", absentStore.code());
        ApiException factsDown = assertThrows(ApiException.class, () -> favorites.addFavorite(
                new Add("SERVICE", Long.toString(SERVICE_BROKEN), writeContext(OWNER))));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, factsDown.code());
        ApiException badType = assertThrows(ApiException.class, () -> favorites.addFavorite(
                new Add("POST", Long.toString(SERVICE_1), writeContext(OWNER))));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, badType.code());
    }

    @Test
    void cancelDeletesAndSucceedsWhenAbsent() {
        favorites.addFavorite(new Add("STORE", Long.toString(STORE_1), writeContext(OWNER)));
        CancelOutcome removed = favorites.cancelFavorite(
                new Cancel("STORE", Long.toString(STORE_1), writeContext(OWNER)));
        assertTrue(removed.receipt().removed());
        CancelOutcome absent = favorites.cancelFavorite(
                new Cancel("STORE", Long.toString(STORE_1), writeContext(OWNER)));
        assertFalse(absent.receipt().removed());
        FavoriteStatus status = favorites.favoriteStatus(new FavoriteStatusQuery(
                "STORE", Long.toString(STORE_1), readContext(OWNER)));
        assertNull(status.favoriteId());
    }

    @Test
    void listOrdersByCreatedAtDescWithFilterAndProjection() {
        favorites.addFavorite(new Add("SERVICE", Long.toString(SERVICE_1), writeContext(OWNER)));
        favorites.addFavorite(new Add("STORE", Long.toString(STORE_1), writeContext(OWNER)));
        // Other user's favorites never surface.
        favorites.addFavorite(new Add("SERVICE", Long.toString(SERVICE_1), writeContext(OTHER)));

        PageResult<FavoriteItemDTO> all =
                favorites.listMyFavorites(new MyFavoriteListQuery(null, 1, 20, readContext(OWNER)));
        assertEquals(2, all.total());
        assertEquals("STORE", all.items().get(0).targetType());
        FavoriteItemDTO service = all.items().get(1);
        assertEquals("宠物洗澡美容全套", service.display().title());
        assertEquals("宠物美容", service.display().categoryLabel());
        assertEquals("80.00", service.display().salePrice());
        assertEquals("9400000000000821", service.display().storeId());
        assertTrue(service.display().targetAvailable());

        PageResult<FavoriteItemDTO> services = favorites.listMyFavorites(
                new MyFavoriteListQuery("SERVICE", 1, 20, readContext(OWNER)));
        assertEquals(1, services.total());

        // Re-favoriting after cancel takes a fresh row; pagination stays stable.
        favorites.cancelFavorite(new Cancel("STORE", Long.toString(STORE_1), writeContext(OWNER)));
        favorites.addFavorite(new Add("STORE", Long.toString(STORE_1), writeContext(OWNER)));
        PageResult<FavoriteItemDTO> firstPage =
                favorites.listMyFavorites(new MyFavoriteListQuery(null, 1, 1, readContext(OWNER)));
        PageResult<FavoriteItemDTO> secondPage =
                favorites.listMyFavorites(new MyFavoriteListQuery(null, 2, 1, readContext(OWNER)));
        assertEquals("STORE", firstPage.items().get(0).targetType());
        assertEquals("SERVICE", secondPage.items().get(0).targetType());
    }

    @Test
    void hiddenTargetKeepsItsRowWithUnavailableProjection() {
        favorites.addFavorite(new Add("SERVICE", Long.toString(SERVICE_1), writeContext(OWNER)));
        // A target hidden after it was favorited (admission no longer possible): seed the stale
        // row directly — the list must keep it with targetAvailable=false and null display so
        // cancel still works.
        database.jdbc().update(
                "INSERT INTO user_favorite (id,user_id,target_type,target_id,created_at,updated_at)"
                        + " VALUES (?,?, 'STORE', ?, NOW(3), NOW(3))",
                9_400_000_000_200_001L, OWNER, STORE_2);
        List<FavoriteItemDTO> items = favorites.listMyFavorites(
                new MyFavoriteListQuery(null, 1, 20, readContext(OWNER))).items();
        assertEquals(2, items.size());
        FavoriteItemDTO stale = items.stream()
                .filter(row -> "9400000000000822".equals(row.targetId())).findFirst().orElseThrow();
        assertFalse(stale.display().targetAvailable());
        assertNull(stale.display().title());
        // Cancel still removes the stale row.
        CancelOutcome removed = favorites.cancelFavorite(
                new Cancel("STORE", Long.toString(STORE_2), writeContext(OWNER)));
        assertTrue(removed.receipt().removed());
    }

    @Test
    void frozenUserCannotWrite() {
        ApiException frozen = assertThrows(ApiException.class, () -> favorites.addFavorite(
                new Add("SERVICE", Long.toString(SERVICE_1), writeContext(FROZEN))));
        assertEquals("USER_FROZEN", frozen.code());
    }

    // --- footprints ---

    @Test
    void recordFootprintInsertsThenDeduplicatesByRefreshingLastViewedAt() throws Exception {
        RecordOutcome first = footprints.recordFootprint(
                new Record("SERVICE", Long.toString(SERVICE_1), writeContext(OWNER)));
        assertTrue(first.created());
        Thread.sleep(10);
        RecordOutcome again = footprints.recordFootprint(
                new Record("SERVICE", Long.toString(SERVICE_1), writeContext(OWNER)));
        assertFalse(again.created());
        assertEquals(first.receipt().footprintId(), again.receipt().footprintId());
        // The touch must move lastViewedAt forward (>= because of clock granularity).
        assertFalse(again.receipt().lastViewedAt().isBefore(first.receipt().lastViewedAt()));

        PageResult<FootprintItemDTO> page = footprints.listMyFootprints(
                new MyFootprintListQuery(1, 20, readContext(OWNER)));
        assertEquals(1, page.total());
        assertEquals("宠物洗澡美容全套", page.items().get(0).display().title());
    }

    @Test
    void footprintListOrdersByLastViewedDescAndTouchBubblesTheTargetToTheHead() throws Exception {
        footprints.recordFootprint(new Record("SERVICE", Long.toString(SERVICE_1), writeContext(OWNER)));
        Thread.sleep(10);
        footprints.recordFootprint(new Record("STORE", Long.toString(STORE_1), writeContext(OWNER)));
        Thread.sleep(10);
        // Re-view the service: it must bubble back above the store despite the older first view.
        footprints.recordFootprint(new Record("SERVICE", Long.toString(SERVICE_1), writeContext(OWNER)));

        List<FootprintItemDTO> items = footprints.listMyFootprints(
                new MyFootprintListQuery(1, 20, readContext(OWNER))).items();
        assertEquals(2, items.size());
        assertEquals("SERVICE", items.get(0).targetType());
        assertEquals("STORE", items.get(1).targetType());

        // Same millisecond bucket: id DESC is the deterministic tie-break.
        PageResult<FootprintItemDTO> paged = footprints.listMyFootprints(
                new MyFootprintListQuery(1, 1, readContext(OWNER)));
        assertEquals(1, paged.items().size());
        assertEquals(2, paged.total());
    }

    @Test
    void deleteFootprintIsIdempotentAndSubjectScoped() {
        RecordOutcome recorded = footprints.recordFootprint(
                new Record("STORE", Long.toString(STORE_1), writeContext(OWNER)));
        DeleteOutcome deleted = footprints.deleteFootprint(
                new Delete(recorded.receipt().footprintId(), writeContext(OWNER)));
        assertTrue(deleted.receipt().deleted());
        DeleteOutcome again = footprints.deleteFootprint(
                new Delete(recorded.receipt().footprintId(), writeContext(OWNER)));
        assertFalse(again.receipt().deleted());
        assertEquals(0, footprints.listMyFootprints(
                new MyFootprintListQuery(1, 20, readContext(OWNER))).total());
    }

    @Test
    void footprintTargetsFollowTheSameAdmissionRules() {
        ApiException hidden = assertThrows(ApiException.class, () -> footprints.recordFootprint(
                new Record("SERVICE", Long.toString(SERVICE_HIDDEN), writeContext(OWNER))));
        assertEquals("SERVICE_NOT_FOUND", hidden.code());
        ApiException factsDown = assertThrows(ApiException.class, () -> footprints.recordFootprint(
                new Record("SERVICE", Long.toString(SERVICE_BROKEN), writeContext(OWNER))));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, factsDown.code());
    }

    @Test
    void subjectsNeverSeeEachOthersRows() {
        favorites.addFavorite(new Add("SERVICE", Long.toString(SERVICE_1), writeContext(OWNER)));
        footprints.recordFootprint(new Record("SERVICE", Long.toString(SERVICE_1), writeContext(OWNER)));
        assertEquals(0, favorites.listMyFavorites(
                new MyFavoriteListQuery(null, 1, 20, readContext(OTHER))).total());
        assertEquals(0, footprints.listMyFootprints(
                new MyFootprintListQuery(1, 20, readContext(OTHER))).total());
        // The status probe for another subject's favorite id still answers 200/null.
        FavoriteStatus otherStatus = favorites.favoriteStatus(new FavoriteStatusQuery(
                "SERVICE", Long.toString(SERVICE_1), readContext(OTHER)));
        assertNull(otherStatus.favoriteId());
        // And favorite ids across the whole page are unique.
        List<FavoriteItemDTO> rows = favorites.listMyFavorites(
                new MyFavoriteListQuery(null, 1, 20, readContext(OWNER))).items();
        assertEquals(rows.size(), new HashSet<>(rows.stream().map(FavoriteItemDTO::favoriteId).toList()).size());
    }
}
