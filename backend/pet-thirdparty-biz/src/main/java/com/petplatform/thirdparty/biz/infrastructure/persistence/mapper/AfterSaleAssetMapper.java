package com.petplatform.thirdparty.biz.infrastructure.persistence.mapper;

import com.petplatform.thirdparty.biz.infrastructure.persistence.entity.AfterSaleAssetGrantEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

public interface AfterSaleAssetMapper {
    Instant now();
    AfterSaleAssetGrantEntity byRequest(@Param("actorType")String actorType,@Param("actorId")long actorId,
                                      @Param("caseId")long caseId,@Param("requestId")String requestId);
    AfterSaleAssetGrantEntity byDigest(@Param("digest")byte[] digest);
    AfterSaleAssetGrantEntity lock(@Param("id")long id);
    int insert(AfterSaleAssetGrantEntity row);
    int consume(@Param("id")long id);
    int audit(@Param("id")long id,@Param("grantId")long grantId,@Param("action")String action,
              @Param("result")String result,@Param("actorType")String actorType,@Param("actorId")long actorId,
              @Param("requestId")String requestId);
}
