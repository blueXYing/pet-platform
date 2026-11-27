import { id, object, type ConsumerApi } from '../../shared/consumer-api'

// Contract-79 (P3 商家自建优惠券) merchant coupon-campaign face. 前缀
// /api/v1/merchant/coupon-campaigns；坐标 merchantId/storeId 走 query string（79号 §3）：
// GET 由 Taro 把 data 序列化为查询串，create/update 把同一坐标拼进 URL query（JSON body 保持
// §3.1 精确 schema，未知字段 400，body 不得携带 merchantId 等服务端字段），submit 只瞄准路径
// 中的活动。写命令走 ConsumerApi.write 槽位日志（同一 X-Request-Id 终端幂等重放）。
export type CouponCampaignStatus =
  | 'DRAFT' | 'PENDING_REVIEW' | 'REJECTED' | 'CANCELED'
  | 'SCHEDULED' | 'ACTIVE' | 'PAUSED' | 'ENDED'
export const COUPON_CAMPAIGN_STATUSES: readonly CouponCampaignStatus[] =
  ['DRAFT', 'PENDING_REVIEW', 'REJECTED', 'CANCELED', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED']
/** 79号 §2.3：THRESHOLD=满减券（需门槛）/ NO_THRESHOLD=无门槛券（立减券）。 */
export type CouponType = 'THRESHOLD' | 'NO_THRESHOLD'

export type CouponCampaignRecord = Readonly<{
  campaignId: string; merchantId: string; storeId: string | null
  name: string; couponType: CouponType; amountOff: string; thresholdAmount: string | null
  typeLabel: string; totalLimit: number; perUserLimit: number; budgetLimit: string
  issuedCount: number; issuedAmount: string
  startAt: string; endAt: string; description: string | null
  status: CouponCampaignStatus; rejectReason: string | null; version: string
  // 列表条目附加统计（79号 §3）；详情「全字段」按同族记录解码，缺失容忍为 null，不伪造 0。
  claimedCount: number | null; usedCount: number | null; drivenOrderCount: number | null
  budgetUsedAmount: string | null; createdAt: string | null; updatedAt: string | null
}>

export type CouponCampaignInput = Readonly<{
  name: string
  amountOff: string
  /** 仅满减券携带；无门槛券省略（79号 §3.1：省略或 null = 无门槛券）。 */
  thresholdAmount?: string
  totalLimit: number
  perUserLimit: number
  budgetLimit: string
  startAt: string
  endAt: string
  description?: string
}>

export type CouponOverview = Readonly<{
  totalCampaignCount: number; activeCampaignCount: number; pendingReviewCount: number
  monthIssuedCount: number
  /** 四位小数字符串（如 "0.6200"；未发放 "0.0000"），展示层再转百分比。 */
  redemptionRate: string
  drivenOrderCount: number
}>

/** submit 回执：79号 §3 data = campaignId/status/version。 */
export type CouponCampaignReceipt = Readonly<{
  campaignId: string; status: CouponCampaignStatus; version: string
}>

const fail = (): never => { throw new Error('INVALID_RESPONSE') }
const text = (value: unknown, max: number): string =>
  typeof value === 'string' && value.length > 0 && Array.from(value).length <= max ? value : fail()
const nullableText = (value: unknown, max: number): string | null =>
  value === null || value === undefined ? null : typeof value === 'string' && Array.from(value).length <= max ? value : fail()
const instant = (value: unknown): string => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})(?![\s\S])/.test(value) || !Number.isFinite(Date.parse(value))) fail()
  return value as string
}
const nullableInstant = (value: unknown): string | null => value === null || value === undefined ? null : instant(value)
/** 金额线形状（79号 §2.3）：两位小数字符串；容忍 JSON 数字归一，不伪造值。 */
const money = (value: unknown): string => {
  if (typeof value === 'string' && /^\d+\.\d{2}$/.test(value)) return value
  if (typeof value === 'number' && Number.isFinite(value) && value >= 0) return (Math.round(value * 100) / 100).toFixed(2)
  return fail()
}
const nullableMoney = (value: unknown): string | null => value === null || value === undefined ? null : money(value)
const integer = (value: unknown, min: number, max: number): number =>
  typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max ? value : fail()
