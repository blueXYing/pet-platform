package com.petplatform.refund.biz.application;
import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;

/** Trusted adapters. No default permissive authority, dictionary, moderation or recovery. */
public final class RefundApplicationPorts {
    private RefundApplicationPorts() {}
    public interface SessionAuthority { void requireCurrent(String userId); }
    public interface OwnerAuthority { void requireOwner(CommandContext context,String merchantId,String storeId); }
    public interface ReasonPolicy { void requireCode(String code); }
    public interface Moderation { Approval check(String text); }
    public record Approval(String textSha256,String policyVersion,boolean allowed) {}
    public interface Protection { byte[] protect(String purpose,byte[] value); byte[] reveal(String purpose,byte[] value); }
    public interface TaskRecovery { void recover(TaskSpec task); }
    public record TaskSpec(String taskKey,String taskType,String bizType,long bizId,Long expectedVersion,
            String payloadJson,int maxRetryCount,String retryPolicy,OffsetDateTime availableAt) {}
}
