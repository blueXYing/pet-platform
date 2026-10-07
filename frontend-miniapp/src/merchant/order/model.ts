import { ApiError } from '../../shared/request'

// Contract 45 merchant manual order decisions (HTTP10 §4.2/§4.3 routes
// POST /api/v1/merchant/orders/{orderId}/confirm|reject, switch
// pet.order.merchant.http.enabled default OFF). The 200 receipt carries exactly
// orderId/decisionId/confirmRound/action/orderStageAtCommit/decidedAt/refundOrderId:
// confirm keeps refundOrderId null with stage PENDING_SERVICE, reject carries the
// same-transaction full refund order id with stage CANCELED. The OWNER authority is
// re-proven server-side on every execution and replay; the page derives nothing beyond
// the frozen reason catalog and the receipt fields the contract returns.
export type RejectReasonCode =
  | 'SCHEDULE_CONFLICT'
  | 'STAFF_UNAVAILABLE'
  | 'PET_NOT_MATCHED'
  | 'TEMPORARY_CLOSURE'
  | 'OTHER'
export const REJECT_REASON_CODES: readonly RejectReasonCode[] = [
  'SCHEDULE_CONFLICT', 'STAFF_UNAVAILABLE', 'PET_NOT_MATCHED', 'TEMPORARY_CLOSURE', 'OTHER',
]

export type OrderDecisionReceipt = Readonly<{
  orderId: string
  decisionId: string
  confirmRound: number
  action: 'CONFIRM' | 'REJECT'
  orderStageAtCommit: 'PENDING_SERVICE' | 'CANCELED'
  decidedAt: string
  refundOrderId: string | null
}>

export interface OrderDecisionDeps {
  confirm(orderId: string, internalNote: string | null): Promise<OrderDecisionReceipt>
  reject(orderId: string, reasonCode: RejectReasonCode, reasonText: string): Promise<OrderDecisionReceipt>
  /** Journaled unknown-outcome decisions for the current merchant workspace, newest-first. */
  pending(): { action: 'confirm' | 'reject'; orderId: string } | null
}

function invalid(): never {
  throw new Error('INVALID_RESPONSE')
}
function exact(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (typeof value !== 'object' || value === null) invalid()
  const record = value as Record<string, unknown>
  if (Object.keys(record).length !== keys.length || keys.some(key => !(key in record))) invalid()
  return record
}
function isId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
}
function isInstant(value: unknown): value is string {
  // UTC instants (MerchantOrderService Receipt.decidedAt is an OffsetDateTime toString):
  // seconds/fraction are optional exactly as java.time prints them, offset Z or ±hh:mm.
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/.test(value)
}

export function decodeOrderDecisionReceipt(value: unknown): OrderDecisionReceipt {
  const v = exact(value, ['orderId', 'decisionId', 'confirmRound', 'action', 'orderStageAtCommit', 'decidedAt', 'refundOrderId'])
  if (!isId(v.orderId) || !isId(v.decisionId)) invalid()
  if (v.confirmRound !== 0 && v.confirmRound !== 1) invalid()
  const action = v.action === 'CONFIRM' || v.action === 'REJECT' ? v.action : invalid()
  const stage = v.orderStageAtCommit === 'PENDING_SERVICE' || v.orderStageAtCommit === 'CANCELED'
    ? v.orderStageAtCommit : invalid()
  if (!isInstant(v.decidedAt)) invalid()
  const refundOrderId = v.refundOrderId === null ? null : isId(v.refundOrderId) ? v.refundOrderId : invalid()
  // 45号: only a reject commits a full refund with the order closed; a confirm receipt
  // claiming either tail is contract-foreign and never reaches the UI.
  if (action === 'CONFIRM' && (refundOrderId !== null || stage !== 'PENDING_SERVICE')) invalid()
  if (action === 'REJECT' && (refundOrderId === null || stage !== 'CANCELED')) invalid()
  return { orderId: v.orderId, decisionId: v.decisionId, confirmRound: v.confirmRound, action, orderStageAtCommit: stage, decidedAt: v.decidedAt, refundOrderId }
}

/** Route/order id: Snowflake decimal string only (whitelist-id discipline). */
export function isOrderId(value: string): boolean {
  return isId(value)
}

/** Unicode code-point bound shared by the reject reason and the confirm note (45号). */
function codePointProblem(value: string, min: number): string | null {
  const count = Array.from(value).length
  if (count < min || count > 200) return min === 0 ? '不能超过200字' : `请输入${min}至200个字符`
  for (const character of value) {
    const code = character.codePointAt(0) ?? 0
    if (code >= 0xD800 && code <= 0xDFFF) return '内容包含不完整的表情符号，请调整后重试'
  }
  return null
}

