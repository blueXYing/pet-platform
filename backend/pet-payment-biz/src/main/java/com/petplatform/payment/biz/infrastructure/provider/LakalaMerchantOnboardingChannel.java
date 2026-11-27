package com.petplatform.payment.biz.infrastructure.provider;

import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.AddMerInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.ApplyLedgerInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.EcApplyInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingProtocol.EcContractText;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyBindInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.BindAuditStatus;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ListRelationApplyInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ProtocolException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.UploadFileInput;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Lakala implementation of {@link MerchantOnboardingChannel} on the shared signed clients, with a fresh
 * timestamp, request id and nonce per outbound call. Channel exceptions are translated into the port's
 * {@link Rejected} / {@link Unknown} classification; protocol details never leave this class.
 */
public final class LakalaMerchantOnboardingChannel implements MerchantOnboardingChannel {
    private static final char[] ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private final LakalaOnboardingHttpClient onboarding;
    private final LakalaV2HttpClient v2;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LakalaMerchantOnboardingChannel(LakalaOnboardingHttpClient onboarding, LakalaV2HttpClient v2,
            Clock clock) {
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.v2 = Objects.requireNonNull(v2, "v2");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Uploaded uploadAttachment(String orderNo, String orgCode, String attType, String extName,
            byte[] content) {
        try {
            var uploaded = v2.uploadFile(new UploadFileInput(orderNo, orgCode, attType, extName, content),
                    nowMillis(), nextReqId(), nextNonce());
            return new Uploaded(uploaded.result().attType(), uploaded.result().attFileId());
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            throw new Rejected(rejected.retCode(), rejected.retMsg());
        } catch (LakalaV2HttpClient.ChannelUnknownException unknown) {
            throw new Unknown(unknown.reason().name(), unknown);
        } catch (ProtocolException malformed) {
            throw new Unknown("CHANNEL_RESPONSE", malformed);
        }
    }

    @Override
    public EcApplied applyElectronicContract(EcApplication application) {
        try {
            var applied = onboarding.ecApply(new EcApplyInput(application.orderNo(), application.orgId(),
                    application.legalName(), application.legalIdCard(), application.legalMobile(),
                    application.licenseNo(), application.licenseName(), application.openningBankCode(),
                    application.openningBankName(), application.acctTypeCode(), application.acctNo(),
                    application.acctName(), new EcContractText(application.partnerName(),
                    application.cooperationAgreementName(), application.merchantLowestRatio(),
                    application.platformName(), application.signDate(), application.legalName()),
                    application.retUrl()), nowMillis(), nextReqId(), nextNonce());
            return new EcApplied(applied.result().ecApplyId(), applied.result().resultUrl());
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            throw new Rejected(rejected.retCode(), rejected.retMsg());
        } catch (LakalaV2HttpClient.ChannelUnknownException unknown) {
            throw new Unknown(unknown.reason().name(), unknown);
        } catch (ProtocolException malformed) {
            throw new Unknown("CHANNEL_RESPONSE", malformed);
        }
    }

    @Override
    public EcProgress queryElectronicContract(String orderNo, String orgId, String ecApplyId) {
        try {
            var status = onboarding.ecQueryStatus(orderNo, orgId, ecApplyId, nowMillis(), nextReqId(), nextNonce());
            return new EcProgress(status.result().completed(), status.result().ecNo());
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            throw new Rejected(rejected.retCode(), rejected.retMsg());
        } catch (LakalaV2HttpClient.ChannelUnknownException unknown) {
            throw new Unknown(unknown.reason().name(), unknown);
        } catch (ProtocolException malformed) {
            throw new Unknown("CHANNEL_RESPONSE", malformed);
        }
    }

    @Override
    public ContractAdded addMerchant(ContractApplication application) {
        List<LakalaOnboardingProtocol.FeeRate> fees = new ArrayList<>();
        for (FeeRate fee : application.feeData()) {
            fees.add(new LakalaOnboardingProtocol.FeeRate(
                    fee.feeRateTypeCode(), fee.feeRateTypeName(), fee.feeRatePct()));
        }
        List<LakalaOnboardingProtocol.FileItem> files = new ArrayList<>();
        for (Uploaded file : application.fileData()) {
            files.add(new LakalaOnboardingProtocol.FileItem(file.attType(), file.attFileId()));
        }
        try {
            var added = onboarding.addMer(new AddMerInput(application.orderNo(), application.posType(),
                    application.orgCode(), application.merRegName(), application.merBizName(),
                    application.merRegDistCode(), application.merRegAddr(), application.mccCode(),
                    application.merBlisName(), application.merBlis(), application.merBlisStDt(),
                    application.merBlisExpDt(), application.merBusiContent(), application.larName(),
                    application.larIdcard(), application.larIdcardStDt(), application.larIdcardExpDt(),
                    application.merContactMobile(), application.merContactName(), application.openningBankCode(),
                    application.openningBankName(), application.clearingBankCode(), application.acctNo(),
                    application.acctName(), application.acctTypeCode(), application.acctIdcard(),
                    application.acctIdDt(), application.settlePeriod(), application.retUrl(),
                    application.contractNo(), fees, files), nowMillis(), nextReqId(), nextNonce());
            return new ContractAdded(added.result().contractId());
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            throw new Rejected(rejected.retCode(), rejected.retMsg());
        } catch (LakalaV2HttpClient.ChannelUnknownException unknown) {
            throw new Unknown(unknown.reason().name(), unknown);
        } catch (ProtocolException malformed) {
            throw new Unknown("CHANNEL_RESPONSE", malformed);
        }
    }

    @Override
    public ContractProgress queryContract(String orderNo, String orgCode, String contractId) {
        try {
            var info = onboarding.queryContract(orderNo, orgCode, contractId, nowMillis(), nextReqId(), nextNonce());
            return new ContractProgress(info.result().contractId(), info.result().contractStatus(),
                    info.result().contractMemo(), info.result().merInnerNo(), info.result().merCupNo(),
                    info.result().firstTermNo());
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            throw new Rejected(rejected.retCode(), rejected.retMsg());
        } catch (LakalaV2HttpClient.ChannelUnknownException unknown) {
            throw new Unknown(unknown.reason().name(), unknown);
        } catch (ProtocolException malformed) {
            throw new Unknown("CHANNEL_RESPONSE", malformed);
        }
    }

    @Override
    public LedgerApplied applyLedger(LedgerApplication application) {
        try {
            var applied = onboarding.applyLedgerMer(new ApplyLedgerInput(application.orderNo(),
                    application.orgCode(), application.merCupNo(), application.contactMobile(),
                    application.splitLowestRatio(), application.eleContractNo(), application.retUrl()),
                    nowMillis(), nextReqId(), nextNonce());
            return new LedgerApplied(applied.result().applyId());
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            throw new Rejected(rejected.retCode(), rejected.retMsg());
        } catch (LakalaV2HttpClient.ChannelUnknownException unknown) {
            throw new Unknown(unknown.reason().name(), unknown);
        } catch (ProtocolException malformed) {
            throw new Unknown("CHANNEL_RESPONSE", malformed);
        }
    }

    @Override
    public LedgerProgress queryLedger(String orderNo, String orgCode, String merCupNo) {
        try {
            var info = onboarding.queryLedgerMer(orderNo, orgCode, merCupNo, nowMillis(), nextReqId(), nextNonce());
            return new LedgerProgress(info.result().valid());
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            throw new Rejected(rejected.retCode(), rejected.retMsg());
        } catch (LakalaV2HttpClient.ChannelUnknownException unknown) {
            throw new Unknown(unknown.reason().name(), unknown);
        } catch (ProtocolException malformed) {
            throw new Unknown("CHANNEL_RESPONSE", malformed);
        }
    }

    @Override
    public BindApplied applyBind(BindApplication application) {
        try {
            var bound = v2.applyBind(new ApplyBindInput(application.orderNo(), application.orgCode(), null,
                    application.merCupNo(), application.receiverNo(), application.entrustFileName(),
                    application.entrustFile().attFileId(), application.retUrl(), List.of()),
                    nowMillis(), nextReqId(), nextNonce());
            return new BindApplied(bound.result().applyId());
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            throw new Rejected(rejected.retCode(), rejected.retMsg());
        } catch (LakalaV2HttpClient.ChannelUnknownException unknown) {
            throw new Unknown(unknown.reason().name(), unknown);
        } catch (ProtocolException malformed) {
            throw new Unknown("CHANNEL_RESPONSE", malformed);
        }
    }

    @Override
    public BindProgress queryBind(String orderNo, String orgCode, String merCupNo, String receiverNo,
            String applyId) {
        try {
            var page = v2.listRelationApply(new ListRelationApplyInput(orderNo, orgCode, null, merCupNo,
                    receiverNo, applyId, "ADD", null, 10, 1), nowMillis(), nextReqId(), nextNonce());
            for (var item : page.result().list()) {
                if (!receiverNo.equals(item.receiverNo())) continue;
                if (applyId == null || applyId.isBlank() || applyId.equals(item.applyId())) {
                    if (item.auditStatus() == BindAuditStatus.PENDING) {
                        return new BindProgress(false, false, item.remark());
                    }
                    return new BindProgress(true, item.auditStatus() == BindAuditStatus.APPROVED, item.remark());
                }
            }
            return new BindProgress(false, false, null);
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            throw new Rejected(rejected.retCode(), rejected.retMsg());
        } catch (LakalaV2HttpClient.ChannelUnknownException unknown) {
            throw new Unknown(unknown.reason().name(), unknown);
        } catch (ProtocolException malformed) {
            throw new Unknown("CHANNEL_RESPONSE", malformed);
        }
    }

    private long nowMillis() {
        return Math.max(1L, clock.millis());
    }

    private String nextReqId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private RequestNonce nextNonce() {
        long seconds = Math.max(1L, clock.instant().getEpochSecond());
        char[] chars = new char[12];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = ALNUM[random.nextInt(ALNUM.length)];
        }
        return new RequestNonce(Long.toString(seconds), new String(chars));
    }

    /** 14-digit channel time plus 8 random digits, exactly the orderNo shape every document shows. */
    public static String nextOrderNo(Clock clock, SecureRandom random) {
        String time = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
                .format(clock.instant().atZone(java.time.ZoneOffset.ofHours(8)));
        return time + String.format("%08d", random.nextInt(100_000_000));
    }

}
