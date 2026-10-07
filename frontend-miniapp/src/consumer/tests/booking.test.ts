import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import {
  PREVIEW_BOOKING_SERVICE, PREVIEW_BOOKING_STORE, PREVIEW_BOOKING_TODAY, PREVIEW_PAY_ORDER, PREVIEW_PICKUP_SERVICE,
  availabilityQuery, availabilityReadMessage, beijingClock, beijingToday, bookingCreateMessage, bookingDates,
  bookingFormError, bookingFormErrorLabels, bookingPaymentMessage, buildOrderRequest, canInitiatePayment,
  decodeAvailability, decodeAvailabilityItem, decodeCreateOrderReceipt, decodePaymentReceipt, draftFromCommandData,
  isBookingScenario, paymentDeadline, paymentGateNotice, paymentSandboxNotice, pickupReturnIntervalInvalid,
  receiptBadge, returnCandidates, slotViews, validateBookingFixtures, windowLabel,
  PreviewBookingRepository, type AvailabilityItem, type BookingDraft,
} from '../booking/model'
import { ORDER_CREATE_SLOT, RealBookingRepository, paymentSlot } from '../booking/repository'
import { ApiError } from '../../shared/request'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'

// 预约下单/支付发起切片测试（10号 §3.4/§3.5/§3.6）。ARCH-005 分工：测试不触碰订单原始事实
// 字段的属性形态；订单侧断言仅经 actions 布尔（canInitiatePayment）与模块层产物
//（receiptBadge/slotViews 等）。真实仓库经 ConsumerApi 白名单/幂等槽（23号 X-Request-Id 重放）。

// ---- preview 夹具（设计验收通道）：schema 事实自检 + 场景守卫 ----

test('booking scenario guard only accepts the registered preview scenarios', () => {
  assert.equal(isBookingScenario('normal'), true)
  assert.equal(isBookingScenario('unavailable'), true)
  assert.equal(isBookingScenario('conflict'), true)
  assert.equal(isBookingScenario('pay-error'), true)
  assert.equal(isBookingScenario('load-error'), false)
  assert.equal(isBookingScenario(undefined), false)
})

test('preview fixtures satisfy the strict decoder', () => {
  assert.equal(validateBookingFixtures(), true)
})

// ---- §3.4 解码（严格 exact-key + 失败关闭） ----

const wireWindow = {
  start: '2026-10-01T09:15:00.000+08:00', end: '2026-10-01T10:45:00.000+08:00',
  effectiveCapacity: 3, occupiedCount: 1, remainingCapacity: 2, available: true,
}

test('availability decoder accepts the six-field projection and start ordering', () => {
  const view = decodeAvailability({ items: [wireWindow, { ...wireWindow, start: '2026-10-01T14:00:00.000+08:00', end: '2026-10-01T15:30:00.000+08:00' }] })
  assert.equal(view.items.length, 2)
  assert.deepEqual(decodeAvailabilityItem(wireWindow), { ...wireWindow })
  // 乱序（start 非升序）按契约拒绝。
  assert.throws(() => decodeAvailability({ items: [
    { ...wireWindow, start: '2026-10-01T14:00:00.000+08:00' }, wireWindow] }), /INVALID_RESPONSE/)
  assert.throws(() => decodeAvailability({ items: [wireWindow], page: 1 } as unknown), /INVALID_RESPONSE/) // 信封外键
})

