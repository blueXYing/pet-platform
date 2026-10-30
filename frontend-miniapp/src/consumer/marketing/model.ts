import { id, object, type ConsumerApi } from '../../shared/consumer-api'

export type MarketingTag = Readonly<{ tagCode: string; tagName: string; campaignCount: number }>

export type PlacementCard = Readonly<{
  placementId: string; positionCode: string; campaignId: string
  title: string; subtitle: string | null; tagCode: string | null; tagName: string | null
  coverUrl: string | null; templateCode: string | null; jumpType: string; jumpParams: string
  startAt: string; endAt: string
}>

export type HomeBlock = Readonly<{
  blockId: string; positionCode: string; templateCode: string
  title: string; subtitle: string | null; tagName: string | null; imageUrl: string | null
  targetType: string; targetValue: string | null; cityCode: string | null
  sponsorMerchantId: string | null; sponsorStoreId: string | null; sortWeight: number
}>

export type CampaignCard = Readonly<{
  campaignId: string; title: string; subtitle: string | null; tagCode: string | null
  tagName: string | null; coverUrl: string | null; startAt: string; endAt: string; storeCount: number
}>

export type Promotion = Readonly<{ level: number | null; title: string | null; text: string | null }>

// 71 v0.3 §3.7：热门服务二级页顶部投放位（全平台最多 1 条启用；C 端按城市匹配后最多返回 1 条）。
export const PLACEMENT_POSITION_HOT_SERVICE_TOP = 'HOT_SERVICE_TOP'
// 71 v0.3 §10：首页内容位落地热门服务二级页的目标类型（targetValue 允许 null）。
export const HOME_BLOCK_TARGET_HOT_SERVICE = 'HOT_SERVICE'

export type StoreCard = Readonly<{
  storeId: string; storeName: string; merchantName: string | null
  cityCode: string | null; address: string | null
  offerTag: string | null; offerTitle: string | null; offerCopy: string | null
  source: 'MERCHANT_PROMOTION' | 'CAMPAIGN'
  campaignId: string | null; promotion: Promotion | null
  // 74 §3.5 / 71 v0.3 §3.4 商家钜惠图片增量：门店头像/照片优先、活动封面兜底；无图 null。
  // 签名素材带过期时间，公开素材 URL 允许 imageUrlExpiresAt=null（71 §3.4），不做跨字段绑定。
  imageUrl: string | null; imageUrlExpiresAt: string | null
}>

export type CouponCampaignView = Readonly<{
  campaignId: string; name: string; templateId: string
  amountOff: string | null; thresholdAmount: string | null; typeLabel: string | null
  remaining: number; perUserLimit: number; claimed: boolean; canClaim: boolean
  reason: string | null; startAt: string; endAt: string; status: string
}>

export type PageComponent = Readonly<{ type: string; [key: string]: unknown }>
export type PageSchema = Readonly<{ schemaVersion: 1; components: readonly PageComponent[] }>

export type CampaignDetail = Readonly<{
  campaign: CampaignCard
  schema: PageSchema | null
  storeCount: number
  stores: readonly StoreCard[]
  couponCampaign: CouponCampaignView | null
}>

export type ClaimReceipt = Readonly<{
  campaignId: string; claimId: string; couponInstanceId: string
  amountOff: string | null; expireAt: string | null; claimedAt: string
}>

const fail = (): never => { throw new Error('INVALID_RESPONSE') }
const text = (value: unknown, max: number): string =>
  typeof value === 'string' && value.length > 0 && Array.from(value).length <= max ? value : fail()
const nullableText = (value: unknown, max: number): string | null =>
  value === null || value === undefined ? null
    : typeof value === 'string' && Array.from(value).length <= max ? value : fail()
const instant = (value: unknown): string => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})(?![\s\S])/.test(value) || !Number.isFinite(Date.parse(value))) fail()
  return value as string
}
const nullableInstant = (value: unknown): string | null => value === null || value === undefined ? null : instant(value)
const nullableId = (value: unknown): string | null => value === null || value === undefined ? null : id(value)
const integer = (value: unknown, min: number, max: number): number =>
  typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max ? value : fail()
const money = (value: unknown): string | null =>
  value === null || value === undefined ? null
    : typeof value === 'string' && /^(?:0|[1-9][0-9]{0,15})(?:\.[0-9]{1,2})?(?![\s\S])/.test(value) ? value : fail()

