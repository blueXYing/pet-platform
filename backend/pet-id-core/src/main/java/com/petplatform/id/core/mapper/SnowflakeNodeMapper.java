package com.petplatform.id.core.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** snowflake_worker_state statements, SQL kept verbatim (PLAT-002 S2 / PLAT-006). */
public interface SnowflakeNodeMapper {

    @Update("SET SESSION time_zone = '+00:00'")
    void setTimeZoneUtc();

    @Select("SELECT * FROM snowflake_worker_state WHERE node_id=#{nodeId}")
    SnowflakeNodeRowEntity selectByNode(@Param("nodeId") int nodeId);

    @Select("SELECT * FROM snowflake_worker_state WHERE node_id=#{nodeId} FOR UPDATE")
    SnowflakeNodeRowEntity selectByNodeForUpdate(@Param("nodeId") int nodeId);

    @Select("SELECT NOW(3)")
    LocalDateTime sampleNow();

    @Update("""
            UPDATE snowflake_worker_state
            SET owner_incarnation=#{owner}, fence=#{fence}, grant_start=#{start}, grant_through=#{through}, reserved_through=#{reservedThrough},
                lease_until=#{leaseUntil}, updated_at=NOW(3)
            WHERE node_id=#{nodeId} AND fence=#{expectedFence} AND reserved_through=#{expectedReservedThrough}
            """)
    int updateAcquired(@Param("owner") byte[] owner, @Param("fence") long fence,
                       @Param("start") long start, @Param("through") long through,
                       @Param("reservedThrough") long reservedThrough, @Param("leaseUntil") LocalDateTime leaseUntil,
                       @Param("nodeId") int nodeId, @Param("expectedFence") long expectedFence,
                       @Param("expectedReservedThrough") long expectedReservedThrough);

    @Update("""
            UPDATE snowflake_worker_state
            SET grant_through=#{through}, reserved_through=#{reservedThrough}, lease_until=#{leaseUntil}, updated_at=NOW(3)
            WHERE node_id=#{nodeId} AND owner_incarnation=#{owner} AND fence=#{fence} AND reserved_through=#{expectedReservedThrough}
            """)
    int updateRenewed(@Param("through") long through, @Param("reservedThrough") long reservedThrough,
                      @Param("leaseUntil") LocalDateTime leaseUntil, @Param("nodeId") int nodeId,
                      @Param("owner") byte[] owner, @Param("fence") long fence,
                      @Param("expectedReservedThrough") long expectedReservedThrough);
}
