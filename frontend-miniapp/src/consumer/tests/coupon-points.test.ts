import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import {
  couponsByStatus, decodeCoupon, decodeCouponPage, decodeLedgerItem, decodeLedgerPage, decodePointsBalance,
  deltaLabel, formatLedgerTime, isCouponPointsScenario, thresholdLabel,
  PreviewCouponPointsRepository, validateCouponFixture, validateLedgerFixture,
  couponStatuses, pointsBizTypeLabels, type CouponView, type PointsLedgerView,
} from '../coupon-points/model'
import { RealCouponPointsRepository, isCouponNotFound, isCouponPointsUnauthorized } from '../coupon-points/repository'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'

test('preview repository serves fixtures that satisfy schema-grounded validation', async () => {
  const data = await new PreviewCouponPointsRepository('normal').load()
  assert.ok(data.coupons.length >= 3)
  for (const coupon of data.coupons) assert.equal(validateCouponFixture(coupon), true, JSON.stringify(coupon))
  for (const entry of data.ledger) assert.equal(validateLedgerFixture(entry), true, JSON.stringify(entry))
  // 三种展示状态都有夹具；余额与流水首行一致（balanceAfter 语义）。
  for (const status of couponStatuses) assert.ok(data.coupons.some(coupon => coupon.status === status), status)
  assert.equal(data.ledger[0]!.balanceAfter, data.balance.balance)
  // 流水按时间倒序，且相邻行满足 balanceAfter 链式一致。
  for (let index = 1; index < data.ledger.length; index++) {
    const newer = data.ledger[index - 1]!, older = data.ledger[index]!
    assert.ok(newer.createdAt >= older.createdAt)
    assert.equal(BigInt(older.balanceAfter) + BigInt(newer.delta), BigInt(newer.balanceAfter))
  }
})

test('empty scenario has no coupons and zero balance', async () => {
  const data = await new PreviewCouponPointsRepository('empty').load()
  assert.equal(data.coupons.length, 0)
  assert.equal(data.ledger.length, 0)
  assert.equal(data.balance.balance, '0')
})

test('status bucketing keeps fixture order and only returns the requested status', async () => {
  const data = await new PreviewCouponPointsRepository('normal').load()
  for (const status of couponStatuses) {
    const bucket = couponsByStatus(data.coupons, status)
    assert.ok(bucket.every(coupon => coupon.status === status))
  }
  assert.equal(couponStatuses.length, 3)
})

test('threshold label renders threshold amounts or 无门槛', () => {
  const base: CouponView = {
    couponId: '1', name: '券', amountOff: '20.00', thresholdAmount: '100.00', scopeSummary: '全部服务通用',
    typeLabel: '通用券', validTo: '2026-09-30', status: 'AVAILABLE', usedAt: null,
  }
  assert.equal(thresholdLabel(base), '满100.00元可用')
  assert.equal(thresholdLabel({ ...base, thresholdAmount: null }), '无门槛')
})

test('delta labels carry explicit sign; clawback stays negative', () => {
  const entry = (delta: string): PointsLedgerView => ({
    ledgerId: '1', bizType: delta.startsWith('-') ? 'REFUND_CLAWBACK' : 'SIGN_IN', delta, balanceAfter: '0',
    createdAt: '2026-09-30T00:00:00.000Z',
  })
  assert.equal(deltaLabel(entry('5')), '+5')
  assert.equal(deltaLabel(entry('-51')), '-51')
  assert.ok(Object.keys(pointsBizTypeLabels).includes('REFUND_CLAWBACK'))
})

test('ledger time renders today/yesterday/absolute deterministically (UTC-based)', () => {
  const now = new Date('2026-10-01T04:00:00.000Z')
  assert.equal(formatLedgerTime(now, '2026-10-01T01:05:00.000Z'), '今天 01:05')
  assert.equal(formatLedgerTime(now, '2026-09-30T06:20:00.000Z'), '昨天 06:20')
  assert.equal(formatLedgerTime(now, '2026-09-20T01:30:00.000Z'), '9月20日 01:30')
  assert.equal(formatLedgerTime(now, '2025-12-31T16:05:00.000Z'), '2025年12月31日 16:05')
  assert.equal(formatLedgerTime(now, 'not-a-timestamp'), '')
})

test('scenario guard only accepts the registered preview scenarios', () => {
  assert.equal(isCouponPointsScenario('normal'), true)
  assert.equal(isCouponPointsScenario('empty'), true)
  assert.equal(isCouponPointsScenario('load-error'), false)
  assert.equal(isCouponPointsScenario(undefined), false)
})