/** reasonText: required, 5~200 code points, not all whitespace; never trimmed or rewritten. */
export function reasonTextProblem(value: string): string | null {
  if (value.trim().length === 0) return '请填写拒单原因，不能为空白'
  return codePointProblem(value, 5)
}

/** internalNote: optional; when present a non-null string of 0~200 code points. */
export function internalNoteProblem(value: string): string | null {
  return codePointProblem(value, 0)
}

export const rejectReasonText: Record<RejectReasonCode, string> = {
  SCHEDULE_CONFLICT: '排期冲突',
  STAFF_UNAVAILABLE: '人员不足',
  PET_NOT_MATCHED: '宠物情况不匹配',
  TEMPORARY_CLOSURE: '门店临时停业',
  OTHER: '其他',
}

/** Product clock is UTC+8; device settings must not move a decision time. */
export function decisionTimeText(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '—'
  const local = new Date(date.getTime() + 8 * 60 * 60 * 1000)
  return `${local.toISOString().slice(0, 10)} ${local.toISOString().slice(11, 19)}`
}

/** Per-error-code Chinese mapping for the merchant decision channel (45号 / 12号).
 *  A missing or foreign-store order reads exactly like the 403 (anti-enumeration, one copy). */
export function orderDecisionMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 400) return '提交内容无效，请核对订单编号与填写内容后重试。'
    if (error.statusCode === 401) return '登录已失效，请重新登录后再处理订单。'
    if (error.statusCode === 403 || error.statusCode === 404) {
      // Anti-enumeration: a missing order, a foreign-store order and a permission denial all
      // read exactly like this — one copy covers them, never more detail.
      return '订单不存在或不属于当前商家，或当前身份无处理权限；请核对订单编号。'
    }
    if (error.statusCode === 409) {
      if (error.code === 'ORDER_STATE_NOT_ALLOWED') return '该订单已接单、已拒单或已取消，当前状态不允许再次处理。'
      if (error.code === 'ORDER_CONFIRM_DEADLINE_PASSED') return '已超过30分钟确认期限，系统将自动接单；不能再手动处理。'
      if (error.code === 'ORDER_REFUND_ALREADY_CREATED') return '该订单已创建退款单，不能再接单或拒单。'
      if (error.code === 'ORDER_OPERATION_BUSY') return '订单正在被其他操作处理，请稍后按原操作重试。'
      if (error.code === 'IDEMPOTENCY_KEY_CONFLICT') return '该请求编号已用于其他内容，请按原内容重试。'
      if (error.code === 'COMMON_CONFLICT') return '订单条件已变化，请核对后重试。'
      return '处理与订单当前状态冲突，请核对后重试。'
    }
    if (error.statusCode === 503) return '服务暂不可用，请稍后重试；已提交的处理不会重复执行。'
    return '操作结果尚未确认，请重试原操作；不会重复处理。'
  }
  if (error instanceof Error && error.message === 'PENDING_WRITE_CHANGED') {
    return '上次处理结果尚未确认，请按原订单与原内容重试；不会重复处理。'
  }
  if (error instanceof Error && error.message === 'ORDER_FORM_INVALID') {
    return '请核对订单编号与填写内容。'
  }
  if (error instanceof Error && error.message === 'WORKSPACE_PATH_MISMATCH') {
    return '工作区已切换，请重新进入后再试。'
  }
  return '操作结果尚未确认，请重试原操作；不会重复处理。'
}

/** Switch-off (403) / dependency fault (503) fail-closed classification for the whole-page
 *  non-interactive panel (invitation/members/verify pattern): both conditions outlive a
 *  retry with the same inputs, so the form is withdrawn instead of kept editable. */
export function orderDecisionAvailability(error: unknown): 'ok' | 'closed' {
  if (error instanceof ApiError && (error.statusCode === 403 || error.statusCode === 503)) return 'closed'
  return 'ok'
}

/** 409 codes that definitively prove this exact payload+requestId never committed (the
 *  durable binding is not SUCCEEDED and another key decided the order), so the journal is
 *  retired and a corrected payload may reuse the slot. Busy conflicts prove nothing and
 *  keep the original key. */
export function definitiveNoWrite(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 409
    && (error.code === 'ORDER_STATE_NOT_ALLOWED'
      || error.code === 'ORDER_CONFIRM_DEADLINE_PASSED'
      || error.code === 'ORDER_REFUND_ALREADY_CREATED'
      || error.code === 'IDEMPOTENCY_KEY_CONFLICT')
}
