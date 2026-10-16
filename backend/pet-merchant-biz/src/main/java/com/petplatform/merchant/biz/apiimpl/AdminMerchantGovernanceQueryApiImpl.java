package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminMerchantDetailQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminMerchantLedgerDetail;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminMerchantLedgerSummary;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminMerchantListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffInvitationListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffInvitationRow;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffMemberListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffMemberRow;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffProfile;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.MerchantGovernanceAuthorizationPort;
import com.petplatform.merchant.biz.application.AdminMerchantGovernanceQueryService;
import com.petplatform.merchant.biz.infrastructure.persistence.AdminMerchantGovernanceStore;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-64 merchant governance read face. */
public final class AdminMerchantGovernanceQueryApiImpl implements AdminMerchantGovernanceQueryApi {
    private final AdminMerchantGovernanceQueryService service;

    public AdminMerchantGovernanceQueryApiImpl(
            DataSource dataSource, MerchantGovernanceAuthorizationPort authorization) {
        this.service = new AdminMerchantGovernanceQueryService(
                new AdminMerchantGovernanceStore(
                        Objects.requireNonNull(dataSource, "dataSource is required")),
                Objects.requireNonNull(authorization, "authorization is required"));
    }

    @Override
    public PageResult<AdminMerchantLedgerSummary> listMerchants(AdminMerchantListQuery query) {
        return service.listMerchants(query);
    }

    @Override
    public AdminMerchantLedgerDetail getMerchant(AdminMerchantDetailQuery query) {
        return service.getMerchant(query);
    }

    @Override
    public PageResult<AdminStaffProfile> listStaff(AdminStaffListQuery query) {
        return service.listStaff(query);
    }

    @Override
    public PageResult<AdminStaffMemberRow> listStaffMembers(AdminStaffMemberListQuery query) {
        return service.listStaffMembers(query);
    }

    @Override
    public PageResult<AdminStaffInvitationRow> listStaffInvitations(AdminStaffInvitationListQuery query) {
        return service.listInvitations(query);
    }
}
