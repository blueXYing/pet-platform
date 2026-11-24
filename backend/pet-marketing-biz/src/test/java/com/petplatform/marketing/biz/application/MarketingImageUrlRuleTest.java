package com.petplatform.marketing.biz.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.common.ApiException;
import org.junit.jupiter.api.Test;

/**
 * 营销图片字段的取值规则（2026-11-24 用户裁决：读写一体存 OSS Key）。
 * 三个图片字段——活动封面 coverUrl、投放插画 imageUrlOverride、首页内容位图 imageUrl——共用这条规则：
 * 接受 OSS Key、完整的 https 地址（本地 E2E 可放行 http）和空；其余一律拒绝。
 */
class MarketingImageUrlRuleTest {

    // optionalUrl 是 MarketingSupport 的 protected static；本测试与它同包，直接调用，不需要实例。

    private static String accept(String value) {
        return MarketingSupport.optionalUrl(value, "coverUrl", false);
    }

    private static String acceptLocal(String value) {
        return MarketingSupport.optionalUrl(value, "coverUrl", true);
    }

    @Test
    void acceptsObjectKeysOfSeededAndUploadedShapes() {
        assertEquals("marketing/fullcard/grooming-clean.png", accept("marketing/fullcard/grooming-clean.png"));
        assertEquals("marketing-assets/7100000000000001.png", accept("marketing-assets/7100000000000001.png"));
        assertEquals("marketing-assets/7100000000000002.jpg", accept("marketing-assets/7100000000000002.jpg"));
        assertEquals("a.png", accept("a.png"));
    }

    @Test
    void acceptsHttpsUrlsAndBlankAsNull() {
        assertEquals("https://cdn.example.com/a.png", accept("https://cdn.example.com/a.png"));
        assertEquals("https://cdn.example.com/a.png", accept("  https://cdn.example.com/a.png  "));
        assertNull(accept(null));
        assertNull(accept(""));
        assertNull(accept("   "));
    }

    @Test
    void trimsSurroundingWhitespaceBeforeStoring() {
        // 首尾空白在落库前被规整掉：存进去的永远是干净的值。
        assertEquals("marketing/fullcard/a.png", accept("  marketing/fullcard/a.png\t"));
        assertEquals("marketing/fullcard/a.png", accept("\nmarketing/fullcard/a.png "));
    }

    @Test
    void httpIsAcceptedOnlyWhenTheLocalSwitchIsOn() {
        String local = "http://127.0.0.1:18081/api/v1/c/marketing/assets/1";
        assertEquals(local, acceptLocal(local));
        assertThrows(ApiException.class, () -> accept(local));
    }

    @Test
    void rejectsValuesThatWouldSmuggleNonKeysIntoAnImageField() {
        String[] rejected = {
                // 路径穿越
                "../a.png", "marketing/../secret.png", "marketing/a.png/../b.png", "/marketing/a.png",
                // 协议相对 / 伪协议 / 非图片扩展名
                "//evil.example/a.png", "javascript:alert(1)", "data:image/png;base64,AAAA",
                "marketing/a", "marketing/a.phar",
                // 带查询串或 fragment（签名地址不是 Key）
                "marketing/a.png?x-signature=abc", "marketing/a.png#frag",
                // 畸形：空目录段、大写、内部空白与控制字符（首尾空白会被规整，不算）
                "marketing//a.png", "Marketing/A.PNG", "marketing/a b.png", "marketing/a\tb.png",
                "marketing/a\nb.png", "marketing/a\u0000b.png",
        };
        for (String value : rejected) {
            assertThrows(ApiException.class, () -> acceptLocal(value), "应被拒绝: " + value.replace("\t", "\\t").replace("\n", "\\n").replace("\u0000", "\\0"));
        }
    }

    @Test
    void rejectsValuesOverTheLengthLimit() {
        assertThrows(ApiException.class, () -> accept("https://example.com/" + "a".repeat(600) + ".png"));
        assertThrows(ApiException.class, () -> accept("marketing/" + "a".repeat(600) + ".png"));
    }
}
