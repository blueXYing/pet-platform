package com.petplatform.user.biz.infrastructure.persistence;

import com.petplatform.user.biz.infrastructure.persistence.entity.PetHealthRecordAuditEntity;
import com.petplatform.user.biz.infrastructure.persistence.entity.PetHealthRecordEntity;
import com.petplatform.user.biz.infrastructure.persistence.mapper.PetHealthRecordMapper;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * This Owner's contract-68 tables (user_pet_health_record + its metadata audit). SQL errors never
 * escape with bound values. The audit insert is part of the caller's execution transaction —
 * either the record and the audit land together or neither does (contract 68 hard rule 4).
 */
public final class PetHealthRecordStore {

    public record RecordRow(long id, long userId, long petId, String recordType, LocalDate recordDate,
                            String vaccineName, Integer doseNumber, String dewormingType,
                            String productName, LocalDate nextRecordDate, String note, String status) {}

    private final SqlSessionTemplate template;

    public PetHealthRecordStore(DataSource dataSource) {
        this.template = UserMybatis.template(dataSource);
    }

    public List<RecordRow> listActiveRecords(long petId, String recordType) {
        return records().selectActiveRecords(petId, recordType).stream()
                .map(PetHealthRecordStore::toRow).toList();
    }

    public long countActive(long petId, String recordType) {
        return records().countActiveByType(petId, recordType);
    }

    public void insertRecord(RecordRow record) {
        int changed = records().insertRecord(toEntity(record));
        if (changed != 1) throw new IllegalStateException("Health record insert failed; transaction rolled back");
    }

    /** Same-transaction audit line: action/request metadata only, never health content. */
    public void insertAudit(long id, long actorId, String actionCode, long recordId, long petId,
                            String recordType, String requestKey, String requestId, String traceId) {
        PetHealthRecordAuditEntity audit = new PetHealthRecordAuditEntity();
        audit.setId(id);
        audit.setActorId(actorId);
        audit.setActionCode(actionCode);
        audit.setRecordId(recordId);
        audit.setPetId(petId);
        audit.setRecordType(recordType);
        audit.setRequestKey(bytes(requestKey));
        audit.setRequestId(bytes(requestId));
        audit.setTraceId(traceId);
        int changed = records().insertAudit(audit);
        if (changed != 1) throw new IllegalStateException("Health record audit insert failed; transaction rolled back");
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private PetHealthRecordMapper records() {
        return template.getMapper(PetHealthRecordMapper.class);
    }

    private static RecordRow toRow(PetHealthRecordEntity row) {
        return new RecordRow(row.getId(), row.getUserId(), row.getPetId(), row.getRecordType(),
                row.getRecordDate(), row.getVaccineName(), row.getDoseNumber(), row.getDewormingType(),
                row.getProductName(), row.getNextRecordDate(), row.getNote(), row.getStatus());
    }

    private static PetHealthRecordEntity toEntity(RecordRow record) {
        PetHealthRecordEntity entity = new PetHealthRecordEntity();
        entity.setId(record.id());
        entity.setUserId(record.userId());
        entity.setPetId(record.petId());
        entity.setRecordType(record.recordType());
        entity.setRecordDate(record.recordDate());
        entity.setVaccineName(record.vaccineName());
        entity.setDoseNumber(record.doseNumber());
        entity.setDewormingType(record.dewormingType());
        entity.setProductName(record.productName());
        entity.setNextRecordDate(record.nextRecordDate());
        entity.setNote(record.note());
        entity.setStatus(record.status());
        return entity;
    }
}
