package com.petplatform.id.core.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/** snowflake_worker_state access; SQL lives in resources/mapper/SnowflakeNodeMapper.xml. */
public interface SnowflakeNodeMapper {

    void setTimeZoneUtc();

    SnowflakeNodeRowEntity selectByNode(@Param("nodeId") int nodeId);

    SnowflakeNodeRowEntity selectByNodeForUpdate(@Param("nodeId") int nodeId);

    LocalDateTime sampleNow();

    int updateAcquired(@Param("owner") byte[] owner, @Param("fence") long fence,
                       @Param("start") long start, @Param("through") long through,
                       @Param("reservedThrough") long reservedThrough, @Param("leaseUntil") LocalDateTime leaseUntil,
                       @Param("nodeId") int nodeId, @Param("expectedFence") long expectedFence,
                       @Param("expectedReservedThrough") long expectedReservedThrough);

    int updateRenewed(@Param("through") long through, @Param("reservedThrough") long reservedThrough,
                      @Param("leaseUntil") LocalDateTime leaseUntil, @Param("nodeId") int nodeId,
                      @Param("owner") byte[] owner, @Param("fence") long fence,
                      @Param("expectedReservedThrough") long expectedReservedThrough);
}
