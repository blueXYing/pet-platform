package com.petplatform.merchant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.merchant.api.dto.MerchantOrderEligibilityDTO;
import com.petplatform.merchant.api.dto.MerchantStaffDTO;
import com.petplatform.merchant.api.dto.MerchantStaffIdentityFactsDTO;
import com.petplatform.merchant.api.dto.MerchantStaffMembershipDTO;
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

    @Test
    void staffIdentityDtosSerializeIdsAndVersionAsStrings() throws Exception {
        ObjectMapper timeMapper = mapper.copy()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String large = "9007199254740993";
        String membershipJson = mapper.writeValueAsString(new MerchantStaffMembershipDTO(
                large, "商家", "9007199254740995", "门店", "STAFF", "9007199254740997"));
        JsonNode membership = mapper.readTree(membershipJson);
        assertTrue(membership.get("merchantId").isTextual());
        assertTrue(membership.get("storeId").isTextual());
        assertTrue(membership.get("staffId").isTextual());
        assertEquals("STAFF", membership.get("membershipKind").textValue());

        String factsJson = timeMapper.writeValueAsString(new MerchantStaffIdentityFactsDTO(
                large, "9007199254740995", "STAFF", true, "APPROVED", "SIGNED", "ACTIVE",
                "ACTIVE", "0123456789abcdef", java.time.OffsetDateTime.parse("2026-10-02T08:00:00.000Z"),
                null, java.util.List.of("merchant.order.verify")));
        JsonNode facts = mapper.readTree(factsJson);
        assertTrue(facts.get("merchantId").isTextual());
        assertTrue(facts.get("storeId").isTextual());
        assertTrue(facts.get("membershipEnabled").isBoolean());
        assertTrue(facts.get("authzVersion").isTextual());
        assertTrue(facts.get("checkedAt").isTextual());
        assertTrue(facts.get("staffId").isNull());
        assertEquals(java.util.List.of("merchant.order.verify"),
                mapper.readValue(facts.get("grantedActions").toString(), java.util.List.class));
    }
}
