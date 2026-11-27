import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import type { WireRequest } from '../../shared/request'
import { ApiError } from '../../shared/request'
import { MerchantCouponApi, type CouponCampaignInput } from '../marketing/coupon-api'
import {
  COUPON_TYPE_CHIPS, beijingDateText, beijingDayToUtcIso, couponEditable, couponMessage, couponStatusPill,
  couponSubline, moneyAtLeast, moneyDisplay, normalizeMoneyInput, parseCountInput, redemptionRatePercent,
} from '../marketing/coupon-model'

// Contract-79 P3 前端纯逻辑测试：活动记录/概览/回执解码（两位小数金额、四位小数核销率、
// 枚举/类型不符 fail、number 版本与字符串金额归一）、商家端路径/坐标纪律（GET 坐标走 query
// data；create/update 坐标拼 URL query、body 保持 §3.1 精确 schema 不携带服务端字段；submit
// 路径即目标）、写命令 X-Request-Id 槽位重放、八态胶囊与可编辑集合、优惠形式 chip 置灰、
// 金额归一/预算比较、北京时间日期↔UTC、核销率百分比、错误码中文映射（fake transport）。

const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00Z' }
const ok = (data: unknown, statusCode = 200) => ({ statusCode, data: { success: true, code: 'SUCCESS', data } })
const couponRecord = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  campaignId: '740001', merchantId: '9001', storeId: '8001',
  name: '新客立减20元券', couponType: 'THRESHOLD', amountOff: '20.00', thresholdAmount: '100.00',
  typeLabel: '满减券', totalLimit: 100, perUserLimit: 1, budgetLimit: '2000.00',
  issuedCount: 31, issuedAmount: '620.00',
  startAt: '2026-12-01T00:00:00Z', endAt: '2026-12-31T15:59:59Z',
  description: '新客进店立减', status: 'DRAFT', rejectReason: null, version: 1,
  claimedCount: 31, usedCount: 20, drivenOrderCount: 18, budgetUsedAmount: '400.00',
  createdAt: '2026-11-27T08:00:00Z', updatedAt: '2026-11-27T08:00:00Z',
  ...overrides,
})
const overview = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  totalCampaignCount: 3, activeCampaignCount: 2, pendingReviewCount: 1,
  monthIssuedCount: 128, redemptionRate: '0.6200', drivenOrderCount: 326,
  ...overrides,
})

type Setup = ReturnType<typeof setup>
function setup(json: (spec: WireRequest) => unknown = spec => {
  if (spec.path === '/api/v1/c/auth/session') return session
  if (spec.method === 'GET' && spec.path === '/api/v1/merchant/coupon-campaigns') return { items: [couponRecord()], page: 1, pageSize: 20, total: 1 }
  if (spec.method === 'GET' && spec.path === '/api/v1/merchant/coupon-campaigns/overview') return overview()
  if (spec.method === 'GET' && /^\/api\/v1\/merchant\/coupon-campaigns\/[1-9][0-9]{0,18}$/.test(spec.path)) return couponRecord()
  if (spec.method !== 'GET' && /coupon-campaigns/.test(spec.path)) return { campaignId: '740001', status: 'PENDING_REVIEW', version: 2 }
  return {}
}) {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => { values.set(key, structuredClone(value)) }, remove: key => { values.delete(key) } }
  values.set('pet.c.session.v1', { ...session, accessToken: 'test-only-token', tokenType: 'Bearer' })
  const requests: WireRequest[] = []
  const api = new ConsumerApi(async spec => { requests.push(spec); return ok(spec.path === '/api/v1/c/auth/session' ? session : json(spec)) }, store, async () => randomUUID())
  return {
    api, store, requests,
    async merchantScope() {
      await api.restore()
      api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '9001', storeId: '8001' })
    },
  }
}

const input: CouponCampaignInput = {
  name: '新客立减20元券', amountOff: '20.00', thresholdAmount: '100.00',
  totalLimit: 100, perUserLimit: 1, budgetLimit: '2000.00',
  startAt: '2026-12-01T00:00:00.000Z', endAt: '2026-12-31T15:59:59.000Z',
  description: '新客进店立减',
}

