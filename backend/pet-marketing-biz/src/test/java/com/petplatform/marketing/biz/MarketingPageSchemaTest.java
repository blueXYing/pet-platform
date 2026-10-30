package com.petplatform.marketing.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.marketing.biz.application.MarketingCanonical;
import com.petplatform.marketing.biz.application.MarketingPageSchema;
import com.petplatform.marketing.biz.infrastructure.persistence.MarketingStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MarketingPageSchemaTest {

    @Test
    void acceptsWhitelistedComponentsAndProducesStableJson() {
        String schema = """
                {"schemaVersion":1,"components":[
                  {"type":"hero","title":"秋季洗护","subtitle":"到店有礼","tag":"限时"},
                  {"type":"coupon","buttonText":"立即领取"},
                  {"type":"storeList","title":"参与门店"},
                  {"type":"richText","title":"说明","paragraphs":["以门店实际服务为准"]},
                  {"type":"notice","text":"数量有限"},
                  {"type":"buttonGroup","buttons":[{"label":"去看看","action":{"type":"STORE","targetId":"123"}}]},
                  {"type":"spacer","height":24},
                  {"type":"divider"}
                ]}""";
        String normalized = MarketingPageSchema.validate(schema);
        assertTrue(normalized.contains("\"hero\""));
        assertEquals(normalized, MarketingPageSchema.validate(normalized));
    }

    @Test
    void rejectsUnknownComponentTypesAndUnboundedContent() {
        assertThrows(ApiException.class, () -> MarketingPageSchema.validate(
                "{\"schemaVersion\":1,\"components\":[{\"type\":\"html\",\"text\":\"x\"}]}"));
        assertThrows(ApiException.class, () -> MarketingPageSchema.validate(
                "{\"schemaVersion\":1,\"components\":[{\"type\":\"richText\",\"paragraphs\":[]}]}"));
        assertThrows(ApiException.class, () -> MarketingPageSchema.validate(
                "{\"schemaVersion\":2,\"components\":[{\"type\":\"divider\"}]}"));
    }

    @Test
    void localE2eMayUseHttpArtworkButProductionRemainsHttpsOnly() {
        String schema = "{\"schemaVersion\":1,\"components\":[{\"type\":\"hero\",\"title\":\"活动\",\"imageUrl\":\"http://127.0.0.1:18081/a.png\"}]}";
        assertThrows(ApiException.class, () -> MarketingPageSchema.validate(schema));
        assertTrue(MarketingPageSchema.validate(schema, true).contains("http://127.0.0.1:18081/a.png"));
    }

    @Test
    void canonicalIsOrderInsensitiveAndParameterSensitive() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", 1);
        first.put("b", "x");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("b", "x");
        second.put("a", 1);
        assertEquals(MarketingCanonical.of(first).sha256(), MarketingCanonical.of(second).sha256());
        second.put("a", 2);
        assertTrue(!MarketingCanonical.of(first).sha256().equals(MarketingCanonical.of(second).sha256()));
    }

    @Test
    void canonicalSerializesCommandTimesAndLists() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("startAt", java.time.OffsetDateTime.parse("2026-11-01T08:00:00Z"));
        params.put("endAt", java.time.OffsetDateTime.parse("2026-11-30T08:00:00Z"));
        params.put("cityCodes", java.util.List.of("shanghai", "beijing"));
        MarketingCanonical canonical = MarketingCanonical.of(params);
        assertNotNull(canonical.sha256());
        assertEquals(64, canonical.sha256().length());
    }

    @Test
    void mapperXmlParsesWithoutAConnection() {
        MarketingStore store = new MarketingStore(
                new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/not_connected", "u", "p"),
                () -> 1L);
        assertNotNull(store);
    }
}
