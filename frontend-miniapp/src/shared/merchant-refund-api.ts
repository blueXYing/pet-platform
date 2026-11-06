import { ConsumerApi, id, object } from './consumer-api'
import { ApiError, type RequestSpec } from './request'

export type RefundApplicationStatus = 'PENDING_MERCHANT' | 'APPROVED' | 'AUTO_APPROVED' | 'REJECTED'
/**
 * CCR-M-REFUND-VIEW-001 §3.1（2026-11-06 批准）三个可空展示键：客户昵称（空值由页面降级）、
 * 服务端脱敏手机号（1xx****xxxx，永不回明文）与订单服务快照名。买家明文说明与买家身份字段
 * （userId）仍不在商家读侧（49号密文留存，§3.2 不批维持不变）。
 */
export type CustomerView = {
  customerNickname: string | null; customerPhoneMasked: string | null; serviceName: string | null
}
export type RefundTimelineStage = 'APPLIED' | 'MERCHANT_DEADLINE' | 'DECIDED' | 'REFUND_ORDER_CREATED'
/** §3.3 只读时间线条目：仅由既有时间戳派生（出现即有事实），不推导 DisplayOrderStatus。 */
export type RefundTimelineEntry = { stage: RefundTimelineStage; occurredAt: string }
/** 列表项（57号 Summary）：金额两位小数字符串；解码后恒含 §3.1 三键（旧形状读 null）。 */
export type RefundApplicationSummary = {
  applicationId: string; applicationNo: string; orderId: string; status: RefundApplicationStatus
  applicationVersion: string; reasonCode: string; refundAmount: string
  createdAt: string; merchantDeadline: string
} & CustomerView
/** 详情（57号 Detail）：列表同形另含终态字段与 §3.3 timeline（按 occurredAt 升序；旧形状读空数组）。 */
export type RefundApplicationDetail = RefundApplicationSummary & {
  decidedAt: string | null; decisionId: string | null; refundOrderId: string | null
  timeline: readonly RefundTimelineEntry[]
}
export type RefundApplicationPage = { page: number; pageSize: number; total: number; items: RefundApplicationSummary[] }
/** 57号决定回执（49号七字段）：APPROVED 不表示渠道退款成功。 */
export type RefundDecisionReceipt = {
  orderId: string; applicationId: string; applicationStatus: 'APPROVED' | 'REJECTED'
  applicationVersion: string; merchantDeadline: string; decidedAt: string; decisionId: string
}

const statuses: RefundApplicationStatus[] = ['PENDING_MERCHANT', 'APPROVED', 'AUTO_APPROVED', 'REJECTED']
const fail = (): never => { throw new Error('INVALID_RESPONSE') }
const one = <T extends string>(v: unknown, options: readonly T[]): T => typeof v === 'string' && options.includes(v as T) ? v as T : fail()
const nullable = <T>(v: unknown, decode: (v: unknown) => T): T | null => v === null ? null : decode(v)
const code = (v: unknown): string => typeof v === 'string' && /^[A-Z][A-Z0-9_]{0,63}(?![\s\S])/.test(v) ? v : fail()
export const version = (v: unknown): string => typeof v === 'string' && /^(0|[1-9][0-9]{0,18})(?![\s\S])/.test(v) && BigInt(v) <= 9223372036854775807n ? v : fail()
export const instant = (v: unknown): string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(v) && Number.isFinite(Date.parse(v)) && new Date(v).toISOString() === v ? v : fail()
const amount = (v: unknown): string => typeof v === 'string' && /^(0|[1-9][0-9]{0,15})\.[0-9]{2}(?![\s\S])/.test(v) ? v : fail()
const exact = (value: unknown, fields: string) => { const v = object(value); if (Object.keys(v).sort().join(',') !== fields.split(',').sort().join(',')) fail(); return v }

