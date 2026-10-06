package com.petplatform.verification.biz.application;
import com.petplatform.common.CommandContext;
import com.petplatform.order.api.command.OrderVerificationCommitApi.OperatorIdentity;
public final class CredentialPorts {
    private CredentialPorts(){}
    public interface Sessions { void requireCurrent(String userId); }
    /**
     * Mandatory trusted adapter resolving the one operator identity for this command under the
     * caller's held store guard (contract 48 K1 v0.2): OWNER, or STAFF through the contract-52
     * member action gate. Re-invoked at beforeCommit and on replay; no client-declared identity
     * is ever accepted. The returned tuple is persisted verbatim on verification rows.
     */
    public interface AttemptAuthority { OperatorIdentity requireAuthorized(CommandContext context,String merchantId,String storeId); }
}
