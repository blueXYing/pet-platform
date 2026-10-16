package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Contract-63 v0.2 merchant governance SQL: merchant disable CAS + append-only audit row. */
public interface AdminMerchantDispositionMapper {

    MerchantRow selectMerchantForUpdate(@Param("merchantId") long merchantId);

    int disableMerchant(@Param("merchantId") long merchantId, @Param("now") LocalDateTime now);

    int insertAction(@Param("id") long id, @Param("merchantId") long merchantId,
            @Param("reason") String reason, @Param("statusBefore") String statusBefore,
            @Param("merchantVersion") long merchantVersion, @Param("operatorId") long operatorId,
            @Param("actedAt") LocalDateTime actedAt, @Param("authzVersion") String authzVersion,
            @Param("requestId") String requestId, @Param("traceId") String traceId,
            @Param("now") LocalDateTime now);

    List<ActionRow> selectByRequest(@Param("requestId") String requestId);

    class MerchantRow {
        public long id;
        public String status;
        public long version;
    }

    class ActionRow {
        public long id;
        public long merchantId;
        public String reason;
        public String statusBefore;
        public long merchantVersion;
        public long actedByOperatorId;
        public LocalDateTime actedAt;
        public String authzVersion;
        public String requestId;
        public String traceId;
    }
}
