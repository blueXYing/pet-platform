package com.petplatform.payment.biz.settlement;

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

/** Channel port for Lakala v2 MMS split-receiver operations. Always called outside DB transactions. */
public interface ReceiverChannel {
    VerifiedUploadFile uploadFile(UploadFileInput input);
    VerifiedReceiverAction applyLedgerReceiver(ApplyReceiverInput input);
    VerifiedReceiverAction modifyLedgerReceiver(ModifyReceiverInput input);
    VerifiedReceiverDetail queryReceiverDetail(QueryReceiverInput input);
    VerifiedApplyBind applyBind(ApplyBindInput input);
    VerifiedRelationApplyPage listRelationApply(ListRelationApplyInput input);
}
