package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

import com.petplatform.merchant.biz.infrastructure.persistence.entity.AdminMerchantGovernanceEntities.AdminActionRefRow;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.AdminMerchantGovernanceEntities.AdminInvitationRow;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.AdminMerchantGovernanceEntities.AdminMemberRow;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.AdminMerchantGovernanceEntities.AdminMerchantLedgerRow;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.AdminMerchantGovernanceEntities.AdminStaffRow;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.AdminMerchantGovernanceEntities.AdminStoreRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Contract-64 merchant governance read side (ledger / staff / members / invitations). Every
 * statement lives in AdminMerchantGovernanceMapper.xml; joins stay inside module-owned tables
 * and all filters bind with #{} (no substitution, no annotation SQL).
 */
public interface AdminMerchantGovernanceMapper {

    long countLedger(
            @Param("status") String status,
            @Param("storeStatus") String storeStatus,
            @Param("cityCode") String cityCode,
            @Param("merchantTypeCode") String merchantTypeCode,
            @Param("keyword") String keyword,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo);

    List<AdminMerchantLedgerRow> selectLedgerPage(
            @Param("status") String status,
            @Param("storeStatus") String storeStatus,
            @Param("cityCode") String cityCode,
            @Param("merchantTypeCode") String merchantTypeCode,
            @Param("keyword") String keyword,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo,
            @Param("limit") int limit,
            @Param("offset") long offset);

    AdminMerchantLedgerRow selectLedgerRow(@Param("merchantId") long merchantId);

    List<AdminStoreRow> selectStores(@Param("merchantId") long merchantId);

    long countStaff(
            @Param("merchantId") long merchantId,
            @Param("storeId") Long storeId,
            @Param("employmentStatus") String employmentStatus,
            @Param("serviceEnabled") Boolean serviceEnabled);

    List<AdminStaffRow> selectStaffPage(
            @Param("merchantId") long merchantId,
            @Param("storeId") Long storeId,
            @Param("employmentStatus") String employmentStatus,
            @Param("serviceEnabled") Boolean serviceEnabled,
            @Param("limit") int limit,
            @Param("offset") long offset);

    List<AdminMemberRow> selectMemberPage(
            @Param("merchantId") long merchantId,
            @Param("storeId") Long storeId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") long offset);

    long countMembers(
            @Param("merchantId") long merchantId,
            @Param("storeId") Long storeId,
            @Param("status") String status);

    List<AdminActionRefRow> selectMemberActions(@Param("memberIds") List<Long> memberIds);

    List<AdminInvitationRow> selectInvitationPage(
            @Param("merchantId") long merchantId,
            @Param("storeId") Long storeId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") long offset);

    long countInvitations(
            @Param("merchantId") long merchantId,
            @Param("storeId") Long storeId,
            @Param("status") String status);

    List<AdminActionRefRow> selectInvitationActions(@Param("invitationIds") List<Long> invitationIds);
}
