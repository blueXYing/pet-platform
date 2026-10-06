import { ApiError } from '../../shared/request'

// Contract 48 K2 v0.3 merchant verification completion (HTTP10 §4.7 route
// POST /api/v1/merchant/orders/{orderId}/verification, switch
// pet.verification.completion.http.enabled default OFF). The 200 receipt carries exactly
// orderId/attemptId/resultCode/verificationId/verifiedAt/orderVersion; VERIFIED comes with
// the last three, while an invalid/expired/risk-locked code is a committed business result
// (HTTP 200, resultCode set, last three null — Error12 §7). The operator identity quartet
// is resolved server-side by the K1 v0.2 chain and never appears in the receipt, so the
// page shows only the traceability fields the contract actually returns.
export type VerifyResultCode =
  | 'VERIFIED'
  | 'VERIFICATION_CODE_INVALID'
  | 'VERIFICATION_CODE_EXPIRED'
  | 'VERIFICATION_RISK_LOCKED'
const RESULT_CODES: readonly VerifyResultCode[] = ['VERIFIED', 'VERIFICATION_CODE_INVALID', 'VERIFICATION_CODE_EXPIRED', 'VERIFICATION_RISK_LOCKED']

export type VerificationReceipt = Readonly<{
  orderId: string
  attemptId: string
  resultCode: VerifyResultCode
  verificationId: string | null
  verifiedAt: string | null
  orderVersion: string | null
}>

export interface VerificationDeps {
  verify(orderId: string, verificationCode: string): Promise<VerificationReceipt>
  /** The journaled unknown-outcome verification command for the current workspace, if any. */
  pending(): { orderId: string; verificationCode: string } | null
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
  // UTC millisecond instants (MerchantHttpSupport.time) — same lexical shape as the
  // aftersale/schedule timestamps.
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)
}
function isVersion(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value)
}

export function decodeVerificationReceipt(value: unknown): VerificationReceipt {
  const v = exact(value, ['orderId', 'attemptId', 'resultCode', 'verificationId', 'verifiedAt', 'orderVersion'])
  if (!isId(v.orderId) || !isId(v.attemptId)) invalid()
  const resultCode = typeof v.resultCode === 'string' && (RESULT_CODES as readonly string[]).includes(v.resultCode)
    ? v.resultCode as VerifyResultCode : invalid()
  const verified = resultCode === 'VERIFIED'
  const verificationId = v.verificationId === null ? null : isId(v.verificationId) ? v.verificationId : invalid()
  const verifiedAt = v.verifiedAt === null ? null : isInstant(v.verifiedAt) ? v.verifiedAt : invalid()
  const orderVersion = v.orderVersion === null ? null : isVersion(v.orderVersion) ? v.orderVersion : invalid()
  // K2: the tail trio travels all-or-nothing with resultCode=VERIFIED — partial mixes
  // (some null, some not) are contract-foreign and never reach the UI.
  if (verified && (verificationId === null || verifiedAt === null || orderVersion === null)) invalid()
  if (!verified && (verificationId !== null || verifiedAt !== null || orderVersion !== null)) invalid()
  return { orderId: v.orderId, attemptId: v.attemptId, resultCode, verificationId, verifiedAt, orderVersion }
}

/** Route/order id: Snowflake decimal string only (whitelist-id discipline). */
export function isOrderId(value: string): boolean {
  return isId(value)
}

/** Code syntax follows 47 (1~128 uppercase alphanumerics); input is trimmed and upper-cased. */
export function normalizeCode(value: string): string {
  return value.trim().toUpperCase()
}
export function codeProblem(value: string): string | null {
  if (!/^[A-Z0-9]{1,128}$/.test(value)) return '核销码为1至128位大写字母或数字'
  return null
}

export const verifyResultText: Record<VerifyResultCode, string> = {
  VERIFIED: '核销成功',
  VERIFICATION_CODE_INVALID: '核销码无效',
  VERIFICATION_CODE_EXPIRED: '核销码已过期',
  VERIFICATION_RISK_LOCKED: '风控临时锁定',
}
export const verifyResultHint: Record<VerifyResultCode, string> = {
  VERIFIED: '订单已完成核销，核销后不可重复核销。',
  VERIFICATION_CODE_INVALID: '本次结果已记录为已提交业务结果；请让顾客在订单详情重新出示有效核销码。',
  VERIFICATION_CODE_EXPIRED: '核销码已过期；请让顾客在订单详情刷新核销码后重新出示。',
  VERIFICATION_RISK_LOCKED: '多次无效尝试已触发风控临时锁定，锁定期间不可核销；请稍后再试或联系平台。',
}

/** Product clock is UTC+8; device settings must not move a verification time. */
export function verifyTimeText(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '—'
  const local = new Date(date.getTime() + 8 * 60 * 60 * 1000)
  return `${local.toISOString().slice(0, 10)} ${local.toISOString().slice(11, 19)}`
}

/** Per-error-code Chinese mapping for the verification command channel (48 K2 / Error12 §7). */
export function verificationMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 400) return '提交内容无效，请核对订单编号与核销码后重试。'
    if (error.statusCode === 401) return '登录已失效，请重新登录后再核销。'
    if (error.statusCode === 403) return '当前身份未获核销授权或核销通道未开放（平台开关默认关闭），请联系商家或平台。'
    if (error.statusCode === 404) {
      // K1 anti-enumeration: a missing order, a wrong-store order and an unconfirmed/revoked
      // staff relation all read exactly like this — one copy covers them, never more detail.
      return '订单不存在或不属于当前门店，请核对订单编号。'
    }
    if (error.statusCode === 409) {
      if (error.code === 'VERIFICATION_ALREADY_DONE') return '该订单已核销，不可重复核销。'
      if (error.code === 'VERIFICATION_BLOCKED_BY_REFUND') return '该订单已创建退款单，禁止核销。'
      if (error.code === 'VERIFICATION_NOT_ALLOWED') return '当前订单状态不允许核销。'
      if (error.code === 'COMMON_CONFLICT') return '核销条件已变化（如核销码已刷新），请让顾客重新出示后重试。'
      if (error.code === 'IDEMPOTENCY_KEY_CONFLICT') return '该请求编号已用于其他内容，请按原内容重试。'
      return '核销与订单当前状态冲突，请核对后重试。'
    }
    if (error.statusCode === 503) return '核销服务暂不可用，请稍后重试；已提交的核销不会重复执行。'
    return '操作结果尚未确认，请重试原操作；不会重复核销。'
  }
  if (error instanceof Error && error.message === 'PENDING_WRITE_CHANGED') {
    return '上次核销结果尚未确认，请按原订单与核销码重试；不会重复核销。'
  }
  if (error instanceof Error && error.message === 'VERIFY_FORM_INVALID') {
    return '请核对订单编号与核销码格式。'
  }
  if (error instanceof Error && error.message === 'WORKSPACE_PATH_MISMATCH') {
    return '工作区已切换，请重新进入后再试。'
  }
  return '操作结果尚未确认，请重试原操作；不会重复核销。'
}

/** Switch-off (403) / dependency fault (503) fail-closed classification for the whole-page
 *  non-interactive panel (invitation/members pattern): both conditions outlive a retry with
 *  the same inputs, so the form is withdrawn instead of kept editable. */
export function verificationAvailability(error: unknown): 'ok' | 'closed' {
  if (error instanceof ApiError && (error.statusCode === 403 || error.statusCode === 503)) return 'closed'
  return 'ok'
}
