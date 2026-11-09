package com.petplatform.refund.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi.DisplayLabel;
import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundApplicationQueryMapper.Row;
import com.petplatform.user.api.dto.UserBasicDTO;
import com.petplatform.user.api.query.UserQueryApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * CCR-M-REFUND-VIEW-001 §3.1 (approved 2026-11-06) display facts for merchant refund rows whose
 * OWNER read has already been proven, read inside the caller's READ_COMMITTED store-guard
 * transaction (contract 57 read side). Batched, never per row:
 * <ul>
 * <li>user basics via {@link UserQueryApi#listUsers} (1..200 ids per call, contract-69 channel):
 * only the nickname and the user domain's own masked phone cross — the raw credential never
 * leaves the user module and the avatar is not taken;</li>
 * <li>the order-time service name via the existing ORDER port
 * {@link OrderAfterSaleFactsApi#displayLabels} (1..50 distinct orders of the one proven store
 * per call, SYSTEM context, the caller's own DataSource so the held guard is re-checked).</li>
 * </ul>
 * Absent accounts/snapshots and blank values read null (the page owns the placeholder); a masked
 * phone that is not exactly the contract shape {@code 1xx****xxxx} also reads null, so nothing
 * phone-like but the server mask can ever reach the wire. A null, foreign, duplicate or failing
 * answer fails the read closed (503), never a fabricated label. Without an assembled ORDER
 * after-sale facts port (pet.aftersale.enabled=false) the service name is simply not projected.
 */
final class MerchantRefundApplicationLabels {

    /** Nullable display facts of one application row. */
    record Labels(String customerNickname, String customerPhoneMasked, String serviceName) {
        static final Labels NONE = new Labels(null, null, null);
    }

    /** OrderAfterSaleFactsApi.displayLabels admits 1..50 distinct canonical order ids per call. */
    static final int ORDER_BATCH = 50;
    /** UserQueryApi.listUsers admits 1..200 canonical user ids per call (contract 69). */
    static final int USER_BATCH = 200;
    /** OpenAPI 11 customerPhoneMasked pattern: the server mask only, never a raw number. */
    private static final Pattern MASKED_PHONE = Pattern.compile("1[0-9]{2}\\*{4}[0-9]{4}");
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final DataSource source;
    private final UserQueryApi users;
    private final Supplier<OrderAfterSaleFactsApi> orders;

    MerchantRefundApplicationLabels(DataSource source, UserQueryApi users,
            Supplier<OrderAfterSaleFactsApi> orders) {
        this.source = Objects.requireNonNull(source, "source is required");
        this.users = Objects.requireNonNull(users, "users is required");
        this.orders = Objects.requireNonNull(orders, "orders is required");
    }

    /** Labels keyed by application id, in row order; rows must come from the proven store read. */
    Map<Long, Labels> read(List<Row> rows, String storeId, String traceId) {
        Map<Long, Labels> result = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) return result;
        Set<Long> orderIds = new LinkedHashSet<>();
        Set<Long> userIds = new LinkedHashSet<>();
        for (Row row : rows) {
            if (row == null || row.id == null || row.orderId == null || row.orderId <= 0
                    || row.applicantUserId == null || row.applicantUserId <= 0) {
                throw unavailable(null);
            }
            orderIds.add(row.orderId);
            userIds.add(row.applicantUserId);
        }
        Map<Long, String> services = serviceNames(List.copyOf(orderIds), storeId, traceId);
        Map<Long, UserBasicDTO> basics = basics(List.copyOf(userIds));
        for (Row row : rows) {
            UserBasicDTO basic = basics.get(row.applicantUserId);
            result.put(row.id, new Labels(
                    basic == null ? null : present(basic.nickname()),
                    basic == null ? null : masked(basic.mobileMasked()),
                    services.get(row.orderId)));
        }
        return result;
    }

    private Map<Long, String> serviceNames(List<Long> orderIds, String storeId, String traceId) {
        Map<Long, String> names = new HashMap<>();
        OrderAfterSaleFactsApi port = orders.get();
        if (port == null) return names;
        QueryContext system = new QueryContext(traceId, OperatorType.SYSTEM, null);
        for (int from = 0; from < orderIds.size(); from += ORDER_BATCH) {
            List<String> chunk = canonical(orderIds.subList(from, Math.min(from + ORDER_BATCH, orderIds.size())));
            List<DisplayLabel> answered;
            try {
                answered = port.displayLabels(chunk, storeId, system, source);
            } catch (RuntimeException failure) {
                throw unavailable(failure);
            }
            if (answered == null) throw unavailable(null);
            Set<String> seen = new HashSet<>();
            for (DisplayLabel label : answered) {
                if (label == null || !chunk.contains(label.orderId()) || !seen.add(label.orderId())) {
                    throw unavailable(null);
                }
                names.put(IDS.fromApi(label.orderId()), present(label.serviceName()));
            }
        }
        return names;
    }

    private Map<Long, UserBasicDTO> basics(List<Long> userIds) {
        Map<Long, UserBasicDTO> basics = new HashMap<>();
        for (int from = 0; from < userIds.size(); from += USER_BATCH) {
            List<String> chunk = canonical(userIds.subList(from, Math.min(from + USER_BATCH, userIds.size())));
            List<UserBasicDTO> answered;
            try {
                answered = users.listUsers(new UserQueryApi.UserBasicsQuery(chunk));
            } catch (RuntimeException failure) {
                throw unavailable(failure);
            }
            if (answered == null) throw unavailable(null);
            for (UserBasicDTO basic : answered) {
                if (basic == null || !chunk.contains(basic.userId())
                        || basics.put(IDS.fromApi(basic.userId()), basic) != null) {
                    throw unavailable(null);
                }
            }
        }
        return basics;
    }

    /** Server mask only: anything not exactly {@code 1xx****xxxx} degrades to null. */
    static String masked(String value) {
        return value != null && MASKED_PHONE.matcher(value).matches() ? value : null;
    }

    static String present(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> canonical(List<Long> ids) {
        List<String> out = new ArrayList<>(ids.size());
        for (Long id : ids) out.add(IDS.toApi(id));
        return List.copyOf(out);
    }

    /** A foreign dependency failure is a projection outage (503), never a client error or a fallback. */
    private static ApiException unavailable(RuntimeException cause) {
        if (cause instanceof ApiException known
                && CommonApiCodes.DEPENDENCY_UNAVAILABLE.equals(known.code())) {
            return known;
        }
        ApiException failure = new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "退款申请展示信息不可用");
        if (cause != null) failure.initCause(cause);
        return failure;
    }
}