function promotionOf(value: unknown): Promotion | null {
  if (value === null || value === undefined) return null
  const v = object(value)
  return {
    level: v.level === null || v.level === undefined ? null : integer(v.level, 1, 9),
    title: nullableText(v.title, 100), text: nullableText(v.text, 100),
  }
}

function storeOf(value: unknown): StoreCard {
  const v = object(value)
  return {
    storeId: id(v.storeId), storeName: text(v.storeName, 128),
    merchantName: nullableText(v.merchantName, 128), cityCode: nullableText(v.cityCode, 32),
    address: nullableText(v.address, 255),
    offerTag: nullableText(v.offerTag, 32), offerTitle: nullableText(v.offerTitle, 100),
    offerCopy: nullableText(v.offerCopy, 100),
    source: v.source === 'CAMPAIGN' ? 'CAMPAIGN' : 'MERCHANT_PROMOTION',
    campaignId: nullableId(v.campaignId), promotion: promotionOf(v.promotion),
    imageUrl: nullableText(v.imageUrl, 512), imageUrlExpiresAt: nullableInstant(v.imageUrlExpiresAt),
  }
}

function placementOf(value: unknown): PlacementCard {
  const v = object(value)
  return {
    placementId: id(v.placementId), positionCode: text(v.positionCode, 24),
    campaignId: id(v.campaignId), title: text(v.title, 100),
    subtitle: nullableText(v.subtitle, 200), tagCode: nullableText(v.tagCode, 32),
    tagName: nullableText(v.tagName, 32), coverUrl: nullableText(v.coverUrl, 512),
    templateCode: nullableText(v.templateCode, 32),
    jumpType: text(v.jumpType, 24), jumpParams: text(v.jumpParams, 512),
    startAt: instant(v.startAt), endAt: instant(v.endAt),
  }
}

function homeBlockOf(value: unknown): HomeBlock {
  const v = object(value)
  return {
    blockId: id(v.blockId), positionCode: text(v.positionCode, 32),
    templateCode: text(v.templateCode, 48), title: text(v.title, 100),
    subtitle: nullableText(v.subtitle, 200), tagName: nullableText(v.tagName, 32),
    imageUrl: nullableText(v.imageUrl, 512), targetType: text(v.targetType, 32),
    targetValue: nullableText(v.targetValue, 255), cityCode: nullableText(v.cityCode, 32),
    sponsorMerchantId: nullableId(v.sponsorMerchantId), sponsorStoreId: nullableId(v.sponsorStoreId),
    sortWeight: integer(v.sortWeight, 0, 9999),
  }
}

function campaignOf(value: unknown): CampaignCard {  const v = object(value)
  return {
    campaignId: id(v.campaignId), title: text(v.title, 100),
    subtitle: nullableText(v.subtitle, 200), tagCode: nullableText(v.tagCode, 32),
    tagName: nullableText(v.tagName, 32), coverUrl: nullableText(v.coverUrl, 512),
    startAt: instant(v.startAt), endAt: instant(v.endAt), storeCount: integer(v.storeCount, 0, 100000),
  }
}

function schemaOf(value: unknown): PageSchema | null {
  if (value === null || value === undefined) return null
  const v = object(value)
  if (v.schemaVersion !== 1 || !Array.isArray(v.components) || v.components.length > 10) fail()
  return { schemaVersion: 1, components: v.components.map((item: unknown) => {
    const c = object(item)
    if (typeof c.type !== 'string' || !c.type) fail()
    return c as PageComponent
  }) }
}

function couponOf(value: unknown): CouponCampaignView | null {
  if (value === null || value === undefined) return null
  const v = object(value)
  return {
    campaignId: id(v.campaignId), name: text(v.name, 50), templateId: id(v.templateId),
    amountOff: money(v.amountOff), thresholdAmount: money(v.thresholdAmount),
    typeLabel: nullableText(v.typeLabel, 32), remaining: integer(v.remaining, 0, 1000000),
    perUserLimit: integer(v.perUserLimit, 1, 10), claimed: v.claimed === true,
    canClaim: v.canClaim === true, reason: nullableText(v.reason, 64),
    startAt: instant(v.startAt), endAt: instant(v.endAt), status: text(v.status, 16),
  }
}