const nullableInteger = (value: unknown, min: number, max: number): number | null =>
  value === null || value === undefined ? null : integer(value, min, max)
/** 核销率：四位小数字符串（79号 §3.1 统计口径）；容忍数字归一为四位小数。 */
const redemptionRate = (value: unknown): string => {
  if (typeof value === 'string' && /^\d+\.\d{4}$/.test(value)) return value
  if (typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1) return value.toFixed(4)
  return fail()
}
/** 乐观锁版本：容忍 JSON 数字与数字字符串（P1 教训：字符串/整数兼容）。 */
const version = (value: unknown): string => {
  if (typeof value === 'number' && Number.isInteger(value) && value >= 1) return String(value)
  if (typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value)) return value
  return fail()
}
const couponType = (value: unknown): CouponType => value === 'THRESHOLD' || value === 'NO_THRESHOLD' ? value : fail()
const status = (value: unknown): CouponCampaignStatus =>
  typeof value === 'string' && (COUPON_CAMPAIGN_STATUSES as readonly string[]).includes(value) ? value as CouponCampaignStatus : fail()
const expectedVersionOf = (value: string): number => {
  const parsed = Number(value)
  if (!Number.isInteger(parsed) || parsed < 1) fail()
  return parsed
}

function recordOf(value: unknown): CouponCampaignRecord {
  const v = object(value)
  return {
    campaignId: id(v.campaignId), merchantId: id(v.merchantId), storeId: nullableId(v.storeId),
    name: text(v.name, 50), couponType: couponType(v.couponType),
    amountOff: money(v.amountOff), thresholdAmount: nullableMoney(v.thresholdAmount),
    typeLabel: text(v.typeLabel, 16),
    totalLimit: integer(v.totalLimit, 1, 1000000), perUserLimit: integer(v.perUserLimit, 1, 10),
    budgetLimit: money(v.budgetLimit),
    issuedCount: integer(v.issuedCount, 0, 1000000), issuedAmount: money(v.issuedAmount),
    startAt: instant(v.startAt), endAt: instant(v.endAt), description: nullableText(v.description, 300),
    status: status(v.status), rejectReason: nullableText(v.rejectReason, 500), version: version(v.version),
    claimedCount: nullableInteger(v.claimedCount, 0, 1000000), usedCount: nullableInteger(v.usedCount, 0, 1000000),
    drivenOrderCount: nullableInteger(v.drivenOrderCount, 0, 1000000000),
    budgetUsedAmount: nullableMoney(v.budgetUsedAmount),
    createdAt: nullableInstant(v.createdAt), updatedAt: nullableInstant(v.updatedAt),
  }
}
const nullableId = (value: unknown): string | null => value === null || value === undefined ? null : id(value)

function overviewOf(value: unknown): CouponOverview {
  const v = object(value)
  return {
    totalCampaignCount: integer(v.totalCampaignCount, 0, 1000000000),
    activeCampaignCount: integer(v.activeCampaignCount, 0, 1000000000),
    pendingReviewCount: integer(v.pendingReviewCount, 0, 1000000000),
    monthIssuedCount: integer(v.monthIssuedCount, 0, 1000000000),
    redemptionRate: redemptionRate(v.redemptionRate),
    drivenOrderCount: integer(v.drivenOrderCount, 0, 1000000000),
  }
}

function receiptOf(value: unknown): CouponCampaignReceipt {
  const v = object(value)
  return { campaignId: id(v.campaignId), status: status(v.status), version: version(v.version) }
}

function pageOf(value: unknown): { items: readonly CouponCampaignRecord[]; total: number } {
  const v = object(value)
  if (!Array.isArray(v.items) || v.items.length > 50) fail()
  const total = integer(v.total, 0, 1000000000)
  return { items: v.items.map(recordOf), total }
}

