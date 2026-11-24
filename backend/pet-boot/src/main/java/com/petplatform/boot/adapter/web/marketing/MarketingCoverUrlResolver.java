package com.petplatform.boot.adapter.web.marketing;

import com.petplatform.thirdparty.biz.infrastructure.oss.OssConnection;

/**
 * 营销图片的存储值 ↔ 展示地址（活动封面 coverUrl、投放插画 imageUrlOverride、首页内容位图 imageUrl 共用）。
 *
 * 2026-11-23 用户裁决：营销图片存 OSS Key（非完整 URL），读取时动态拼接。
 * 2026-11-24 用户裁决：不考虑日后换 bucket/CDN，读写一体——库里一律存 Key。
 * 读取侧（resolve）把 Key 拼成完整地址给前端预览；写入侧（toKey）把本 bucket 的完整地址还原成 Key，
 * 这样「编辑后保存」不会把 Key 覆盖成完整地址。其它来源的完整地址（历史数据、自建素材地址）原样保留。
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

    /**
     * 写入侧归一化：本 bucket 的完整公网地址 → Key；其它值（已是 Key、外部地址、null/空）原样返回。
     * 与 {@link #resolve} 互逆：resolve(toKey(publicUrl(k))) == publicUrl(k)。
     */
    static String toKey(String value) {
        if (value == null || value.isBlank() || oss == null) return value;
        String trimmed = value.strip();
        String prefix = oss.publicUrl("");
        return trimmed.startsWith(prefix) ? trimmed.substring(prefix.length()) : trimmed;
    }

    private MarketingCoverUrlResolver() {}
}