test('availability decoder fails closed on contract violations', () => {
  for (const over of [
    { windowId: '20190001' },                                 // 39号 selection 增补键未启用即出现 → 失败关闭
    { kind: 'GENERAL' },                                      // 同上
    { start: '2026-10-01 09:15' },                            // 非带偏移 ISO-8601
    { end: '2026-10-01T25:00:00+08:00' },                     // 越界时刻
    { remainingCapacity: -1 },                                // 负容量
    { effectiveCapacity: 1.5 },                               // 非整数
    { available: 'true' },                                    // 布尔变字符串
  ]) {
    assert.throws(() => decodeAvailabilityItem({ ...wireWindow, ...over }), /INVALID_RESPONSE/, JSON.stringify(over))
  }
  for (const key of ['start', 'end', 'effectiveCapacity', 'occupiedCount', 'remainingCapacity', 'available']) {
    const missing = { ...wireWindow } as Record<string, unknown>
    delete missing[key]
    assert.throws(() => decodeAvailabilityItem(missing), /INVALID_RESPONSE/, key)
  }
})

// ---- §3.4 查询参数（仅三键；未知参数服务端 400） ----

test('availability query carries exactly storeId/startDate/endDate for a single day', () => {
  const query = availabilityQuery('20001', '957002', '2026-10-01')
  assert.equal(query.path, '/api/v1/c/services/20001/availability')
  assert.deepEqual(query.data, { storeId: '957002', startDate: '2026-10-01', endDate: '2026-10-01' })
  for (const bad of [['x', '957002', '2026-10-01'], ['20001', 'x', '2026-10-01'], ['20001', '957002', '2026/10/01'], ['20001', '957002', '2026-13-01']]) {
    assert.throws(() => availabilityQuery(bad[0]!, bad[1]!, bad[2]!), /INVALID/, JSON.stringify(bad))
  }
})

// ---- 时段选择推导（可选=available 且 remaining>0；已满置灰「已约满」；无状态枚举） ----

test('slot views mark selectable windows and grey sold-out windows without a status enum', () => {
  const views = slotViews([wireWindow, { ...wireWindow, start: '2026-10-01T10:15:00.000+08:00', end: '2026-10-01T11:15:00.000+08:00', effectiveCapacity: 2, occupiedCount: 2, remainingCapacity: 0, available: false }], null)
  assert.equal(views[0].selectable, true)
  assert.equal(views[0].note, '剩2个')
  assert.equal(views[1].selectable, false)
  assert.equal(views[1].note, '已约满')
  assert.equal(views[1].className.includes('is-full'), true)
  const chosen = slotViews([wireWindow], wireWindow.start)
  assert.equal(chosen[0].className, 'bkg-slot is-selected')
  // available=false 即使 remaining>0 也置灰（服务端统一不可约表达）。
  const suspended = slotViews([{ ...wireWindow, available: false }], null)
  assert.equal(suspended[0].selectable, false)
  assert.equal(suspended[0].note, '已约满')
})

test('slot labels are minute-level and cross-day windows are marked honestly', () => {
  assert.equal(windowLabel('2026-10-01T09:15:00.000+08:00', '2026-10-01T10:45:00.000+08:00'), '09:15~10:45')
  assert.equal(windowLabel('2026-10-01T20:00:00.000+08:00', '2026-10-02T09:00:00.000+08:00'), '20:00~次日09:00')
  assert.equal(beijingClock('2026-09-30T17:00:00.000Z'), '01:00') // 设备时区不作假设：北京时间口径
  for (const view of slotViews([wireWindow], null)) assert.match(view.className, /^bkg-slot( is-selected| is-full)?$/) // WXSS：闭集变体，禁 data-*
})

