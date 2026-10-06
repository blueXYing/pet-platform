package com.petplatform.aftersale.biz.application;

import com.petplatform.common.CommandContext;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi.RouteParty;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;

/** Trusted boot adapters. No permissive production fallback is provided. */
public final class AfterSalePorts {
    private AfterSalePorts() {}
    public record Resource(String afterSaleId, String orderId, String userId, String merchantId,
            String storeId, String cityCode, String scopeVersion) {}
    public interface Authority {
        void requireUser(CommandContext context);
        void requireOwner(CommandContext context, Resource resource);
        /** Returns the current nonempty authorization revision. */
        AdminAuthority requireAdmin(CommandContext context, Resource resource, String actionCode);
        /** Rechecks a real buyer, current OWNER or admin and returns USER/MERCHANT/OPS plus revision. */
        ReadAuthority requireRead(CommandContext context, Resource resource);
        default ReadAuthority requireRead(CommandContext context, Resource resource, RouteParty routeParty) {
            throw new UnsupportedOperationException("explicit party authority unavailable");
        }
        default String requireBuyerRead(CommandContext context) {
            throw new UnsupportedOperationException("buyer collection authority unavailable");
        }
        default String requireStoreRead(CommandContext context, RouteParty routeParty, String merchantId, String storeId) {
            throw new UnsupportedOperationException("store collection authority unavailable");
        }
    }
    public record AdminAuthority(String authzVersion, String scopeVersion) {}
    public record ReadAuthority(String party, String authzVersion) {}
    public interface ReasonPolicy {
        void requireCodes(String typeCode, String demandCode);
        default com.petplatform.aftersale.api.query.AfterSaleQueryApi.Options options() {
            throw new com.petplatform.common.ApiException(com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Aftersale catalog unavailable");
        }
    }
    public interface Moderation { Approval check(String text); }
    public record Approval(String textSha256, String policyVersion, boolean allowed) {}
    public interface Protection {
        byte[] protect(String purpose, byte[] value);
        byte[] reveal(String purpose, byte[] value);
    }
    /** Local current reads only; owner/purpose/READY/hash/version must be revalidated in the caller transaction. */
    public interface Assets {
        List<Asset> requireReadyOwned(String ownerUserId, List<String> assetIds, DataSource transactionSource);
        void requireStillReady(Asset asset, DataSource transactionSource);
    }
    public record Asset(String assetId, String ownerUserId, String objectSha256,
            String objectVersionRef, String factVersion, String mediaType, long bytes) {}
    public interface Tasks {
        void enqueue(TaskSpec task);
        void recover(TaskSpec task);
        void requireTrusted(CommandContext context, TaskSpec task);
    }
    public record TaskSpec(String taskKey, String taskType, String bizType, long bizId,
            Long expectedVersion, String payloadJson, int maxRetryCount, String retryPolicy,
            OffsetDateTime availableAt) {}
}
