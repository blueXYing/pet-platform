import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import {
  PREVIEW_VERIFY_ORDER, actionLabels, actionOrder, appointmentWindow, canShowVerifyBlock, decodeOrderDetail,
  decodeOrderPage, displayOrderStatuses, displayStatusLabels, formatOrderInstant, isOrdersScenario,
  orderReadMessage, statusVariant, validateOrdersFixture, PreviewOrderReadRepository,
  type OrderDetailView,
} from '../orders/model'
import { RealOrderReadRepository, isOrderReadUnauthorized } from '../orders/repository'
import { ApiError } from '../../shared/request'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'

// ---- preview 夹具（设计验收通道）：schema 事实自检 ----

test('preview fixtures satisfy the strict decoder and cover every display status', async () => {
  const repository = new PreviewOrderReadRepository('normal')
  const page = await repository.list(null, 1, 50)
  assert.equal(validateOrdersFixture(page.items), true)
  assert.equal(page.total, page.items.length)
  assert.equal(new Set(page.items.map(order => order.orderId)).size, page.items.length)
  for (const status of displayOrderStatuses) assert.ok(page.items.some(order => order.displayStatus === status), status)
  // 核销码入口样例：canShowVerificationCode=true，且复用 #116 夹具订单号（preview 取码模拟同源）。
  const entry = page.items.find(order => order.orderId === PREVIEW_VERIFY_ORDER)!
  assert.equal(canShowVerifyBlock(entry), true)
  assert.equal(entry.verificationStatus, 'UNVERIFIED')
  // 已核销样例：VERIFIED 与 verifiedAt 成对出现（契约事实字段）。
  for (const order of page.items.filter(item => item.verificationStatus === 'VERIFIED')) assert.ok(order.verifiedAt !== null)
  // 其余订单不呈现核销码区块（仅凭服务端 actions，不推导）。
  assert.ok(page.items.filter(order => order.orderId !== PREVIEW_VERIFY_ORDER).every(order => !canShowVerifyBlock(order)))
})

test('preview repository buckets by display status locally and pages deterministically', async () => {
  const repository = new PreviewOrderReadRepository('normal')
  const all = await repository.list(null)
  assert.equal(all.page, 1); assert.equal(all.pageSize, 20); assert.equal(all.total, all.items.length)
  for (const status of displayOrderStatuses) {
    const bucket = await repository.list(status)
    assert.ok(bucket.items.every(order => order.displayStatus === status), status)
    assert.equal(bucket.total, all.items.filter(order => order.displayStatus === status).length)
  }
  const paged = await new PreviewOrderReadRepository('normal').list(null, 1, 3)
  assert.equal(paged.items.length, 3); assert.equal(paged.total, 10)
  const full = await new PreviewOrderReadRepository('normal').list(null, 1, 50)
  const rest = await new PreviewOrderReadRepository('normal').list(null, 2, 3)
  assert.equal(rest.items[0]!.orderId, full.items[3]!.orderId) // 第二页从第 4 条继续
  const empty = await new PreviewOrderReadRepository('empty').list(null)
  assert.equal(empty.total, 0); assert.equal(empty.items.length, 0)
})

test('preview detail returns the fixture order or fails closed with 404 for unknown ids', async () => {
  const repository = new PreviewOrderReadRepository('normal')
  const detail = await repository.detail(PREVIEW_VERIFY_ORDER)
  assert.equal(detail.orderId, PREVIEW_VERIFY_ORDER)
  const missing = await repository.detail('900199999999999').catch(error => error)
  assert.ok(missing instanceof ApiError && missing.statusCode === 404)
})

test('scenario guard only accepts the registered preview scenarios', () => {
  assert.equal(isOrdersScenario('normal'), true)
  assert.equal(isOrdersScenario('empty'), true)
  assert.equal(isOrdersScenario('load-error'), false)
  assert.equal(isOrdersScenario(undefined), false)
})

// ---- 解码（10号 §3.7 + 11号 OrderDetailData；严格 exact-key + 失败关闭） ----

