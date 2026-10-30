// 服务 tab 页纯函数（F-C-02，74 §2.1/§3.1/§3.4/§6 + 71 v0.3 §3.4）。无 Taro 依赖，node 单测
// 直接驱动；展示语义一律从契约事实推导，缺失/null 走显式降级（不渲染该段），不虚构数据。

// 74 §2.1 categoryCode 词法（与 hot-service.ts / stores 页 isCategoryCode 同口径）：
// 路由落地参数词法非法不跳转（fail-closed，不猜参）。
export const CATEGORY_CODE_PATTERN = /^[A-Z][A-Z0-9_]{1,31}$/

/** 分类点击落地：stores 页（F-C-04 已支持 categoryCode 精确筛选）；词法非法返回 null 不跳。 */
export function storesCategoryUrl(categoryCode: string): string | null {
  return CATEGORY_CODE_PATTERN.test(categoryCode)
    ? `/consumer/pages/store-services/stores?preview=0&categoryCode=${encodeURIComponent(categoryCode)}`
    : null
}

export type RatingLike = Readonly<{ score: string | null; count: number; available: boolean }>

/**
 * 74 §6.1/§11 评分展示：available 且有 score → 一位小数原样；available 且 score=null
 * （评价数 <5）→「暂无评分」；available=false（聚合读取失败/内核未装配）→ 不展示，
 * 不用 0 分或占位分冒充。
 */
export function ratingText(rating: RatingLike): string | null {
  if (!rating.available) return null
  return rating.score ?? '暂无评分'
}

/** 月售/已售（74 §6.2）：聚合不可用=null → 不展示该段，不用假数据补位。 */
export function soldText(prefix: string, sold: number | null): string | null {
  return sold === null ? null : `${prefix}${sold}`
}

/** DECIMAL(18,2) 字符串 → 展示价：整数尾 `.00` 去掉（'80.00'→'¥80'），非整分保留（'128.50'→'¥128.50'）。 */
export function priceText(salePrice: string): string {
  const trimmed = salePrice.endsWith('.00') ? salePrice.slice(0, -3) : salePrice
  return `¥${trimmed}`
}

/** 74 §6.4 标签：真实分类名（V1 最多 1 个）；tags 空时回退主分类名（同为真实字典值）。 */
export function storeTagLabel(card: Readonly<{ tags: readonly string[]; categoryName: string }>): string {
  return card.tags.length > 0 ? card.tags[0]! : card.categoryName
}

/** 促销文案行（74 §6.3）：标题优先、文案兜底；无促销/两者皆无 → null 不渲染条。 */
export function promotionLine(
  promotion: Readonly<{ offerTitle: string | null; offerCopy: string | null }> | null,
): string | null {
  if (!promotion) return null
  return promotion.offerTitle ?? promotion.offerCopy
}

/** 钜惠卡主/副文案（71 §3.4 StoreCard 投影）：标题优先，副行只在两行齐备时展示，不虚构兜底文案。 */
export function dealLines(card: Readonly<{ offerTitle: string | null; offerCopy: string | null }>): {
  primary: string | null
  secondary: string | null
} {
  return {
    primary: card.offerTitle ?? card.offerCopy,
    secondary: card.offerTitle !== null && card.offerCopy !== null ? card.offerCopy : null,
  }
}

/** 钜惠角标（71 §3.4）：offerTag 优先；促销档位为真实事实，可折算「N折」；两者皆无不展示。 */
export function dealBadgeText(card: Readonly<{
  offerTag: string | null
  promotion: Readonly<{ level: number | null }> | null
}>): string | null {
  if (card.offerTag !== null && card.offerTag !== '') return card.offerTag
  const level = card.promotion?.level ?? null
  return level !== null && level > 0 ? `${level}折` : null
}

/** 74 §3.4 分页：是否还有下一页以服务端 total 为准（本地不猜、不虚构剩余数）。 */
export function hasMoreFeatured(loadedCount: number, total: number): boolean {
  return loadedCount < total
}

/** 74 §3.4 优质商家分页窗口上限 20（C 端列表取 10，翻页拼接）。 */
export const FEATURED_PAGE_SIZE = 10
