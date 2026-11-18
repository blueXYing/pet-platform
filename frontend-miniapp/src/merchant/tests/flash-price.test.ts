import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import type { WireRequest } from '../../shared/request'
import { ApiError } from '../../shared/request'
import { MerchantFlashPriceApi, type FlashPriceInput } from '../marketing/flash-api'
import {
  PRICE_MODE_OPTIONS, beijingDateTimeParts, beijingDateTimeText, beijingDateTimeToUtcIso, discountRateValid,
  flashCancellable, flashEditable, flashPriceMessage, flashPricePreview, flashStatusPill, parseQuota, quotaProblem,
} from '../marketing/flash-model'

// Contract-77 P2 前端纯逻辑测试：闪价记录解码（任务线形状字符串金额/折扣 + number 版本归一、
// 枚举/类型不符 fail）、商家五接口路径/载荷（不传商家坐标；expectedVersion 数值；写命令带
// X-Request-Id 且同载荷重放同 ID）、状态胶囊（ACTIVE 按时间窗）、可编辑/可取消集合、折扣
// 校验与限时价 HALF_UP 预览、北京时间↔UTC 组合/回显、配额校验与错误码中文映射（fake transport）。

const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00Z' }
const ok = (data: unknown, statusCode = 200) => ({ statusCode, data: { success: true, code: 'SUCCESS', data } })
const flashRecord = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  flashPriceId: '730001', serviceId: '710301', merchantId: '9001', storeId: '8001',
  title: '洗护秒杀', description: '门店洗护限时特价',
  priceMode: 'DISCOUNT', discountRate: '8.8',
  flashPrice: '88.00', listPriceSnapshot: '100.00',
  startAt: '2026-11-20T10:00:00Z', endAt: '2026-11-20T12:00:00Z',
  totalQuota: 20, perUserLimit: 1, soldCount: 3, status: 'DRAFT',
  rejectReason: null, version: 1, participantCount: 3,
  createdAt: '2026-11-18T08:00:00Z', updatedAt: '2026-11-18T08:00:00Z',
  ...overrides,
})

