package com.petplatform.order.biz.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * Merchant customer note write statements (contract 69 expansion, resources/mapper/
 * OrderCustomerNoteMapper.xml). Companion table 69-Merchant-Customer-Note plus the 45号
 * order_merchant_command binding rows (distinct command namespace); audit keeps field names
 * only. Every statement is store+user double-key scoped; execution happens after the service's
 * guard + real-OWNER proof and inside its own REQUIRES_NEW transaction.
 */
public interface OrderCustomerNoteMapper {

    void setUtcTimeZone();

    void setLockWaitTimeout();

    /** Anti-fabrication guard: the note target must be a derived customer of this store. */
    int customerHasStoreOrder(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("userId") long userId);

    int reserve(Map<String, Object> values);

    Binding binding(Map<String, Object> values);

    int succeed(@Param("id") long id, @Param("result") String result);

    NoteRow lockNote(@Param("storeId") long storeId, @Param("userId") long userId);

    int insertNote(@Param("storeId") long storeId, @Param("merchantId") long merchantId,
            @Param("userId") long userId, @Param("note") String note,
            @Param("now") LocalDateTime now);

    int updateNote(@Param("storeId") long storeId, @Param("merchantId") long merchantId,
            @Param("userId") long userId, @Param("note") String note,
            @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now);

    int insertAudit(Map<String, Object> values);

    final class Binding {
        public Long id;
        public String canonicalVersion;
        public String payloadSha256;
        public byte[] canonicalBytes;
        public String state;
        public String resultBytes;
        public Integer resultVersion;
    }

    final class NoteRow {
        public Long storeId;
        public Long merchantId;
        public Long userId;
        public String note;
        public Long version;
        public LocalDateTime updatedAt;
    }
}