test('coupon list/overview decode: task wire shape (string money, number version, rate 4dp) survives strict decoding', async () => {
  const h = await setup(); await h.merchantScope()
  const face = new MerchantCouponApi(h.api)
  const page = await face.list('9001', '8001')
  const record = page.items[0]
  assert.equal(record.campaignId, '740001')
  assert.equal(record.couponType, 'THRESHOLD')
  assert.equal(record.amountOff, '20.00')
  assert.equal(record.thresholdAmount, '100.00')
  assert.equal(record.budgetLimit, '2000.00')
  assert.equal(record.totalLimit, 100)
  assert.equal(record.perUserLimit, 1)
  assert.equal(record.issuedAmount, '620.00')
  assert.equal(record.status, 'DRAFT')
  assert.equal(record.version, '1')
  assert.equal(record.drivenOrderCount, 18)
  assert.equal(record.rejectReason, null)
  assert.equal(page.total, 1)
  const stats = await face.overview('9001', '8001')
  assert.equal(stats.monthIssuedCount, 128)
  assert.equal(stats.redemptionRate, '0.6200')
  assert.equal(stats.drivenOrderCount, 326)
  const detail = await face.detail('9001', '8001', '740001')
  assert.equal(detail.name, '新客立减20元券')
})

test('coupon decode: number money/rate and string version normalize; bad shapes fail closed', async () => {
  const normalized = await setup(() => ({
    items: [couponRecord({ amountOff: 20, budgetLimit: 2000.5, redemptionRatePlaceholder: undefined })], page: 1, pageSize: 20, total: 1,
  }))
  await normalized.merchantScope()
  const normalizedPage = await new MerchantCouponApi(normalized.api).list('9001', '8001')
  const row = normalizedPage.items[0]
  assert.equal(row.amountOff, '20.00')
  assert.equal(row.budgetLimit, '2000.50')
  const bad: Record<string, unknown>[] = [
    { name: '' }, { name: 'x'.repeat(51) }, { couponType: 'DISCOUNT' }, { amountOff: '20' },
    { thresholdAmount: '100.0' }, { totalLimit: 0 }, { perUserLimit: 11 }, { issuedCount: -1 },
    { status: 'ONLINE' }, { version: 0 }, { startAt: '2026-12-01 00:00:00' }, { campaignId: 'x1' },
  ]
  for (const overrides of bad) {
    const h = await setup(() => ({ items: [couponRecord(overrides)], page: 1, pageSize: 20, total: 1 }))
    await h.merchantScope()
    await assert.rejects(new MerchantCouponApi(h.api).list('9001', '8001'), /INVALID_RESPONSE/, JSON.stringify(overrides))
  }
  const badRates = ['0.62', 1.5, 'x', 2]
  for (const rate of badRates) {
    const h = await setup(() => overview({ redemptionRate: rate }))
    await h.merchantScope()
    await assert.rejects(new MerchantCouponApi(h.api).overview('9001', '8001'), /INVALID_RESPONSE/, String(rate))
  }
})

test('coupon reads carry merchant coordinates in the GET query; submit is path-targeted only', async () => {
  const h = await setup(); await h.merchantScope()
  const face = new MerchantCouponApi(h.api)
  await face.list('9001', '8001', 2, 20)
  await face.overview('9001', '8001')
  await face.detail('9001', '8001', '740001')
  const [list, stats, detail] = h.requests.filter(spec => spec.method === 'GET' && /coupon-campaigns/.test(spec.path))
  assert.deepEqual(list?.data, { merchantId: '9001', storeId: '8001', page: 2, pageSize: 20 })
  assert.equal(list?.path, '/api/v1/merchant/coupon-campaigns')
  assert.deepEqual(stats?.data, { merchantId: '9001', storeId: '8001' })
  assert.equal(stats?.path, '/api/v1/merchant/coupon-campaigns/overview')
  assert.deepEqual(detail?.data, { merchantId: '9001', storeId: '8001' })
  assert.equal(detail?.path, '/api/v1/merchant/coupon-campaigns/740001')
  // 错误坐标在客户端即被拒（order-list 同款坐标纪律）。
  await assert.rejects(face.list('9999', '8001'), /WORKSPACE_PATH_MISMATCH/)
})

