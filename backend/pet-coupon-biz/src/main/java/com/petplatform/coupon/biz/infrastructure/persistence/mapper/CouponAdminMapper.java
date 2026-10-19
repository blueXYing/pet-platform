package com.petplatform.coupon.biz.infrastructure.persistence.mapper;

import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminBindingEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminInstanceEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminLedgerEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminTemplateEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * coupon_template / coupon_instance / coupon_ledger admin face (contract 65 §4).
 * SQL lives in resources/mapper/CouponAdminMapper.xml (22号 MyBatis ruling).
 */
public interface CouponAdminMapper {

    List<AdminTemplateEntity> selectTemplates(
            @Param("status") String status, @Param("limit") int limit, @Param("offset") int offset);

    long countTemplates(@Param("status") String status);

    AdminTemplateEntity selectTemplateById(@Param("templateId") long templateId);

    /** Row-locked read for the grant guard (status/window checked under the same lock). */
    AdminTemplateEntity selectTemplateForUpdate(@Param("templateId") long templateId);

    /** Row-locked read for the void guard (status checked under the same lock). */
    AdminInstanceEntity selectInstanceForUpdate(@Param("instanceId") long instanceId);

    /** Void write: terminal AVAILABLE→VOID transition (CAS on the AVAILABLE state). */
    int voidInstance(
            @Param("instanceId") long instanceId,
            @Param("now") LocalDateTime now);

    /** Void audit: the append-only VOID ledger row (uk request_id, terminal once forever). */
    int insertVoidLedger(
            @Param("id") long id,
            @Param("instanceId") long instanceId,
            @Param("userId") long userId,
            @Param("requestId") String requestId,
            @Param("remark") String remark,
            @Param("now") LocalDateTime now);

    /** Grant write: one AVAILABLE instance inheriting the template validity window. */
    int insertInstance(
            @Param("id") long id,
            @Param("templateId") long templateId,
            @Param("userId") long userId,
            @Param("originalExpireAt") LocalDateTime originalExpireAt,
            @Param("expireAt") LocalDateTime expireAt,
            @Param("now") LocalDateTime now);

    /**
     * Grant guard (hardening 2026-10-19): AVAILABLE+FROZEN instances the user already holds
     * of the template, compared against the configured per-template hold cap.
     */
    long countHeldInstances(
            @Param("templateId") long templateId,
            @Param("userId") long userId);

    /**
     * Grant guard (hardening 2026-10-19): ISSUE ledger rows the operator produced for the
     * (user, template) pair inside the sliding frequency window; the remark carries the
     * operator audit prefix written by the grant itself.
     */
    long countRecentOperatorGrants(
            @Param("templateId") long templateId,
            @Param("userId") long userId,
            @Param("operatorId") String operatorId,
            @Param("since") LocalDateTime since);

    /** Grant audit: the append-only ISSUE ledger row (uk request_id). */
    int insertIssueLedger(
            @Param("id") long id,
            @Param("instanceId") long instanceId,
            @Param("userId") long userId,
            @Param("requestId") String requestId,
            @Param("remark") String remark,
            @Param("now") LocalDateTime now);

    /**
     * Grant drawdown: issued_count is the only template counter the grant face maintains.
     * Hardening 2026-10-19: the statement refuses (returns 0) once total_stock is set and
     * exhausted; NULL stays unlimited. The row is already locked by the guard read.
     */
    int incrementIssuedCount(
            @Param("templateId") long templateId,
            @Param("now") LocalDateTime now);

    List<AdminInstanceEntity> selectInstances(
            @Param("templateId") Long templateId,
            @Param("status") String status,
            @Param("orderId") Long orderId,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countInstances(
            @Param("templateId") Long templateId,
            @Param("status") String status,
            @Param("orderId") Long orderId,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo);

    AdminInstanceEntity selectInstanceById(@Param("instanceId") long instanceId);

    List<AdminLedgerEntity> selectLedgerByInstance(@Param("instanceId") long instanceId);

    int insertTemplate(
            @Param("id") long id,
            @Param("name") String name,
            @Param("status") String status,
            @Param("validStartAt") LocalDateTime validStartAt,
            @Param("validEndAt") LocalDateTime validEndAt,
            @Param("ruleJson") String ruleJson,
            @Param("now") LocalDateTime now);

    int updateTemplateFields(
            @Param("templateId") long templateId,
            @Param("name") String name,
            @Param("validStartAt") LocalDateTime validStartAt,
            @Param("validEndAt") LocalDateTime validEndAt,
            @Param("ruleJson") String ruleJson,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int updateTemplateStatus(
            @Param("templateId") long templateId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int insertBinding(
            @Param("id") long id,
            @Param("requestKey") String requestKey,
            @Param("canonicalVersion") String canonicalVersion,
            @Param("paramsSha256") String paramsSha256,
            @Param("paramsCanonical") byte[] paramsCanonical);

    CouponAdminBindingEntity selectBindingForUpdate(@Param("requestKey") String requestKey);

    int markBindingSucceeded(
            @Param("requestKey") String requestKey,
            @Param("receiptJson") String receiptJson);
}
