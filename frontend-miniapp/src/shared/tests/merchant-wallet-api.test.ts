import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { ConsumerApi, isMerchantWalletPath, type LocalStore } from '../consumer-api'
import { ApiError, type WireRequest } from '../request'
import {
  MerchantWalletClient, decodeMerchantReceiverStatus, decodeMerchantWallet,
  decodeWithdrawalOrder, decodeWithdrawalPage, merchantWalletErrorMessage,
  normalizeWithdrawalAmount, previewMerchantReceiver, previewMerchantWallet,
  previewWithdrawals, withdrawalSlot,
} from '../merchant-wallet-api'

const sessionView = { userId: '957001', sessionId: '957101', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const ok = (data: unknown) => ({ statusCode: 200, data: { success: true, code: 'SUCCESS', message: 'OK', data, traceId: 'test' } })
const failure = (statusCode: number, code: string, message = 'err') => ({
  statusCode,
  data: { success: false, code, message, data: null, traceId: 'test' },
})

function setupMerchantApi(handler: (request: WireRequest) => Promise<{ statusCode: number; data: unknown }>) {
  const values = new Map<string, unknown>()
  const store: LocalStore = {
    get: key => values.get(key),
    set: (key, value) => values.set(key, JSON.parse(JSON.stringify(value))),
    remove: key => { values.delete(key) },
  }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'test-only', tokenType: 'Bearer' })
  const calls: WireRequest[] = []
  const transport = async (request: WireRequest) => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    calls.push(structuredClone(request))
    return handler(request)
  }
  const api = new ConsumerApi(transport, store, async () => randomUUID())
  return {
    api,
    calls,
    store,
    transport,
    async enterMerchant(merchantId = '5001', storeId = '6001') {
      await api.restore()
      api.scope.replace({ userId: sessionView.userId, workspace: 'merchant', merchantId, storeId })
    },
  }
}

test('isMerchantWalletPath allows exact Contract 80 §5 routes and rejects lookalikes/writes on receiver', () => {
  assert.equal(isMerchantWalletPath({ method: 'GET', path: '/api/v1/merchant/settlement/receiver' }), true)
  assert.equal(isMerchantWalletPath({ method: 'GET', path: '/api/v1/merchant/wallet' }), true)
  assert.equal(isMerchantWalletPath({ method: 'GET', path: '/api/v1/merchant/wallet/withdrawals' }), true)
  assert.equal(isMerchantWalletPath({ method: 'POST', path: '/api/v1/merchant/wallet/withdrawals' }), true)

  // 商家不可自助填写或修改接收方（40 号 §4.2 / §8）
  assert.equal(isMerchantWalletPath({ method: 'POST', path: '/api/v1/merchant/settlement/receiver' }), false)
  assert.equal(isMerchantWalletPath({ method: 'PUT', path: '/api/v1/merchant/settlement/receiver' }), false)
  assert.equal(isMerchantWalletPath({ method: 'DELETE', path: '/api/v1/merchant/wallet/withdrawals' }), false)
  assert.equal(isMerchantWalletPath({ method: 'GET', path: '/api/v1/merchant/wallet/extra' }), false)
  assert.equal(isMerchantWalletPath({ method: 'GET', path: '/api/v1/merchant/settlement/orders' }), false)
})

test('normalizeWithdrawalAmount validates positive 2-decimal amounts without float drift', () => {
  assert.equal(normalizeWithdrawalAmount('100'), '100.00')
  assert.equal(normalizeWithdrawalAmount('100.5'), '100.50')
  assert.equal(normalizeWithdrawalAmount('0.01'), '0.01')
  assert.equal(normalizeWithdrawalAmount(' 999999999999.99 '), '999999999999.99')
  assert.equal(normalizeWithdrawalAmount('0'), null)
  assert.equal(normalizeWithdrawalAmount('0.00'), null)
  assert.equal(normalizeWithdrawalAmount('-10.00'), null)
  assert.equal(normalizeWithdrawalAmount('1.234'), null)
  assert.equal(normalizeWithdrawalAmount('abc'), null)
  assert.equal(normalizeWithdrawalAmount(''), null)
})

