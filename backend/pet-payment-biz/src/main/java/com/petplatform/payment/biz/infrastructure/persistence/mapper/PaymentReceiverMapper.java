package com.petplatform.payment.biz.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** MyBatis XML mapper for Contract 80 / V40 split-receiver tables. Zero SQL annotations. */
public interface PaymentReceiverMapper {
    ReceiverAccountRow selectById(long id);
    ReceiverAccountRow selectByIdForUpdate(long id);
    ReceiverAccountRow selectByMerchantId(long merchantId);
    ReceiverAccountRow selectByMerchantIdForUpdate(long merchantId);
    ReceiverAccountRow selectByReceiverNo(String receiverNo);
    ReceiverAccountRow selectByApplyId(String applyId);
    ReceiverAccountRow selectActiveByMerchantId(long merchantId);
    List<ReceiverAccountRow> selectStuckBindApplyingAccounts(Map<String, Object> params);
    List<ReceiverAccountRow> listAccounts(Map<String, Object> params);
    long countAccounts(Map<String, Object> params);
    int insertAccount(Map<String, Object> params);
    int updateAccount(Map<String, Object> params);
    int updateAccountState(Map<String, Object> params);

    ReceiverAuditRow selectAuditByRequestKeyForUpdate(byte[] requestKey);
    int insertAudit(Map<String, Object> params);
    List<ReceiverAuditRow> listAuditsByMerchant(long merchantId);

    final class ReceiverAccountRow {
        public long id;
        public long merchantId;
        public String receiverName;
        public String receiverType;
        public String acctName;
        public String acctTypeCode;
        public String acctCertificateType;
        public String acctOpenBankCode;
        public String acctOpenBankName;
        public String acctClearBankCode;
        public String licenseName;
        public String legalPersonName;
        public String legalPersonCertType;
        public String acctNoCipher;
        public String acctCertNoCipher;
        public String contactMobileCipher;
        public String licenseNoCipher;
        public String legalCertNoCipher;
        public String acctNoMasked;
        public String acctNoLast4;
        public String acctCertNoMasked;
        public String contactMobileMasked;
        public String licenseNoMasked;
        public String legalCertNoMasked;
        public String status;
        public String receiverNo;
        public String applyId;
        public String settleType;
        public String attachmentsJson;
        public String cooperationAssetId;
        public String rejectReason;
        public String lastChannelOrderNo;
        public LocalDateTime lastSyncedAt;
        public long version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    final class ReceiverAuditRow {
        public long id;
        public long merchantId;
        public long receiverAccountId;
        public String actionType;
        public byte[] requestKey;
        public String requestFingerprint;
        public String operatorType;
        public Long operatorId;
        public String reason;
        public String detailJson;
        public LocalDateTime createdAt;
    }
}
