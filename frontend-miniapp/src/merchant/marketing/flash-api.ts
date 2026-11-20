import { id, object, type ConsumerApi } from '../../shared/consumer-api'

// Contract-77 (P2 服务限时秒杀) merchant flash-price face. 前缀
// /api/v1/merchant/services/{serviceId}/flash-prices；路径只携带 serviceId 目标——商家坐标由
// 服务端按服务归属重证（77号 §3/§8），客户端不传 merchantId/storeId。写命令走 ConsumerApi.write
// 槽位日志（同一 X-Request-Id 终端幂等重放，跨页面重进不二次创建）。
export type FlashPriceStatus =
  | 'DRAFT' | 'PENDING_REVIEW' | 'REJECTED' | 'SCHEDULED'
  | 'ACTIVE' | 'PAUSED' | 'ENDED' | 'CANCELED'
export const FLASH_PRICE_STATUSES: readonly FlashPriceStatus[] =
  ['DRAFT', 'PENDING_REVIEW', 'REJECTED', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED', 'CANCELED']
/** 77号 §3.2：优惠形式仅展示标签（LIMITED_DISCOUNT/FLASH_SALE），服务端统一落 priceMode=DISCOUNT。 */
export type PriceModeLabel = 'LIMITED_DISCOUNT' | 'FLASH_SALE'

export type FlashPriceRecord = Readonly<{
  flashPriceId: string; serviceId: string; merchantId: string; storeId: string
  title: string; description: string | null
  priceMode: 'DISCOUNT'; discountRate: string
  flashPrice: string; listPriceSnapshot: string
  startAt: string; endAt: string
  totalQuota: number | null; perUserLimit: number | null
  soldCount: number; status: FlashPriceStatus
  rejectReason: string | null; version: string
  participantCount: number
  createdAt: string; updatedAt: string
}>

export type FlashPriceInput = Readonly<{
  title?: string; description?: string
  priceModeLabel: PriceModeLabel
  discountRate: string
  startAt: string; endAt: string
  totalQuota?: number; perUserLimit?: number
}>

/** submit/cancel 回执：容忍完整记录形态（status/version 为公共超集，P1 receiptOf 同款宽松解码）。 */
export type FlashPriceReceipt = Readonly<{ status: FlashPriceStatus; version: string }>

const fail = (): never => { throw new Error('INVALID_RESPONSE') }
const text = (value: unknown, max: number): string =>
  typeof value === 'string' && value.length > 0 && Array.from(value).length <= max ? value : fail()
const nullableText = (value: unknown, max: number): string | null =>
  value === null || value === undefined ? null : typeof value === 'string' && Array.from(value).length <= max ? value : fail()
const instant = (value: unknown): string => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})(?![\s\S])/.test(value) || !Number.isFinite(Date.parse(value))) fail()
  return value as string
}
/** 金额线形状（任务线形状权威）：两位小数字符串；77号 §2.1 记 number，解码层归一，不伪造值。 */
const money = (value: unknown): string => {
  if (typeof value === 'string' && /^\d+\.\d{2}$/.test(value)) return value
  if (typeof value === 'number' && Number.isFinite(value) && value >= 0) return (Math.round(value * 100) / 100).toFixed(2)
  return fail()
}
/** 折扣线形状：字符串 "8.8"（任务线形状）；77号 §3.2 示例为 JSON 数字，解码层归一为字符串。 */
const discountRate = (value: unknown): string => {
  if (typeof value === 'string' && /^[0-9](\.[0-9])?$/.test(value) && Number(value) > 0 && Number(value) < 10) return value
  if (typeof value === 'number' && Number.isFinite(value) && value > 0 && value < 10 && Number.isInteger(Math.round(value * 10)) && Math.abs(value * 10 - Math.round(value * 10)) < 1e-9) {
    return String(Math.round(value * 10) / 10)
  }
  return fail()
}
/** 乐观锁版本：任务线形状为 JSON 数字（"version":1）；容忍数字字符串（P1 教训：字符串/整数兼容）。 */
const version = (value: unknown): string => {
  if (typeof value === 'number' && Number.isInteger(value) && value >= 1) return String(value)
  if (typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value)) return value
  return fail()
}
const integer = (value: unknown, min: number, max: number): number =>
  typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max ? value : fail()
const nullableInteger = (value: unknown, min: number, max: number): number | null =>
  value === null || value === undefined ? null : integer(value, min, max)
const status = (value: unknown): FlashPriceStatus =>
  typeof value === 'string' && (FLASH_PRICE_STATUSES as readonly string[]).includes(value) ? value as FlashPriceStatus : fail()
const expectedVersionOf = (value: string): number => {
  const parsed = Number(value)
  if (!Number.isInteger(parsed) || parsed < 1) fail()
  return parsed
}