test('preview availability keeps minute-level windows with sold-out entries', async () => {
  const repository = new PreviewBookingRepository('normal')
  const view = await repository.availability(PREVIEW_BOOKING_SERVICE, PREVIEW_BOOKING_STORE, PREVIEW_BOOKING_TODAY)
  const views = slotViews(view.items, null)
  assert.equal(views.length, 5)
  assert.equal(views[0].label, '09:15~10:45')
  assert.equal(views[4].label, '20:00~次日09:00')
  assert.ok(views.some(view => !view.selectable && view.note === '已约满'))
  assert.ok(views.some(view => view.selectable))
  // 范围外日期=200 空列表（与 404/503 严格三区分，§3.4）；未知服务/门店 404 防探测。
  assert.equal((await repository.availability(PREVIEW_BOOKING_SERVICE, PREVIEW_BOOKING_STORE, '2026-10-08')).items.length, 0)
  const missing = await repository.availability('29999', PREVIEW_BOOKING_STORE, PREVIEW_BOOKING_TODAY).catch(error => error)
  assert.ok(missing instanceof ApiError && missing.statusCode === 404)
  const unavailable = await new PreviewBookingRepository('unavailable').availability(PREVIEW_BOOKING_SERVICE, PREVIEW_BOOKING_STORE, PREVIEW_BOOKING_TODAY).catch(error => error)
  assert.ok(unavailable instanceof ApiError && unavailable.statusCode === 503)
})

// ---- 日期条（业务时区 Asia/Shanghai；≤31 天跨度） ----

test('booking dates anchor to the business-zone today with weekday labels', () => {
  const dates = bookingDates('2026-10-01', 7, '2026-10-01')
  assert.equal(dates.length, 7)
  assert.equal(dates[0].label, '今天')
  assert.equal(dates[0].weekday, '周四') // 2026-10-01 为周四
  assert.equal(dates[1].iso, '2026-10-02')
  assert.equal(dates[6].iso, '2026-10-07')
  assert.equal(dates[0].className, 'bkg-date is-selected')
  assert.equal(dates[1].className, 'bkg-date')
  assert.equal(dates[1].label, '10月2日')
  assert.throws(() => bookingDates('2026-10-01', 32), /INVALID_DATE/) // §3.4 跨度上限 31 天
  const beijing = beijingToday(Date.parse('2026-10-01T02:00:00.000Z')) // 北京 2026-10-01 10:00
  assert.equal(beijing, '2026-10-01')
  assert.equal(beijingToday(Date.parse('2026-09-30T20:00:00.000Z')), '2026-10-01') // UTC 尚在 09-30
})

// ---- §3.5 请求构造（CreateOrderRequest 七必填；接送两键；couponInstanceId 不携带） ----

const storeDraft: BookingDraft = {
  storeId: '957002', serviceId: '20001', petId: '30001', fulfillmentType: 'IN_STORE',
  appointmentStart: '2026-10-01T09:15:00.000+08:00', appointmentEnd: '2026-10-01T10:45:00.000+08:00',
  pickupStart: null, returnStart: null, remark: '  怕生，请提前沟通  ',
}
const pickupDraft: BookingDraft = {
  storeId: '957002', serviceId: PREVIEW_PICKUP_SERVICE, petId: '30001', fulfillmentType: 'PICKUP_DELIVERY',
  appointmentStart: '2026-10-01T12:00:00.000+08:00', appointmentEnd: '2026-10-01T13:30:00.000+08:00',
  pickupStart: '2026-10-01T09:00:00.000+08:00', returnStart: '2026-10-01T11:00:00.000+08:00', remark: '',
}

test('buildOrderRequest emits the seven required keys; remark trimmed; coupon key never present', () => {
  assert.deepEqual(buildOrderRequest(storeDraft), {
    storeId: '957002', serviceId: '20001', petId: '30001', fulfillmentType: 'IN_STORE',
    appointmentStart: '2026-10-01T09:15:00.000+08:00', appointmentEnd: '2026-10-01T10:45:00.000+08:00',
    remark: '怕生，请提前沟通',
  })
  assert.equal('couponInstanceId' in buildOrderRequest(storeDraft), false) // 本切片边界
  const quiet = buildOrderRequest({ ...storeDraft, remark: '   ' })
  assert.equal('remark' in quiet, false) // 空白备注不入 JSON
  assert.deepEqual(buildOrderRequest(pickupDraft), {
    storeId: '957002', serviceId: '20002', petId: '30001', fulfillmentType: 'PICKUP_DELIVERY',
    appointmentStart: '2026-10-01T12:00:00.000+08:00', appointmentEnd: '2026-10-01T13:30:00.000+08:00',
    pickupStart: '2026-10-01T09:00:00.000+08:00', returnStart: '2026-10-01T11:00:00.000+08:00',
  })
})