/** CCR-M-REFUND-VIEW-001 §3.1 追加的 3 个可空客户/服务键（12 键新形状）；9 键旧形状按回归兼容仍接受。 */
const CUSTOMER_KEYS = ['customerNickname', 'customerPhoneMasked', 'serviceName'] as const
/** 详情另含 3 个终态键；§3.3 再追加 timeline（16 键新形状）；旧 12 键形状按回归兼容仍接受。 */
const SUMMARY_BASE = 'applicationId,applicationNo,orderId,status,applicationVersion,reasonCode,refundAmount,createdAt,merchantDeadline'
const DETAIL_BASE = SUMMARY_BASE + ',decidedAt,decisionId,refundOrderId'
const STAGES = ['APPLIED', 'MERCHANT_DEADLINE', 'DECIDED', 'REFUND_ORDER_CREATED'] as const
/** 旧形状（回归兼容）缺客户键时读 null；页面负责降级占位，解码器不臆造。 */
const customerNulls: CustomerView = { customerNickname: null, customerPhoneMasked: null, serviceName: null }

/** 允许旧形状（回归兼容）或旧形状+追加键；追加键必须整组出现（all-or-none），其余一律契约违例。 */
function shaped(value: unknown, base: string, extra: readonly string[]): { v: Record<string, any>; extended: boolean } {
  const v = object(value)
  const keys = Object.keys(v).sort().join(',')
  const legacy = base.split(',').sort().join(',')
  const extended = [...base.split(','), ...extra].sort().join(',')
  if (keys !== legacy && keys !== extended) fail()
  return { v, extended: keys === extended }
}

/** 客户/服务键：null 或含非空白字符的字符串（空值由页面降级占位，解码器不臆造）。 */
const customerText = (v: unknown): string | null => v === null ? null : typeof v === 'string' && /\S/.test(v) ? v : fail()
/** 脱敏手机号：null 或服务端掩码 1xx****xxxx；明文号码或其他形状一律契约违例。 */
const maskedPhone = (v: unknown): string | null => v === null ? null : typeof v === 'string' && /^1[0-9]{2}\*{4}[0-9]{4}(?![\s\S])/.test(v) ? v : fail()
function customerFields(v: Record<string, any>, extended: boolean): CustomerView {
  if (!extended) return { ...customerNulls }
  return { customerNickname: customerText(v.customerNickname), customerPhoneMasked: maskedPhone(v.customerPhoneMasked),
    serviceName: customerText(v.serviceName) }
}

/**
 * §3.3 timeline 独立严格解码：数组、每项恰为 {stage, occurredAt}、stage 枚举且每档至多一次、
 * 毫秒 UTC 时间、按 occurredAt 升序（同刻允许）。出现即有事实：客户端不补档、不推导、不重排。
 */
export function decodeTimeline(value: unknown): RefundTimelineEntry[] {
  if (!Array.isArray(value) || value.length > STAGES.length) fail()
  const entries: RefundTimelineEntry[] = []
  for (const item of value as unknown[]) {
    const v = exact(item, 'stage,occurredAt')
    const stage = one(v.stage, STAGES)
    const occurredAt = instant(v.occurredAt)
    if (entries.some(entry => entry.stage === stage)) fail()
    const previous = entries[entries.length - 1]
    if (previous && Date.parse(occurredAt) < Date.parse(previous.occurredAt)) fail()
    entries.push({ stage, occurredAt })
  }
  return entries
}

/** 出现即有事实：每档必须与详情自身的事实一致（DECIDED 需决定时间、REFUND_ORDER_CREATED 需退款单）。 */
function factualTimeline(detail: Omit<RefundApplicationDetail, 'timeline'>, timeline: RefundTimelineEntry[]): RefundTimelineEntry[] {
  for (const entry of timeline) {
    if (entry.stage === 'APPLIED' && entry.occurredAt !== detail.createdAt) fail()
    if (entry.stage === 'MERCHANT_DEADLINE' && entry.occurredAt !== detail.merchantDeadline) fail()
    if (entry.stage === 'DECIDED' && entry.occurredAt !== detail.decidedAt) fail()
    if (entry.stage === 'REFUND_ORDER_CREATED' && detail.refundOrderId === null) fail()
  }
  return timeline
}

