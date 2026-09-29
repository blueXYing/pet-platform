package com.petplatform.order.biz.application;
/** Production requires explicit protection and moderation providers; no permissive defaults. */
public final class MerchantOrderPorts {
    private MerchantOrderPorts() {}
    public interface Protection {
        byte[] protect(String purpose, byte[] plaintext);
        byte[] reveal(String purpose, byte[] protectedValue);
    }
    public interface Moderation {
        Approval check(String exactText);
    }
    public record Approval(String textSha256, String policyVersion, boolean allowed) {}
    /** Rechecks the current session at admission/execution/replay, beyond initial HTTP authentication. */
    public interface SessionAuthority { void requireCurrent(String userId); }
}