test('form validation walks every contract branch with Chinese labels', () => {
  assert.equal(bookingFormError(storeDraft), null)
  assert.equal(bookingFormError({ ...storeDraft, storeId: 'x' }), 'store')
  assert.equal(bookingFormError({ ...storeDraft, serviceId: 'x' }), 'service')
  assert.equal(bookingFormError({ ...storeDraft, petId: '' }), 'pet')
  assert.equal(bookingFormError({ ...storeDraft, appointmentStart: '2026-10-01 09:15' }), 'window')
  assert.equal(bookingFormError({ ...pickupDraft, pickupStart: null }), 'pickup')
  assert.equal(bookingFormError({ ...pickupDraft, returnStart: null }), 'return')
  assert.equal(bookingFormError({ ...pickupDraft, returnStart: '2026-10-01T10:59:00.000+08:00' }), 'interval')
  assert.equal(bookingFormError({ ...storeDraft, remark: '长'.repeat(501) }), 'remark')
  for (const key of Object.keys(bookingFormErrorLabels) as (keyof typeof bookingFormErrorLabels)[]) assert.ok(bookingFormErrorLabels[key])
  assert.throws(() => buildOrderRequest({ ...storeDraft, petId: '' }), /BOOKING_FORM_PET/)
})

// ---- 接送硬规则：returnStart >= pickupStart + 120 分钟（C 端置灰联动，SCH-D4） ----

test('pickup-return interval rule greys return candidates below 120 minutes', () => {
  assert.equal(pickupReturnIntervalInvalid('2026-10-01T09:00:00.000+08:00', '2026-10-01T10:59:00.000+08:00'), true)
  assert.equal(pickupReturnIntervalInvalid('2026-10-01T09:00:00.000+08:00', '2026-10-01T11:00:00.000+08:00'), false) // 恰好 120 分钟
  const items: AvailabilityItem[] = [
    { ...wireWindow, start: '2026-10-01T10:00:00.000+08:00', end: '2026-10-01T11:00:00.000+08:00' },
    { ...wireWindow, start: '2026-10-01T11:00:00.000+08:00', end: '2026-10-01T12:00:00.000+08:00' },
  ]
  const candidates = returnCandidates(items, '2026-10-01T09:00:00.000+08:00')
  assert.deepEqual(candidates.map(item => item.start), ['2026-10-01T11:00:00.000+08:00'])
})

// ---- §3.5 回执解码与展示（CreateOrderData 五键；卡面必需键缺失失败关闭） ----

const wireReceipt = {
  orderId: '900101001990000', orderNo: '2026100100001', displayStatus: 'PENDING_PAYMENT',
  payAmount: '80.00', paymentExpireAt: '2026-10-01T09:55:00.000+08:00',
}

test('create-order receipt decoder accepts the contract shape and fails closed', () => {
  const receipt = decodeCreateOrderReceipt(JSON.parse(JSON.stringify(wireReceipt)))
  assert.equal(receipt.orderId, '900101001990000')
  assert.equal(receipt.payAmount, '80.00')
  assert.deepEqual(receiptBadge(receipt), { label: '待支付', className: 'is-pending-payment' })
  assert.equal(paymentDeadline(receipt), '2026/10/01 09:55（北京时间）')
  for (const over of [
    { displayStatus: 'IN_PROGRESS' }, { payAmount: '80.0' }, { payAmount: '-80.00' },
    { orderId: '9223372036854775808' }, { paymentExpireAt: '2026-10-01T09:55:00' }, { serviceName: '专业美容套餐' },
  ]) {
    assert.throws(() => decodeCreateOrderReceipt({ ...wireReceipt, ...over }), /INVALID_RESPONSE/, JSON.stringify(over))
  }
  for (const key of ['orderId', 'orderNo', 'displayStatus', 'payAmount', 'paymentExpireAt']) {
    const missing = { ...wireReceipt } as Record<string, unknown>
    delete missing[key]
    assert.throws(() => decodeCreateOrderReceipt(missing), /INVALID_RESPONSE/, key)
  }
})

