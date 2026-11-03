package com.petplatform.coupon.api.command;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Coupon campaign/batch operations (contract 72 §4). */
public interface AdminCouponCampaignApi {

    Page<CampaignView> listCampaigns(ListQuery query);

    CampaignDetail getCampaign(DetailQuery query);

    Receipt createCampaign(CreateCommand command);

    Receipt updateCampaign(UpdateCommand command);

    Receipt publishCampaign(StatusCommand command);

    Receipt pauseCampaign(StatusCommand command);

    Receipt endCampaign(StatusCommand command);

    Page<ClaimView> listClaims(ClaimsQuery query);

    record AdminAuthorization(String sessionId, long sessionGeneration, String operatorId) {}

    record CampaignInput(
            String name,
            String templateId,
            String audienceType,
            int perUserLimit,
            int totalLimit,
            BigDecimal budgetLimit,
            OffsetDateTime startAt,
            OffsetDateTime endAt) {}

    record ListQuery(String status, int page, int pageSize,
            AdminAuthorization authorization, QueryContext context) {}

    record DetailQuery(String campaignId, AdminAuthorization authorization, QueryContext context) {}

    record CreateCommand(CampaignInput input, AdminAuthorization authorization, CommandContext context) {}

    record UpdateCommand(String campaignId, CampaignInput input, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}

    record StatusCommand(String campaignId, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}

    record ClaimsQuery(String campaignId, int page, int pageSize,
            AdminAuthorization authorization, QueryContext context) {}

    record CampaignView(
            String campaignId,
            String name,
            String templateId,
            String templateName,
            String amountOff,
            String thresholdAmount,
            String typeLabel,
            String audienceType,
            int perUserLimit,
            int totalLimit,
            String budgetLimit,
            int issuedCount,
            String issuedAmount,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String status,
            int version) {}

    record CampaignDetail(CampaignView campaign, String ruleJson, int claimCount) {}

    record ClaimView(String claimId, String userId, String couponInstanceId, OffsetDateTime claimedAt) {}

    record Receipt(String campaignId, String status, long version, OffsetDateTime occurredAt) {}

    record Page<T>(List<T> items, int page, int pageSize, long total) {}
}
