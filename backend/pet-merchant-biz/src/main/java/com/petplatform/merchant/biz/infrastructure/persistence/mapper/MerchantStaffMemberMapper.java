package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantActionRefEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantCommandBindingEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantMemberGrantScopeEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantMemberInvitationEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantMemberRowEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffScopeEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreFactEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Contract 54 binding writes; every statement runs inside a merchant-owned transaction. */
public interface MerchantStaffMemberMapper {
    void setTimeZoneUtc();
    void setLockWaitTimeout2Seconds();

    // owner admission (35号 shape, reused rows)
    MerchantStaffScopeEntity selectOwnedScope(@Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("ownerUserId") long ownerUserId);
    MerchantStaffScopeEntity lockOwnedScope(@Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("ownerUserId") long ownerUserId);
    Long lockApplication(@Param("merchantId") long merchantId);
    MerchantStaffScopeEntity lockMerchant(@Param("merchantId") long merchantId);

    // invitations
    MerchantMemberInvitationEntity selectInvitation(@Param("invitationId") long invitationId);
    MerchantMemberInvitationEntity selectInvitationForOwner(@Param("invitationId") long invitationId,
        @Param("merchantId") long merchantId, @Param("storeId") long storeId);
    MerchantMemberInvitationEntity lockInvitation(@Param("invitationId") long invitationId);
    MerchantMemberInvitationEntity lockPendingInvitation(@Param("merchantId") long merchantId,
        @Param("phone") String phone);
    int insertInvitation(@Param("id") long id, @Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("phone") String phone,
        @Param("memberName") String memberName, @Param("invitedBy") long invitedBy,
        @Param("now") LocalDateTime now);
    int insertInvitationAction(@Param("id") long id, @Param("invitationId") long invitationId,
        @Param("actionCode") String actionCode, @Param("now") LocalDateTime now);
    List<String> listInvitationActions(@Param("invitationId") long invitationId);
    int cancelInvitation(@Param("invitationId") long invitationId,
        @Param("merchantId") long merchantId, @Param("expectedVersion") long expectedVersion,
        @Param("now") LocalDateTime now);
    int confirmInvitation(@Param("invitationId") long invitationId,
        @Param("merchantId") long merchantId, @Param("confirmedBy") long confirmedBy,
        @Param("memberId") long memberId, @Param("expectedVersion") long expectedVersion,
        @Param("now") LocalDateTime now);
    List<MerchantMemberInvitationEntity> listInvitationRows(@Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("limit") int limit, @Param("offset") int offset);
    long countInvitationRows(@Param("merchantId") long merchantId,
        @Param("storeId") long storeId);
    List<MerchantActionRefEntity> listInvitationActionsByIds(
        @Param("invitationIds") List<Long> invitationIds);
    // Contract 54 §7 employee-side list (user ruling 2026-10-07): phone-equality seek over
    // idx_mer_member_inv_phone (phone, id), id DESC backward-scan pagination.
    long countInvitationRowsByPhone(@Param("phone") String phone);
    List<MerchantMemberInvitationEntity> listInvitationRowsByPhone(@Param("phone") String phone,
        @Param("limit") int limit, @Param("offset") int offset);

    // members / grants / actions (contract 52 relations, written here from contract 54 commands)
    MerchantMemberGrantScopeEntity lockMemberScope(@Param("merchantId") long merchantId,
        @Param("memberId") long memberId, @Param("storeId") long storeId);
    MerchantMemberGrantScopeEntity selectMemberScope(@Param("merchantId") long merchantId,
        @Param("memberId") long memberId, @Param("storeId") long storeId);
    MerchantMemberGrantScopeEntity lockMemberByUser(@Param("merchantId") long merchantId,
        @Param("userId") long userId);
    MerchantMemberRowEntity readMemberRow(@Param("merchantId") long merchantId,
        @Param("memberId") long memberId, @Param("storeId") long storeId);
    int insertMember(@Param("id") long id, @Param("merchantId") long merchantId,
        @Param("userId") long userId, @Param("now") LocalDateTime now);
    int updateMemberStatus(@Param("memberId") long memberId, @Param("merchantId") long merchantId,
        @Param("status") String status, @Param("expectedVersion") long expectedVersion,
        @Param("now") LocalDateTime now);
    int insertGrant(@Param("id") long id, @Param("memberId") long memberId,
        @Param("storeId") long storeId, @Param("now") LocalDateTime now);
    int updateGrantStatus(@Param("memberId") long memberId, @Param("storeId") long storeId,
        @Param("status") String status, @Param("expectedVersion") long expectedVersion,
        @Param("now") LocalDateTime now);
    int deleteGrantActions(@Param("memberId") long memberId, @Param("storeId") long storeId);
    int insertGrantAction(@Param("id") long id, @Param("memberId") long memberId,
        @Param("storeId") long storeId, @Param("actionCode") String actionCode,
        @Param("now") LocalDateTime now);
    List<String> listGrantActions(@Param("memberId") long memberId,
        @Param("storeId") long storeId);
    List<MerchantMemberRowEntity> listMemberRows(@Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("limit") int limit, @Param("offset") int offset);
    long countMemberRows(@Param("merchantId") long merchantId, @Param("storeId") long storeId);
    List<MerchantActionRefEntity> listGrantActionsByMembers(
        @Param("memberIds") List<Long> memberIds, @Param("storeId") long storeId);

    // confirm-page facts
    MerchantStoreFactEntity selectStoreFact(@Param("merchantId") long merchantId,
        @Param("storeId") long storeId);

    // audit (contract 54 storage)
    int insertAudit(@Param("id") long id, @Param("actorId") long actorId,
        @Param("action") String action, @Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("memberId") Long memberId,
        @Param("invitationId") Long invitationId, @Param("fromStatus") String fromStatus,
        @Param("toStatus") String toStatus, @Param("fromVersion") Long fromVersion,
        @Param("toVersion") long toVersion, @Param("requestKey") byte[] requestKey,
        @Param("requestId") byte[] requestId, @Param("traceId") String traceId,
        @Param("now") LocalDateTime now);

    // supplement-23 binding (shared merchant_command_idempotency)
    int insertBinding(@Param("id") long id, @Param("requestKey") byte[] requestKey,
        @Param("canonicalVersion") String canonicalVersion, @Param("paramsSha256") String paramsSha256,
        @Param("paramsCanonical") byte[] paramsCanonical, @Param("traceId") String traceId);
    MerchantCommandBindingEntity selectBindingForUpdate(@Param("requestKey") byte[] requestKey);
    int markBindingSucceeded(@Param("requestKey") byte[] requestKey,
        @Param("receiptJson") String receiptJson);
}
