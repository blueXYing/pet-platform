package com.petplatform.boot.adapter.web.marketing;

import com.petplatform.thirdparty.biz.infrastructure.oss.OssConnection;

/**
 * 2026-11-23 用户裁决：营销图片存 OSS Key（非完整 URL），读取时动态拼接——
 * 换 bucket/CDN 只改环境变量，零数据迁移。对已存的完整 URL 向后兼容（原样返回）。
 * 静态单例：static 序列化方法中直接引用，控制器构造器注入 OssConnection 后初始化。
 */
final class MarketingCoverUrlResolver {
    private static volatile OssConnection oss;
    private static volatile boolean initialized = false;

    static void init(OssConnection connection) {
        oss = connection;
        initialized = true;
    }

    /** key → 公网 URL；已是完整 URL 则原样返回（向后兼容）。null/blank → null。 */
    static String resolve(String stored) {
        if (stored == null || stored.isBlank()) return null;
        if (stored.startsWith("https://") || stored.startsWith("http://")) return stored;
        if (!initialized || oss == null) return stored; // OSS 未配置时原样返回（不阻断）
        return oss.publicUrl(stored);
    }

    private MarketingCoverUrlResolver() {}
}
