package com.petplatform.merchant.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only platform governance face for the single operator (contract 64, ADM-001 gap
 * slice / A-002 phase 2): the merchant ledger, the per-merchant staff governance views and
 * nothing else. The caller is always the ADMIN_WEB operator carried by {@link QueryContext};
 * {@code authorization} re-proves the current session, action code and resource scope inside
 * the merchant kernel on every call (controller snapshots are never authorization evidence).
 * Phones only ever leave this face pre-masked; no plaintext phone, no credential or reason
 * cipher, no store contact facts. Read-only by construction: merchant disable/freeze
 * (HTTP10 §5.6) and every staff disposition stay outside this interface — SSOT §30 grants the
 * operator no write action over staff, and 54号 OWNER commands are not operator-reachable.
 */
public interface AdminMerchantGovernanceQueryApi {

    /** One page of the merchant ledger, newest first (created_at DESC, id DESC). */
    PageResult<AdminMerchantLedgerSummary> listMerchants(AdminMerchantListQuery query);

    /** One merchant ledger detail: ledger row + stores + application + signing facts. */
    AdminMerchantLedgerDetail getMerchant(AdminMerchantDetailQuery query);

    /** Staff profile rows of one merchant, oldest first (staff_id ASC, 27号 list ordering). */
    PageResult<AdminStaffProfile> listStaff(AdminStaffListQuery query);

    /** Member binding rows of one merchant, oldest first (member_id ASC). */
    PageResult<AdminStaffMemberRow> listStaffMembers(AdminStaffMemberListQuery query);

    /** Invitation rows of one merchant, newest first (id DESC, 54号 §7 ordering). */
    PageResult<AdminStaffInvitationRow> listStaffInvitations(AdminStaffInvitationListQuery query);

    /**
     * Trusted final-authorization input re-checked by the merchant kernel against the admin
     * authorization owner; mirrors the contract-63 read slices' reference shape.
     */
    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    record AdminMerchantListQuery(
            String status, String storeStatus, String cityCode, String merchantTypeCode,
            String keyword, OffsetDateTime createdFrom, OffsetDateTime createdTo,
            int page, int pageSize, QueryContext context, AdminAuthorization authorization) {}

    record AdminMerchantDetailQuery(
            String merchantId, QueryContext context, AdminAuthorization authorization) {}

    record AdminStaffListQuery(
            String merchantId, String storeId, String employmentStatus, Boolean serviceEnabled,
            int page, int pageSize, QueryContext context, AdminAuthorization authorization) {}

    record AdminStaffMemberListQuery(
            String merchantId, String storeId, String status,
            int page, int pageSize, QueryContext context, AdminAuthorization authorization) {}

    record AdminStaffInvitationListQuery(
            String merchantId, String storeId, String status,
            int page, int pageSize, QueryContext context, AdminAuthorization authorization) {}

    /** Ledger row; providerMerchantNoMasked keeps at most the last four digits. */
    record AdminMerchantLedgerSummary(
            String merchantId, String merchantName, String status, String merchantTypeCode,
            String cityCode, int storeCount, int activeStoreCount, String applicationStatus,
            String signingStatus, String providerMerchantNoMasked, OffsetDateTime createdAt) {}

    record AdminMerchantStore(
            String storeId, String storeName, String address, String status) {}

    /** Application facts of the reserved merchant; null fields for a DRAFT-less legacy row. */
    record AdminApplicationFact(
            String applicationId, String applicationNo, String status,
            OffsetDateTime submittedAt, OffsetDateTime reviewedAt) {}

    /** Signing fact against the current published agreement version (28号 semantics). */
    record AdminSigningFact(
            String signingStatus, String agreementVersion, OffsetDateTime acceptedAt) {}

    record AdminMerchantLedgerDetail(
            String merchantId, String merchantName, String status, String merchantTypeCode,
            String cityCode, int storeCount, int activeStoreCount, String applicationStatus,
            String signingStatus, String providerMerchantNoMasked, OffsetDateTime createdAt,
            List<AdminMerchantStore> stores, AdminApplicationFact application,
            AdminSigningFact signing) {}

    /** merchant_staff row (SQL06 vocabulary); phone is the pre-masked owner-visible form. */
    record AdminStaffProfile(
            String staffId, String storeId, String staffName, String phoneMasked,
            String employmentStatus, boolean serviceEnabled, String version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    /**
     * One member binding: member row + its grant state. grantedActions is empty once the
     * grant is REVOKED (54号 deletes action rows on revoke); grantRevoked surfaces the
     * terminal revoke instead of hiding the row. memberName/phoneMasked come from the
     * CONFIRMED invitation row (54号 §4 source) and are null for a pre-invitation member.
     */
    record AdminStaffMemberRow(
            String memberId, String storeId, String status, String memberName,
            String phoneMasked, List<String> grantedActions, boolean grantRevoked,
            String memberVersion, String grantVersion,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    /** Invitation state-machine row (54号 §2); actions frozen at invite time. */
    record AdminStaffInvitationRow(
            String invitationId, String storeId, String memberName, String phoneMasked,
            String status, List<String> grantedActions, String invitedBy,
            OffsetDateTime invitedAt, OffsetDateTime updatedAt) {}
}
