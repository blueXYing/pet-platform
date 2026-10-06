package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.merchant.api.command.CancelStaffMemberInvitationCommand;
import com.petplatform.merchant.api.command.ConfirmStaffMemberInvitationCommand;
import com.petplatform.merchant.api.command.GrantStaffMemberActionsCommand;
import com.petplatform.merchant.api.command.InviteStaffMemberCommand;
import com.petplatform.merchant.api.command.MerchantStaffMemberCommandApi;
import com.petplatform.merchant.api.command.StaffMemberLifecycleCommand;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationCommandResult;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationDetailDTO;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationPageDTO;
import com.petplatform.merchant.api.dto.MerchantStaffMemberCommandResult;
import com.petplatform.merchant.api.dto.MerchantStaffMemberPageDTO;
import com.petplatform.merchant.api.query.MerchantStaffMemberManagementQueryApi;
import com.petplatform.merchant.api.query.MyStaffInvitationQuery;
import com.petplatform.merchant.api.query.StaffInvitationManagementQuery;
import com.petplatform.merchant.api.query.StaffMemberManagementQuery;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.MerchantStaffMemberService;
import com.petplatform.merchant.biz.application.StaffLoginPhonePort;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantStaffMemberStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.Clock;
import javax.sql.DataSource;

/** Contract 54 command + management-query facade; assembled only behind the default-off switch. */
public final class MerchantStaffMemberApiImpl
        implements MerchantStaffMemberCommandApi, MerchantStaffMemberManagementQueryApi {
    private final MerchantStaffMemberService service;

    public MerchantStaffMemberApiImpl(DataSource source, SnowflakeIdGenerator ids,
            ApplicationReviewFactsReader applicationFacts,
            ApplicationValidationPorts.ProtectedValuePort protection,
            StaffLoginPhonePort loginPhones, ScheduleCapacityGuardApi guard, Clock clock) {
        this(source, ids, applicationFacts, protection, loginPhones, guard, clock, null);
    }

    /** NTF slice: a non-null publisher appends the lifecycle outbox events in-command. */
    public MerchantStaffMemberApiImpl(DataSource source, SnowflakeIdGenerator ids,
            ApplicationReviewFactsReader applicationFacts,
            ApplicationValidationPorts.ProtectedValuePort protection,
            StaffLoginPhonePort loginPhones, ScheduleCapacityGuardApi guard, Clock clock,
            IntegrationEventPublisher events) {
        this.service = new MerchantStaffMemberService(new MerchantStaffMemberStore(source, ids),
                applicationFacts, protection, loginPhones, guard, clock, events);
    }

    public static ApplicationReviewFactsReader unavailableApplicationFacts() {
        return merchantId -> {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant application review facts are not configured");
        };
    }

    public static ApplicationValidationPorts.ProtectedValuePort unavailableProtection() {
        return (purpose, value) -> {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "staff member canonical protection is not configured");
        };
    }

    public static StaffLoginPhonePort unavailableLoginPhones() {
        return (userId, phone) -> {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "staff login phone facts are not configured");
        };
    }

    @Override
    public MerchantStaffInvitationCommandResult inviteMember(InviteStaffMemberCommand command) {
        return service.inviteMember(command);
    }

    @Override
    public MerchantStaffInvitationCommandResult cancelInvitation(
            CancelStaffMemberInvitationCommand command) {
        return service.cancelInvitation(command);
    }

    @Override
    public MerchantStaffMemberCommandResult disableMember(StaffMemberLifecycleCommand command) {
        return service.disableMember(command);
    }

    @Override
    public MerchantStaffMemberCommandResult enableMember(StaffMemberLifecycleCommand command) {
        return service.enableMember(command);
    }

    @Override
    public MerchantStaffMemberCommandResult grantActions(GrantStaffMemberActionsCommand command) {
        return service.grantActions(command);
    }

    @Override
    public MerchantStaffMemberCommandResult revokeStoreGrant(StaffMemberLifecycleCommand command) {
        return service.revokeStoreGrant(command);
    }

    @Override
    public MerchantStaffMemberCommandResult confirmInvitation(
            ConfirmStaffMemberInvitationCommand command) {
        return service.confirmInvitation(command);
    }

    @Override
    public MerchantStaffMemberPageDTO listMembers(StaffMemberManagementQuery query) {
        return service.listMembers(query);
    }

    @Override
    public MerchantStaffInvitationPageDTO listInvitations(StaffInvitationManagementQuery query) {
        return service.listInvitations(query);
    }

    @Override
    public MerchantStaffInvitationDetailDTO getMyInvitation(MyStaffInvitationQuery query) {
        return service.getMyInvitation(query);
    }
}