const wireDetail = {
  orderId: '900101001990003', orderNo: '2026100100003', displayStatus: 'PENDING_SERVICE',
  orderStage: 'PENDING_SERVICE', paymentStatus: 'PAID', verificationStatus: 'UNVERIFIED',
  refundApplicationStatus: null, refundStatus: null, afterSaleStatus: null,
  payAmount: '80.00', appointmentStart: '2026-10-12T14:00:00.000+08:00', appointmentEnd: '2026-10-12T15:00:00.000+08:00',
  verifiedAt: null,
  actions: { canPay: false, canReschedule: true, canApplyRefund: true, canShowVerificationCode: true, canReview: false, canApplyAfterSale: false },
}

test('detail decoder accepts the contract shape with offset instants and six-key actions', () => {
  const decoded = decodeOrderDetail(JSON.parse(JSON.stringify(wireDetail)))
  assert.equal(decoded.orderId, '900101001990003')
  assert.equal(decoded.displayStatus, 'PENDING_SERVICE')
  assert.equal(decoded.payAmount, '80.00')
  assert.equal(canShowVerifyBlock(decoded), true)
  assert.deepEqual(decoded.actions && actionOrder.filter(key => decoded.actions![key]), ['canReschedule', 'canApplyRefund', 'canShowVerificationCode'])
})

test('detail decoder reads absent optional keys as null and accepts the §3.7 fact-only example', () => {
  const absent = JSON.parse(JSON.stringify(wireDetail))
  for (const key of ['orderStage', 'paymentStatus', 'verificationStatus', 'refundApplicationStatus', 'refundStatus', 'afterSaleStatus', 'verifiedAt', 'actions']) delete absent[key]
  const decoded = decodeOrderDetail(absent)
  assert.equal(decoded.orderStage, null)
  assert.equal(decoded.actions, null)
  assert.equal(canShowVerifyBlock(decoded), false)
  // 10号 §3.7 详情事实字段示例（displayStatus=AFTERSALE + 三事实串）补齐卡面必需键后可解码。
  const factExample = { orderId: '900101001990011', orderNo: '2026100100011', displayStatus: 'AFTERSALE',
    orderStage: 'PENDING_SERVICE', paymentStatus: 'PAID', refundApplicationStatus: null, refundStatus: null,
    afterSaleStatus: 'PROCESSING', verificationStatus: 'UNVERIFIED',
    payAmount: '168.00', appointmentStart: '2026-10-02T14:30:00.000+08:00', appointmentEnd: '2026-10-02T16:00:00Z', verifiedAt: null }
  const decodedFacts = decodeOrderDetail(factExample)
  assert.equal(decodedFacts.afterSaleStatus, 'PROCESSING')
  assert.equal(decodedFacts.appointmentEnd, '2026-10-02T16:00:00Z')
  // 无毫秒偏移形式（§3.8 示例同款）也接受。
  assert.equal(decodeOrderDetail({ ...factExample, appointmentStart: '2026-10-02T14:30:00+08:00' }).appointmentStart, '2026-10-02T14:30:00+08:00')
})

test('detail decoder fails closed on contract violations', () => {
  for (const mutate of [
    (v: Record<string, unknown>) => { v.serviceName = '专业美容套餐' },                    // 契约外键（设计原稿字段）
    (v: Record<string, unknown>) => { v.storeName = '萌宠之家宠物店' },                     // 契约外键
    (v: Record<string, unknown>) => { v.displayStatus = 'IN_PROGRESS' },                   // 设计桶枚举不存在于契约
    (v: Record<string, unknown>) => { v.displayStatus = 'REFUNDING' ; v.payAmount = '80.0' }, // 金额一位小数
    (v: Record<string, unknown>) => { v.payAmount = '-80.00' },                            // 负数金额
    (v: Record<string, unknown>) => { v.orderId = '9223372036854775808' },                   // Long 上界之外
    (v: Record<string, unknown>) => { v.appointmentStart = '2026-10-12 14:00' },           // 非带偏移 ISO-8601
    (v: Record<string, unknown>) => { v.appointmentEnd = '2026-10-12T25:00:00+08:00' },    // 越界时刻
    (v: Record<string, unknown>) => { v.verifiedAt = '2026-10-12T14:00:00' },              // 无时区
    (v: Record<string, unknown>) => { v.orderStage = 'REFUNDING' },                        // 阶段枚举外
    (v: Record<string, unknown>) => { v.paymentStatus = 'REFUNDED' },                      // 支付枚举外
    (v: Record<string, unknown>) => { v.verificationStatus = 'UNKNOWN' },                  // 核销枚举外
    (v: Record<string, unknown>) => { v.refundApplicationStatus = '' },                    // 空白事实串
    (v: Record<string, unknown>) => { v.actions = { ...wireDetail.actions, canPay: 'false' } }, // 布尔变字符串
    (v: Record<string, unknown>) => { v.actions = { ...wireDetail.actions, canCancel: true } },  // 契约外动作键
    (v: Record<string, unknown>) => { const actions = { ...wireDetail.actions }; delete (actions as Record<string, unknown>).canReview; v.actions = actions },
    (v: Record<string, unknown>) => { delete v.orderNo },                                  // 卡面必需键缺失
    (v: Record<string, unknown>) => { delete v.payAmount },
    (v: Record<string, unknown>) => { delete v.appointmentEnd },
  ]) {
    const value = JSON.parse(JSON.stringify(wireDetail))
    mutate(value)
    assert.throws(() => decodeOrderDetail(value), /INVALID_RESPONSE/, JSON.stringify(mutate))
  }
})

