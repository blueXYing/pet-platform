import { ConsumerApi, id, object } from './consumer-api'
import { ApiError, type RequestSpec } from './request'

export type RefundApplicationStatus = 'PENDING_MERCHANT' | 'APPROVED' | 'AUTO_APPROVED' | 'REJECTED'
/** 列表项（57号 Summary）：金额两位小数字符串；买家明文说明与买家身份字段不在商家读侧。 */
export type RefundApplicationSummary = {
  applicationId: string; applicationNo: string; orderId: string; status: RefundApplicationStatus
  applicationVersion: string; reasonCode: string; refundAmount: string
  createdAt: string; merchantDeadline: string
}
export type RefundApplicationDetail = RefundApplicationSummary & {
  decidedAt: string | null; decisionId: string | null; refundOrderId: string | null
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

function summaryFields(v: Record<string, any>) {
  return { applicationId: id(v.applicationId), applicationNo: id(v.applicationNo), orderId: id(v.orderId),
    status: one(v.status, statuses), applicationVersion: version(v.applicationVersion), reasonCode: code(v.reasonCode),
    refundAmount: amount(v.refundAmount), createdAt: instant(v.createdAt), merchantDeadline: instant(v.merchantDeadline) }
}
export function decodeSummary(value: unknown): RefundApplicationSummary { return summaryFields(exact(value, 'applicationId,applicationNo,orderId,status,applicationVersion,reasonCode,refundAmount,createdAt,merchantDeadline')) }
export function decodeDetail(value: unknown): RefundApplicationDetail {
  const v = exact(value, 'applicationId,applicationNo,orderId,status,applicationVersion,reasonCode,refundAmount,createdAt,merchantDeadline,decidedAt,decisionId,refundOrderId')
  return { ...summaryFields(v), decidedAt: nullable(v.decidedAt, instant), decisionId: nullable(v.decisionId, id), refundOrderId: nullable(v.refundOrderId, id) }
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
