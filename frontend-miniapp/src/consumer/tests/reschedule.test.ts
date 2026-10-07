import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import {
  buildPickupInput, buildStoreInput, canRescheduleEntry, decodeRescheduleReceipt, decodeSelectionAvailability,
  isDefiniteRescheduleConflict, isRescheduleScenario, isVersionConflict, minuteInstant, originalDurationMinutes,
  PREVIEW_RESCHEDULE_ORDER, PreviewRescheduleRepository, receiptBody, receiptHeadline, receiptRows,
  RescheduleController, rescheduleMessage, rescheduleReadiness, rescheduleSlots, returnWindowCandidates,
  selectionBranch, type RescheduleInput, type RescheduleReceipt, type RescheduleState, type SelectionWindow,
} from '../orders/reschedule'
import { RealRescheduleRepository } from '../orders/reschedule-repository'
import { decodeOrderDetail, PreviewOrderReadRepository, type OrderDetailView } from '../orders/model'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { ApiError } from '../../shared/request'
import { WorkspaceScope } from '../../shared/workspace'

// ---- 解码：本切片线上形状（10号 §3.8 + 46号：恰十二键、confirmRound=1、接送两键可空）。

const wireStore = {
  orderId: '900101001990003', reservationId: '900102001990001', rescheduleId: '900103001990002',
  confirmRound: 1, orderVersion: '1', orderStageAtCommit: 'PENDING_CONFIRM',
  appointmentStart: '2026-10-09T06:30:00.000Z', appointmentEnd: '2026-10-09T08:00:00.000Z',
  pickupStart: null, returnStart: null, rescheduledAt: '2026-10-07T09:05:00.000Z', confirmDeadline: '2026-10-07T09:35:00.000Z',
}
const wirePickup = {
  ...wireStore, rescheduleId: '900103001990003',
  appointmentStart: '2026-10-09T05:20:00.000Z', appointmentEnd: '2026-10-09T08:00:00.000Z',
  pickupStart: '2026-10-09T02:30:00.000Z', returnStart: '2026-10-09T06:20:00.000Z',
}

test('receipt decoder accepts both fulfillment branches and keeps the fixed twelve keys', () => {
  const store = decodeRescheduleReceipt(JSON.parse(JSON.stringify(wireStore)))
  assert.equal(store.confirmRound, 1)
  assert.equal(store.orderStageAtCommit, 'PENDING_CONFIRM')
  assert.equal(store.pickupStart, null)
  assert.equal(store.returnStart, null)
  const pickup = decodeRescheduleReceipt(JSON.parse(JSON.stringify(wirePickup)))
  assert.equal(pickup.pickupStart, '2026-10-09T02:30:00.000Z')
  assert.equal(pickup.returnStart, '2026-10-09T06:20:00.000Z')
})

test('receipt decoder fails closed on contract violations', () => {
  for (const mutate of [
    (v: Record<string, unknown>) => { v.extra = 1 },
    (v: Record<string, unknown>) => { delete v.confirmDeadline },
    (v: Record<string, unknown>) => { v.confirmRound = 2 },
    (v: Record<string, unknown>) => { v.confirmRound = '1' },
    (v: Record<string, unknown>) => { v.orderStageAtCommit = 'PENDING_SERVICE' },
    (v: Record<string, unknown>) => { v.orderVersion = '01' },
    (v: Record<string, unknown>) => { v.rescheduleId = '0' },
    (v: Record<string, unknown>) => { v.appointmentStart = '2026-10-09T06:30:00Z' },   // 缺毫秒
    (v: Record<string, unknown>) => { v.confirmDeadline = '2026-10-07 09:35:00.000Z' }, // 非ISO
    // 到店分支不允许半空接送键（oneOf 破坏）。
    (v: Record<string, unknown>) => { v.pickupStart = '2026-10-09T02:30:00.000Z' },
  ]) {
    const value = JSON.parse(JSON.stringify(wireStore)) as Record<string, unknown>
    mutate(value)
    assert.throws(() => decodeRescheduleReceipt(value), /INVALID_RESPONSE/, JSON.stringify(mutate))
  }
})

// ---- 39号 §1 选窗增补解码（恰八键 + kind 闭集 + 升序）。