// ---- §3.6 支付回执解码（五键；wechatPayParameters 精确五参数） ----

const wirePayment = {
  paymentId: '2019000000000000101', paymentNo: '2019000000000000102', channel: 'LAKALA_WECHAT',
  wechatPayParameters: { timeStamp: '1791234567', nonceStr: 'nonce', package: 'prepay_id=x', signType: 'RSA', paySign: 'sign' },
}

test('payment receipt decoder enforces the five keys and honest parameter shape', () => {
  const receipt = decodePaymentReceipt(JSON.parse(JSON.stringify(wirePayment)))
  assert.equal(receipt.channel, 'LAKALA_WECHAT')
  assert.equal(receipt.wechatPayParameters.signType, 'RSA')
  for (const over of [
    { channel: '' }, { paymentId: '0' },
    { wechatPayParameters: { timeStamp: '1', nonceStr: 'n', package: 'p', signType: 'RSA' } },        // 缺 paySign
    { wechatPayParameters: { timeStamp: '1', nonceStr: 'n', package: 'p', signType: 'RSA', paySign: 's', extra: 1 } }, // 参数外键
    { wechatPayParameters: null },
  ]) {
    assert.throws(() => decodePaymentReceipt({ ...wirePayment, ...over }), /INVALID_RESPONSE/, JSON.stringify(over))
  }
  for (const key of ['paymentId', 'paymentNo', 'channel', 'wechatPayParameters']) {
    const missing = { ...wirePayment } as Record<string, unknown>
    delete missing[key]
    assert.throws(() => decodePaymentReceipt(missing), /INVALID_RESPONSE/, key)
  }
  assert.match(paymentSandboxNotice, /沙箱/)   // 渠道 stub 语义如实呈现：不虚构支付成功
  assert.match(paymentSandboxNotice, /不会自动扣款/)
})

// ---- 支付入口门禁（仅凭服务端 OrderActions.canPay，§3.7 #123） ----

test('payment gate uses only the server-returned actions flag', () => {
  assert.equal(canInitiatePayment({ actions: { canPay: true } }), true)
  assert.equal(canInitiatePayment({ actions: { canPay: false } }), false)
  assert.equal(canInitiatePayment({ actions: null }), false)
  assert.equal(paymentGateNotice(true), '')
  assert.match(paymentGateNotice(false), /不支持发起支付/)
})

// ---- 逐错误码中文映射（§3.5/§3.6 典型错误 + 401/400/404/开关关/503） ----

test('create error mapping covers every contract code and the common envelope', () => {
  assert.match(bookingCreateMessage(new ApiError('SCHEDULE_CAPACITY_EXCEEDED', 409)), /已约满/)
  assert.match(bookingCreateMessage(new ApiError('SCHEDULE_NOT_AVAILABLE', 409)), /排期已变化/)
  assert.match(bookingCreateMessage(new ApiError('SCHEDULE_PICKUP_RETURN_INTERVAL_INVALID', 409)), /120 分钟/)
  assert.match(bookingCreateMessage(new ApiError('MERCHANT_DISABLED', 409)), /商家已停用/)
  assert.match(bookingCreateMessage(new ApiError('STORE_DISABLED', 409)), /门店已停用/)
  assert.match(bookingCreateMessage(new ApiError('SERVICE_NOT_BOOKABLE', 409)), /不可预约/)
  assert.match(bookingCreateMessage(new ApiError('COUPON_NOT_AVAILABLE', 409)), /优惠券不可用/)
  assert.match(bookingCreateMessage(new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409)), /请求编号/)
  assert.match(bookingCreateMessage(new ApiError('COMMON_UNAUTHORIZED', 401)), /重新登录/)
  assert.match(bookingCreateMessage(new ApiError('COMMON_INVALID_ARGUMENT', 400)), /无效/)
  assert.match(bookingCreateMessage(new ApiError('COMMON_NOT_FOUND', 404)), /未开放或服务不存在/)
  assert.match(bookingCreateMessage(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), /尚未确认/)
  assert.match(bookingCreateMessage(new Error('PENDING_WRITE_CHANGED')), /尚未确认/)
  assert.match(bookingCreateMessage(new Error('INVALID_RESPONSE')), /返回异常/)
  assert.match(bookingCreateMessage(new Error('anything')), /不会重复创建/)
})