function inputToBody(input: CouponCampaignInput): Record<string, unknown> {
  // 79号 §3.1：请求不得携带 status/issuedCount/issuedAmount/rejectReason/merchantId 等服务端
  // 字段；thresholdAmount 省略 = 无门槛券；description 空串省略（不伪造空值）。
  const data: Record<string, unknown> = {
    name: input.name, amountOff: input.amountOff,
    totalLimit: input.totalLimit, perUserLimit: input.perUserLimit,
    budgetLimit: input.budgetLimit, startAt: input.startAt, endAt: input.endAt,
  }
  if (input.thresholdAmount !== undefined) data.thresholdAmount = input.thresholdAmount
  if (input.description !== undefined && input.description !== '') data.description = input.description
  return data
}

/** 写命令的坐标 query（79号 §3：merchantId/storeId 走 query string，不进 JSON body）。 */
function coordsQuery(merchantId: string, storeId: string): string {
  if (!/^[1-9][0-9]{0,18}$/.test(merchantId) || !/^[1-9][0-9]{0,18}$/.test(storeId)) fail()
  return `?merchantId=${merchantId}&storeId=${storeId}`
}

/** Merchant coupon-campaign face; coordinates ride the query string and are re-proved server-side. */
export class MerchantCouponApi {
  constructor(private api: ConsumerApi) {}

  /** 79号 §3：分页列表（created_at DESC, id DESC 服务端序）。 */
  list(merchantId: string, storeId: string, page = 1, pageSize = 20) {
    return this.api.request({
      method: 'GET', path: '/api/v1/merchant/coupon-campaigns',
      data: { merchantId, storeId, page, pageSize },
    }, pageOf)
  }

  /** 79号 §3：统计概览（本月发券/核销率/带动订单）。 */
  overview(merchantId: string, storeId: string) {
    return this.api.request({
      method: 'GET', path: '/api/v1/merchant/coupon-campaigns/overview', data: { merchantId, storeId },
    }, overviewOf)
  }

  /** 79号 §3：单条全字段。 */
  detail(merchantId: string, storeId: string, campaignId: string) {
    return this.api.request({
      method: 'GET', path: `/api/v1/merchant/coupon-campaigns/${encodeURIComponent(campaignId)}`,
      data: { merchantId, storeId },
    }, recordOf)
  }

  /** 79号 §3 创建 DRAFT；槽位日志：同载荷重试同一 X-Request-Id（终端幂等）。 */
  create(merchantId: string, storeId: string, input: CouponCampaignInput) {
    return this.api.write(`merchant-coupon-create:${merchantId}`, {
      method: 'POST', path: `/api/v1/merchant/coupon-campaigns${coordsQuery(merchantId, storeId)}`,
      data: inputToBody(input),
    }, receiptOf)
  }

  /** 79号 §3 编辑（仅 DRAFT/REJECTED）；expectedVersion 乐观锁随槽位载荷固化。 */
  update(merchantId: string, storeId: string, campaignId: string, input: CouponCampaignInput, expectedVersion: string) {
    return this.api.write(`merchant-coupon-update:${campaignId}`, {
      method: 'PUT', path: `/api/v1/merchant/coupon-campaigns/${encodeURIComponent(campaignId)}${coordsQuery(merchantId, storeId)}`,
      data: { ...inputToBody(input), expectedVersion: expectedVersionOf(expectedVersion) },
    }, receiptOf)
  }

  /** 79号 §3 提交审核 DRAFT/REJECTED → PENDING_REVIEW；路径即目标，body 仅 expectedVersion。 */
  submit(campaignId: string, expectedVersion: string) {
    return this.api.write(`merchant-coupon-submit:${campaignId}`, {
      method: 'POST', path: `/api/v1/merchant/coupon-campaigns/${encodeURIComponent(campaignId)}/submit`,
      data: { expectedVersion: expectedVersionOf(expectedVersion) },
    }, receiptOf)
  }
}
