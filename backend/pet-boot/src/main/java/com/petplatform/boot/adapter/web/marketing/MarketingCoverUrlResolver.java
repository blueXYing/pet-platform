package com.petplatform.boot.adapter.web.marketing;

import com.petplatform.thirdparty.biz.infrastructure.oss.OssConnection;

/**
 * 2026-11-23 用户裁决：营销图片存 OSS Key（非完整 URL），读取时动态拼接——
 * 换 bucket/CDN 只改环境变量，零数据迁移。对已存的完整 URL 向后兼容（原样返回）。
 *
 * 静态初始化直接从环境变量读 OSS 配置，不依赖 Spring Bean 注入——
 * E2E 服务器的 OssConnection 不一定注册为可注入 Bean，依赖注入会拿到 null 导致 Key 原样返回。
 */
final class MarketingCoverUrlResolver {
    private static final OssConnection oss;
    static {
        OssConnection created = null;
        try {
            String endpoint = System.getenv("OSS_ENDPOINT");
            String region = System.getenv("OSS_REGION");
            String bucket = System.getenv("OSS_BUCKET");
            String key = System.getenv("OSS_ACCESS_KEY_ID");
            String secret = System.getenv("OSS_SECRET_ACCESS_KEY");
            String publicBase = System.getenv("OSS_PUBLIC_BASE_URL");
            if (endpoint != null && !endpoint.isBlank()
                && bucket != null && !bucket.isBlank()
                && key != null && !key.isBlank()
                && secret != null && !secret.isBlank()) {
                created = new OssConnection(endpoint, region, bucket, key, secret, publicBase);
            }
        } catch (RuntimeException ignored) {
            // OSS 配置不完整时降级为原样返回
        }
        oss = created;
    }

    /** key → 公网 URL；已是完整 URL 则原样返回（向后兼容）。null/blank → null。 */
    static String resolve(String stored) {
        if (stored == null || stored.isBlank()) return null;
        if (stored.startsWith("https://") || stored.startsWith("http://")) return stored;
        if (oss == null) return stored; // OSS 未配置时原样返回（不阻断）
        return oss.publicUrl(stored);
    }

    private MarketingCoverUrlResolver() {}
}