test('payment and availability error mappings keep switch-off and dependency failures honest', () => {
  assert.match(bookingPaymentMessage(new ApiError('COMMON_UNAUTHORIZED', 401)), /重新登录/)
  assert.match(bookingPaymentMessage(new ApiError('COMMON_NOT_FOUND', 404)), /支付服务未开放|不存在/)
  assert.match(bookingPaymentMessage(new ApiError('COMMON_CONFLICT', 409)), /支付状态已变化/)
  assert.match(bookingPaymentMessage(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), /尚未确认/)
  assert.match(bookingPaymentMessage(new Error('PENDING_WRITE_CHANGED')), /重试原操作/)
  assert.match(availabilityReadMessage(new ApiError('SERVICE_NOT_FOUND', 404)), /服务不存在/)
  assert.match(availabilityReadMessage(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), /排期事实/)
  assert.match(availabilityReadMessage(new ApiError('COMMON_UNAUTHORIZED', 401)), /重新登录/)
})

// ---- 幂等恢复：pendingCommand 载荷 → 草稿（同载荷重试同一 X-Request-Id，23号） ----

test('draftFromCommandData round-trips the emitted request payload and rejects foreign shapes', () => {
  const request = buildOrderRequest(storeDraft)
  const restored = draftFromCommandData(JSON.parse(JSON.stringify(request)))
  assert.deepEqual(restored, { ...storeDraft, remark: '怕生，请提前沟通' })
  const pickupRestored = draftFromCommandData(JSON.parse(JSON.stringify(buildOrderRequest(pickupDraft))))
  assert.deepEqual(pickupRestored, pickupDraft)
  assert.equal(draftFromCommandData({ ...request, extra: 1 }), null)     // 载荷外键
  assert.equal(draftFromCommandData({ ...request, petId: '0' }), null)   // 非法 id
  assert.equal(draftFromCommandData('not-an-object'), null)
})

// ---- preview 仓库：创建回执链路 + 冲突/支付错误场景 ----

test('preview create returns the receipt chained to the fixture unpaid order; conflict scenario answers 409', async () => {
  const repository = new PreviewBookingRepository('normal')
  const receipt = await repository.create({ ...storeDraft, serviceId: PREVIEW_BOOKING_SERVICE, fulfillmentType: 'IN_STORE' })
  assert.equal(receipt.orderId, PREVIEW_PAY_ORDER)
  assert.equal(receiptBadge(receipt).label, '待支付')
  const conflict = await new PreviewBookingRepository('conflict').create(storeDraft).catch(error => error)
  assert.ok(conflict instanceof ApiError && conflict.code === 'SCHEDULE_CAPACITY_EXCEEDED')
  const paid = await repository.pay(PREVIEW_PAY_ORDER)
  assert.equal(paid.channel, 'LAKALA_WECHAT')
  const payError = await new PreviewBookingRepository('pay-error').pay(PREVIEW_PAY_ORDER).catch(error => error)
  assert.ok(payError instanceof ApiError && payError.statusCode === 503)
})

