import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import {
  isPayLaunchOrderId, PAY_CANCELLED_NOTICE, PAY_PREVIEW_LAUNCH_NOTICE, PAY_SUBMITTED_NOTICE, requestPaymentFailure,
} from '../booking/pay-launch'

// 「去支付」直接拉起微信支付（43 号裁决 2026-11-27）：点「去支付」后由小程序建支付单并调起微信原生支付，
// 不再经过确认支付页；支付窗口结束后去订单详情，终态以服务端为准。

test('requestPayment failure is classified as cancel or failure and never as a payment result', () => {
  assert.deepEqual(requestPaymentFailure({ errMsg: 'requestPayment:fail cancel' }), { kind: 'cancelled' })
  assert.deepEqual(requestPaymentFailure(new Error('requestPayment:fail cancelled')), { kind: 'cancelled' })
  assert.deepEqual(requestPaymentFailure({ errMsg: 'requestPayment:fail (detail message)' }),
    { kind: 'failed', message: '微信支付未完成（requestPayment:fail (detail message)），请前往订单详情查看或重试。' })
  assert.deepEqual(requestPaymentFailure({}), { kind: 'failed', message: '微信支付未完成，请前往订单详情查看或重试。' })
  assert.deepEqual(requestPaymentFailure(undefined), { kind: 'failed', message: '微信支付未完成，请前往订单详情查看或重试。' })
})

test('notices never claim the order is paid', () => {
  for (const notice of [PAY_CANCELLED_NOTICE, PAY_SUBMITTED_NOTICE, PAY_PREVIEW_LAUNCH_NOTICE]) {
    assert.doesNotMatch(notice, /支付成功|已支付|付款成功/)
  }
  assert.match(PAY_SUBMITTED_NOTICE, /以|最终状态/)
  assert.match(PAY_SUBMITTED_NOTICE, /订单详情/)
})

test('order id shape guard for launching payment', () => {
  assert.equal(isPayLaunchOrderId('720100'), true)
  assert.equal(isPayLaunchOrderId('9223372036854775807'), true)
  for (const bad of ['', '0', '0123', '12a', '9223372036854775808', '-5']) assert.equal(isPayLaunchOrderId(bad), false, bad)
})

const consumerRoot = fileURLToPath(new URL('../', import.meta.url))
const source = (relative: string) =>
  readFileSync(path.join(consumerRoot, relative), 'utf8').replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, '')

test('both pay entries launch WeChat Pay in place, with no confirm step and no pay page navigation', () => {
  const bookingSheet = source('components/booking-sheet/booking-sheet.tsx')
  const orderDetail = source('pages/orders/detail.tsx')
  for (const [name, src] of [['booking-sheet', bookingSheet], ['orders/detail', orderDetail]] as const) {
    assert.doesNotMatch(src, /booking\/pay/, name)
    assert.doesNotMatch(src, /PaySheet|bps-/, name)
    assert.match(src, /usePayLaunch/, name)
    assert.match(src, /void launchPay\(/, name)
    // 按钮在调起期间禁用，避免重复发起。
    assert.match(src, /disabled=\{payLaunching\}/, name)
  }
  // 两个入口的按钮 id 保持不变（回执态「去支付」、详情页「去支付」）。
  assert.match(bookingSheet, /id=\{`\$\{idPrefix\}-go-pay`\}/)
  assert.match(orderDetail, /id='ord-go-pay'/)
})

test('the standalone pay page is only a fallback with a single tap-to-pay button', () => {
  const payPage = source('pages/booking/pay.tsx')
  assert.doesNotMatch(payPage, /Taro\.requestPayment\(/)
  assert.doesNotMatch(payPage, /确认支付|支付方式/)
  assert.match(payPage, /usePayLaunch/)
  assert.match(payPage, /id='bkp-pay'[^>]*onClick=\{pay\}/)
  // 兜底页支付窗口结束后同样去订单详情。
  assert.match(payPage, /onSubmitted: goOrderDetail/)
})