function summaryFields(v: Record<string, any>) {
  return { applicationId: id(v.applicationId), applicationNo: id(v.applicationNo), orderId: id(v.orderId),
    status: one(v.status, statuses), applicationVersion: version(v.applicationVersion), reasonCode: code(v.reasonCode),
    refundAmount: amount(v.refundAmount), createdAt: instant(v.createdAt), merchantDeadline: instant(v.merchantDeadline) }
}
export function decodeSummary(value: unknown): RefundApplicationSummary {
  const { v, extended } = shaped(value, SUMMARY_BASE, CUSTOMER_KEYS)
  return { ...summaryFields(v), ...customerFields(v, extended) }
}
export function decodeDetail(value: unknown): RefundApplicationDetail {
  const { v, extended } = shaped(value, DETAIL_BASE, [...CUSTOMER_KEYS, 'timeline'])
  const detail = { ...summaryFields(v), ...customerFields(v, extended),
    decidedAt: nullable(v.decidedAt, instant), decisionId: nullable(v.decisionId, id), refundOrderId: nullable(v.refundOrderId, id) }
  // 旧 12 键形状（回归兼容）没有时间线事实：读作空数组，页面不渲染时间线块。
  return { ...detail, timeline: extended ? factualTimeline(detail, decodeTimeline(v.timeline)) : [] }
}
export function decodePage(value: unknown): RefundApplicationPage {
  const v = exact(value, 'page,pageSize,total,items')
  if (!Number.isInteger(v.page) || v.page < 1 || v.page > 10000 || !Number.isInteger(v.pageSize) || v.pageSize < 1 || v.pageSize > 100 || !Number.isSafeInteger(v.total) || v.total < 0) fail()
  const items = Array.isArray(v.items) && v.items.length <= v.pageSize ? v.items.map(decodeSummary) : fail()
  return { page: v.page, pageSize: v.pageSize, total: v.total, items }
}
export function decodeReceipt(value: unknown): RefundDecisionReceipt {
  const v = exact(value, 'orderId,applicationId,applicationStatus,applicationVersion,merchantDeadline,decidedAt,decisionId')
  return { orderId: id(v.orderId), applicationId: id(v.applicationId), applicationStatus: one(v.applicationStatus, ['APPROVED', 'REJECTED'] as const),
    applicationVersion: version(v.applicationVersion), merchantDeadline: instant(v.merchantDeadline), decidedAt: instant(v.decidedAt), decisionId: id(v.decisionId) }
}

/** 409 codes that definitively prove this exact payload+requestId never committed (a journaled
 *  replay that HAD succeeded would answer 200 with the first receipt), so the journal is
 *  retired and a corrected payload mints a fresh key. Busy/dependency conflicts keep the key. */
export function definitiveRefundNoWrite(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 409
    && (error.code === 'REFUND_APPLICATION_ALREADY_PROCESSED'
      || error.code === 'REFUND_MERCHANT_DEADLINE_PASSED'
      || error.code === 'REFUND_NOT_ELIGIBLE'
      || error.code === 'REFUND_ORDER_ALREADY_EXISTS'
      || error.code === 'REFUND_ALREADY_EXISTS'
      || error.code === 'IDEMPOTENCY_KEY_CONFLICT')
}

/** Contract 57 client (M face only); no alternate identity, no amount input, no fixture fallback. */
export class MerchantRefundClient {
  constructor(readonly api: ConsumerApi) {}