const window = (over: Partial<SelectionWindow>): SelectionWindow => ({
  start: '2026-10-09T09:15:00+08:00', end: '2026-10-09T10:45:00+08:00',
  effectiveCapacity: 3, occupiedCount: 1, remainingCapacity: 2, available: true,
  windowId: '710593', kind: 'GENERAL', ...over,
})

test('selection decoder keeps the eight-key projection and fails closed outside it', () => {
  const view = decodeSelectionAvailability({ items: [window({}), window({ windowId: '710594', start: '2026-10-09T11:00:00+08:00', end: '2026-10-09T12:30:00+08:00' })] })
  assert.equal(view.items.length, 2)
  assert.equal(view.items[0]!.kind, 'GENERAL')
  for (const bad of [
    { items: [{ ...window({ }) , extra: 1 }] },                       // 未知键
    { items: [(({ windowId: '710593', start: 'x', end: 'y', effectiveCapacity: 1, occupiedCount: 0, remainingCapacity: 1, available: true, kind: 'GENERAL' }))] },
    { items: [window({ kind: 'FREE' as SelectionWindow['kind'] })] },               // kind 闭集外
    { items: [window({ windowId: '01' })] },                          // id 前导零
    { items: [window({ remainingCapacity: -1 })] },                   // 负容量
    { items: [window({}), window({ start: '2026-10-09T08:00:00+08:00', end: '2026-10-09T09:00:00+08:00', windowId: '710595' })] }, // 非升序
  ]) {
    assert.throws(() => decodeSelectionAvailability(bad), /INVALID_RESPONSE/, JSON.stringify(bad))
  }
  // 六字段投影（39号增补未开启）不是本页可消费形状：失败关闭而非猜测窗口身份。
  assert.throws(() => decodeSelectionAvailability({ items: [(({ start: '2026-10-09T09:15:00+08:00', end: '2026-10-09T10:45:00+08:00', effectiveCapacity: 3, occupiedCount: 1, remainingCapacity: 2, available: true }))] }), /INVALID_RESPONSE/)
})

test('selection branch and return candidates follow the 39号/46号 projections', () => {
  assert.equal(selectionBranch([window({})]), 'IN_STORE')
  assert.equal(selectionBranch([window({ kind: 'PICKUP' }), window({ kind: 'RETURN', windowId: '710596', start: '2026-10-09T13:30:00+08:00', end: '2026-10-09T14:20:00+08:00' })]), 'PICKUP_DELIVERY')
  assert.equal(selectionBranch([]), 'EMPTY')
  const pickup = window({ kind: 'PICKUP', start: '2026-10-09T09:00:00+08:00', end: '2026-10-09T09:35:00+08:00', windowId: '710597' })
  const early = window({ kind: 'RETURN', start: '2026-10-09T10:00:00+08:00', end: '2026-10-09T10:50:00+08:00', windowId: '710598' })
  const late = window({ kind: 'RETURN', start: '2026-10-09T11:30:00+08:00', end: '2026-10-09T12:20:00+08:00', windowId: '710599' })
  const candidates = returnWindowCandidates([pickup, early, late], pickup.start)
  assert.deepEqual(candidates.map(item => item.windowId), ['710599'])
})

// ---- 请求构造：expectedOrderVersion CAS + 原时长快照 + 分钟对齐 + 120 分钟间隔。

const detailBase = {
  orderId: '900101001990003', orderNo: '2026100100003', displayStatus: 'PENDING_SERVICE' as const,
  orderStage: 'PENDING_SERVICE' as const, paymentStatus: 'PAID' as const, verificationStatus: 'UNVERIFIED' as const,
  refundApplicationStatus: null, refundStatus: null, afterSaleStatus: null,
  payAmount: '80.00', appointmentStart: '2026-10-12T14:00:00.000+08:00', appointmentEnd: '2026-10-12T15:00:00.000+08:00',
  verifiedAt: null,
  actions: { canPay: false, canReschedule: true, canApplyRefund: true, canShowVerificationCode: true, canReview: false, canApplyAfterSale: false },
  serviceId: '20001', storeId: '957002', orderVersion: '0',
}