test('create/update: coordinates ride the URL query; body stays schema-exact (no server fields)', async () => {
  const h = await setup(); await h.merchantScope()
  const face = new MerchantCouponApi(h.api)
  await face.create('9001', '8001', input)
  await face.update('9001', '8001', '740001', input, '1')
  const [create, update] = h.requests.filter(spec => /coupon-campaigns/.test(spec.path) && spec.method !== 'GET')
  assert.equal(create?.method, 'POST')
  assert.equal(create?.path, '/api/v1/merchant/coupon-campaigns?merchantId=9001&storeId=8001')
  assert.deepEqual(create?.data, {
    name: '新客立减20元券', amountOff: '20.00', thresholdAmount: '100.00',
    totalLimit: 100, perUserLimit: 1, budgetLimit: '2000.00',
    startAt: '2026-12-01T00:00:00.000Z', endAt: '2026-12-31T15:59:59.000Z',
    description: '新客进店立减',
  })
  assert.equal(update?.method, 'PUT')
  assert.equal(update?.path, '/api/v1/merchant/coupon-campaigns/740001?merchantId=9001&storeId=8001')
  assert.equal((update?.data as Record<string, unknown>).expectedVersion, 1)
  for (const spec of [create, update]) {
    for (const forbidden of ['status', 'merchantId', 'storeId', 'issuedCount', 'issuedAmount', 'rejectReason', 'couponType']) {
      assert.ok(!(forbidden in ((spec?.data as Record<string, unknown>) ?? {})), `client must never send ${forbidden}`)
    }
  }
  // 无门槛券：thresholdAmount 省略 = 无门槛（79号 §3.1）；空说明省略（不伪造空值）。
  const lean = await setup()
  await lean.merchantScope()
  await new MerchantCouponApi(lean.api).create('9001', '8001', {
    ...input, thresholdAmount: undefined, description: undefined,
  })
  const leanCreate = lean.requests.find(spec => spec.method === 'POST' && /coupon-campaigns/.test(spec.path))
  assert.equal(leanCreate?.path, '/api/v1/merchant/coupon-campaigns?merchantId=9001&storeId=8001')
  assert.ok(!('thresholdAmount' in (leanCreate?.data as Record<string, unknown>)))
  assert.ok(!('description' in (leanCreate?.data as Record<string, unknown>)))
})

test('write commands journal the X-Request-Id; a lost create replays the same id on retry', async () => {
  const h = await setup(async spec => {
    if (spec.method === 'POST' && /coupon-campaigns\?/.test(spec.path)) throw new Error('lost connection')
    return ok(spec.path === '/api/v1/c/auth/session' ? session : { campaignId: '740001', status: 'DRAFT', version: 1 })
  })
  await h.merchantScope()
  const face = new MerchantCouponApi(h.api)
  await assert.rejects(face.create('9001', '8001', input), /lost connection/)
  // 槽位日志保留：同载荷重试用同一 X-Request-Id（终端幂等，不二次创建）。
  await assert.rejects(face.create('9001', '8001', input), /lost connection/)
  const writes = h.requests.filter(spec => spec.method === 'POST' && /coupon-campaigns\?/.test(spec.path))
  assert.equal(writes.length, 2)
  assert.match(writes[0].requestId || '', /^[0-9a-f-]{36}$/i)
  assert.equal(writes[0].requestId, writes[1].requestId)
  // 改载荷另发被拒（不盲发新 ID）。
  await assert.rejects(face.create('9001', '8001', { ...input, amountOff: '25.00' }), /PENDING_WRITE_CHANGED/)
})

test('submit targets the campaign in the path with numeric expectedVersion and decodes the receipt', async () => {
  const h = await setup(); await h.merchantScope()
  const face = new MerchantCouponApi(h.api)
  const receipt = await face.submit('740001', '1')
  assert.deepEqual(receipt, { campaignId: '740001', status: 'PENDING_REVIEW', version: '2' })
  const submit = h.requests.find(spec => /\/submit$/.test(spec.path))
  assert.equal(submit?.method, 'POST')
  assert.equal(submit?.path, '/api/v1/merchant/coupon-campaigns/740001/submit')
  assert.deepEqual(submit?.data, { expectedVersion: 1 })
  assert.match(submit?.requestId || '', /^[0-9a-f-]{36}$/i)
})