type Setup = ReturnType<typeof setup>
function setup(json: (spec: WireRequest) => unknown = spec => {
  if (spec.path === '/api/v1/c/auth/session') return session
  if (spec.method === 'GET' && /^\/api\/v1\/merchant\/services\/[1-9][0-9]{0,18}\/flash-prices$/.test(spec.path)) return { items: [flashRecord()], page: 1, pageSize: 50, total: 1 }
  if (spec.method !== 'GET' && /\/flash-prices/.test(spec.path)) return flashRecord({ status: 'PENDING_REVIEW', version: 2 })
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

test('flash list decode: task wire shape (string money/rate, number version) survives strict decoding', async () => {
  const h = await setup(); await h.merchantScope()
  const [record] = await new MerchantFlashPriceApi(h.api).list('710301')
  assert.equal(record.flashPriceId, '730001')
  assert.equal(record.priceMode, 'DISCOUNT')
  assert.equal(record.discountRate, '8.8')
  assert.equal(record.flashPrice, '88.00')
  assert.equal(record.listPriceSnapshot, '100.00')
  assert.equal(record.totalQuota, 20)
  assert.equal(record.perUserLimit, 1)
  assert.equal(record.soldCount, 3)
  assert.equal(record.status, 'DRAFT')
  assert.equal(record.version, '1')
  assert.equal(record.participantCount, 3)
  assert.equal(record.rejectReason, null)
})

test('flash list decode: number money/rate and string version normalize; bad shapes fail closed', async () => {
  const numberShapes = await setup(() => ({ items: [flashRecord({ discountRate: 8.8, flashPrice: 88, listPriceSnapshot: 100.5, version: '3' })], page: 1, pageSize: 50, total: 1 }))
  await numberShapes.merchantScope()
  const [normalized] = await new MerchantFlashPriceApi(numberShapes.api).list('710301')
  assert.equal(normalized.discountRate, '8.8')
  assert.equal(normalized.flashPrice, '88.00')
  assert.equal(normalized.listPriceSnapshot, '100.50')
  assert.equal(normalized.version, '3')
  const bad: Record<string, unknown>[] = [
    { discountRate: '8.85' }, { discountRate: 0 }, { discountRate: 10 }, { discountRate: 'x' },
    { flashPrice: '88.0' }, { status: 'ONLINE' }, { version: 0 }, { totalQuota: 0 }, { perUserLimit: 1001 },
    { soldCount: -1 }, { title: '' }, { startAt: '2026-11-20 10:00:00' }, { flashPriceId: 'x1' },
  ]
  for (const overrides of bad) {
    const h = await setup(() => ({ items: [flashRecord(overrides)], page: 1, pageSize: 50, total: 1 }))
    await h.merchantScope()
    await assert.rejects(new MerchantFlashPriceApi(h.api).list('710301'), /INVALID_RESPONSE/, JSON.stringify(overrides))
  }
})

test('merchant flash routes carry no merchant coordinates; query is status/page/pageSize only', async () => {
  const h = await setup(); await h.merchantScope()
  const face = new MerchantFlashPriceApi(h.api)
  await face.list('710301', 'DRAFT', 1, 50)
  const request = h.requests.find(spec => spec.method === 'GET' && /\/flash-prices$/.test(spec.path))
  assert.equal(request?.path, '/api/v1/merchant/services/710301/flash-prices')
  assert.deepEqual(request?.data, { status: 'DRAFT', page: 1, pageSize: 50 })
  assert.ok(!('merchantId' in (request?.data ?? {})) && !('storeId' in (request?.data ?? {})))
})

test('create/update payloads omit blank optional fields and never carry server-fixed fields', async () => {
  const h = await setup(); await h.merchantScope()
  const face = new MerchantFlashPriceApi(h.api)
  const input: FlashPriceInput = {
    title: '洗护秒杀', description: '门店洗护限时特价',
    priceModeLabel: 'FLASH_SALE', discountRate: '8.8',
    startAt: '2026-11-20T02:00:00.000Z', endAt: '2026-11-20T04:00:00.000Z',
    totalQuota: 20, perUserLimit: 1,
  }
  await face.create('710301', input)
  await face.update('710301', '730001', input, '1')
  const [create, update] = h.requests.filter(spec => /\/flash-prices/.test(spec.path) && spec.method !== 'GET')
  assert.equal(create?.method, 'POST')
  assert.equal(create?.path, '/api/v1/merchant/services/710301/flash-prices')
  assert.deepEqual(create?.data, {
    priceModeLabel: 'FLASH_SALE', discountRate: '8.8',
    startAt: '2026-11-20T02:00:00.000Z', endAt: '2026-11-20T04:00:00.000Z',
    title: '洗护秒杀', description: '门店洗护限时特价', totalQuota: 20, perUserLimit: 1,
  })
  assert.equal(update?.method, 'PUT')
  assert.equal(update?.path, '/api/v1/merchant/services/710301/flash-prices/730001')
  assert.equal((update?.data as Record<string, unknown>).expectedVersion, 1)
  for (const spec of [create, update]) {
    for (const forbidden of ['status', 'merchantId', 'storeId', 'flashPrice', 'listPriceSnapshot', 'soldCount']) {
      assert.ok(!(forbidden in ((spec?.data as Record<string, unknown>) ?? {})), `client must never send ${forbidden}`)
    }
  }
  // 空可选字段省略（description 清空/不限量）：不伪造空串或 0。
  const lean = await setup()
  await lean.merchantScope()
  await new MerchantFlashPriceApi(lean.api).create('710301', {
    priceModeLabel: 'LIMITED_DISCOUNT', discountRate: '9',
    startAt: '2026-11-21T02:00:00.000Z', endAt: '2026-11-21T04:00:00.000Z',
  })
  const leanCreate = lean.requests.find(spec => spec.method === 'POST' && /\/flash-prices$/.test(spec.path))
  assert.deepEqual(leanCreate?.data, {
    priceModeLabel: 'LIMITED_DISCOUNT', discountRate: '9',
    startAt: '2026-11-21T02:00:00.000Z', endAt: '2026-11-21T04:00:00.000Z',
  })
})

test('write commands journal the X-Request-Id; a lost create replays the same id on retry', async () => {
  const h = await setup(async spec => {
    if (spec.method === 'POST' && /\/flash-prices$/.test(spec.path)) throw new Error('lost connection')
    return ok(spec.path === '/api/v1/c/auth/session' ? session : flashRecord())
  })
  await h.merchantScope()
  const face = new MerchantFlashPriceApi(h.api)
  const input: FlashPriceInput = {
    priceModeLabel: 'FLASH_SALE', discountRate: '8.8',
    startAt: '2026-11-20T02:00:00.000Z', endAt: '2026-11-20T04:00:00.000Z',
  }
  await assert.rejects(face.create('710301', input), /lost connection/)
  // 槽位日志保留：同载荷重试用同一 X-Request-Id（终端幂等，不二次创建）。
  await assert.rejects(face.create('710301', input), /lost connection/)
  const writes = h.requests.filter(spec => spec.method === 'POST' && /\/flash-prices$/.test(spec.path))
  assert.equal(writes.length, 2)
  assert.match(writes[0].requestId || '', /^[0-9a-f-]{36}$/i)
  assert.equal(writes[0].requestId, writes[1].requestId)
  // 改载荷另发被拒（不盲发新 ID）。
  await assert.rejects(face.create('710301', { ...input, discountRate: '9' }), /PENDING_WRITE_CHANGED/)
})

test('submit/cancel target the record in the path with numeric expectedVersion', async () => {
  const h = await setup(); await h.merchantScope()
  const face = new MerchantFlashPriceApi(h.api)
  await face.submit('710301', '730001', '1')
  await face.cancel('710301', '730001', '2')
  const [submit, cancel] = h.requests.filter(spec => /\/(submit|cancel)$/.test(spec.path))
  assert.equal(submit?.path, '/api/v1/merchant/services/710301/flash-prices/730001/submit')
  assert.deepEqual(submit?.data, { expectedVersion: 1 })
  assert.equal(cancel?.path, '/api/v1/merchant/services/710301/flash-prices/730001/cancel')
  assert.deepEqual(cancel?.data, { expectedVersion: 2 })
  assert.match(submit?.requestId || '', /^[0-9a-f-]{36}$/i)
})

test('status pill: eight statuses with the ACTIVE time-window split', () => {
  const start = '2026-11-20T02:00:00.000Z', end = '2026-11-20T04:00:00.000Z'
  const during = Date.parse('2026-11-20T03:00:00.000Z')
  assert.deepEqual(flashStatusPill('DRAFT', during, start, end), { text: '草稿', tone: 'draft' })
  assert.deepEqual(flashStatusPill('PENDING_REVIEW', during, start, end), { text: '待审核', tone: 'review' })
  assert.deepEqual(flashStatusPill('REJECTED', during, start, end), { text: '已驳回', tone: 'rejected' })
  assert.deepEqual(flashStatusPill('SCHEDULED', during, start, end), { text: '待开始', tone: 'scheduled' })
  assert.deepEqual(flashStatusPill('PAUSED', during, start, end), { text: '已暂停', tone: 'paused' })
  assert.deepEqual(flashStatusPill('ENDED', during, start, end), { text: '已结束', tone: 'ended' })
  assert.deepEqual(flashStatusPill('CANCELED', during, start, end), { text: '已取消', tone: 'canceled' })
  assert.deepEqual(flashStatusPill('ACTIVE', Date.parse('2026-11-20T01:00:00.000Z'), start, end), { text: '待开始', tone: 'scheduled' })
  assert.deepEqual(flashStatusPill('ACTIVE', during, start, end), { text: '进行中', tone: 'online' })
  assert.deepEqual(flashStatusPill('ACTIVE', Date.parse('2026-11-20T05:00:00.000Z'), start, end), { text: '已结束', tone: 'ended' })
  assert.equal(flashEditable('DRAFT'), true)
  assert.equal(flashEditable('REJECTED'), true)
  assert.equal(flashEditable('PENDING_REVIEW'), false)
  assert.equal(flashEditable('ACTIVE'), false)
  assert.equal(flashCancellable('DRAFT'), true)
  assert.equal(flashCancellable('PENDING_REVIEW'), true)
  assert.equal(flashCancellable('SCHEDULED'), false)
  assert.equal(flashCancellable('ACTIVE'), false)
})

test('discount validation and the HALF_UP flash-price preview', () => {
  assert.equal(discountRateValid('8.8'), true)
  assert.equal(discountRateValid('9'), true)
  assert.equal(discountRateValid('0.5'), true)
  for (const bad of ['0', '10', '8.85', '8.', '.8', '-1', 'a', '8.80', '']) assert.equal(discountRateValid(bad), false)
  assert.equal(flashPricePreview('100.00', '8.8'), '88.00')
  assert.equal(flashPricePreview('99.99', '8.5'), '84.99')
  assert.equal(flashPricePreview('10.00', '9.9'), '9.90')
  assert.equal(flashPricePreview('1.00', '0.1'), '0.01')
  assert.equal(flashPricePreview('100.00', 'bad'), null)
  assert.equal(flashPricePreview('bad', '8.8'), null)
  assert.equal(quotaProblem('', 1000000, '发放总量'), null)
  assert.equal(quotaProblem('20', 1000000, '发放总量'), null)
  assert.equal(quotaProblem('0', 1000000, '发放总量'), '发放总量需为 1-1000000 的正整数，留空表示不限')
  assert.equal(quotaProblem('1000001', 1000000, '发放总量'), '发放总量需为 1-1000000 的正整数，留空表示不限')
  assert.equal(parseQuota(''), undefined)
  assert.equal(parseQuota('3'), 3)
})

test('beijing date+time pickers compose to UTC and split back for the form', () => {
  assert.equal(beijingDateTimeToUtcIso('2026-11-20', '10:00'), '2026-11-20T02:00:00.000Z')
  assert.equal(beijingDateTimeToUtcIso('2026-11-20', '00:05'), '2026-11-19T16:05:00.000Z')
  assert.throws(() => beijingDateTimeToUtcIso('2026/11/20', '10:00'), /INVALID_DATE/)
  assert.throws(() => beijingDateTimeToUtcIso('2026-11-20', '24:00'), /INVALID_DATE/)
  assert.equal(beijingDateTimeText('2026-11-20T02:00:00.000Z'), '2026-11-20 10:00')
  assert.equal(beijingDateTimeText(null), '--')
  assert.deepEqual(beijingDateTimeParts('2026-11-19T16:05:00.000Z'), { date: '2026-11-20', time: '00:05' })
  assert.deepEqual(beijingDateTimeParts(null), { date: '', time: '' })
  assert.deepEqual(PRICE_MODE_OPTIONS.map(option => option.label), ['限时折扣', '限时秒杀'])
})

test('error mapping: flash error codes reach merchant-facing copy without inventing numbers', () => {
  assert.match(flashPriceMessage(new ApiError('FLASH_PRICE_WINDOW_OVERLAP', 409)), /重叠/)
  assert.match(flashPriceMessage(new ApiError('FLASH_PRICE_INVALID', 409)), /低于服务当前价/)
  assert.match(flashPriceMessage(new ApiError('FLASH_PRICE_STATE_NOT_ALLOWED', 409)), /状态/)
  assert.match(flashPriceMessage(new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409)), /请求编号/)
  assert.match(flashPriceMessage(new Error('PENDING_WRITE_CHANGED')), /原提交/)
  assert.match(flashPriceMessage(new Error('boom')), /请重试/)
})
