package com.petplatform.coupon.api.command;

import com.petplatform.common.CommandContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Coupon template maintenance writes for the single operator (contract 65 §4.1, PRD 6.4.1,
 * A-005): create (DRAFT), white-listed edit, enable (DRAFT→ACTIVE) and offline
 * (ACTIVE→OFFLINE). SSOT §9 stays the only coupon rule source; this face adds no state
 * vocabulary beyond the SQL06 template words DRAFT/ACTIVE/OFFLINE and never touches coupon
 * instances (offline does not modify or void issued instances — PRD 6.4.1; the 61 freeze
 * precondition ④ already reads template status at freeze time). Every command carries a
 * terminal requestId; idempotency binds inside the coupon kernel on command_idempotency
 * (14号) with same-parameter replay returning the first receipt; expectedVersion guards
 * concurrent edits (409). Templates with issuedCount&gt;0 or status OFFLINE are immutable.
 */
public interface AdminCouponCommandApi {

    /** Creates a DRAFT template; rule_json takes the frozen 61 §2 four-key shape. */
    TemplateMutation createTemplate(CreateTemplateCommand command);

    /** Edits name/amountOff/thresholdAmount/validity/scope/label while DRAFT/ACTIVE &amp; issuedCount=0. */
    TemplateMutation updateTemplate(UpdateTemplateCommand command);

    /** DRAFT → ACTIVE; the template becomes freezable (61 condition ④). */
    TemplateMutation enableTemplate(TemplateStatusCommand command);

    /** ACTIVE → OFFLINE; issued instances stay untouched. */
    TemplateMutation offlineTemplate(TemplateStatusCommand command);

    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    record TemplateMutation(
            String templateId, String name, String status, long version,
            OffsetDateTime occurredAt) {}

    /** typeLabel defaults server-side (满减券/无门槛券 by threshold) when absent. */
    record CreateTemplateCommand(
            String name, BigDecimal amountOff, BigDecimal thresholdAmount,
            OffsetDateTime validStartAt, OffsetDateTime validEndAt,
            String scopeSummary, String typeLabel,
            CommandContext context, AdminAuthorization authorization) {}

    record UpdateTemplateCommand(
            String templateId, String name, BigDecimal amountOff, BigDecimal thresholdAmount,
            OffsetDateTime validStartAt, OffsetDateTime validEndAt,
            String scopeSummary, String typeLabel, long expectedVersion,
            CommandContext context, AdminAuthorization authorization) {}

    record TemplateStatusCommand(
            String templateId, long expectedVersion,
            CommandContext context, AdminAuthorization authorization) {}
}