test('decoders accept valid Contract 80 §5 shapes and fail closed on malformed payloads', () => {
  const receiver = decodeMerchantReceiverStatus(previewMerchantReceiver())
  assert.equal(receiver.status, 'ACTIVE')
  assert.equal(receiver.active, true)
  const unconfigured = decodeMerchantReceiverStatus({
    status: 'UNCONFIGURED', statusLabel: '未配置', receiverNo: null,
    acctNameMasked: null, acctNoLast4: null, bankName: null, active: false, rejectReason: null, updatedAt: null,
  })
  assert.equal(unconfigured.active, false)
  assert.throws(() => decodeMerchantReceiverStatus({ status: 'ACTIVE', active: 'yes' }), /INVALID_RESPONSE/)

  const wallet = decodeMerchantWallet(previewMerchantWallet())
  assert.equal(wallet.availableBalance, '680.50')
  assert.equal(wallet.pendingSettlementCount, 3)
  assert.throws(() => decodeMerchantWallet({ ...previewMerchantWallet(), availableBalance: '-1.00' }), /INVALID_RESPONSE/)

  const page = decodeWithdrawalPage(previewWithdrawals())
  assert.equal(page.items.length, 2)
  assert.equal(decodeWithdrawalOrder(previewWithdrawals().items[0]).merOrderNo, 'WD202611240001')
  assert.throws(() => decodeWithdrawalPage({ items: 'bad', page: 1, size: 20, total: 0 }), /INVALID_RESPONSE/)
})

test('MerchantWalletClient reads receiver, wallet and paged withdrawals in merchant workspace only', async () => {
  const h = setupMerchantApi(async req => {
    if (req.path === '/api/v1/merchant/settlement/receiver') return ok(previewMerchantReceiver())
    if (req.path === '/api/v1/merchant/wallet') return ok(previewMerchantWallet())
    if (req.path === '/api/v1/merchant/wallet/withdrawals') return ok(previewWithdrawals())
    return failure(404, 'COMMON_NOT_FOUND')
  })
  await h.api.restore() // consumer workspace
  const client = new MerchantWalletClient(h.api)
  await assert.rejects(client.getWallet(), /MERCHANT_ENTRY_REQUIRED/)

  await h.enterMerchant('5001', '6001')
  const receiver = await client.getReceiver()
  const wallet = await client.getWallet()
  const page = await client.listWithdrawals({ status: 'SUCCEEDED', page: 1, size: 20 })
  assert.equal(receiver.acctNoLast4, '8821')
  assert.equal(wallet.availableBalance, '680.50')
  assert.equal(page.total, 2)
  assert.deepEqual(h.calls.map(c => `${c.method} ${c.path}`), [
    'GET /api/v1/merchant/settlement/receiver',
    'GET /api/v1/merchant/wallet',
    'GET /api/v1/merchant/wallet/withdrawals',
  ])
  assert.deepEqual(h.calls[2]!.data, { status: 'SUCCEEDED', page: 1, size: 20 })
})