function recordOf(value: unknown): FlashPriceRecord {
  const v = object(value)
  const priceMode = v.priceMode === 'DISCOUNT' ? 'DISCOUNT' as const : fail()
  return {
    flashPriceId: id(v.flashPriceId), serviceId: id(v.serviceId), merchantId: id(v.merchantId), storeId: id(v.storeId),
    title: text(v.title, 100), description: nullableText(v.description, 300),
    priceMode, discountRate: discountRate(v.discountRate),
    flashPrice: money(v.flashPrice), listPriceSnapshot: money(v.listPriceSnapshot),
    startAt: instant(v.startAt), endAt: instant(v.endAt),
    totalQuota: nullableInteger(v.totalQuota, 1, 1000000), perUserLimit: nullableInteger(v.perUserLimit, 1, 1000),
    soldCount: integer(v.soldCount, 0, 1000000), status: status(v.status),
    rejectReason: nullableText(v.rejectReason, 500), version: version(v.version),
    participantCount: integer(v.participantCount, 0, 1000000),
    createdAt: instant(v.createdAt), updatedAt: instant(v.updatedAt),
  }
}
function receiptOf(value: unknown): FlashPriceReceipt {
  const v = object(value)
  return { status: status(v.status), version: version(v.version) }
}
function pageOf(value: unknown): readonly FlashPriceRecord[] {
  const v = object(value)
  if (!Array.isArray(v.items) || v.items.length > 50) fail()
  return v.items.map(recordOf)
}

function inputToBody(input: FlashPriceInput): Record<string, unknown> {
  // 77号 §2.1：商家请求不得携带 status/merchantId/storeId/flashPrice/listPriceSnapshot/soldCount。
  const data: Record<string, unknown> = {
    priceModeLabel: input.priceModeLabel,
    discountRate: input.discountRate,
    startAt: input.startAt,
    endAt: input.endAt,
  }
  if (input.title !== undefined && input.title !== '') data.title = input.title
  if (input.description !== undefined && input.description !== '') data.description = input.description
  if (input.totalQuota !== undefined) data.totalQuota = input.totalQuota
  if (input.perUserLimit !== undefined) data.perUserLimit = input.perUserLimit
  return data
}

/** Merchant flash-price face; the service in the path is the only target (OWNER re-proved server-side). */
export class MerchantFlashPriceApi {
  constructor(private api: ConsumerApi) {}

  /** 77号 §3.1：按服务维度列表（created_at DESC 服务端序）；pageSize 1..50。 */
  list(serviceId: string, statusFilter?: FlashPriceStatus, page = 1, pageSize = 50) {
    const data: Record<string, unknown> = statusFilter ? { status: statusFilter, page, pageSize } : { page, pageSize }
    return this.api.request({ method: 'GET', path: `/api/v1/merchant/services/${encodeURIComponent(serviceId)}/flash-prices`, data }, pageOf)
  }

  /** 77号 §3.2 创建 DRAFT；响应为完整记录（含服务端计算的 flashPrice 预览）。槽位日志：同载荷重试同一 X-Request-Id。 */
  create(serviceId: string, input: FlashPriceInput) {
    return this.api.write(`flash-price-create:${serviceId}`, {
      method: 'POST', path: `/api/v1/merchant/services/${encodeURIComponent(serviceId)}/flash-prices`, data: inputToBody(input),
    }, recordOf)
  }

  /** 77号 §3.3 编辑（仅 DRAFT/REJECTED）；expectedVersion 乐观锁随槽位载荷固化。 */
  update(serviceId: string, flashPriceId: string, input: FlashPriceInput, expectedVersion: string) {
    return this.api.write(`flash-price-update:${flashPriceId}`, {
      method: 'PUT', path: `/api/v1/merchant/services/${encodeURIComponent(serviceId)}/flash-prices/${encodeURIComponent(flashPriceId)}`,
      data: { ...inputToBody(input), expectedVersion: expectedVersionOf(expectedVersion) },
    }, recordOf)
  }

  /** 77号 §3.4 提交审核 DRAFT/REJECTED → PENDING_REVIEW。 */
  submit(serviceId: string, flashPriceId: string, expectedVersion: string) {
    return this.api.write(`flash-price-submit:${flashPriceId}`, {
      method: 'POST', path: `/api/v1/merchant/services/${encodeURIComponent(serviceId)}/flash-prices/${encodeURIComponent(flashPriceId)}/submit`,
      data: { expectedVersion: expectedVersionOf(expectedVersion) },
    }, receiptOf)
  }

  /** 77号 §3.5 取消（仅 DRAFT/REJECTED/PENDING_REVIEW）。 */
  cancel(serviceId: string, flashPriceId: string, expectedVersion: string) {
    return this.api.write(`flash-price-cancel:${flashPriceId}`, {
      method: 'POST', path: `/api/v1/merchant/services/${encodeURIComponent(serviceId)}/flash-prices/${encodeURIComponent(flashPriceId)}/cancel`,
      data: { expectedVersion: expectedVersionOf(expectedVersion) },
    }, receiptOf)
  }
}
