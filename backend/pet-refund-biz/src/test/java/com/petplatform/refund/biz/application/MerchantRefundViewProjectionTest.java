package com.petplatform.refund.biz.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi.DisplayLabel;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.Summary;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.TimelineEntry;
import com.petplatform.refund.biz.application.MerchantRefundApplicationLabels.Labels;
import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundApplicationQueryMapper.Row;
import com.petplatform.user.api.dto.UserBasicDTO;
import com.petplatform.user.api.dto.UserStatus;
import com.petplatform.user.api.query.UserQueryApi;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * CCR-M-REFUND-VIEW-001 (approved 2026-11-06 §3.1/§3.3) projection boundary, offline mocks only.
 * §3.1: one page is one batched user-basics read plus batched ORDER snapshot reads (≤50 distinct
 * orders per call, SYSTEM context, the caller's own DataSource); only nickname + the server mask
 * cross from the basics (never the avatar, never a raw number); absent/blank facts read null and
 * any null/foreign/duplicate/failing answer fails closed (503). §3.3: the timeline is derived only
 * from persisted timestamps, ascending, PENDING rows carry exactly APPLIED+MERCHANT_DEADLINE and
 * decided rows add DECIDED; inconsistent rows fail closed instead of showing a fabricated stage.
 * §3.2 was NOT approved: no reason text component exists anywhere. The authorization order (labels
 * only after the proven OWNER read, refusals without labels) is exercised by the BOOT suites.
 */
class MerchantRefundViewProjectionTest {
    private static final String STORE = "601";
    private static final LocalDateTime T0 = LocalDateTime.parse("2026-11-05T08:15:32.649");
    private final DataSource source = mock(DataSource.class);
    private final OrderAfterSaleFactsApi orders = mock(OrderAfterSaleFactsApi.class);
    private final UserQueryApi users = mock(UserQueryApi.class);
    private final MerchantRefundApplicationLabels labels =
            new MerchantRefundApplicationLabels(source, users, () -> orders);

    private static Row row(long id, long order, long user) {
        Row row = new Row();
        row.id = id; row.applicationNo = id + 1000; row.orderId = order; row.applicantUserId = user;
        row.status = "PENDING_MERCHANT"; row.version = 0L; row.reasonCode = "QA_REASON";
        row.requestedAmount = new BigDecimal("128.00");
        row.createdAt = T0; row.merchantDeadline = T0.plusHours(24);
        return row;
    }
    private static Row decided(String status, LocalDateTime decidedAt) {
        Row row = row(301, 401, 701);
        row.status = status; row.version = 1L; row.decisionId = 9001L; row.decidedAt = decidedAt;
        return row;
    }
    private static UserBasicDTO basic(String id, String masked, String nickname, String avatar) {
        return new UserBasicDTO(id, masked, nickname, UserStatus.ACTIVE, avatar);
    }
    private static List<String> stages(List<TimelineEntry> timeline) {
        return timeline.stream().map(TimelineEntry::stage).toList();
    }
    private static void assertUnavailable(org.junit.jupiter.api.function.Executable action) {
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class, action).code());
    }

    // ---------------------------------------------------------------- §3.1 display facts

    @Test void onePageIsOneBatchedBasicsReadAndOneBatchedSnapshotRead() {
        when(orders.displayLabels(eq(List.of("401", "402", "403")), eq(STORE), any(), same(source))).thenReturn(List.of(
                new DisplayLabel("401", "E2E到店精细洗护", "豆豆", "DOG", "柯基"),
                new DisplayLabel("402", "   ", null, null, null)));
        when(users.listUsers(new UserQueryApi.UserBasicsQuery(List.of("701", "702")))).thenReturn(List.of(
                basic("701", "138****0100", "E2E体验用户", "https://wx.qlogo.cn/alice.png"),
                basic("702", "13800000200", "   ", null)));
        var read = labels.read(List.of(row(301, 401, 701), row(302, 402, 702), row(303, 403, 701)), STORE, "trace");

        assertEquals(new Labels("E2E体验用户", "138****0100", "E2E到店精细洗护"), read.get(301L));
        // A blank nickname/service name reads null; a basics value that is not the server mask
        // (here a raw number) never reaches the projection — it reads null, not the digits.
        assertEquals(new Labels(null, null, null), read.get(302L));
        // An order without any snapshot row reads a null service name (no fabricated label).
        assertEquals(new Labels("E2E体验用户", "138****0100", null), read.get(303L));
        assertEquals(List.of(301L, 302L, 303L), List.copyOf(read.keySet()));

        verify(orders, times(1)).displayLabels(any(), any(), argThat(q -> q.operatorType() == OperatorType.SYSTEM
                && q.operatorId() == null && "trace".equals(q.traceId())), same(source));
        verify(users, times(1)).listUsers(any());
        // Nothing raw or avatar-like crosses from the basics.
        for (Labels value : read.values()) {
            for (RecordComponent component : Labels.class.getRecordComponents()) {
                Object text = invoke(component, value);
                assertFalse(text instanceof String s && (s.matches(".*\\d{11}.*") || s.startsWith("https://")),
                        component.getName() + "=" + text);
            }
        }
    }

    @Test void snapshotReadsAreChunkedToFiftyDistinctOrdersInTheSameTransaction() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) rows.add(row(3000 + i, 4000 + i, 701));
        when(orders.displayLabels(any(), eq(STORE), any(), same(source))).thenAnswer(call -> {
            List<String> ids = call.getArgument(0);
            return ids.stream().map(id -> new DisplayLabel(id, "服务" + id, null, null, null)).toList();
        });
        when(users.listUsers(any())).thenReturn(List.of(basic("701", "138****0100", "E2E体验用户", null)));
        var read = labels.read(rows, STORE, "trace");
        assertEquals(100, read.size());
        assertEquals("服务4099", read.get(3099L).serviceName());
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<String>>) (Class<?>) List.class);
        verify(orders, times(2)).displayLabels(captor.capture(), eq(STORE), any(), same(source));
        assertEquals(List.of(50, 50), captor.getAllValues().stream().map(List::size).toList());
        assertEquals(100, new HashSet<>(captor.getAllValues().stream().flatMap(List::stream).toList()).size());
        // One distinct buyer → one basics call with one id.
        verify(users, times(1)).listUsers(new UserQueryApi.UserBasicsQuery(List.of("701")));
    }

    @Test void emptyPageNeedsNoForeignRead() {
        assertTrue(labels.read(List.of(), STORE, "trace").isEmpty());
        verifyNoInteractions(orders, users);
    }

    @Test void absentAccountAndUnassembledOrderPortDegradeToNullsNeverPlaceholders() {
        when(orders.displayLabels(any(), eq(STORE), any(), same(source))).thenReturn(List.of(new DisplayLabel("401", "E2E到店精细洗护", null, null, null)));
        when(users.listUsers(any())).thenReturn(List.of());
        assertEquals(new Labels(null, null, "E2E到店精细洗护"), labels.read(List.of(row(301, 401, 701)), STORE, "trace").get(301L));
        // Without the ORDER after-sale facts port (aftersale runtime off) the name is not projected.
        Supplier<OrderAfterSaleFactsApi> none = () -> null;
        var withoutPort = new MerchantRefundApplicationLabels(source, users, none);
        when(users.listUsers(any())).thenReturn(List.of(basic("701", "138****0100", "E2E体验用户", null)));
        assertEquals(new Labels("E2E体验用户", "138****0100", null), withoutPort.read(List.of(row(301, 401, 701)), STORE, "trace").get(301L));
    }

    @Test void nullForeignDuplicateAndFailingAnswersFailClosed() {
        var rows = List.of(row(301, 401, 701));
        when(users.listUsers(any())).thenReturn(List.of(basic("701", "138****0100", "E2E体验用户", null)));
        when(orders.displayLabels(any(), any(), any(), any())).thenReturn(List.of(new DisplayLabel("499", "他店服务", null, null, null)));
        assertUnavailable(() -> labels.read(rows, STORE, "trace"));
        when(orders.displayLabels(any(), any(), any(), any())).thenReturn(List.of(new DisplayLabel("401", "A", null, null, null), new DisplayLabel("401", "B", null, null, null)));
        assertUnavailable(() -> labels.read(rows, STORE, "trace"));
        when(orders.displayLabels(any(), any(), any(), any())).thenReturn(null);
        assertUnavailable(() -> labels.read(rows, STORE, "trace"));
        when(orders.displayLabels(any(), any(), any(), any())).thenThrow(new IllegalStateException("guard not held"));
        assertUnavailable(() -> labels.read(rows, STORE, "trace"));

        reset(orders);
        when(orders.displayLabels(any(), any(), any(), any())).thenReturn(List.of());
        when(users.listUsers(any())).thenReturn(List.of(basic("799", "139****0200", "别人", null)));
        assertUnavailable(() -> labels.read(rows, STORE, "trace"));
        when(users.listUsers(any())).thenReturn(List.of(basic("701", null, "甲", null), basic("701", null, "乙", null)));
        assertUnavailable(() -> labels.read(rows, STORE, "trace"));
        when(users.listUsers(any())).thenReturn(null);
        assertUnavailable(() -> labels.read(rows, STORE, "trace"));
        // A dependency argument failure is an outage (503), never relabelled as a client 400.
        when(users.listUsers(any())).thenThrow(new IllegalArgumentException("userIds must hold 1..200 ids"));
        assertUnavailable(() -> labels.read(rows, STORE, "trace"));
        // A known dependency outage is propagated unchanged (no silent empty-label fallback).
        var outage = new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "basics down");
        reset(users);
        when(users.listUsers(any())).thenThrow(outage);
        assertSame(outage, assertThrows(ApiException.class, () -> labels.read(rows, STORE, "trace")));
        // A row without its internal buyer key never reaches a foreign read.
        reset(users, orders);
        var broken = row(302, 402, 702);
        broken.applicantUserId = null;
        assertUnavailable(() -> labels.read(List.of(broken), STORE, "trace"));
        verifyNoInteractions(users, orders);
    }

    @Test void onlyTheExactServerMaskIsEverProjected() {
        assertEquals("138****0100", MerchantRefundApplicationLabels.masked("138****0100"));
        for (String bad : Arrays.asList(null, "13800000100", "****", "+86****0100", "138****010", "138***0100",
                "238****0100", " 138****0100", "138****0100 ", "138＊＊＊＊0100")) {
            assertNull(MerchantRefundApplicationLabels.masked(bad), String.valueOf(bad));
        }
        assertNull(MerchantRefundApplicationLabels.present("   "));
        assertEquals("小橘😀", MerchantRefundApplicationLabels.present("小橘😀"));
    }

    // ---------------------------------------------------------------- §3.3 timeline

    @Test void pendingApplicationCarriesExactlyAppliedAndMerchantDeadline() {
        Row pending = row(301, 401, 701);
        MerchantRefundApplicationQueryService.validate(pending);
        var timeline = MerchantRefundApplicationQueryService.timeline(pending);
        assertEquals(List.of("APPLIED", "MERCHANT_DEADLINE"), stages(timeline));
        // Each entry is its own persisted column, never a computed or shifted instant.
        assertEquals(T0.atOffset(ZoneOffset.UTC), timeline.get(0).occurredAt());
        assertEquals(T0.plusHours(24).atOffset(ZoneOffset.UTC), timeline.get(1).occurredAt());
    }

    @Test void decidedApplicationsAddDecidedAndTheBoundRefundOrderInAscendingOrder() {
        // Merchant approval within the window, refund order created later by the durable task.
        Row approved = decided("APPROVED", T0.plusHours(1));
        approved.refundOrderId = 7001L;
        approved.refundCreatedAt = T0.plusHours(1).plusSeconds(5);
        MerchantRefundApplicationQueryService.validate(approved);
        var timeline = MerchantRefundApplicationQueryService.timeline(approved);
        assertEquals(List.of("APPLIED", "DECIDED", "REFUND_ORDER_CREATED", "MERCHANT_DEADLINE"), stages(timeline));
        assertEquals(T0.plusHours(1).atOffset(ZoneOffset.UTC), timeline.get(1).occurredAt());
        assertEquals(T0.plusHours(1).plusSeconds(5).atOffset(ZoneOffset.UTC), timeline.get(2).occurredAt());
        assertAscending(timeline);

        // Approved but the refund order not created yet: no REFUND_ORDER_CREATED stage.
        Row waiting = decided("APPROVED", T0.plusHours(1));
        MerchantRefundApplicationQueryService.validate(waiting);
        assertEquals(List.of("APPLIED", "DECIDED", "MERCHANT_DEADLINE"), stages(MerchantRefundApplicationQueryService.timeline(waiting)));

        // Rejected: DECIDED only, never a refund order.
        Row rejected = decided("REJECTED", T0.plusHours(2));
        MerchantRefundApplicationQueryService.validate(rejected);
        assertEquals(List.of("APPLIED", "DECIDED", "MERCHANT_DEADLINE"), stages(MerchantRefundApplicationQueryService.timeline(rejected)));

        // System takeover after the deadline: the deadline sorts before the decision.
        Row timeout = decided("AUTO_APPROVED", T0.plusHours(24).plusNanos(250_000_000));
        timeout.refundOrderId = 7002L;
        timeout.refundCreatedAt = T0.plusHours(24).plusSeconds(2);
        MerchantRefundApplicationQueryService.validate(timeout);
        var takeover = MerchantRefundApplicationQueryService.timeline(timeout);
        assertEquals(List.of("APPLIED", "MERCHANT_DEADLINE", "DECIDED", "REFUND_ORDER_CREATED"), stages(takeover));
        assertAscending(takeover);

        // Pre-service automatic approval is decided at its creation instant: lifecycle tie-break.
        Row preService = decided("AUTO_APPROVED", T0);
        MerchantRefundApplicationQueryService.validate(preService);
        assertEquals(List.of("APPLIED", "DECIDED", "MERCHANT_DEADLINE"), stages(MerchantRefundApplicationQueryService.timeline(preService)));
    }

    @Test void inconsistentRowsFailClosedInsteadOfShowingAFabricatedStage() {
        Row pendingDecided = row(301, 401, 701);
        pendingDecided.decidedAt = T0.plusHours(1);
        assertUnavailable(() -> MerchantRefundApplicationQueryService.validate(pendingDecided));
        Row decidedWithoutInstant = decided("APPROVED", null);
        assertUnavailable(() -> MerchantRefundApplicationQueryService.validate(decidedWithoutInstant));
        Row boundWithoutRow = decided("APPROVED", T0.plusHours(1));
        boundWithoutRow.refundOrderId = 7001L;
        assertUnavailable(() -> MerchantRefundApplicationQueryService.validate(boundWithoutRow));
        Row rowWithoutBinding = decided("APPROVED", T0.plusHours(1));
        rowWithoutBinding.refundCreatedAt = T0.plusHours(2);
        assertUnavailable(() -> MerchantRefundApplicationQueryService.validate(rowWithoutBinding));
        Row rejectedWithRefund = decided("REJECTED", T0.plusHours(1));
        rejectedWithRefund.refundOrderId = 7001L;
        rejectedWithRefund.refundCreatedAt = T0.plusHours(2);
        assertUnavailable(() -> MerchantRefundApplicationQueryService.validate(rejectedWithRefund));
        Row withoutBuyerKey = row(301, 401, 701);
        withoutBuyerKey.applicantUserId = null;
        assertUnavailable(() -> MerchantRefundApplicationQueryService.validate(withoutBuyerKey));
    }

    // ---------------------------------------------------------------- shapes

    @Test void recordsCarryNoReasonTextNoBuyerIdAndNoRawPhoneComponent() {
        List<String> summary = Arrays.stream(Summary.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertEquals(List.of("applicationId", "applicationNo", "orderId", "status", "applicationVersion", "reasonCode",
                "refundAmount", "createdAt", "merchantDeadline", "decidedAt", "decisionId", "refundOrderId",
                "customerNickname", "customerPhoneMasked", "serviceName", "timeline"), summary);
        assertEquals(List.of("stage", "occurredAt"),
                Arrays.stream(TimelineEntry.class.getRecordComponents()).map(RecordComponent::getName).toList());
        assertEquals(List.of("customerNickname", "customerPhoneMasked", "serviceName"),
                Arrays.stream(Labels.class.getRecordComponents()).map(RecordComponent::getName).toList());
        for (Class<?> type : List.of(Summary.class, TimelineEntry.class, Labels.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("reasontext") || name.contains("cipher") || name.equals("userid")
                        || name.contains("avatar") || name.contains("mobile")
                        || name.contains("phone") && !name.equals("customerphonemasked"), type.getSimpleName() + "." + name);
            }
        }
        // The timeline component is never null (an absent list reads empty, not null).
        assertEquals(List.of(), new Summary("1", "2", "3", "PENDING_MERCHANT", "0", "R", BigDecimal.ONE, null, null, null,
                null, null, null, null, null, null).timeline());
        assertEquals(List.of("APPLIED", "MERCHANT_DEADLINE", "DECIDED", "REFUND_ORDER_CREATED"),
                MerchantRefundApplicationQueryService.STAGES);
    }

    private static void assertAscending(List<TimelineEntry> timeline) {
        for (int i = 1; i < timeline.size(); i++) {
            assertFalse(timeline.get(i).occurredAt().isBefore(timeline.get(i - 1).occurredAt()), timeline.toString());
        }
    }
    private static Object invoke(RecordComponent component, Object target) {
        try { return component.getAccessor().invoke(target); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