  /** The workspace ticket must already be a full merchant coordinate; the server re-proves it. */
  private coordinates(): { merchantId: string; storeId: string } {
    const context = this.api.scope.capture().context
    if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) throw new Error('WORKSPACE_PATH_MISMATCH')
    return { merchantId: context.merchantId, storeId: context.storeId }
  }

  /** OWNER(merchant)-scoped slots (the server locates the application's store per call). */
  private slot(action: 'approve' | 'reject', applicationId: string): string {
    const { merchantId } = this.coordinates()
    return `merchant-refund:${merchantId}:${id(applicationId)}:${action}`
  }

  /** 待处理列表：固定 pageSize 20（与订单列表读侧同纪律），服务端固定 PENDING_MERCHANT。 */
  list(query: { merchantId: string; storeId: string; page: number; pageSize: number }): Promise<RefundApplicationPage> {
    const coordinates = this.coordinates()
    if (query.merchantId !== coordinates.merchantId || query.storeId !== coordinates.storeId) throw new Error('WORKSPACE_PATH_MISMATCH')
    if (!Number.isInteger(query.page) || query.page < 1 || query.page > 10000
      || !Number.isInteger(query.pageSize) || query.pageSize < 1 || query.pageSize > 100) throw new Error('INVALID_QUERY')
    const data: Record<string, unknown> = { merchantId: query.merchantId, storeId: query.storeId, page: query.page, pageSize: query.pageSize }
    return this.api.request({ path: '/api/v1/merchant/refund-applications', method: 'GET', data }, value => {
      const page = decodePage(value)
      if (page.page !== query.page || page.pageSize !== query.pageSize) fail()
      return page
    })
  }

  detail(applicationId: string): Promise<RefundApplicationDetail> {
    const target = id(applicationId)
    const { merchantId, storeId } = this.coordinates()
    return this.api.request({ path: `/api/v1/merchant/refund-applications/${target}`, method: 'GET',
      data: { merchantId, storeId } }, value => {
      const detail = decodeDetail(value)
      if (detail.applicationId !== target) fail()
      return detail
    })
  }

  /** 同意全额：无金额输入；57号固定 expectedApplicationVersion="0"（49号不变式，服务端持有）。 */
  approve(applicationId: string): Promise<RefundDecisionReceipt> {
    return this.decide('approve', applicationId, {})
  }

  /** 拒绝：必填理由 1..500 码点、非全空白（缺失/空白是 400 REFUND_MERCHANT_REASON_REQUIRED）。 */
  reject(applicationId: string, reasonText: string): Promise<RefundDecisionReceipt> {
    if (typeof reasonText !== 'string' || reasonText.trim().length < 1
      || Array.from(reasonText).length > 500) throw new Error('REJECT_REASON_INVALID')
    return this.decide('reject', applicationId, { reasonText })
  }

  private async decide(action: 'approve' | 'reject', applicationId: string, data: Record<string, unknown>): Promise<RefundDecisionReceipt> {
    const target = id(applicationId)
    const slot = this.slot(action, target)
    const spec: Omit<RequestSpec, 'requestId'> = { path: `/api/v1/merchant/refund-applications/${target}/${action}`, method: 'POST', data }
    try {
      return await this.api.write(slot, spec, value => {
        const receipt = decodeReceipt(value)
        if (receipt.applicationId !== target) fail()
        return receipt
      })
    } catch (error) {
      if (definitiveRefundNoWrite(error)) {
        try { this.api.retireRejectedCommand(slot, spec as RequestSpec) } catch { /* already retired or changed */ }
      }
      throw error
    }
  }

  /** Journaled unknown-outcome commands of the current merchant workspace (crash replay).
   *  The reject payload is the journaled reasonText — an explicit retry must replay it exactly. */
  pending(): { action: 'approve' | 'reject'; applicationId: string; reasonText: string | null } | null {
    let coordinates: { merchantId: string; storeId: string }
    try { coordinates = this.coordinates() } catch { return null }
    for (const action of ['approve', 'reject'] as const) {
      for (const { command } of this.api.pendingCommands(`merchant-refund:${coordinates.merchantId}:`)) {
        const applicationId = new RegExp(`^/api/v1/merchant/refund-applications/([1-9][0-9]{0,18})/${action}$`).exec(command.path)?.[1]
        if (!applicationId) continue
        const reason = (command.data as { reasonText?: unknown } | undefined)?.reasonText
        return { action, applicationId, reasonText: typeof reason === 'string' ? reason : null }
      }
    }
    return null
  }
}
