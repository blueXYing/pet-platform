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

// ---------------------------------------------------------------------------
// 商家订单列表读侧（10号 §4.1 增补，#129 后续切片）：GET /api/v1/merchant/orders。
// 商家坐标作用域（merchantId+storeId 由工作台坐标携带，服务端在门店 guard 事务内以
// requireOwnerRead 重验）；displayStatus 由服务端统一计算（与 C 端 §3.7 同词汇同过滤），
// 页面只呈现不推导；固定排序 created_at DESC, id DESC；PageResult 信封；失败关闭。
// ---------------------------------------------------------------------------

/** DisplayOrderStatus 十值（11号 schema；商家侧仅作呈现词汇，不复制状态机）。 */
export const merchantDisplayStatuses = [
  'PENDING_PAYMENT', 'PENDING_CONFIRM', 'PENDING_SERVICE', 'COMPLETED', 'CANCELED',
  'REFUND_PENDING_CONFIRM', 'REFUNDING', 'REFUNDED', 'PARTIAL_REFUND', 'AFTERSALE',
] as const
export type MerchantDisplayStatus = typeof merchantDisplayStatuses[number]

/** 商家侧展示文案：待接单即 displayStatus=PENDING_CONFIRM（30 分钟确认窗口）。 */
export const merchantDisplayStatusLabels: Record<MerchantDisplayStatus, string> = {
  PENDING_PAYMENT: '待支付', PENDING_CONFIRM: '待接单', PENDING_SERVICE: '待服务', COMPLETED: '已完成', CANCELED: '已取消',
  REFUND_PENDING_CONFIRM: '退款待确认', REFUNDING: '退款中', REFUNDED: '已退款', PARTIAL_REFUND: '部分退款', AFTERSALE: '售后中',
}

/** 列表项 = 契约商家运营最小字段集（orderId/orderNo/displayStatus/payAmount/时间窗/paidAt）。 */
export type MerchantOrderSummary = Readonly<{
  orderId: string
  orderNo: string
  displayStatus: MerchantDisplayStatus
  payAmount: string
  appointmentStart: string | null
  appointmentEnd: string | null
  paidAt: string | null
}>

export type MerchantOrderPage = Readonly<{
  items: readonly MerchantOrderSummary[]
  page: number
  pageSize: number
  total: number
}>

function listExact(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null) invalid()
  const record = value as Record<string, unknown>
  if (Object.keys(record).length !== 7
    || !['orderId', 'orderNo', 'displayStatus', 'payAmount', 'appointmentStart', 'appointmentEnd', 'paidAt'].every(key => key in record)) invalid()
  return record
}

/** 毫秒精度 ISO-8601（服务端固定三位小数 + Z）；空值读作 null。 */
function listInstant(value: unknown): string | null {
  if (value === null) return null
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)) invalid()
  return value
}

export function decodeMerchantOrderSummary(value: unknown): MerchantOrderSummary {
  const v = listExact(value)
  const display = merchantDisplayStatuses.includes(v.displayStatus as MerchantDisplayStatus)
    ? v.displayStatus as MerchantDisplayStatus : invalid()
  if (typeof v.orderId !== 'string' || !isId(v.orderId)) invalid()
  if (typeof v.orderNo !== 'string' || !isId(v.orderNo)) invalid()
  if (typeof v.payAmount !== 'string' || !/^(?:0|[1-9][0-9]{0,15})\.[0-9]{2}$/.test(v.payAmount)) invalid()
  return {
    orderId: v.orderId, orderNo: v.orderNo, displayStatus: display, payAmount: v.payAmount,
    appointmentStart: listInstant(v.appointmentStart), appointmentEnd: listInstant(v.appointmentEnd),
    paidAt: listInstant(v.paidAt),
  }
}

export function decodeMerchantOrderPage(value: unknown): MerchantOrderPage {
  if (typeof value !== 'object' || value === null) invalid()
  const record = value as Record<string, unknown>
  if (Object.keys(record).length !== 4 || !['items', 'page', 'pageSize', 'total'].every(key => key in record)) invalid()
  const page = Number(record.page), pageSize = Number(record.pageSize), total = Number(record.total)
  if (!Number.isInteger(page) || page < 1 || page > 10000) invalid()
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100) invalid()
  if (!Number.isSafeInteger(total) || total < 0) invalid()
  if (!Array.isArray(record.items) || record.items.length > pageSize) invalid()
  return { items: record.items.map(decodeMerchantOrderSummary), page, pageSize, total }
}

/** 预约时间窗文案（事实字段原样格式化为 UTC+8；无窗口时「时间待定」）。 */
export function merchantAppointmentWindow(item: MerchantOrderSummary): string {
  const start = merchantOrderTimeText(item.appointmentStart)
  const end = merchantOrderTimeText(item.appointmentEnd)
  return start && end ? `${start} ~ ${end}` : '时间待定'
}

/** 产品时钟 UTC+8；设备设置不得移动订单时间（与 decisionTimeText 同口径）。 */
export function merchantOrderTimeText(value: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return ''
  const local = new Date(date.getTime() + 8 * 60 * 60 * 1000)
  return `${local.toISOString().slice(0, 10)} ${local.toISOString().slice(11, 16)}`
}

/** 商家订单列表错误面中文映射（12号 COMMON_*；防枚举：403 不区分“他店/无权/不存在”）。 */
export function merchantOrderListMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 400) return '查询参数不合法，请刷新后重试。'
    if (error.statusCode === 401) return '登录已失效，请重新登录后再查看订单。'
    if (error.statusCode === 403) return '当前身份无权查看该门店订单，或通道未开放；请从商家工作台进入。'
    if (error.statusCode === 503) return '订单读取暂时不可用，请稍后重试。'
    return '订单读取失败，请稍后重试。'
  }
  if (error instanceof Error && error.message === 'WORKSPACE_PATH_MISMATCH') {
    return '工作区已切换，请重新进入商家工作台。'
  }
  return '订单读取失败，请稍后重试。'
}

/** 403（开关未开/无权限/非本店）与 503 依赖故障整页失败关闭（重试不可恢复条件）。 */
export function merchantOrderListAvailability(error: unknown): 'ok' | 'closed' {
  if (error instanceof ApiError && (error.statusCode === 403 || error.statusCode === 503)) return 'closed'
  return 'ok'
}