test('store input derives the new appointment from the window start plus the original duration', () => {
  const detail = decodeOrderDetail(JSON.parse(JSON.stringify(detailBase)))
  assert.equal(originalDurationMinutes(detail), 60)
  assert.equal(minuteInstant('2026-10-09T09:15:00+08:00'), '2026-10-09T01:15:00.000Z')
  assert.equal(minuteInstant('2026-10-09T09:15:30+08:00'), null)
  const input = buildStoreInput(window({ start: '2026-10-09T09:15:00+08:00', end: '2026-10-09T10:45:00+08:00' }), detail, '0')
  assert.deepEqual(input, { expectedOrderVersion: '0', appointmentStart: '2026-10-09T01:15:00.000Z', appointmentEnd: '2026-10-09T02:15:00.000Z', selectedGeneralWindowId: '710593' })
})

test('pickup input enforces the 120-minute interval and carries both whole windows', () => {
  const pickup = window({ kind: 'PICKUP', start: '2026-10-09T09:00:00+08:00', end: '2026-10-09T09:35:00+08:00', windowId: '710597' })
  const tooEarly = window({ kind: 'RETURN', start: '2026-10-09T10:00:00+08:00', end: '2026-10-09T10:50:00+08:00', windowId: '710598' })
  const valid = window({ kind: 'RETURN', start: '2026-10-09T11:00:00+08:00', end: '2026-10-09T11:50:00+08:00', windowId: '710599' })
  assert.equal(buildPickupInput(pickup, tooEarly, '0'), null)
  assert.deepEqual(buildPickupInput(pickup, valid, '3'), { expectedOrderVersion: '3', pickupStart: '2026-10-09T01:00:00.000Z', returnStart: '2026-10-09T03:00:00.000Z', selectedPickupWindowId: '710597', selectedReturnWindowId: '710599' })
})

// ---- 入口门控与就绪度（canReschedule 布尔 + 读侧增补三事实）。

test('entry gating reads only the server actions boolean; readiness needs the amended facts', async () => {
  const repository = new PreviewOrderReadRepository('normal')
  const eligible = await repository.detail(PREVIEW_RESCHEDULE_ORDER)
  assert.equal(canRescheduleEntry(eligible), true)
  assert.deepEqual(rescheduleReadiness(eligible), { ok: true, version: '0', serviceId: '20001', storeId: '957002' })
  const withoutActions = await repository.detail('900101001990005')
  assert.equal(canRescheduleEntry(withoutActions), false)
  assert.equal(rescheduleReadiness(withoutActions).ok, false)
  // 读侧未升级（增补事实缺失）：门控通过但就绪度失败关闭。
  const upgradedLater = decodeOrderDetail({ ...JSON.parse(JSON.stringify(detailBase)), serviceId: undefined, storeId: undefined, orderVersion: undefined })
  const readiness = rescheduleReadiness(upgradedLater)
  assert.equal(readiness.ok, false)
  assert.ok(/未升级/.test((readiness as { notice: string }).notice))
})

// ---- 展示映射（ARCH-005：口径文案/槽位变体只在此处）。

test('slot views and receipt copy keep the closed variant sets and the 46号 wording', () => {
  const slots = rescheduleSlots([window({}), window({ windowId: '710594', remainingCapacity: 0, available: false }), window({ kind: 'PICKUP' })], 'GENERAL', '710593', 60)
  assert.equal(slots.length, 2)
  assert.equal(slots[0]!.className, 'bkg-slot is-selected')
  assert.equal(slots[0]!.note, '剩2个')
  assert.ok(slots[0]!.label.includes('服务 60 分钟'))
  assert.equal(slots[1]!.className, 'bkg-slot is-full')
  assert.equal(slots[1]!.note, '已约满')
  const receipt = decodeRescheduleReceipt(JSON.parse(JSON.stringify(wireStore)))
  assert.equal(receiptHeadline(), '改期成功，订单已回到待确认')
  assert.ok(/新预约时间/.test(receiptBody(receipt)))
  assert.ok(/30 分钟/.test(receiptBody(receipt)))
  assert.ok(/原核销码已失效/.test(receiptBody(receipt)))
  assert.deepEqual(receiptRows(receipt).map(row => row.id), ['reservationId', 'rescheduleId', 'orderVersion', 'confirmDeadline'])
})

// ---- 错误面：46号/12号 §6 逐码中文 + CAS 409 呈现重读。

