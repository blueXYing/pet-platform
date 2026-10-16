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
