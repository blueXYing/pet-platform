package com.petplatform.user.biz.infrastructure.persistence.mapper;

import com.petplatform.user.biz.infrastructure.persistence.entity.CommandIdempotencyEntity;
import org.apache.ibatis.annotations.Param;

/** command_idempotency bindings; SQL lives in resources/mapper/CommandIdempotencyMapper.xml. */
public interface CommandIdempotencyMapper {

    int insertBinding(@Param("id") long id, @Param("requestKey") String requestKey,
                      @Param("canonicalVersion") String canonicalVersion,
                      @Param("paramsSha256") String paramsSha256,
                      @Param("paramsCanonical") byte[] paramsCanonical);

    CommandIdempotencyEntity selectBindingForUpdate(@Param("requestKey") String requestKey);

    int markSucceeded(@Param("requestKey") String requestKey, @Param("receiptJson") String receiptJson);
}
