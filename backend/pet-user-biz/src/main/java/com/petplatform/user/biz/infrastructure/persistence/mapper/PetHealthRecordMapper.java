package com.petplatform.user.biz.infrastructure.persistence.mapper;

import com.petplatform.user.biz.infrastructure.persistence.entity.PetHealthRecordAuditEntity;
import com.petplatform.user.biz.infrastructure.persistence.entity.PetHealthRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** SQL68 face: contract-68 record rows plus the same-transaction audit rows. */
public interface PetHealthRecordMapper {

    List<PetHealthRecordEntity> selectActiveRecords(
            @Param("petId") long petId, @Param("recordType") String recordType);

    long countActiveByType(@Param("petId") long petId, @Param("recordType") String recordType);

    int insertRecord(PetHealthRecordEntity record);

    int insertAudit(PetHealthRecordAuditEntity audit);
}
