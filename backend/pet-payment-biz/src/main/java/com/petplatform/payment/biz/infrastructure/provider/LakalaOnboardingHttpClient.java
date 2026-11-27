package com.petplatform.payment.biz.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.AddMerInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.AddMerResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.ApplyLedgerInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.ApplyLedgerResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.BusinessRejected;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.ContractInfo;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.EcApplyInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.EcApplyResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.EcEnvelope;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.EcStatusResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.LedgerInfo;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.PreparedV2Request;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ProtocolException;
import java.util.Objects;
import java.util.function.Function;

/**
 * Typed Lakala calls for merchant acquiring onboarding (CCR-PAY-ONBOARD-001) on top of the shared signed
 * {@link LakalaV2HttpClient}: 1004 request signing, raw-byte response verification, endpoint whitelist,
 * 65,536B response cap and the no-active-transaction guard all apply unchanged. Every call classifies its
 * outcome as a verified success, a {@link LakalaV2HttpClient.ChannelRejectedException} (deterministic
 * business error) or a {@link LakalaV2HttpClient.ChannelUnknownException} (outcome unknown; callers must
 * not blindly resubmit).
 */
public final class LakalaOnboardingHttpClient {
    private final LakalaV2HttpClient client;

    public LakalaOnboardingHttpClient(LakalaV2HttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public record VerifiedAddMer(AddMerResult result, String rawResponseSha256) {}

    public record VerifiedContractInfo(ContractInfo result, String rawResponseSha256) {}

    public record VerifiedApplyLedger(ApplyLedgerResult result, String rawResponseSha256) {}

    public record VerifiedLedgerInfo(LedgerInfo result, String rawResponseSha256) {}

    public record VerifiedEcApply(EcApplyResult result, String rawResponseSha256) {}

    public record VerifiedEcStatus(EcStatusResult result, String rawResponseSha256) {}

    /** 1020 新增商户进件. */
    public VerifiedAddMer addMer(AddMerInput input, long timestampMillis, String reqId, RequestNonce nonce) {
        PreparedV2Request request = LakalaOnboardingProtocol.prepareAddMer(input, timestampMillis, reqId);
        Parsed<AddMerResult> parsed = typed(client.postV2(request, nonce),
                data -> LakalaOnboardingProtocol.parseAddMerRespData(data, input.orderNo()));
        return new VerifiedAddMer(parsed.result(), parsed.rawResponseSha256());
    }

    /** 1082 进件信息查询; accepts both documented response envelopes. */
    public VerifiedContractInfo queryContract(String orderNo, String orgCode, String contractId,
            long timestampMillis, String reqId, RequestNonce nonce) {
        PreparedV2Request request = LakalaOnboardingProtocol.prepareQueryContract(
                orderNo, orgCode, contractId, timestampMillis, reqId);
        Raw raw = raw(request, nonce);
        try {
            return new VerifiedContractInfo(LakalaOnboardingProtocol.parseContractInfoResponse(raw.body()),
                    raw.sha256());
        } catch (BusinessRejected rejected) {
            throw rejected(rejected);
        } catch (ProtocolException malformed) {
            throw malformed();
        }
    }

    /** 1284 商户分账业务开通申请 (manual review at Lakala; result arrives by callback or 1286). */
    public VerifiedApplyLedger applyLedgerMer(ApplyLedgerInput input, long timestampMillis, String reqId,
            RequestNonce nonce) {
        PreparedV2Request request = LakalaOnboardingProtocol.prepareApplyLedgerMer(input, timestampMillis, reqId);
        Parsed<ApplyLedgerResult> parsed = typed(client.postV2(request, nonce),
                data -> LakalaOnboardingProtocol.parseApplyLedgerRespData(data, input.orderNo()));
        return new VerifiedApplyLedger(parsed.result(), parsed.rawResponseSha256());
    }

    /** 1286 商户分账信息查询. */
    public VerifiedLedgerInfo queryLedgerMer(String orderNo, String orgCode, String merCupNo,
            long timestampMillis, String reqId, RequestNonce nonce) {
        PreparedV2Request request = LakalaOnboardingProtocol.prepareQueryLedgerMer(
                orderNo, orgCode, merCupNo, timestampMillis, reqId);
        Parsed<LedgerInfo> parsed = typed(client.postV2(request, nonce),
                data -> LakalaOnboardingProtocol.parseLedgerInfoRespData(data, merCupNo));
        return new VerifiedLedgerInfo(parsed.result(), parsed.rawResponseSha256());
    }

    /** 1208 电子合同申请; returns the signing link the legal person opens in WeChat. */
    public VerifiedEcApply ecApply(EcApplyInput input, long timestampMillis, String reqId, RequestNonce nonce) {
        Raw raw = raw(LakalaOnboardingProtocol.prepareEcApply(input, timestampMillis, reqId), nonce);
        return new VerifiedEcApply(
                LakalaOnboardingProtocol.parseEcApplyRespData(ecEnvelope(raw).data(), input.orderNo()),
                raw.sha256());
    }

    /** 1212 电子合同签约状态查询. */
    public VerifiedEcStatus ecQueryStatus(String orderNo, String orgId, String ecApplyId,
            long timestampMillis, String reqId, RequestNonce nonce) {
        Raw raw = raw(LakalaOnboardingProtocol.prepareEcQueryStatus(
                orderNo, orgId, ecApplyId, timestampMillis, reqId), nonce);
        return new VerifiedEcStatus(
                LakalaOnboardingProtocol.parseEcQueryRespData(ecEnvelope(raw).data(), ecApplyId), raw.sha256());
    }

    private record Parsed<T>(T result, String rawResponseSha256) {}

    private record Raw(byte[] body, String sha256) {}

    private Raw raw(PreparedV2Request request, RequestNonce nonce) {
        LakalaV2HttpClient.RawVerifiedResponse raw = client.postRaw(request, nonce);
        return new Raw(raw.rawBody(), raw.rawResponseSha256());
    }

    private static EcEnvelope ecEnvelope(Raw raw) {
        try {
            EcEnvelope envelope = LakalaOnboardingProtocol.parseEcEnvelope(raw.body());
            if (!envelope.isSuccess()) throw new BusinessRejected(envelope.code(), envelope.message());
            return envelope;
        } catch (BusinessRejected rejected) {
            throw rejected(rejected);
        } catch (ProtocolException malformed) {
            throw malformed();
        }
    }

    private static <T> Parsed<T> typed(LakalaV2HttpClient.VerifiedV2Response response,
            Function<JsonNode, T> parser) {
        if (!response.isSuccess()) {
            throw new LakalaV2HttpClient.ChannelRejectedException(response.retCode(), response.retMsg());
        }
        try {
            return new Parsed<>(parser.apply(response.respData()), response.rawResponseSha256());
        } catch (BusinessRejected rejected) {
            throw rejected(rejected);
        } catch (ProtocolException malformed) {
            throw malformed();
        }
    }

    private static LakalaV2HttpClient.ChannelRejectedException rejected(BusinessRejected rejected) {
        return new LakalaV2HttpClient.ChannelRejectedException(rejected.code(), rejected.message());
    }

    private static LakalaV2HttpClient.ChannelUnknownException malformed() {
        return LakalaV2HttpClient.unknown(LakalaV2HttpClient.Reason.CHANNEL_RESPONSE);
    }
}
