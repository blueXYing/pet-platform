package com.petplatform.payment.biz.settlement;

import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedApplyBind;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedReceiverAction;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedReceiverDetail;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedRelationApplyPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedUploadFile;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyBindInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ListRelationApplyInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ModifyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.QueryReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.UploadFileInput;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;

/** Adapts {@link LakalaV2HttpClient} with fresh timestamp, reqId, and 1004 nonce per outbound request. */
public final class LakalaReceiverChannel implements ReceiverChannel {
    private static final char[] ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private final LakalaV2HttpClient client;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LakalaReceiverChannel(LakalaV2HttpClient client, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public VerifiedUploadFile uploadFile(UploadFileInput input) {
        return client.uploadFile(input, nowMillis(), nextReqId(), nextNonce());
    }

    @Override
    public VerifiedReceiverAction applyLedgerReceiver(ApplyReceiverInput input) {
        return client.applyLedgerReceiver(input, nowMillis(), nextReqId(), nextNonce());
    }

    @Override
    public VerifiedReceiverAction modifyLedgerReceiver(ModifyReceiverInput input) {
        return client.modifyLedgerReceiver(input, nowMillis(), nextReqId(), nextNonce());
    }

    @Override
    public VerifiedReceiverDetail queryReceiverDetail(QueryReceiverInput input) {
        return client.queryReceiverDetail(input, nowMillis(), nextReqId(), nextNonce());
    }

    @Override
    public VerifiedApplyBind applyBind(ApplyBindInput input) {
        return client.applyBind(input, nowMillis(), nextReqId(), nextNonce());
    }

    @Override
    public VerifiedRelationApplyPage listRelationApply(ListRelationApplyInput input) {
        return client.listRelationApply(input, nowMillis(), nextReqId(), nextNonce());
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
}