test('definite conflict set, CAS detection and error copy follow 46号 semantics', () => {
  for (const code of ['COMMON_CONFLICT', 'IDEMPOTENCY_KEY_CONFLICT', 'ORDER_RESCHEDULE_LIMIT_REACHED', 'ORDER_RESCHEDULE_AFTER_START',
    'ORDER_STATE_NOT_ALLOWED', 'ORDER_REFUND_ALREADY_CREATED', 'SCHEDULE_CAPACITY_EXCEEDED', 'SCHEDULE_SWAP_FAILED'])
    assert.equal(isDefiniteRescheduleConflict(new ApiError(code, 409)), true, code)
  for (const error of [new ApiError('ORDER_OPERATION_BUSY', 409), new ApiError('ORDER_STATE_NOT_ALLOWED', 400), new Error('x')])
    assert.equal(isDefiniteRescheduleConflict(error), false)
  assert.equal(isVersionConflict(new ApiError('COMMON_CONFLICT', 409)), true)
  assert.equal(isVersionConflict(new ApiError('SCHEDULE_CAPACITY_EXCEEDED', 409)), false)
  assert.ok(/次数已用完/.test(rescheduleMessage(new ApiError('ORDER_RESCHEDULE_LIMIT_REACHED', 409))))
  assert.ok(/预约开始时间/.test(rescheduleMessage(new ApiError('ORDER_RESCHEDULE_AFTER_START', 409))))
  assert.ok(/已核销|已完成/.test(rescheduleMessage(new ApiError('ORDER_STATE_NOT_ALLOWED', 409))))
  assert.ok(/退款/.test(rescheduleMessage(new ApiError('ORDER_REFUND_ALREADY_CREATED', 409))))
  assert.ok(/已被占用/.test(rescheduleMessage(new ApiError('SCHEDULE_CAPACITY_EXCEEDED', 409))))
  assert.ok(/原预约保持不变/.test(rescheduleMessage(new ApiError('SCHEDULE_SWAP_FAILED', 409))))
  assert.ok(/重新读取订单/.test(rescheduleMessage(new ApiError('COMMON_CONFLICT', 409))))
  assert.ok(/重新登录/.test(rescheduleMessage(new ApiError('COMMON_UNAUTHORIZED', 401))))
  assert.ok(/仅订单本人/.test(rescheduleMessage(new ApiError('COMMON_FORBIDDEN', 403))))
  assert.ok(/重试原操作/.test(rescheduleMessage(new Error('PENDING_WRITE_CHANGED'))))
})

test('scenario guard only accepts the registered preview scenarios', () => {
  for (const value of ['normal', 'conflict', 'limit']) assert.equal(isRescheduleScenario(value), true)
  for (const value of ['auto', '', undefined]) assert.equal(isRescheduleScenario(value), false)
})

// ---- preview 夹具：一次成功改期 + 二次拒绝 + 容量冲突。

test('preview repository answers one success per order and rejects a second reschedule', async () => {
  const normal = new PreviewRescheduleRepository('normal')
  const view = await normal.availability('20001', '957002', '2026-10-03')
  assert.equal(selectionBranch(view.items), 'IN_STORE')
  const detail = await normal.detail(PREVIEW_RESCHEDULE_ORDER)
  const input = buildStoreInput(view.items[0]!, detail, detail.orderVersion!)!
  const receipt = await normal.reschedule(PREVIEW_RESCHEDULE_ORDER, input)
  assert.equal(receipt.confirmRound, 1)
  assert.equal(receipt.orderStageAtCommit, 'PENDING_CONFIRM')
  const again = await normal.reschedule(PREVIEW_RESCHEDULE_ORDER, input).catch(error => error)
  assert.ok(again instanceof ApiError && again.code === 'ORDER_RESCHEDULE_LIMIT_REACHED')
  const conflict = new PreviewRescheduleRepository('conflict')
  const rejected = await conflict.reschedule(PREVIEW_RESCHEDULE_ORDER, input).catch(error => error)
  assert.ok(rejected instanceof ApiError && rejected.code === 'SCHEDULE_CAPACITY_EXCEEDED')
  const limit = new PreviewRescheduleRepository('limit')
  const limited = await limit.reschedule(PREVIEW_RESCHEDULE_ORDER, input).catch(error => error)
  assert.ok(limited instanceof ApiError && limited.code === 'ORDER_RESCHEDULE_LIMIT_REACHED')
})

