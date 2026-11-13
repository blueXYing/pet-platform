package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Read-only staff display-name statements (CCR-M-VERIFY-RECORD-001); SQL lives in
 * MerchantStaffNamingMapper.xml over the module-owned merchant_staff table (schema 52). Only
 * id/store_id/staff_name are selected — no phone or profile fact ever crosses this face.
 */
public interface MerchantStaffNamingMapper {

    List<StaffNameRow> selectStaffNames(
            @Param("storeId") long storeId,
            @Param("staffIds") List<Long> staffIds);

    class StaffNameRow {
        private Long staffId;
        private String staffName;

        public Long getStaffId() { return staffId; }
        public void setStaffId(Long staffId) { this.staffId = staffId; }
        public String getStaffName() { return staffName; }
        public void setStaffName(String staffName) { this.staffName = staffName; }
    }
}