test('page decoder enforces the paging envelope and rejects oversized item arrays', () => {
  const item = JSON.parse(JSON.stringify(wireDetail))
  const decoded = decodeOrderPage({ items: [item, item], page: 1, pageSize: 20, total: 2 })
  assert.equal(decoded.items.length, 2)
  for (const mutate of [
    (v: Record<string, unknown>) => { v.page = 0 },
    (v: Record<string, unknown>) => { v.pageSize = 51 },
    (v: Record<string, unknown>) => { v.total = -1 },
    (v: Record<string, unknown>) => { v.total = 1.5 },
    (v: Record<string, unknown>) => { v.items = { length: 2 } },
    (v: Record<string, unknown>) => { v.cursor = 'next' },                       // 信封外键
    (v: Record<string, unknown>) => { delete v.total },
    (v: Record<string, unknown>) => { v.pageSize = 1; v.items = [item, item] },  // items 超过 pageSize
  ]) {
    const value: Record<string, unknown> = { items: [item], page: 1, pageSize: 20, total: 1 }
    mutate(value)
    assert.throws(() => decodeOrderPage(value), /INVALID_RESPONSE/, JSON.stringify(mutate))
  }
})

// ---- 展示映射与错误文案 ----

test('display labels cover the closed contract enums; action labels mirror OrderActions keys', () => {
  for (const status of displayOrderStatuses) assert.ok(displayStatusLabels[status], status)
  assert.equal(displayOrderStatuses.length, 10)
  assert.deepEqual([...actionOrder].sort(), (Object.keys(actionLabels) as (keyof typeof actionLabels)[]).sort())
  assert.equal(actionOrder.length, 6)
})

test('verify block gate uses only the server-returned OrderActions flag', () => {
  const base = decodeOrderDetail(JSON.parse(JSON.stringify(wireDetail)))
  assert.equal(canShowVerifyBlock(base), true)
  const withoutActions = { ...base, actions: null }
  assert.equal(canShowVerifyBlock(withoutActions), false)
  const refused = { ...base, actions: { ...base.actions!, canShowVerificationCode: false } }
  assert.equal(canShowVerifyBlock(refused), false)
})

test('beijing-time formatting and appointment window (offset instants, no device tz assumptions)', () => {
  assert.equal(formatOrderInstant('2026-10-12T14:00:00.000+08:00'), '2026-10-12 14:00')
  assert.equal(formatOrderInstant('2026-10-12T06:00:00.000Z'), '2026-10-12 14:00')
  assert.equal(formatOrderInstant('2026-10-12T16:05:00+00:00'), '2026-10-13 00:05')
  assert.equal(formatOrderInstant(null), '—')
  const detail = decodeOrderDetail(JSON.parse(JSON.stringify(wireDetail)))
  assert.equal(appointmentWindow(detail), '2026-10-12 14:00 ~ 15:00（北京时间）')
})

test('status variants stay inside the enumerated className set (WXSS: no data-*)', () => {
  assert.equal(statusVariant('PENDING_SERVICE'), 'is-pending-service')
  assert.equal(statusVariant('REFUND_PENDING_CONFIRM'), 'is-refund-pending-confirm')
  for (const status of displayOrderStatuses) assert.match(statusVariant(status), /^is-[a-z-]+$/)
})

