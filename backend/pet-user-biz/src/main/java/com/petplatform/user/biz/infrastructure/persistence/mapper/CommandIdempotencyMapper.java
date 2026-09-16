package com.petplatform.user.biz.infrastructure.persistence.mapper;

import com.petplatform.user.biz.infrastructure.persistence.entity.CommandIdempotencyEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** command_idempotency binding statements (SQL 14, supplement 23 §5), SQL kept verbatim. */
public interface CommandIdempotencyMapper {

    @Insert("""
            INSERT INTO command_idempotency
            (id,request_key,canonical_version,params_sha256,params_canonical,status,created_at)
            VALUES (#{id},#{requestKey},#{canonicalVersion},#{paramsSha256},#{paramsCanonical},'RESERVED',NOW(3))
            """)
    int insertBinding(@Param("id") long id, @Param("requestKey") String requestKey,
                      @Param("canonicalVersion") String canonicalVersion,
                      @Param("paramsSha256") String paramsSha256,
                      @Param("paramsCanonical") byte[] paramsCanonical);

    @Select("""
            SELECT id,request_key,canonical_version,params_sha256,params_canonical,status,receipt_json
            FROM command_idempotency WHERE request_key=#{requestKey} FOR UPDATE
            """)
    CommandIdempotencyEntity selectBindingForUpdate(@Param("requestKey") String requestKey);

    @Update("""
            UPDATE command_idempotency SET status='SUCCEEDED',receipt_json=#{receiptJson},succeeded_at=NOW(3)
            WHERE request_key=#{requestKey} AND status='RESERVED'
            """)
    int markSucceeded(@Param("requestKey") String requestKey, @Param("receiptJson") String receiptJson);
}