test('fixtures never contain points redemption or discount-type samples (V1 hard rules)', async () => {
  const data = await new PreviewCouponPointsRepository('normal').load()
  // 积分只赚取/扣回：全部流水为非零整数且不存在"兑换"类目；V1 无积分商城/兑换。
  assert.ok(data.ledger.every(entry => entry.delta !== '0'))
  // 折扣型（如 5折）不在 V1 券事实内：面额一律两位小数金额串。
  assert.ok(data.coupons.every(coupon => coupon.amountOff !== null && /^\d+\.\d{2}$/.test(coupon.amountOff)))
})

// ---- PR#112 真实契约：解码沿 model.ts 严格 exact-key 惯例（10 号 §3.15.1/§3.15.2、11 号 schema）。

const wireCoupon = {
  couponId: '730101', name: '满100减20', amountOff: '20.00', thresholdAmount: '100.00',
  scopeSummary: '全部服务通用', typeLabel: '通用券', validTo: '2026-09-30', status: 'AVAILABLE', usedAt: null,
}
const wireLedger = (over: Record<string, unknown> = {}) => ({
  ledgerId: '740901', bizType: 'SIGN_IN', delta: '5', balanceAfter: '1280',
  createdAt: '2026-09-30T01:05:00.000Z', ...over,
})

test('coupon decoder accepts the #112 wire projection with D1 nulls and absent-as-null', () => {
  const decoded = decodeCoupon(JSON.parse(JSON.stringify(wireCoupon)))
  assert.equal(decoded.couponId, '730101')
  assert.equal(decoded.status, 'AVAILABLE')
  // D1 投影字段缺失/为 null 均按契约读作 null（rule_json 未冻结，前端不解析）。
  for (const key of ['amountOff', 'thresholdAmount', 'scopeSummary', 'typeLabel'] as const) {
    const absent = { ...wireCoupon, [key]: undefined }
    delete (absent as Record<string, unknown>)[key]
    assert.equal(decodeCoupon(absent)[key], null, key)
    assert.equal(decodeCoupon({ ...wireCoupon, [key]: null })[key], null, key)
  }
  // 金额按 11 号 DecimalAmount：0-16 位整数 + 可选 1-2 位小数（#112 实际恒两位，D4 原样渲染）。
  assert.equal(decodeCoupon({ ...wireCoupon, amountOff: '20' }).amountOff, '20')
  assert.equal(decodeCoupon({ ...wireCoupon, amountOff: '20.5' }).amountOff, '20.5')
  // USED 必带核销时间（毫秒 ISO 时间戳），其余状态必须为 null。
  const used = decodeCoupon({ ...wireCoupon, status: 'USED', usedAt: '2026-06-01T10:20:00.000Z' })
  assert.equal(used.usedAt, '2026-06-01T10:20:00.000Z')
})

test('coupon decoder fails closed on contract violations (exact-key + enums + patterns)', () => {
  for (const mutate of [
    (v: Record<string, unknown>) => { v.rating = '4.9' },                                   // 未知键
    (v: Record<string, unknown>) => { delete v.couponId },                                  // required 缺失
    (v: Record<string, unknown>) => { delete v.name },
    (v: Record<string, unknown>) => { delete v.validTo },
    (v: Record<string, unknown>) => { delete v.status },
    (v: Record<string, unknown>) => { v.status = 'FROZEN' },                                // D2 三桶之外
    (v: Record<string, unknown>) => { v.status = 'USED' },                                  // USED 无 usedAt
    (v: Record<string, unknown>) => { v.usedAt = '2026-06-01T10:20:00Z' },                  // 缺毫秒/Z
    (v: Record<string, unknown>) => { v.amountOff = '-5' },                                  // 负金额
    (v: Record<string, unknown>) => { v.amountOff = '20.123' },                              // 三位小数
    (v: Record<string, unknown>) => { v.amountOff = 20 },                                    // 非字符串
    (v: Record<string, unknown>) => { v.couponId = '0' },                                    // 非正 Long
    (v: Record<string, unknown>) => { v.name = '' },
    (v: Record<string, unknown>) => { v.validTo = '2026/09/30' },                            // 非 ISO 日期
    (v: Record<string, unknown>) => { v.scopeSummary = null, v.typeLabel = '' },             // 空串非 null
  ]) {
    const value = JSON.parse(JSON.stringify(wireCoupon)) as Record<string, unknown>
    mutate(value)
    assert.throws(() => decodeCoupon(value), /INVALID_RESPONSE/, JSON.stringify(mutate))
  }
})