test('read error mapping keeps 403/404 anti-enumeration and dependency failures honest', () => {
  assert.match(orderReadMessage(new ApiError('COMMON_UNAUTHORIZED', 401)), /重新登录/)
  assert.match(orderReadMessage(new ApiError('COMMON_NOT_FOUND', 404)), /订单不存在/)
  assert.match(orderReadMessage(new ApiError('COMMON_FORBIDDEN', 403)), /订单不存在/)
  assert.match(orderReadMessage(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), /暂不可用/)
  assert.match(orderReadMessage(new Error('INVALID_RESPONSE')), /返回异常/)
  assert.equal(isOrderReadUnauthorized(new ApiError('COMMON_UNAUTHORIZED', 401)), true)
  assert.equal(isOrderReadUnauthorized(new ApiError('COMMON_NOT_FOUND', 404)), false)
})

// ---- 真实 repository：经 ConsumerApi 白名单/会话门禁的 §3.7 两条读路由 ----

const sessionView = { userId: '957001', sessionId: '957101', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data, traceId: 'test' } })
const failure = (statusCode: number, code: string) => ({ statusCode, data: { code, message: 'x', data: null, traceId: 'test' } })

function authenticatedApi(transport: (request: { method: string; path: string; data?: Record<string, unknown>; requestId?: string; headers: Record<string, string> }) => Promise<{ statusCode: number; data: unknown }>) {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, JSON.parse(JSON.stringify(value))), remove: key => { values.delete(key) } }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'test-only', tokenType: 'Bearer' })
  const api = new ConsumerApi(transport, store, async () => randomUUID())
  return { api, restore: () => api.restore() }
}

test('real repository sends displayStatus/page/pageSize only, and omits displayStatus for 全部', async () => {
  const seen: { method: string; path: string; data?: Record<string, unknown> }[] = []
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    seen.push({ method: request.method, path: request.path, data: request.data })
    if (request.path === '/api/v1/c/orders') return ok({ items: [JSON.parse(JSON.stringify(wireDetail))], page: 1, pageSize: 20, total: 1 })
    return ok(JSON.parse(JSON.stringify(wireDetail)))
  })
  await restore()
  const repository = new RealOrderReadRepository(api)
  const all = await repository.list(null)
  assert.equal(all.total, 1)
  assert.deepEqual(seen[0]!.data, { page: 1, pageSize: 20 })                     // “全部”不发送 displayStatus
  const bucket = await repository.list('PENDING_SERVICE', 2, 10)
  assert.deepEqual(seen[1]!.data, { page: 2, pageSize: 10, displayStatus: 'PENDING_SERVICE' })
  assert.equal(seen.every(entry => entry.method === 'GET'), true)                // 只读切片：无写路由
  const detail = await repository.detail('900101001990003')
  assert.equal(detail.orderId, '900101001990003')
  assert.equal(seen[2]!.path, '/api/v1/c/orders/900101001990003')
  assert.equal(seen[2]!.data, undefined)                                         // 详情不带 query
})

test('real repository rejects mismatched echo ids and invalid path ids before the network', async () => {
  const mismatched = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    return ok({ ...JSON.parse(JSON.stringify(wireDetail)), orderId: '900101001990999' })
  })
  await mismatched.restore()
  const rejected = await new RealOrderReadRepository(mismatched.api).detail('900101001990003').catch(error => error)
  assert.match(String((rejected as Error)?.message), /INVALID_RESPONSE/)
  const offline = authenticatedApi(async () => ok(sessionView))
  await offline.restore()
  // 非法路径 id 在网络前即失败关闭（id() 词法 + Long 上界）。
  await assert.rejects(new RealOrderReadRepository(offline.api).detail('not-an-id'), /INVALID_RESPONSE/)
})

test('real repository surfaces switched-off routes and dependency failures as-is (fail closed)', async () => {
  const switchedOff = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    return failure(404, 'COMMON_NOT_FOUND') // 路由未挂载（后端同批交付，未开放/未部署期间）
  })
  await switchedOff.restore()
  const missing = await new RealOrderReadRepository(switchedOff.api).list(null).catch(error => error)
  assert.ok(missing instanceof ApiError && missing.statusCode === 404)
  const degraded = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    return failure(503, 'COMMON_DEPENDENCY_UNAVAILABLE')
  })
  await degraded.restore()
  const unavailable = await new RealOrderReadRepository(degraded.api).detail('900101001990003').catch(error => error)
  assert.ok(unavailable instanceof ApiError && unavailable.statusCode === 503)
})
