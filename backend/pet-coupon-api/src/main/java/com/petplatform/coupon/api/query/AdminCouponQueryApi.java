package com.petplatform.coupon.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only platform coupon face for the single operator (contract 65 §4, PRD 6.4, A-005):
 * template page/detail/trial-calculation plus the instance ledger (PRD 6.4.3 券实例台账) with
 * its append-only state timeline. The caller is always the ADMIN_WEB operator carried by
 * {@link QueryContext}; {@code authorization} re-proves the current session, action code
 * (coupon.template.read / coupon.instance.read) and the ALL data scope inside the coupon
 * kernel on every call. No write path lives here — instance status moves (void/return/risk
 * freeze) stay unauthorized (CCR-ADM-A005-OPS-001) and template maintenance belongs to
 * {@code AdminCouponCommandApi}.
 */
public interface AdminCouponQueryApi {

    /** One page of templates, fixed order created_at DESC, id DESC. */
    PageResult<AdminTemplateSummary> listTemplates(AdminTemplateListQuery query);

    /** One template with its frozen four-key rule_json document verbatim. */
    AdminTemplateDetail getTemplate(AdminTemplateQuery query);

    /**
     * Pure read projection of the freeze eligibility semantics (61号 conditions ③④⑥⑦):
     * eligible=false carries the first failing reason, amounts never mutate state.
     */
    TrialCalculation trialCalculation(TrialCalculationQuery query);

    /** One page of instances, fixed order created_at DESC, id DESC. */
    PageResult<AdminInstanceSummary> listInstances(AdminInstanceListQuery query);

    /** One instance with its coupon_ledger timeline (created_at ASC, id ASC). */
    AdminInstanceDetail getInstance(AdminInstanceQuery query);

    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    /** Template summary; money renders as two-scale decimal strings at the adapter. */
    record AdminTemplateSummary(
            String templateId, String name, String status, BigDecimal amountOff,
            BigDecimal thresholdAmount, OffsetDateTime validStartAt, OffsetDateTime validEndAt,
            Long totalStock, long issuedCount, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    record AdminTemplateDetail(
            String templateId, String name, String status, BigDecimal amountOff,
            BigDecimal thresholdAmount, String scopeSummary, String typeLabel,
            OffsetDateTime validStartAt, OffsetDateTime validEndAt,
            Long totalStock, long issuedCount, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    record TrialCalculation(
            String templateId, BigDecimal orderAmount, BigDecimal thresholdAmount,
            BigDecimal discountAmount, BigDecimal payAmount, boolean eligible,
            String ineligibleReason) {}

    /** Instance ledger row (coupon_ledger verbatim; remark truncates to the stored 500). */
    record LedgerRow(
            String ledgerId, String action, String fromStatus, String toStatus,
            String orderId, String requestId, String remark, OffsetDateTime createdAt) {}

    record AdminInstanceSummary(
            String instanceId, String templateId, String templateName, String userId,
            String status, BigDecimal amountOff, BigDecimal thresholdAmount,
            OffsetDateTime originalExpireAt, OffsetDateTime expireAt, String orderId,
            OffsetDateTime frozenAt, OffsetDateTime usedAt, OffsetDateTime createdAt) {}

    record AdminInstanceDetail(
            String instanceId, String templateId, String templateName, String userId,
            String status, BigDecimal amountOff, BigDecimal thresholdAmount,
            OffsetDateTime originalExpireAt, OffsetDateTime expireAt, String orderId,
            OffsetDateTime frozenAt, OffsetDateTime freezeExpireAt, OffsetDateTime usedAt,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, List<LedgerRow> ledger) {}

    record AdminTemplateListQuery(String status, int page, int pageSize,
            QueryContext context, AdminAuthorization authorization) {}

    record AdminTemplateQuery(String templateId, QueryContext context,
            AdminAuthorization authorization) {}

    record TrialCalculationQuery(String templateId, BigDecimal orderAmount,
            QueryContext context, AdminAuthorization authorization) {}

    record AdminInstanceListQuery(String templateId, String status, String orderId,
            OffsetDateTime createdFrom, OffsetDateTime createdTo,
            int page, int pageSize, QueryContext context, AdminAuthorization authorization) {}

    record AdminInstanceQuery(String instanceId, QueryContext context,
            AdminAuthorization authorization) {}
}
