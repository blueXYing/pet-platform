package com.petplatform.boot.adapter.web.marketing;

import com.petplatform.thirdparty.biz.infrastructure.oss.OssConnection;

/**
 * 2026-11-23 用户裁决：营销图片存 OSS Key（非完整 URL），读取时动态拼接——
 * 换 bucket/CDN 只改环境变量，零数据迁移。对已存的完整 URL 向后兼容（原样返回）。
 *
 * 静态初始化直接从环境变量读 OSS 配置，不依赖 Spring Bean 注入——
 * E2E 服务器的 OssConnection 不一定注册为可注入 Bean，依赖注入会拿到 null 导致 Key 原样返回。
 */
public final class MarketingCoverUrlResolver {
    private static volatile OssConnection oss = buildFrom(
            System.getenv("OSS_ENDPOINT"),
            System.getenv("OSS_REGION"),
            System.getenv("OSS_BUCKET"),
            System.getenv("OSS_ACCESS_KEY_ID"),
            System.getenv("OSS_SECRET_ACCESS_KEY"),
            System.getenv("OSS_PUBLIC_BASE_URL"));

    /** 由 Spring 配置从统一 pet.oss.* 注入，覆盖启动期默认环境变量快照。 */
    public static void configure(
            String endpoint, String region, String bucket,
            String key, String secret, String publicBase) {
        OssConnection configured = buildFrom(endpoint, region, bucket, key, secret, publicBase);
        if (configured != null) {
            oss = configured;
        }
    }

    private static OssConnection buildFrom(
            String endpoint, String region, String bucket,
            String key, String secret, String publicBase) {
        try {
            if (endpoint != null && !endpoint.isBlank()
                    && bucket != null && !bucket.isBlank()
                    && key != null && !key.isBlank()
                    && secret != null && !secret.isBlank()) {
                return new OssConnection(endpoint, region, bucket, key, secret, publicBase);
            }
        } catch (RuntimeException ignored) {
            // OSS 配置不完整时降级为原样返回
        }
        return null;
    }

    /** key → 公网 URL；已是完整 URL 则原样返回（向后兼容）。null/blank → null。 */
    static String resolve(String stored) {
        if (stored == null || stored.isBlank()) return null;
        if (stored.startsWith("https://") || stored.startsWith("http://")) return stored;
        OssConnection current = oss;
        if (current == null) return stored; // OSS 未配置时原样返回（不阻断）
        return current.publicUrl(stored);
    }

    private MarketingCoverUrlResolver() {}
}