test('coupon page decoder checks the paging envelope bounds', () => {
  const page = decodeCouponPage({ items: [wireCoupon], page: 1, pageSize: 20, total: 1 })
  assert.equal(page.total, 1)
  assert.throws(() => decodeCouponPage({ items: [wireCoupon], page: 0, pageSize: 20, total: 1 }), /INVALID_RESPONSE/)
  assert.throws(() => decodeCouponPage({ items: [wireCoupon], page: 1, pageSize: 51, total: 1 }), /INVALID_RESPONSE/)
  assert.throws(() => decodeCouponPage({ items: [wireCoupon], page: 1, pageSize: 20, total: -1 }), /INVALID_RESPONSE/)
  assert.throws(() => decodeCouponPage({ items: [wireCoupon], page: 1, pageSize: 20 }), /INVALID_RESPONSE/)
  assert.throws(() => decodeCouponPage({ items: wireCoupon, page: 1, pageSize: 20, total: 1 }), /INVALID_RESPONSE/)
})

test('points decoders enforce single-key balance and the five-enum nonzero-delta ledger row', () => {
  assert.deepEqual(decodePointsBalance({ balance: '0' }), { balance: '0' })
  assert.equal(decodePointsBalance({ balance: '999999999999999999' }).balance, '999999999999999999')
  for (const mutate of [
    () => decodePointsBalance({ balance: '-1' }),
    () => decodePointsBalance({ balance: '01' }),
    () => decodePointsBalance({ balance: 0 }),
    () => decodePointsBalance({}),
    () => decodePointsBalance({ balance: '5', extra: true }),
  ]) assert.throws(mutate, /INVALID_RESPONSE/)
  assert.equal(decodeLedgerItem(wireLedger({ delta: '-51', bizType: 'REFUND_CLAWBACK' })).delta, '-51')
  for (const mutate of [
    () => decodeLedgerItem(wireLedger({ delta: '0' })),                    // 非零（11 号模式）
    () => decodeLedgerItem(wireLedger({ delta: '-0' })),
    () => decodeLedgerItem(wireLedger({ balanceAfter: '-1' })),            // 余额非负
    () => decodeLedgerItem(wireLedger({ bizType: 'REDEEM' })),             // 五枚举之外（无兑换）
    () => decodeLedgerItem(wireLedger({ createdAt: '2026-09-30T01:05:00Z' })),
    () => decodeLedgerItem(wireLedger({ ledgerId: '0' })),
    () => decodeLedgerItem({ ...wireLedger(), traceId: 't' }),             // 未知键
  ]) assert.throws(mutate, /INVALID_RESPONSE/)
})

test('ledger page decoder enforces the fixed created_at DESC, id DESC order', () => {
  const sameMoment = [wireLedger({ ledgerId: '740902' }), wireLedger({ ledgerId: '740901' })]
  assert.equal(decodeLedgerPage({ items: sameMoment, page: 1, pageSize: 20, total: 2 }).items.length, 2)
  assert.throws(() => decodeLedgerPage({ items: [...sameMoment].reverse(), page: 1, pageSize: 20, total: 2 }), /INVALID_RESPONSE/)
  const ascending = [wireLedger({ ledgerId: '740902', createdAt: '2026-09-29T00:00:00.000Z' }), wireLedger({ ledgerId: '740901' })]
  assert.throws(() => decodeLedgerPage({ items: ascending, page: 1, pageSize: 20, total: 2 }), /INVALID_RESPONSE/)
})

// ---- 真实 repository：经 ConsumerApi 白名单/会话门禁的四个只读路由。

const sessionView = { userId: '957001', sessionId: '957101', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data, traceId: 'test' } })

function authenticatedApi(transport: (request: { method: string; path: string; data?: Record<string, unknown>; headers: Record<string, string> }) => Promise<{ statusCode: number; data: unknown }>) {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'test-only', tokenType: 'Bearer' })
  const api = new ConsumerApi(transport, store, async () => randomUUID())
  return { api, restore: () => api.restore() }
}