export function decodePlacements(value: unknown): readonly PlacementCard[] {
  const v = object(value)
  if (!Array.isArray(v.items) || v.items.length > 20) fail()
  return v.items.map(placementOf)
}

export function decodeHomeBlocks(value: unknown): readonly HomeBlock[] {
  const v = object(value)
  if (!Array.isArray(v.items) || v.items.length > 12) fail()
  return v.items.map(homeBlockOf)
}

export function decodeCampaigns(value: unknown): Readonly<{ items: readonly CampaignCard[]; page: number; pageSize: number; total: number }> {
  const v = object(value)
  if (!Array.isArray(v.items) || v.items.length > 50) fail()
  return {
    items: v.items.map(campaignOf), page: integer(v.page, 1, 10000),
    pageSize: integer(v.pageSize, 1, 50), total: integer(v.total, 0, 1000000000),
  }
}

export function decodeTags(value: unknown): readonly MarketingTag[] {
  const v = object(value)
  if (!Array.isArray(v.items) || v.items.length > 100) fail()
  return v.items.map((item: unknown) => {
    const t = object(item)
    return { tagCode: text(t.tagCode, 32), tagName: text(t.tagName, 32), campaignCount: integer(t.campaignCount, 0, 1000000) }
  })
}

export function decodeCampaignDetail(value: unknown): CampaignDetail {
  const v = object(value)
  if (!Array.isArray(v.stores) || v.stores.length > 100) fail()
  return {
    campaign: campaignOf(v.campaign), schema: schemaOf(v.schema),
    storeCount: integer(v.storeCount, 0, 100000), stores: v.stores.map(storeOf),
    couponCampaign: couponOf(v.couponCampaign),
  }
}

export function decodeMerchantDeals(value: unknown): readonly StoreCard[] {
  const v = object(value)
  if (!Array.isArray(v.items) || v.items.length > 20) fail()
  return v.items.map(storeOf)
}

export function decodeClaim(value: unknown): ClaimReceipt {
  const v = object(value)
  return {
    campaignId: id(v.campaignId), claimId: id(v.claimId), couponInstanceId: id(v.couponInstanceId),
    amountOff: money(v.amountOff), expireAt: nullableInstant(v.expireAt), claimedAt: instant(v.claimedAt),
  }
}

/** Thin real API layer; every read is public/anonymous, the claim uses the C session. */
export class MarketingRepository {
  constructor(private api: ConsumerApi) {}

  placements(positionCode: string, cityCode?: string) {
    const data: Record<string, unknown> = { positionCode }
    if (cityCode) data.cityCode = cityCode
    return this.api.anonymousRequest({ path: '/api/v1/c/marketing/placements', method: 'GET', data }, decodePlacements)
  }

  homeBlocks(cityCode?: string) {
    return this.api.anonymousRequest({
      path: '/api/v1/c/marketing/home-blocks', method: 'GET',
      data: cityCode ? { cityCode } : undefined,
    }, decodeHomeBlocks)
  }

  campaigns(tagCode?: string, cityCode?: string, page = 1, pageSize = 20) {    const data: Record<string, unknown> = { page, pageSize }
    if (tagCode) data.tagCode = tagCode
    if (cityCode) data.cityCode = cityCode
    return this.api.anonymousRequest({ path: '/api/v1/c/marketing/campaigns', method: 'GET', data }, decodeCampaigns)
  }

  tags(cityCode?: string) {
    return this.api.anonymousRequest({ path: '/api/v1/c/marketing/tags', method: 'GET', data: cityCode ? { cityCode } : undefined }, decodeTags)
  }

  campaignDetail(campaignId: string) {
    return this.api.anonymousRequest({ path: `/api/v1/c/marketing/campaigns/${encodeURIComponent(campaignId)}`, method: 'GET' }, decodeCampaignDetail)
  }

  merchantDeals(cityCode?: string, limit = 10) {
    const data: Record<string, unknown> = { limit }
    if (cityCode) data.cityCode = cityCode
    return this.api.anonymousRequest({ path: '/api/v1/c/marketing/merchant-deals', method: 'GET', data }, decodeMerchantDeals)
  }

  async claim(couponCampaignId: string) {
    const requestId = await this.api.uuid()
    return this.api.request({ path: `/api/v1/c/coupon-campaigns/${encodeURIComponent(couponCampaignId)}/claims`, method: 'POST', requestId }, decodeClaim)
  }
}