// ---- 真实仓库：经 ConsumerApi 白名单 + 幂等槽（23号 X-Request-Id 重放） ----

const sessionView = { userId: '957001', sessionId: '957101', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data, traceId: 'test' } })
const failure = (statusCode: number, code: string) => ({ statusCode, data: { code, message: 'x', data: null, traceId: 'test' } })

type WireRequest = { method: string; path: string; data?: Record<string, unknown>; requestId?: string; headers: Record<string, string> }
function authenticatedApi(transport: (request: WireRequest) => Promise<{ statusCode: number; data: unknown }>) {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, JSON.parse(JSON.stringify(value))), remove: key => { values.delete(key) } }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'test-only', tokenType: 'Bearer' })
  const api = new ConsumerApi(transport, store, async () => randomUUID())
  return { api, restore: () => api.restore(), store }
}

test('real repository queries availability with the three contract params only', async () => {
  const seen: WireRequest[] = []
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    seen.push(request)
    return ok({ items: [wireWindow] })
  })
  await restore()
  const repository = new RealBookingRepository(api)
  const view = await repository.availability('20001', '957002', '2026-10-01')
  assert.equal(view.items.length, 1)
  assert.equal(seen[0]!.method, 'GET')
  assert.equal(seen[0]!.path, '/api/v1/c/services/20001/availability')
  assert.deepEqual(seen[0]!.data, { storeId: '957002', startDate: '2026-10-01', endDate: '2026-10-01' })
  await assert.rejects(repository.availability('x', '957002', '2026-10-01'), /INVALID/) // 网络前失败关闭
})

test('real create posts the exact request body under a journal-backed X-Request-Id', async () => {
  const seen: WireRequest[] = []
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    seen.push(request)
    if (request.path === '/api/v1/c/orders') return ok(JSON.parse(JSON.stringify(wireReceipt)))
    return ok({})
  })
  await restore()
  const repository = new RealBookingRepository(api)
  const receipt = await repository.create(storeDraft)
  assert.equal(receipt.orderId, '900101001990000')
  assert.equal(seen[0]!.method, 'POST')
  assert.equal(seen[0]!.path, '/api/v1/c/orders')
  assert.deepEqual(seen[0]!.data, buildOrderRequest(storeDraft))
  assert.match(seen[0]!.headers['X-Request-Id'], /^[0-9a-f-]{36}$/)
  // 成功后槽位清空：再次下单可获新请求编号。
  await repository.create({ ...storeDraft, appointmentStart: '2026-10-01T14:00:00.000+08:00', appointmentEnd: '2026-10-01T15:30:00.000+08:00' })
  assert.notEqual(seen[1]!.headers['X-Request-Id'], seen[0]!.headers['X-Request-Id'])
})

test('unknown create outcome keeps the journal: replay reuses the same X-Request-Id across a restart', async () => {
  const seen: WireRequest[] = []
  const transport = async (request: WireRequest) => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    seen.push(request)
    return request.path === '/api/v1/c/orders' ? failure(503, 'COMMON_DEPENDENCY_UNAVAILABLE') : ok({})
  }
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, JSON.parse(JSON.stringify(value))), remove: key => { values.delete(key) } }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'test-only', tokenType: 'Bearer' })
  const first = new ConsumerApi(transport, store, async () => randomUUID())
  await first.restore()
  const repository = new RealBookingRepository(first)
  const unknown = await repository.create(storeDraft).catch(error => error)
  assert.ok(unknown instanceof ApiError && unknown.statusCode === 503)
  assert.equal(first.pendingCommand(ORDER_CREATE_SLOT) !== undefined, true)   // 载荷与编号已入持久日志
  // “重启”：同一存储构造新客户端，重试原载荷 → 同一 X-Request-Id（23号 §5 重放）。
  const second = new ConsumerApi(transport, store, async () => randomUUID())
  await second.restore()
  const replayed = await new RealBookingRepository(second).create(storeDraft).catch(error => error)
  assert.ok(replayed instanceof ApiError && replayed.statusCode === 503)
  assert.equal(seen[1]!.headers['X-Request-Id'], seen[0]!.headers['X-Request-Id'])
  assert.deepEqual(seen[1]!.data, seen[0]!.data)
  // 载荷变化被拒绝：PENDING_WRITE_CHANGED，不盲目换号重试。
  const changed = await new RealBookingRepository(second).create({ ...storeDraft, petId: '30002' }).catch(error => error)
  assert.ok(changed instanceof Error && changed.message === 'PENDING_WRITE_CHANGED')
})