test('withdraw journals X-Request-Id across unknown 503 outcome and retires slot on definite 409 rejection', async () => {
  let attempt = 0
  const h = setupMerchantApi(async req => {
    if (req.path === '/api/v1/merchant/wallet/withdrawals' && req.method === 'POST') {
      attempt++
      if (attempt === 1) return failure(503, 'COMMON_DEPENDENCY_UNAVAILABLE', 'channel timeout')
      if (attempt === 2) return ok(previewWithdrawals().items[0])
      if (attempt === 3) return failure(409, 'INSUFFICIENT_BALANCE', '余额不足')
      return ok(previewWithdrawals().items[1])
    }
    return ok({})
  })
  await h.enterMerchant('5001', '6001')
  const client = new MerchantWalletClient(h.api)

  // 1) 未知结果 503：保留幂等槽，不允许改金额提交，按原金额重试沿用同一 X-Request-Id
  await assert.rejects(client.withdraw('300'), err => err instanceof ApiError && err.statusCode === 503)
  assert.equal(client.pendingWithdrawal()?.amount, '300.00')
  assert.equal(withdrawalSlot('957001', '5001'), 'merchant-wallet-withdraw:957001:5001')
  await assert.rejects(client.withdraw('200'), /PENDING_WRITE_CHANGED/)

  const succeeded = await client.withdraw('300.00')
  assert.equal(succeeded.amount, '300.00')
  assert.equal(h.calls[1]!.headers['X-Request-Id'], h.calls[0]!.headers['X-Request-Id'])
  assert.deepEqual(h.calls[0]!.data, { amount: '300.00' })
  assert.equal(client.pendingWithdrawal(), null)

  // 2) 明确业务 409（非 IDEMPOTENCY_KEY_CONFLICT）：退役槽位，允许调整金额以新 X-Request-Id 提交
  await assert.rejects(client.withdraw('999.00'), err => err instanceof ApiError && err.code === 'INSUFFICIENT_BALANCE')
  assert.equal(client.pendingWithdrawal(), null)
  const retryLower = await client.withdraw('120')
  assert.equal(retryLower.amount, '120.00')
  assert.notEqual(h.calls[3]!.headers['X-Request-Id'], h.calls[2]!.headers['X-Request-Id'])
})

test('error messages cover 403 OWNER-only, 409 conflict and invalid amount', () => {
  assert.match(merchantWalletErrorMessage(new ApiError('COMMON_FORBIDDEN', 403)), /OWNER/)
  assert.match(merchantWalletErrorMessage(new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409)), /相同请求编号/)
  assert.match(merchantWalletErrorMessage(new Error('INVALID_AMOUNT')), /合法提现金额/)
  assert.match(merchantWalletErrorMessage(new Error('PENDING_WRITE_CHANGED')), /原金额重试/)
})

test('product hard-rule static guards for wallet, receiver, and C-end pay.tsx', () => {
  const stripComments = (src: string) => src.replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, '')
  const walletPage = stripComments(readFileSync(new URL('../../merchant/pages/wallet/index.tsx', import.meta.url), 'utf8'))
  const receiverPage = stripComments(readFileSync(new URL('../../merchant/pages/wallet/receiver.tsx', import.meta.url), 'utf8'))
  const withdrawalsPage = stripComments(readFileSync(new URL('../../merchant/pages/wallet/withdrawals.tsx', import.meta.url), 'utf8'))
  const payPage = stripComments(readFileSync(new URL('../../consumer/pages/booking/pay.tsx', import.meta.url), 'utf8'))

  // 40 号 §5.5 / §8 / §9：商家钱包无短信验证码、无平台补贴展示
  assert.doesNotMatch(walletPage, /验证码|smsCode|otp/i)
  assert.doesNotMatch(walletPage, /subsidy|平台补贴/i)
  // 40 号 §4.2 / §7 / §8：商家接收方页严格只读，无输入框/无提交写表单
  assert.doesNotMatch(receiverPage, /<Input\b|createReceiver|updateReceiver/)
  // P-03：商家端禁止 navigateBack
  for (const src of [walletPage, receiverPage, withdrawalsPage]) {
    assert.doesNotMatch(src, /Taro\.navigateBack/)
  }
  // C 端 pay.tsx：真实模式调用 Taro.requestPayment，preview 跳过，不展示渠道选择器，不虚构已支付态
  assert.match(payPage, /Taro\.requestPayment\(/)
  assert.match(payPage, /if \(preview\)/)
  assert.match(payPage, /微信支付已提交，正在确认订单支付结果，请前往订单详情查看最终状态。/)
  assert.match(payPage, /已取消微信支付，可点击按钮重新发起。/)
})