test('status pill: eight fixed texts; only DRAFT/REJECTED are merchant-editable', () => {
  assert.deepEqual(couponStatusPill('DRAFT'), { text: '草稿', tone: 'draft' })
  assert.deepEqual(couponStatusPill('PENDING_REVIEW'), { text: '待审核', tone: 'review' })
  assert.deepEqual(couponStatusPill('REJECTED'), { text: '已驳回', tone: 'rejected' })
  assert.deepEqual(couponStatusPill('CANCELED'), { text: '已取消', tone: 'canceled' })
  assert.deepEqual(couponStatusPill('SCHEDULED'), { text: '待开始', tone: 'scheduled' })
  assert.deepEqual(couponStatusPill('ACTIVE'), { text: '进行中', tone: 'online' })
  assert.deepEqual(couponStatusPill('PAUSED'), { text: '已暂停', tone: 'paused' })
  assert.deepEqual(couponStatusPill('ENDED'), { text: '已结束', tone: 'ended' })
  assert.equal(couponEditable('DRAFT'), true)
  assert.equal(couponEditable('REJECTED'), true)
  for (const status of ['PENDING_REVIEW', 'CANCELED', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED']) {
    assert.equal(couponEditable(status), false, status)
  }
})

test('coupon type chips: threshold/no-threshold selectable; discount/voucher disabled with 暂不支持', () => {
  assert.deepEqual(COUPON_TYPE_CHIPS.map(chip => chip.label), ['满减券', '折扣券', '立减券', '兑换券'])
  assert.deepEqual(COUPON_TYPE_CHIPS.filter(chip => chip.available).map(chip => chip.value), ['THRESHOLD', 'NO_THRESHOLD'])
  for (const chip of COUPON_TYPE_CHIPS.filter(entry => !entry.available)) {
    assert.equal(chip.hint, '暂不支持')
    assert.equal(chip.value, null)
  }
})

test('money input normalization and the budget ≥ amountOff comparison (integer cents, no floats)', () => {
  assert.equal(normalizeMoneyInput('20'), '20.00')
  assert.equal(normalizeMoneyInput('20.5'), '20.50')
  assert.equal(normalizeMoneyInput('0.01'), '0.01')
  assert.equal(normalizeMoneyInput(' 88.88 '), '88.88')
  for (const bad of ['', '0', '0.00', '-1', '20.', '.5', '20.123', '1e3', 'abc', '007']) {
    assert.equal(normalizeMoneyInput(bad), null, bad)
  }
  assert.equal(moneyAtLeast('2000.00', '20.00'), true)
  assert.equal(moneyAtLeast('20.00', '20.00'), true)
  assert.equal(moneyAtLeast('19.99', '20.00'), false)
  assert.equal(moneyDisplay('100.00'), '100')
  assert.equal(moneyDisplay('88.50'), '88.5')
  assert.equal(moneyDisplay('88.05'), '88.05')
})

test('count inputs enforce 1..1,000,000 / 1..10 bounds', () => {
  assert.equal(parseCountInput('100', 1, 1000000), 100)
  assert.equal(parseCountInput('0', 1, 1000000), null)
  assert.equal(parseCountInput('1000001', 1, 1000000), null)
  assert.equal(parseCountInput('3.5', 1, 1000000), null)
  assert.equal(parseCountInput('', 1, 1000000), null)
  assert.equal(parseCountInput('10', 1, 10), 10)
  assert.equal(parseCountInput('11', 1, 10), null)
})

test('beijing day pickers compose to UTC (start 00:00:00 / end 23:59:59) and split back for the form', () => {
  assert.equal(beijingDayToUtcIso('2026-12-01'), '2026-11-30T16:00:00.000Z')
  assert.equal(beijingDayToUtcIso('2026-12-31', true), '2026-12-31T15:59:59.000Z')
  assert.throws(() => beijingDayToUtcIso('2026/12/01'), /INVALID_DATE/)
  assert.throws(() => beijingDayToUtcIso(''), /INVALID_DATE/)
  assert.equal(beijingDateText('2026-12-31T15:59:59.000Z'), '2026-12-31')
  assert.equal(beijingDateText('2026-11-30T16:00:00.000Z'), '2026-12-01')
  assert.equal(beijingDateText(null), '')
})

test('redemption rate renders as a percent with up to two decimals', () => {
  assert.equal(redemptionRatePercent('0.6200'), '62%')
  assert.equal(redemptionRatePercent('0.6250'), '62.50%')
  assert.equal(redemptionRatePercent('0.0000'), '0%')
  assert.equal(redemptionRatePercent('1.0000'), '100%')
  assert.equal(redemptionRatePercent('0.0625'), '6.25%')
  assert.equal(redemptionRatePercent('0.62'), null)
})

test('card subline: threshold coupons show 满 X 元可用; no-threshold shows 本店服务通用', () => {
  assert.equal(couponSubline({ couponType: 'THRESHOLD', thresholdAmount: '100.00' }), '满 100 元可用')
  assert.equal(couponSubline({ couponType: 'THRESHOLD', thresholdAmount: '88.50' }), '满 88.5 元可用')
  assert.equal(couponSubline({ couponType: 'NO_THRESHOLD', thresholdAmount: null }), '本店服务通用')
})

test('error mapping: 79号 §7 codes reach merchant-facing copy without inventing numbers', () => {
  assert.match(couponMessage(new ApiError('MERCHANT_COUPON_NAME_DUPLICATE', 409)), /同名/)
  assert.match(couponMessage(new ApiError('MERCHANT_COUPON_INVALID', 400)), /规则/)
  assert.match(couponMessage(new ApiError('MERCHANT_COUPON_STATE_NOT_ALLOWED', 409)), /状态/)
  assert.match(couponMessage(new ApiError('MERCHANT_COUPON_NOT_FOUND', 404)), /不存在/)
  assert.match(couponMessage(new ApiError('MERCHANT_COUPON_NOT_ELIGIBLE', 403)), /主账号/)
  assert.match(couponMessage(new ApiError('COMMON_CONFLICT', 409)), /版本冲突/)
  assert.match(couponMessage(new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409)), /请求编号/)
  assert.match(couponMessage(new Error('PENDING_WRITE_CHANGED')), /原提交/)
  assert.match(couponMessage(new Error('boom')), /请重试/)
})
