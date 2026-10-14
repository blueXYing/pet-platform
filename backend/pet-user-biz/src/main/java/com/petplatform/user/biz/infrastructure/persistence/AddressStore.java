package com.petplatform.user.biz.infrastructure.persistence;

import com.petplatform.user.biz.infrastructure.persistence.entity.AddressEntity;
import com.petplatform.user.biz.infrastructure.persistence.mapper.AddressMapper;
import java.util.List;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;

/** This Owner's tables (user_address plus the shared user_account guard read). SQL errors never escape with bound values. */
public final class AddressStore {

    public record AddressRow(long id, long userId, String contactName, String contactPhone,
                             String region, String detail, boolean isDefault, String status) {}

    private final SqlSessionTemplate template;

    public AddressStore(DataSource dataSource) {
        this.template = UserMybatis.template(dataSource);
    }

    /** Frozen-user write guard; the user_account read is shared with PetMapper (same module). */
    public com.petplatform.user.biz.infrastructure.persistence.PetStore.UserRow findUser(long userId) {
        com.petplatform.user.biz.infrastructure.persistence.entity.UserAccountEntity row =
                template.getMapper(com.petplatform.user.biz.infrastructure.persistence.mapper.PetMapper.class)
                        .selectUserById(userId);
        return row == null ? null
                : new com.petplatform.user.biz.infrastructure.persistence.PetStore.UserRow(
                        row.getId(), row.getPhone(), row.getNickname(), row.getStatus());
    }

    public AddressRow findAddress(long addressId, boolean forUpdate) {
        AddressEntity row = forUpdate ? addresses().selectAddressByIdForUpdate(addressId)
                : addresses().selectAddressById(addressId);
        return row == null ? null : toRow(row);
    }

    public List<AddressRow> listActiveAddresses(long ownerUserId) {
        return addresses().selectActiveAddressesByOwner(ownerUserId).stream()
                .map(AddressStore::toRow).toList();
    }

    public long countActive(long ownerUserId) {
        return addresses().countActiveByOwner(ownerUserId);
    }

    public void insertAddress(AddressRow address) {
        int changed = addresses().insertAddress(toEntity(address));
        if (changed != 1) throw new IllegalStateException("Address insert failed; transaction rolled back");
    }

    public void updateAddressFields(AddressRow address) {
        int changed = addresses().updateAddressFields(toEntity(address));
        if (changed != 1) throw new IllegalStateException("Address update failed; transaction rolled back");
    }

    public void softDelete(long addressId, long ownerUserId) {
        int changed = addresses().softDeleteAddress(addressId, ownerUserId);
        if (changed != 1) throw new IllegalStateException("Address delete failed; transaction rolled back");
    }

    /** Sets the default flag on one owned row (row lock taken by the caller's FOR UPDATE read). */
    public void markDefault(long addressId, long ownerUserId) {
        int changed = addresses().markDefault(addressId, ownerUserId);
        if (changed != 1) throw new IllegalStateException("Address default flip failed; transaction rolled back");
    }

    public void clearDefault(long ownerUserId, long keepAddressId) {
        addresses().clearDefaultExcept(ownerUserId, keepAddressId);
    }

    private AddressMapper addresses() {
        return template.getMapper(AddressMapper.class);
    }

    private static AddressRow toRow(AddressEntity row) {
        return new AddressRow(row.getId(), row.getUserId(), row.getContactName(), row.getContactPhone(),
                row.getRegion(), row.getDetail(), Boolean.TRUE.equals(row.getIsDefault()), row.getStatus());
    }

    private static AddressEntity toEntity(AddressRow address) {
        AddressEntity entity = new AddressEntity();
        entity.setId(address.id());
        entity.setUserId(address.userId());
        entity.setContactName(address.contactName());
        entity.setContactPhone(address.contactPhone());
        entity.setRegion(address.region());
        entity.setDetail(address.detail());
        entity.setIsDefault(address.isDefault());
        entity.setStatus(address.status());
        return entity;
    }
}