// ---- 页面控制器：门控读取 + 单写操作；未知结果保留原命令仅显式重试。

type ScriptedDeps = {
  views: number
  applied: { orderId: string; input: RescheduleInput }[]
  retired: unknown[]
  detailError?: Error
  applyError?: Error
  pending: { input: RescheduleInput } | null
  detail(orderId: string): Promise<OrderDetailView>
  reschedule(orderId: string, input: RescheduleInput): Promise<RescheduleReceipt>
  pendingReschedule(orderId: string): { input: RescheduleInput } | null
  retireConflict(orderId: string, error: unknown): void
}

function controllerHarness(options: { detailError?: Error; applyError?: Error } = {}) {
  const scope = new WorkspaceScope()
  scope.replace({ userId: '957001', workspace: 'consumer', merchantId: null, storeId: null })
  const input: RescheduleInput = { expectedOrderVersion: '0', appointmentStart: '2026-10-09T01:15:00.000Z', appointmentEnd: '2026-10-09T02:15:00.000Z', selectedGeneralWindowId: '710593' }
  const deps: ScriptedDeps = {
    views: 0, applied: [], retired: [], pending: null,
    detailError: options.detailError, applyError: options.applyError,
    async detail() {
      deps.views++
      if (deps.detailError) throw deps.detailError
      return decodeOrderDetail(JSON.parse(JSON.stringify(detailBase)))
    },
    async reschedule(orderId, value) {
      deps.applied.push({ orderId, input: value })
      if (deps.applyError) { deps.pending = { input: value }; throw deps.applyError }
      deps.pending = null
      return decodeRescheduleReceipt(JSON.parse(JSON.stringify(wireStore)))
    },
    pendingReschedule: () => deps.pending,
    retireConflict(_orderId, error) { deps.retired.push(error); deps.pending = null },
  }
  const controller = new RescheduleController(deps, scope)
  return { controller, deps, input }
}

test('controller gates on server actions, submits the built payload and re-reads after the receipt', async () => {
  const { controller, deps, input } = controllerHarness()
  await controller.load('900101001990003')
  assert.equal(controller.getSnapshot().phase, 'ready')
  assert.equal(canRescheduleEntry(controller.getSnapshot().detail!), true)
  await controller.submit('900101001990003', input)
  assert.deepEqual(deps.applied, [{ orderId: '900101001990003', input }])
  assert.equal(deps.views, 2)
  assert.equal(controller.getSnapshot().receipt!.confirmRound, 1)
  assert.equal(controller.getSnapshot().pending, null)
  controller.dispose()
})

test('controller refuses ineligible orders and routes read failures to closed states', async () => {
  const ineligible = controllerHarness()
  const original = ineligible.deps.detail
  ineligible.deps.detail = async () => decodeOrderDetail({ ...JSON.parse(JSON.stringify(detailBase)), actions: { canPay: false, canReschedule: false, canApplyRefund: false, canShowVerificationCode: false, canReview: false, canApplyAfterSale: false } })
  await ineligible.controller.load('900101001990003')
  assert.equal(ineligible.controller.getSnapshot().phase, 'ineligible')
  await ineligible.controller.submit('900101001990003', ineligible.input)
  assert.equal(ineligible.deps.applied.length, 0)
  ineligible.deps.detail = original
  const forbidden = controllerHarness({ detailError: new ApiError('COMMON_FORBIDDEN', 403) })
  await forbidden.controller.load('900101001990003')
  assert.equal(forbidden.controller.getSnapshot().phase, 'load-error')
  const unauthorized = controllerHarness({ detailError: new ApiError('COMMON_UNAUTHORIZED', 401) })
  await unauthorized.controller.load('900101001990003')
  assert.equal(unauthorized.controller.getSnapshot().phase, 'unauthorized')
  ineligible.controller.dispose(); forbidden.controller.dispose(); unauthorized.controller.dispose()
})

