import type { MerchantAdmission } from '../../shared/merchant-repositories'
import type { RefundApplicationDetail, RefundApplicationStatus } from '../../shared/merchant-refund-api'

export const refundStatusText: Record<RefundApplicationStatus, string> = {
  PENDING_MERCHANT: '待商家处理', APPROVED: '已同意退款', AUTO_APPROVED: '系统自动退款', REJECTED: '已拒绝',
}
/** WXSS attribute selectors never match: Taro 4.1.5 drops dynamic data-* from wxml, so state variants must be class names. */
export function refundStatusTagClass(status: RefundApplicationStatus): string {
  if (status === 'PENDING_MERCHANT') return 'mrf-tag'
  if (status === 'APPROVED' || status === 'AUTO_APPROVED') return 'mrf-tag mrf-tag-approved'
  return 'mrf-tag mrf-tag-closed'
}
export type RejectDraft = Readonly<{ reasonText: string }>
export const emptyRejectDraft = (): RejectDraft => ({ reasonText: '' })

/** 拒绝理由边界（57号）：1..500 Unicode 码点、非全空白；前端先拦，服务端仍是准绳。 */
export function rejectReasonProblem(text: string): string | null {
  if (text.trim().length < 1) return '请填写拒绝原因（1至500字）。'
  const points = Array.from(text).length
  if (points > 500) return '拒绝原因不能超过500字。'
  return null
}

/** UI gate supplements the server-side re-proof; it never grants permission (aftersale model). */
export function refundOwnerAccess(view: MerchantAdmission, merchantId: string, storeId: string) {
  const readable = view.merchantId === merchantId && view.storeId === storeId
    && view.membershipKind === 'OWNER' && view.admission !== 'DENIED'
    && view.allowedActions.includes('merchant.refund.read')
  const frozen = view.facts.merchantStatus === 'FROZEN' || view.facts.storeStatus === 'FROZEN'
  return { readable, writable: readable && !frozen, frozen }
}
/** 只有 PENDING_MERCHANT 且未过 24 小时期限可决定；到期即系统接管（SSOT §4.2/§39，服务端判定为准）。 */
export function canDecide(detail: RefundApplicationDetail, writable: boolean, now: number): boolean {
  if (!writable || detail.status !== 'PENDING_MERCHANT') return false
  return now < Date.parse(detail.merchantDeadline)
}
export function deadlinePassed(detail: RefundApplicationDetail, now: number): boolean {
  return detail.status === 'PENDING_MERCHANT' && now >= Date.parse(detail.merchantDeadline)
}
export function dateText(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '—'
  // The product clock is UTC+8; device settings must not move a merchant deadline.
  const local = new Date(date.getTime() + 8 * 60 * 60 * 1000)
  return `${local.toISOString().slice(0, 10)} ${local.toISOString().slice(11, 16)}`
}
/** 服务端未配置生产原因字典标签时不臆造文案：未知编码原样呈现（aftersale 同裁决）。 */
export function reasonCodeText(code: string): string { return code }
