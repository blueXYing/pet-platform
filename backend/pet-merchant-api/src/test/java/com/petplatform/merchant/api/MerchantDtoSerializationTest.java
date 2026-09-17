package com.petplatform.merchant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.merchant.api.dto.MerchantOrderEligibilityDTO;
import com.petplatform.merchant.api.dto.MerchantStaffDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDTO;
import org.junit.jupiter.api.Test;

/** IDs and decimal coordinates remain JSON strings at the API boundary. */
class MerchantDtoSerializationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void merchantDtosSerializeIdsAndVersionAsStrings() throws Exception {
        String large = "9007199254740993";
        String storeJson = mapper.writeValueAsString(new MerchantStoreDTO(
                large, "9007199254740995", "商家", "门店", "地址", "121.4737", "31.2304",
                "138****2222", "ACTIVE", "ACTIVE", "9223372036854775807"));
        JsonNode store = mapper.readTree(storeJson);
        assertTrue(store.get("merchantId").isTextual());
        assertTrue(store.get("storeId").isTextual());
        assertTrue(store.get("longitude").isTextual());
        assertTrue(store.get("version").isTextual());
        assertEquals(large, store.get("merchantId").textValue());
        assertEquals("9223372036854775807", store.get("version").textValue());

        String staffJson = mapper.writeValueAsString(new MerchantStaffDTO(
                large, "9007199254740995", "9007199254740997", "员工", "139****3333",
                "ACTIVE", true, "1"));
        JsonNode staff = mapper.readTree(staffJson);
        assertTrue(staff.get("staffId").isTextual());
        assertTrue(staff.get("serviceEnabled").isBoolean());

        String eligibilityJson = mapper.writeValueAsString(new MerchantOrderEligibilityDTO(
                large, "9007199254740995", true, true, false));
        JsonNode eligibility = mapper.readTree(eligibilityJson);
        assertTrue(eligibility.get("merchantId").isTextual());
        assertTrue(eligibility.get("acceptsNewOrders").isBoolean());
    }
}
