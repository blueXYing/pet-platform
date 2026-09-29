package com.petplatform.verification.biz.application;
import com.petplatform.common.CommandContext;
public final class CredentialPorts {
    private CredentialPorts(){}
    public interface Sessions { void requireCurrent(String userId); }
    /** Mandatory trusted adapter; no production merchant identity provider is invented in this slice. */
    public interface AttemptAuthority { void requireAuthorized(CommandContext context,String merchantId,String storeId); }
}