test('controller retires the journal on a CAS/limit conflict and presents the re-read; unknown outcomes keep the command', async () => {
  const cas = controllerHarness({ applyError: new ApiError('COMMON_CONFLICT', 409) })
  await cas.controller.load('900101001990003')
  await cas.controller.submit('900101001990003', cas.input)
  // CAS 409：终局拒绝 → 退槽 + 重新读取（重读后按新版本重新门控），文案指向重读。
  assert.equal(cas.deps.retired.length, 1)
  assert.equal(cas.controller.getSnapshot().pending, null)
  assert.equal(cas.deps.views, 2)
  assert.ok(/重新读取订单/.test(cas.controller.getSnapshot().notice))

  const limit = controllerHarness({ applyError: new ApiError('ORDER_RESCHEDULE_LIMIT_REACHED', 409) })
  await limit.controller.load('900101001990003')
  await limit.controller.submit('900101001990003', limit.input)
  assert.equal(limit.deps.retired.length, 1)
  assert.ok(/次数已用完/.test(limit.controller.getSnapshot().notice))

  const unknown = controllerHarness({ applyError: new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503) })
  await unknown.controller.load('900101001990003')
  await unknown.controller.submit('900101001990003', unknown.input)
  const state: RescheduleState = unknown.controller.getSnapshot()
  assert.deepEqual(state.pending, { input: unknown.input })
  assert.ok(/重试原操作/.test(state.notice))
  // 保留的命令只能按原 payload 重试（同 X-Request-Id 语义）。
  await unknown.controller.submit('900101001990003', { ...unknown.input, selectedGeneralWindowId: '710599' })
  assert.equal(unknown.deps.applied.length, 2)
  assert.deepEqual(unknown.deps.applied[0]!.input, unknown.deps.applied[1]!.input)
  cas.controller.dispose(); limit.controller.dispose(); unknown.controller.dispose()
})

test('controller restores a journaled command instead of sending a new one', async () => {
  const { controller, deps, input } = controllerHarness()
  deps.pending = { input }
  await controller.load('900101001990003')
  controller.restore('900101001990003')
  assert.deepEqual(controller.getSnapshot().pending, { input })
  await controller.submit('900101001990003', { ...input, selectedGeneralWindowId: '710599' })
  assert.deepEqual(deps.applied, [{ orderId: '900101001990003', input }])
  controller.dispose()
})

// ---- 真实 repository：经 ConsumerApi 白名单/会话门禁/幂等槽的 §3.8 路由。

const sessionView = { userId: '957001', sessionId: '957101', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const ok = (data: unknown, statusCode = 200) => ({ statusCode, data: { code: 'SUCCESS', message: 'ok', data, traceId: 'test' } })
const failure = (statusCode: number, code: string) => ({ statusCode, data: { code, message: 'x', data: null, traceId: 'test' } })
const wireOrderDetail = JSON.parse(JSON.stringify(detailBase))
const wireSelection = {
  items: [{ start: '2026-10-09T09:15:00+08:00', end: '2026-10-09T10:45:00+08:00', effectiveCapacity: 3, occupiedCount: 1, remainingCapacity: 2, available: true, windowId: '710593', kind: 'GENERAL' }],
}

function authenticatedApi(transport: (request: { method: string; path: string; data?: Record<string, unknown>; requestId?: string; headers: Record<string, string> }) => Promise<{ statusCode: number; data: unknown }>) {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, JSON.parse(JSON.stringify(value))), remove: key => { values.delete(key) } }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'test-only', tokenType: 'Bearer' })
  const api = new ConsumerApi(transport, store, async () => randomUUID())
  return { api, restore: () => api.restore() }
}