test('definite rejection releases the slot: a corrected payload gets a fresh request id', async () => {
  const seen: WireRequest[] = []
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    seen.push(request)
    if (request.path === '/api/v1/c/orders' && seen.filter(entry => entry.path === '/api/v1/c/orders').length === 1) {
      return failure(409, 'SCHEDULE_CAPACITY_EXCEEDED')
    }
    return request.path === '/api/v1/c/orders' ? ok(JSON.parse(JSON.stringify(wireReceipt))) : ok({})
  })
  await restore()
  const repository = new RealBookingRepository(api)
  const rejected = await repository.create(storeDraft).catch(error => error)
  assert.ok(rejected instanceof ApiError && rejected.code === 'SCHEDULE_CAPACITY_EXCEEDED')
  const retried = await repository.create({ ...storeDraft, appointmentStart: '2026-10-01T14:00:00.000+08:00', appointmentEnd: '2026-10-01T15:30:00.000+08:00' })
  assert.equal(retried.orderId, '900101001990000')
  assert.notEqual(seen[1]!.headers['X-Request-Id'], seen[0]!.headers['X-Request-Id'])
})

test('real pay posts the fixed channel body per order and replays the same request id on unknown outcome', async () => {
  const seen: WireRequest[] = []
  let ordersCalls = 0
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.path === '/api/v1/c/orders/900101001990000/payments') {
      seen.push(request)
      ordersCalls++
      return ordersCalls === 1 ? failure(503, 'COMMON_DEPENDENCY_UNAVAILABLE') : ok(JSON.parse(JSON.stringify(wirePayment)))
    }
    return ok({})
  })
  await restore()
  const repository = new RealBookingRepository(api)
  const unknown = await repository.pay('900101001990000').catch(error => error)
  assert.ok(unknown instanceof ApiError && unknown.statusCode === 503)
  const paid = await repository.pay('900101001990000') // 重试原发起：同一 X-Request-Id
  assert.equal(paid.channel, 'LAKALA_WECHAT')
  assert.equal(seen.length, 2)
  assert.deepEqual(seen[0]!.data, { channel: 'WECHAT_MINI_PROGRAM' })
  assert.equal(seen[1]!.headers['X-Request-Id'], seen[0]!.headers['X-Request-Id'])
  assert.equal(paymentSlot('900101001990000'), 'order-pay:900101001990000')
  await assert.rejects(repository.pay('not-an-id'), /INVALID_RESPONSE/) // 非法路径 id 网络前失败关闭
})

test('real create/pay surface switched-off routes and dependency failures as-is (fail closed)', async () => {
  const switchedOff = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    return failure(404, 'COMMON_NOT_FOUND') // 路由未挂载（并行后端切片交付/开放前）
  })
  await switchedOff.restore()
  const repository = new RealBookingRepository(switchedOff.api)
  const missing = await repository.create(storeDraft).catch(error => error)
  assert.ok(missing instanceof ApiError && missing.statusCode === 404)
  assert.match(bookingCreateMessage(missing), /未开放或服务不存在/)
  const payMissing = await repository.pay('900101001990000').catch(error => error)
  assert.ok(payMissing instanceof ApiError && payMissing.statusCode === 404)
})
