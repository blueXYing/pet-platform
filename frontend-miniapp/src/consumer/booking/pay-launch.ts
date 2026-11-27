// §3.6「去支付」直接拉起微信支付（43 号裁决 2026-11-27）：用户点「去支付」后，小程序建支付单并调起
// 微信原生支付，不再经过确认支付页/半屏。本文件只放纯函数与常量（不依赖 Taro，供单测直接引用）；
// 调起流程见 use-pay-launch.ts。

export type RequestPaymentOutcome =
  | Readonly<{ kind: 'cancelled' }>
  | Readonly<{ kind: 'failed'; message: string }>

/** 用户在微信支付窗口取消后的提示：只说明可以再次发起，不推断支付结果。 */
export const PAY_CANCELLED_NOTICE = '已取消微信支付，可点击按钮重新发起。'
/** 微信支付窗口正常结束（支付已提交）后的提示：终态一律以服务端订单为准，绝不本地虚构“已支付”。 */
export const PAY_SUBMITTED_NOTICE = '微信支付已提交，正在确认订单支付结果，请前往订单详情查看最终状态。'
/** 上次发起结果尚未确认时沿用原请求编号重试的提示（同一 X-Request-Id，不会重复发起）。 */
export const PAY_PENDING_LAUNCH_NOTICE = '上次支付发起结果尚未确认，本次沿用原请求编号，不会重复发起。'
/** preview=1 只读预览：不调起微信支付、不发真实请求。 */
export const PAY_PREVIEW_LAUNCH_NOTICE = '只读预览：此处会调起微信支付，不发起真实请求。'
export const PAY_LOGIN_REQUIRED_NOTICE = '登录后可发起支付'

/**
 * 把 wx.requestPayment 的异常归类为「用户取消」或「其它失败」。只用于提示文案——无论哪一类，
 * 订单支付结果都以服务端为准（取消/失败都不等于未支付，也不等于已支付）。
 */
export function requestPaymentFailure(error: unknown): RequestPaymentOutcome {
  const raw = typeof error === 'object' && error !== null && 'errMsg' in error
    ? (error as { errMsg?: unknown }).errMsg
    : undefined
  const errMsg = (typeof raw === 'string' ? raw : error instanceof Error ? error.message : '').trim()
  if (/cancel/i.test(errMsg)) return { kind: 'cancelled' }
  return {
    kind: 'failed',
    message: errMsg
      ? `微信支付未完成（${errMsg}），请前往订单详情查看或重试。`
      : '微信支付未完成，请前往订单详情查看或重试。',
  }
}

/** 订单 ID 形状：契约十进制串（正 Long 范围内）。 */
export function isPayLaunchOrderId(value: string): boolean {
  return /^[1-9][0-9]{0,18}$/.test(value) && (value.length < 19 || BigInt(value) <= 9223372036854775807n)
}