test('real repository posts the strict oneOf body with a terminal UUID and decodes the receipt', async () => {
  const seen: { method: string; path: string; data?: Record<string, unknown>; requestId?: string; headers: Record<string, string> }[] = []
  const { api, restore } = authenticatedApi(async request => {
    seen.push({ method: request.method, path: request.path, data: request.data, requestId: request.requestId, headers: request.headers })
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.method === 'GET' && request.path.includes('/availability')) return ok(JSON.parse(JSON.stringify(wireSelection)))
    if (request.method === 'GET') return ok(wireOrderDetail)
    return ok(JSON.parse(JSON.stringify(wireStore)), 200)
  })
  await restore()
  const repository = new RealRescheduleRepository(api)
  const detail = await repository.detail('900101001990003')
  assert.equal(canRescheduleEntry(detail), true)
  const availability = await repository.availability('20001', '957002', '2026-10-09')
  assert.equal(availability.items[0]!.windowId, '710593')
  const read = seen.find(entry => entry.path.includes('/availability'))!
  assert.deepEqual(read.data, { storeId: '957002', startDate: '2026-10-09', endDate: '2026-10-09' })
  assert.equal(read.requestId, undefined)
  const receipt = await repository.reschedule('900101001990003', { expectedOrderVersion: '0', appointmentStart: '2026-10-09T01:15:00.000Z', appointmentEnd: '2026-10-09T02:15:00.000Z', selectedGeneralWindowId: '710593' })
  assert.equal(receipt.rescheduleId, '900103001990002')
  const write = seen.find(entry => entry.method === 'POST')!
  assert.equal(write.path, '/api/v1/c/orders/900101001990003/reschedule')
  assert.deepEqual(write.data, { expectedOrderVersion: '0', appointmentStart: '2026-10-09T01:15:00.000Z', appointmentEnd: '2026-10-09T02:15:00.000Z', selectedGeneralWindowId: '710593' })
  assert.match(write.requestId!, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)
  assert.equal(write.headers['X-Request-Id'], write.requestId)
  assert.equal(repository.pendingReschedule('900101001990003'), null)
})

test('real repository journals the write, replays the same key, and rejects changed payloads', async () => {
  const writes: { requestId: string }[] = []
  let attempts = 0
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.method === 'GET') return ok(wireOrderDetail)
    writes.push({ requestId: request.requestId! }); attempts++
    if (attempts === 1) throw new Error('test-only lost ACK after real commit')
    return ok(JSON.parse(JSON.stringify(wireStore)), 200)
  })
  await restore()
  const repository = new RealRescheduleRepository(api)
  const input: RescheduleInput = { expectedOrderVersion: '0', appointmentStart: '2026-10-09T01:15:00.000Z', appointmentEnd: '2026-10-09T02:15:00.000Z', selectedGeneralWindowId: '710593' }
  await assert.rejects(repository.reschedule('900101001990003', input), /lost ACK/)
  // 未确认结果：槽内保留原命令（两分支形状可还原）；异参不允许换 payload 重试。
  assert.deepEqual(repository.pendingReschedule('900101001990003'), { input })
  await assert.rejects(repository.reschedule('900101001990003', { ...input, selectedGeneralWindowId: '710599' }), /PENDING_WRITE_CHANGED/)
  // 同参重试复用同一 X-Request-Id（五元组幂等重放 200 首次成功回执）；成功后槽清空。
  const replay = await repository.reschedule('900101001990003', input)
  assert.equal(replay.confirmRound, 1)
  assert.equal(writes.length, 2)
  assert.equal(writes[1]!.requestId, writes[0]!.requestId)
  assert.equal(repository.pendingReschedule('900101001990003'), null)
})

test('real repository retires the journaled command on a definite 409 with proof only', async () => {
  let attempts = 0
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.method === 'GET') return ok(wireOrderDetail)
    attempts++
    return failure(409, attempts === 1 ? 'SCHEDULE_CAPACITY_EXCEEDED' : 'ORDER_RESCHEDULE_LIMIT_REACHED')
  })
  await restore()
  const repository = new RealRescheduleRepository(api)
  const input: RescheduleInput = { expectedOrderVersion: '0', appointmentStart: '2026-10-09T01:15:00.000Z', appointmentEnd: '2026-10-09T02:15:00.000Z', selectedGeneralWindowId: '710593' }
  const rejected = await repository.reschedule('900101001990003', input).catch(error => error)
  assert.ok(rejected instanceof ApiError && rejected.code === 'SCHEDULE_CAPACITY_EXCEEDED')
  assert.deepEqual(repository.pendingReschedule('900101001990003'), { input })
  repository.retireConflict('900101001990003', rejected)
  assert.equal(repository.pendingReschedule('900101001990003'), null)
  // 无证据不得退槽：未经过本仓库写通道的错误对象直接拒绝。
  assert.throws(() => repository.retireConflict('900101001990003', new ApiError('ORDER_STATE_NOT_ALLOWED', 409)), /UNCONFIRMED_WRITE/)
})