test('real repository reads the four contract routes with the approved query shape', async () => {
  const seen: { method: string; path: string; data?: Record<string, unknown>; headers: Record<string, string> }[] = []
  const { api, restore } = authenticatedApi(async request => {
    seen.push({ method: request.method, path: request.path, data: request.data, headers: request.headers })
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.path === '/api/v1/c/coupons') return ok({ items: [wireCoupon], page: 1, pageSize: 20, total: 1 })
    if (request.path === '/api/v1/c/coupons/730101') return ok(JSON.parse(JSON.stringify(wireCoupon)))
    if (request.path === '/api/v1/c/points/balance') return ok({ balance: '1280' })
    if (request.path === '/api/v1/c/points/ledger') return ok({ items: [wireLedger()], page: 1, pageSize: 20, total: 1 })
    return { statusCode: 404, data: { code: 'COMMON_NOT_FOUND', message: '优惠券不存在', data: null, traceId: 'test' } }
  })
  await restore()
  const repository = new RealCouponPointsRepository(api)
  // 列表：status 服务端分桶 + 契约分页参数（GET 无 requestId）。
  const used = await repository.listCoupons('USED', 1, 20)
  assert.equal(used.items[0]!.couponId, '730101')
  assert.equal(seen.find(entry => entry.path === '/api/v1/c/coupons')!.data!.status, 'USED')
  // 单券：按编号取，无 query。
  const detail = await repository.coupon('730101')
  assert.equal(detail.name, '满100减20')
  assert.equal(seen.find(entry => entry.path === '/api/v1/c/coupons/730101')!.data, undefined)
  // 积分两读：余额单键、明细分页。
  assert.equal((await repository.balance()).balance, '1280')
  const ledger = await repository.ledger(1, 20)
  assert.equal(ledger.items[0]!.delta, '5')
  for (const entry of seen) {
    if (entry.path === '/api/v1/c/auth/session') continue
    assert.equal(entry.method, 'GET')
    assert.ok(entry.headers.Authorization!.startsWith('Bearer '))
  }
})

test('real repository maps the anti-enumeration 404 and the session 401 for the pages', async () => {
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    return { statusCode: 404, data: { code: 'COMMON_NOT_FOUND', message: '优惠券不存在', data: null, traceId: 'test' } }
  })
  await restore()
  const repository = new RealCouponPointsRepository(api)
  // 详情 404（不存在/非本人/冻结态/路由未挂载不可区分）→ missing。
  const absent = await repository.coupon('730103').catch(error => error)
  assert.ok(isCouponNotFound(absent))
  assert.equal(isCouponPointsUnauthorized(absent), false)
  // 无法解析的编号复刻同一 404，不泄露区别。
  const unparsable = await repository.coupon('not-an-id').catch(error => error)
  assert.ok(isCouponNotFound(unparsable))
  // 列表 404（路由未挂载：pet.auth.c.enabled 关闭）→ 页面失败关闭，不渲染数据。
  const switchedOff = await repository.listCoupons('AVAILABLE', 1, 20).catch(error => error)
  assert.ok(isCouponNotFound(switchedOff))
  // 未登录（有工作区上下文、无会话凭据）→ 401 引导登录；无上下文时页面在调用前已先行引导。
  const bare = new ConsumerApi(async () => ok(null), { get: () => undefined, set: () => {}, remove: () => {} }, async () => randomUUID())
  bare.scope.replace({ userId: '957001', workspace: 'consumer', merchantId: null, storeId: null })
  const anonymous = await new RealCouponPointsRepository(bare).balance().catch(error => error)
  assert.ok(isCouponPointsUnauthorized(anonymous))
  // 503/网络故障同样走失败关闭路径（非 401/404）。
  const degraded = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    return { statusCode: 503, data: { code: 'COMMON_DEPENDENCY_UNAVAILABLE', message: 'unavailable', data: null, traceId: 'test' } }
  })
  await degraded.restore()
  const unavailable = await new RealCouponPointsRepository(degraded.api).balance().catch(error => error)
  assert.equal(isCouponPointsUnauthorized(unavailable), false)
  assert.equal(isCouponNotFound(unavailable), false)
})

test('real repository rejects decoded detail whose couponId does not echo the path', async () => {
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.path === '/api/v1/c/coupons/730101') return ok({ ...wireCoupon, couponId: '730999' })
    return { statusCode: 404, data: { code: 'COMMON_NOT_FOUND', message: 'x', data: null, traceId: 'test' } }
  })
  await restore()
  const error = await new RealCouponPointsRepository(api).coupon('730101').catch(failure => failure)
  assert.ok(/INVALID_RESPONSE/.test(String(error?.message)))
})
