package com.petplatform.service.biz.infrastructure.persistence;

import com.petplatform.service.biz.infrastructure.persistence.mapper.ServiceBookingMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** Joins the caller's guarded transaction without moving category lookup into the service lock. */
public final class ServiceBookingStore {
    public record Row(long merchantId, long storeId, String serviceName, long categoryId,
            BigDecimal price, int durationMinutes, String fulfillmentType, String description,
            String applicablePetTypes, int verificationRequired, long version, String status) {}

    private final ServiceBookingMapper mapper;

    public ServiceBookingStore(DataSource source) {
        mapper = ServiceMybatis.template(source).getMapper(ServiceBookingMapper.class);
    }

    public List<Row> lockService(long serviceId) {
        return mapper.lockService(serviceId).stream().map(ServiceBookingStore::row).toList();
    }

    public String categoryName(long categoryId) {
        return mapper.categoryName(categoryId);
    }

    private static Row row(Map<String, Object> columns) {
        return new Row(number(columns, "merchant_id"), number(columns, "store_id"),
                (String) columns.get("service_name"), number(columns, "category_id"),
                (BigDecimal) columns.get("price"), (int) number(columns, "duration_minutes"),
                (String) columns.get("fulfillment_type"), (String) columns.get("description"),
                (String) columns.get("applicable_pet_types"), flag(columns.get("verification_required")),
                number(columns, "version"), (String) columns.get("status"));
    }

    private static long number(Map<String, Object> columns, String name) {
        Object value = columns.get(name);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static int flag(Object value) {
        if (value instanceof Boolean bool) return bool ? 1 : 0;
        return value == null ? 0 : ((Number) value).intValue();
    }
}
