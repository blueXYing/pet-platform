package com.petplatform.order.api.query;

import com.petplatform.common.QueryContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** SYSTEM-only dry run. Findings are diagnostics, never permission to confirm or repair an order. */
public interface OrderAutoConfirmTaskInspectionApi {
    Page inspect(QueryContext context, String afterOrderId, int limit);

    enum Finding {
        MISSING_TASK, ACTIVE_TASK, TERMINAL_TASK_REQUIRES_REVIEW,
        TASK_BINDING_CONFLICT, ORDER_FACTS_REQUIRE_REVIEW, UNSUPPORTED_ROUND
    }

    record Item(String orderId, Integer confirmRound, OffsetDateTime confirmDeadline,
            boolean due, String taskStatus, Finding finding) {}

    record Page(List<Item> items, String nextAfterOrderId, Map<Finding, Integer> counts) {
        public Page { items = List.copyOf(items); counts = Map.copyOf(counts); }
    }
}
