package com.petplatform.payment.biz.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentDemoStore;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Runs without MySQL. MyBatis maps the records by column POSITION (no name-based constructor mapping), so the
 * XML column lists and the record components must stay aligned; the center insert never writes ORDER owners.
 */
class PaymentCenterMapperAlignmentTest {
    private static String xml(String name) throws Exception {
        try (var in = PaymentCenterStore.class.getResourceAsStream("/mapper/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String camel(String column) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : column.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }

    private static void aligned(String xml, String sqlId, Class<? extends Record> type, java.util.Map<String, String> renames) {
        var matcher = Pattern.compile("<sql id=\"" + sqlId + "\">([^<]+)</sql>").matcher(xml);
        assertTrue(matcher.find(), sqlId);
        String[] columns = matcher.group(1).trim().split(",");
        var components = type.getRecordComponents();
        assertEquals(components.length, columns.length, type.getSimpleName());
        for (int i = 0; i < columns.length; i++) {
            String expected = renames.getOrDefault(columns[i], camel(columns[i]));
            assertEquals(expected, components[i].getName(), type.getSimpleName() + " position " + i);
        }
    }

    @Test void centerRowMatchesCenterColumns() throws Exception {
        aligned(xml("PaymentCenterMapper.xml"), "centerColumns", PaymentCenterStore.CenterRow.class, java.util.Map.of(
                "payment_no", "no", "expire_at", "expires", "sub_appid", "subAppId",
                "channel_trade_no", "tradeNo", "channel_paid_amount", "paidAmount"));
        // Owner columns are nullable wrappers so a center row (NULL order/store/merchant) maps.
        for (String name : new String[] {"orderId", "storeId", "merchantId", "userId", "successEventId"}) {
            var component = java.util.Arrays.stream(PaymentCenterStore.CenterRow.class.getRecordComponents())
                    .filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
            assertFalse(component.getType().isPrimitive(), name);
        }
    }

    @Test void demoRowMatchesDemoColumns() throws Exception {
        aligned(xml("PaymentDemoMapper.xml"), "demoColumns", PaymentDemoStore.DemoRow.class, java.util.Map.of());
    }

    @Test void centerInsertNeverWritesOrderOwnership() throws Exception {
        String xml = xml("PaymentCenterMapper.xml");
        int start = xml.indexOf("<insert id=\"insertCenterPayment\">");
        String insert = xml.substring(start, xml.indexOf("</insert>", start));
        assertTrue(insert.contains("VALUES(#{id},#{paymentNo},NULL,#{bizType},#{bizNo}"));
        assertTrue(insert.contains("UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),NULL,NULL,#{userId}"));
        assertFalse(insert.contains("#{orderId}") || insert.contains("#{storeId}") || insert.contains("#{merchantId}"));
        assertTrue(insert.contains("#{settleType}"));
        assertFalse(xml.contains("UPDATE payment_order"), "center XML must not add payment_order transitions");
    }
}
