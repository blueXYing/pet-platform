import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import {
  canApplyRefundEntry, decodeRefundApplyReceipt, emptyRefundDraft, isDefiniteRefundConflict,
  isRefundApplyScenario, receiptBody, receiptHeadline, receiptStatusBadge, refundApplyInput, refundApplyMessage,
  validateRefundDraft, RefundApplyController, PreviewRefundApplyRepository, PREVIEW_REFUND_ORDER,
  type RefundApplyInput, type RefundApplyReceipt, type RefundApplyState, type RefundDraft,
} from '../orders/refund'
import { RealRefundApplyRepository } from '../orders/refund-repository'
import { decodeOrderDetail, PreviewOrderReadRepository, type OrderDetailView } from '../orders/model'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { ApiError } from '../../shared/request'
import { WorkspaceScope } from '../../shared/workspace'

// ---- 解码：本切片线上形状（10号 §3.9 + 49号：恰六键、两窗口终态、refundOrderId 可空）。

const wirePre = {
  applicationId: '960101001990001', applicationStatus: 'AUTO_APPROVED', route: 'AUTO_FULL_BEFORE_SERVICE',
  merchantDeadline: '2026-10-07T10:00:00.000Z', refundOrderId: null, displayStatus: 'REFUNDING',
}
const wirePost = {
  applicationId: '960101001990002', applicationStatus: 'PENDING_MERCHANT', route: 'MERCHANT_CONFIRM_AFTER_SERVICE',
  merchantDeadline: '2026-10-08T10:00:00.000Z', refundOrderId: null, displayStatus: 'REFUND_PENDING_CONFIRM',
}

test('receipt decoder accepts both §3.9 window projections and keeps the fixed six keys', () => {
  const pre = decodeRefundApplyReceipt(JSON.parse(JSON.stringify(wirePre)))
  assert.equal(pre.applicationStatus, 'AUTO_APPROVED')
  assert.equal(pre.route, 'AUTO_FULL_BEFORE_SERVICE')
  assert.equal(pre.refundOrderId, null)
  assert.equal(pre.displayStatus, 'REFUNDING')
  const post = decodeRefundApplyReceipt(JSON.parse(JSON.stringify(wirePost)))
  assert.equal(post.displayStatus, 'REFUND_PENDING_CONFIRM')
  assert.equal(post.merchantDeadline, '2026-10-08T10:00:00.000Z')
  // 回执阶段退款单尚未由耐久任务创建，但字段本身可承载合法 ID（§3.10 查看退款同 schema）。
  const created = decodeRefundApplyReceipt({ ...wirePost, refundOrderId: '970101001990009' })
  assert.equal(created.refundOrderId, '970101001990009')
})

test('receipt decoder fails closed on contract violations', () => {
  for (const mutate of [
    (v: Record<string, unknown>) => { v.extra = 1 },
    (v: Record<string, unknown>) => { delete v.refundOrderId },
    (v: Record<string, unknown>) => { v.applicationStatus = 'FROZEN' },
    (v: Record<string, unknown>) => { v.route = 'MERCHANT_CONFIRM' },
    (v: Record<string, unknown>) => { v.displayStatus = 'REFUND' },
    (v: Record<string, unknown>) => { v.merchantDeadline = '2026-10-08T10:00:00Z' },        // 缺毫秒
    (v: Record<string, unknown>) => { v.merchantDeadline = '2026-10-08 10:00:00.000Z' },   // 非ISO
    (v: Record<string, unknown>) => { v.applicationId = '0' },
    (v: Record<string, unknown>) => { v.refundOrderId = '01' },
  ]) {
    const value = JSON.parse(JSON.stringify(wirePost)) as Record<string, unknown>
    mutate(value)
    assert.throws(() => decodeRefundApplyReceipt(value), /INVALID_RESPONSE/, JSON.stringify(mutate))
  }
})

// ---- 表单草稿：结构校验归本模块，字典与业务准入归服务端内核。

test('draft validation enforces the contract bounds and the payload normalization', () => {
  assert.deepEqual(validateRefundDraft(emptyRefundDraft()), { reasonCode: '请填写退款原因代码（服务端配置的原因代码，如渠道公示的代码）' })
  assert.deepEqual(validateRefundDraft({ reasonCode: ' USER_REQUEST ', reasonText: '' }), {})
  assert.equal((validateRefundDraft({ reasonCode: 'x'.repeat(65), reasonText: '' }) as { reasonCode?: string }).reasonCode !== undefined, true)
  assert.equal((validateRefundDraft({ reasonCode: 'A', reasonText: 'y'.repeat(501) }) as { reasonText?: string }).reasonText !== undefined, true)
  // 空白 reasonCode 无效；说明仅超界/坏码点无效（空串合法）。
  assert.equal((validateRefundDraft({ reasonCode: '   ', reasonText: '' }) as { reasonCode?: string }).reasonCode !== undefined, true)
  assert.deepEqual(refundApplyInput({ reasonCode: ' USER_REQUEST ', reasonText: '  临时无法到店  ' }),
    { reasonCode: 'USER_REQUEST', reasonText: '临时无法到店' })
  assert.deepEqual(refundApplyInput({ reasonCode: 'USER_REQUEST', reasonText: '   ' }),
    { reasonCode: 'USER_REQUEST', reasonText: null })
})

// ---- 展示映射（ARCH-005：口径文案/徽标变体只在此处）。

test('receipt copy states both windows honestly and badge variants stay the closed display set', () => {
  const pre = decodeRefundApplyReceipt(JSON.parse(JSON.stringify(wirePre)))
  assert.equal(receiptHeadline(pre), '申请已提交，系统已自动同意全额退款')
  assert.ok(/自动全额原路退回/.test(receiptBody(pre, () => 'T')))
  assert.ok(/无需商家处理/.test(receiptBody(pre, () => 'T')))
  assert.equal(receiptStatusBadge(pre).className, 'is-refunding')
  assert.equal(receiptStatusBadge(pre).label, '退款中')
  const post = decodeRefundApplyReceipt(JSON.parse(JSON.stringify(wirePost)))
  assert.equal(receiptHeadline(post), '申请已提交，等待商家在 24 小时内处理')
  assert.ok(/24 小时内处理/.test(receiptBody(post, () => 'T')))
  assert.ok(receiptBody(post, value => `[${value}]`).includes('[2026-10-08T10:00:00.000Z]'))
  assert.equal(receiptStatusBadge(post).className, 'is-refund-pending-confirm')
  assert.equal(receiptStatusBadge(post).label, '退款待确认')
})

test('detail entry gating reads only the server actions boolean', async () => {
  const repository = new PreviewOrderReadRepository('normal')
  const eligible = await repository.detail(PREVIEW_REFUND_ORDER)
  assert.equal(canApplyRefundEntry(eligible), true)
  // actions 缺失（null）或有在途申请样例（REFUND_PENDING_CONFIRM 夹具无 actions）→ 不呈现入口。
  const withoutActions = await repository.detail('900101001990005')
  assert.equal(canApplyRefundEntry(withoutActions), false)
})

// ---- 错误面：49号语义 + 12号 §6 映射的终局冲突集合与文案。

test('definite conflict set and error copy follow 49号 semantics', () => {
  for (const code of ['COMMON_CONFLICT', 'REFUND_NOT_ELIGIBLE', 'REFUND_APPLICATION_ALREADY_PROCESSED', 'REFUND_ORDER_ALREADY_EXISTS', 'REFUND_ALREADY_EXISTS', 'REFUND_MERCHANT_DEADLINE_PASSED'])
    assert.equal(isDefiniteRefundConflict(new ApiError(code, 409)), true, code)
  for (const error of [new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409), new ApiError('REFUND_NOT_ELIGIBLE', 400), new Error('x')])
    assert.equal(isDefiniteRefundConflict(error), false)
  assert.equal(refundApplyMessage(new ApiError('REFUND_NOT_ELIGIBLE', 409)), '当前订单状态不支持申请退款（需已支付且未取消，处于两个退款窗口之一）')
  assert.ok(/在途退款申请/.test(refundApplyMessage(new ApiError('REFUND_APPLICATION_ALREADY_PROCESSED', 409))))
  assert.ok(/被拒绝后可再次申请/.test(refundApplyMessage(new ApiError('REFUND_APPLICATION_ALREADY_PROCESSED', 409))))
  assert.ok(/已创建退款单/.test(refundApplyMessage(new ApiError('REFUND_ORDER_ALREADY_EXISTS', 409))))
  assert.ok(/暂未开放/.test(refundApplyMessage(new ApiError('REFUND_BEFORE_SERVICE_NOT_IMPLEMENTED', 503))))
  assert.ok(/重新登录/.test(refundApplyMessage(new ApiError('COMMON_UNAUTHORIZED', 401))))
  assert.ok(/仅订单本人/.test(refundApplyMessage(new ApiError('COMMON_FORBIDDEN', 403))))
  assert.ok(/重试原操作/.test(refundApplyMessage(new Error('PENDING_WRITE_CHANGED'))))
})

test('scenario guard only accepts the registered preview scenarios', () => {
  for (const value of ['pre', 'post', 'conflict']) assert.equal(isRefundApplyScenario(value), true)
  for (const value of ['auto', '', undefined]) assert.equal(isRefundApplyScenario(value), false)
})

// ---- preview 夹具（设计验收通道）：两窗口回执 + 在途拒绝语义。

test('preview repository answers both windows and rejects a second live application', async () => {
  const pre = new PreviewRefundApplyRepository('pre')
  const preReceipt = await pre.apply(PREVIEW_REFUND_ORDER, { reasonCode: 'USER_REQUEST', reasonText: null })
  assert.equal(preReceipt.applicationStatus, 'AUTO_APPROVED')
  assert.equal(preReceipt.displayStatus, 'REFUNDING')
  const again = await pre.apply(PREVIEW_REFUND_ORDER, { reasonCode: 'USER_REQUEST', reasonText: null }).catch(error => error)
  assert.ok(again instanceof ApiError && again.code === 'REFUND_APPLICATION_ALREADY_PROCESSED')
  const post = new PreviewRefundApplyRepository('post')
  const postReceipt = await post.apply(PREVIEW_REFUND_ORDER, { reasonCode: 'USER_REQUEST', reasonText: '临时无法到店' })
  assert.equal(postReceipt.applicationStatus, 'PENDING_MERCHANT')
  assert.equal(postReceipt.displayStatus, 'REFUND_PENDING_CONFIRM')
  const conflict = new PreviewRefundApplyRepository('conflict')
  const rejected = await conflict.apply(PREVIEW_REFUND_ORDER, { reasonCode: 'USER_REQUEST', reasonText: null }).catch(error => error)
  assert.ok(rejected instanceof ApiError && rejected.code === 'REFUND_APPLICATION_ALREADY_PROCESSED')
  // 预览夹具读取的订单详情与订单模型夹具一致（入口门控可用）。
  assert.equal(canApplyRefundEntry(await pre.detail(PREVIEW_REFUND_ORDER)), true)
})

// ---- 页面控制器：门控读取 + 单写操作；未知结果保留原命令仅显式重试。

type ScriptedDeps = {
  views: number
  applied: { orderId: string; input: RefundApplyInput }[]
  retired: unknown[]
  detailError?: Error
  applyError?: Error
  detailOverride?: OrderDetailView
  pending: { reasonCode: string; reasonText: string | null } | null
  detail(orderId: string): Promise<OrderDetailView>
  apply(orderId: string, input: RefundApplyInput): Promise<RefundApplyReceipt>
  pendingApply(orderId: string): { reasonCode: string; reasonText: string | null } | null
  retireConflict(orderId: string, error: unknown): void
}

function controllerHarness(options: { detailError?: Error; applyError?: Error; detailOverride?: OrderDetailView } = {}) {
  const scope = new WorkspaceScope()
  scope.replace({ userId: '957001', workspace: 'consumer', merchantId: null, storeId: null })
  const deps: ScriptedDeps = {
    views: 0, applied: [], retired: [], pending: null,
    detailError: options.detailError, applyError: options.applyError, detailOverride: options.detailOverride,
    async detail() {
      deps.views++
      if (deps.detailError) throw deps.detailError
      if (deps.detailOverride) return JSON.parse(JSON.stringify(deps.detailOverride)) as OrderDetailView
      return new PreviewOrderReadRepository('normal').detail(PREVIEW_REFUND_ORDER)
    },
    async apply(orderId, input) {
      deps.applied.push({ orderId, input })
      if (deps.applyError) { deps.pending = { reasonCode: input.reasonCode, reasonText: input.reasonText ?? null }; throw deps.applyError }
      deps.pending = null
      return decodeRefundApplyReceipt(JSON.parse(JSON.stringify(wirePost)))
    },
    pendingApply: () => deps.pending,
    retireConflict(_orderId, error) { deps.retired.push(error); deps.pending = null },
  }
  const controller = new RefundApplyController(deps, scope)
  return { controller, deps, scope }
}

const eligibleDraft: RefundDraft = { reasonCode: 'USER_REQUEST', reasonText: '临时无法到店' }

test('controller gates the form on server actions, submits, and re-gates after the receipt', async () => {
  const { controller, deps } = controllerHarness()
  await controller.load(PREVIEW_REFUND_ORDER)
  assert.equal(controller.getSnapshot().phase, 'ready')
  assert.equal(canApplyRefundEntry(controller.getSnapshot().detail!), true)
  await controller.submit(PREVIEW_REFUND_ORDER, eligibleDraft)
  assert.deepEqual(deps.applied, [{ orderId: PREVIEW_REFUND_ORDER, input: { reasonCode: 'USER_REQUEST', reasonText: '临时无法到店' } }])
  // 成功后回读订单详情（no-store）；回执保留展示，门控以服务端最新 actions 为准。
  assert.equal(deps.views, 2)
  assert.equal(controller.getSnapshot().receipt!.applicationStatus, 'PENDING_MERCHANT')
  assert.equal(controller.getSnapshot().pending, null)
  controller.dispose()
})

test('controller refuses invalid drafts and ineligible orders without sending anything', async () => {
  const { controller, deps } = controllerHarness()
  await controller.load(PREVIEW_REFUND_ORDER)
  await controller.submit(PREVIEW_REFUND_ORDER, { reasonCode: '   ', reasonText: '' })
  assert.equal(deps.applied.length, 0)
  assert.ok(/请完善退款原因/.test(controller.getSnapshot().notice))
  const ineligible = controllerHarness({ detailOverride: decodeOrderDetail({
    orderId: '900101001990006', orderNo: '2026100100006', displayStatus: 'REFUND_PENDING_CONFIRM', payAmount: '99.00',
    appointmentStart: '2026-10-09T11:00:00.000+08:00', appointmentEnd: '2026-10-09T12:30:00.000+08:00',
    orderStage: 'PENDING_SERVICE', paymentStatus: 'PAID', refundApplicationStatus: 'PENDING_MERCHANT',
  }) })
  await ineligible.controller.load('900101001990006')
  assert.equal(ineligible.controller.getSnapshot().phase, 'ineligible')
  await ineligible.controller.submit('900101001990006', eligibleDraft)
  assert.equal(ineligible.deps.applied.length, 0)
  controller.dispose(); ineligible.controller.dispose()
})

test('controller fails closed on read errors and 401 routes to the login state', async () => {
  const forbidden = controllerHarness({ detailError: new ApiError('COMMON_FORBIDDEN', 403) })
  await forbidden.controller.load(PREVIEW_REFUND_ORDER)
  assert.equal(forbidden.controller.getSnapshot().phase, 'load-error')
  assert.ok(/仅订单本人/.test(forbidden.controller.getSnapshot().notice))
  const unauthorized = controllerHarness({ detailError: new ApiError('COMMON_UNAUTHORIZED', 401) })
  await unauthorized.controller.load(PREVIEW_REFUND_ORDER)
  assert.equal(unauthorized.controller.getSnapshot().phase, 'unauthorized')
  forbidden.controller.dispose(); unauthorized.controller.dispose()
})

test('controller retires the journal on a definite conflict; unknown outcomes keep the original command', async () => {
  const conflict = controllerHarness({ applyError: new ApiError('REFUND_APPLICATION_ALREADY_PROCESSED', 409) })
  await conflict.controller.load(PREVIEW_REFUND_ORDER)
  await conflict.controller.submit(PREVIEW_REFUND_ORDER, eligibleDraft)
  // 在途 409：终局拒绝 → 退槽 + 重新读取，页面不再卡在“重试原操作”。
  assert.equal(conflict.deps.retired.length, 1)
  assert.equal(conflict.controller.getSnapshot().pending, null)
  assert.equal(conflict.deps.views, 2)
  assert.ok(/在途退款申请/.test(conflict.controller.getSnapshot().notice))

  const unknown = controllerHarness({ applyError: new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503) })
  await unknown.controller.load(PREVIEW_REFUND_ORDER)
  await unknown.controller.submit(PREVIEW_REFUND_ORDER, eligibleDraft)
  const state: RefundApplyState = unknown.controller.getSnapshot()
  assert.deepEqual(state.pending, { reasonCode: 'USER_REQUEST', reasonText: '临时无法到店' })
  assert.ok(/重试原操作/.test(state.notice))
  // 保留的命令只能按原 payload 重试（同 X-Request-Id 语义）。
  await unknown.controller.submit(PREVIEW_REFUND_ORDER, { reasonCode: 'OTHER', reasonText: '' })
  assert.deepEqual(unknown.deps.applied.map(entry => entry.input.reasonCode), ['USER_REQUEST', 'USER_REQUEST'])
  conflict.controller.dispose(); unknown.controller.dispose()
})

test('controller restores a journaled command instead of sending a new one', async () => {
  const { controller, deps } = controllerHarness()
  deps.pending = { reasonCode: 'USER_REQUEST', reasonText: null }
  await controller.load(PREVIEW_REFUND_ORDER)
  controller.restore(PREVIEW_REFUND_ORDER)
  assert.deepEqual(controller.getSnapshot().pending, { reasonCode: 'USER_REQUEST', reasonText: null })
  // 有未确认命令时 submit 退化为重试原操作（原 payload，而非当前草稿）。
  await controller.submit(PREVIEW_REFUND_ORDER, { reasonCode: 'OTHER', reasonText: '' })
  assert.deepEqual(deps.applied, [{ orderId: PREVIEW_REFUND_ORDER, input: { reasonCode: 'USER_REQUEST', reasonText: null } }])
  controller.dispose()
})

// ---- 真实 repository：经 ConsumerApi 白名单/会话门禁/幂等槽的 §3.9 路由。

const sessionView = { userId: '957001', sessionId: '957101', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const ok = (data: unknown, statusCode = 200) => ({ statusCode, data: { code: 'SUCCESS', message: 'ok', data, traceId: 'test' } })
const failure = (statusCode: number, code: string) => ({ statusCode, data: { code, message: 'x', data: null, traceId: 'test' } })
const wireOrderDetail = {
  orderId: '900101001990003', orderNo: '2026100100003', displayStatus: 'PENDING_SERVICE',
  orderStage: 'PENDING_SERVICE', paymentStatus: 'PAID', verificationStatus: 'UNVERIFIED',
  refundApplicationStatus: null, refundStatus: null, afterSaleStatus: null,
  payAmount: '80.00', appointmentStart: '2026-10-12T14:00:00.000+08:00', appointmentEnd: '2026-10-12T15:00:00.000+08:00',
  verifiedAt: null,
  actions: { canPay: false, canReschedule: true, canApplyRefund: true, canShowVerificationCode: true, canReview: false, canApplyAfterSale: false },
}

function authenticatedApi(transport: (request: { method: string; path: string; data?: Record<string, unknown>; requestId?: string; headers: Record<string, string> }) => Promise<{ statusCode: number; data: unknown }>) {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, JSON.parse(JSON.stringify(value))), remove: key => { values.delete(key) } }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'test-only', tokenType: 'Bearer' })
  const api = new ConsumerApi(transport, store, async () => randomUUID())
  return { api, restore: () => api.restore() }
}

test('real repository posts the strict body with a terminal UUID and decodes both windows', async () => {
  const seen: { method: string; path: string; data?: Record<string, unknown>; requestId?: string; headers: Record<string, string> }[] = []
  const { api, restore } = authenticatedApi(async request => {
    seen.push({ method: request.method, path: request.path, data: request.data, requestId: request.requestId, headers: request.headers })
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.method === 'GET') return ok(JSON.parse(JSON.stringify(wireOrderDetail)))
    return ok(JSON.parse(JSON.stringify(wirePre)), 201)
  })
  await restore()
  const repository = new RealRefundApplyRepository(api)
  const detail = await repository.detail('900101001990003')
  assert.equal(canApplyRefundEntry(detail), true)
  const read = seen.find(entry => entry.method === 'GET')!
  assert.equal(read.requestId, undefined) // GET 无 X-Request-Id
  const receipt = await repository.apply('900101001990003', { reasonCode: 'USER_REQUEST', reasonText: '临时无法到店' })
  assert.equal(receipt.applicationStatus, 'AUTO_APPROVED')
  const write = seen.find(entry => entry.method === 'POST')!
  assert.equal(write.path, '/api/v1/c/orders/900101001990003/refund-applications')
  assert.deepEqual(write.data, { reasonCode: 'USER_REQUEST', reasonText: '临时无法到店' })
  assert.match(write.requestId!, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)
  assert.equal(write.headers['X-Request-Id'], write.requestId)
  assert.equal(repository.pendingApply('900101001990003'), null)
})

test('real repository journals the write, replays the same key, and rejects changed payloads', async () => {
  const writes: { requestId: string }[] = []
  let attempts = 0
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.method === 'GET') return ok(JSON.parse(JSON.stringify(wireOrderDetail)))
    writes.push({ requestId: request.requestId! }); attempts++
    if (attempts === 1) throw new Error('test-only lost ACK after real commit')
    return ok(JSON.parse(JSON.stringify(wirePost)), 200)
  })
  await restore()
  const repository = new RealRefundApplyRepository(api)
  await assert.rejects(repository.apply('900101001990003', { reasonCode: 'USER_REQUEST', reasonText: null }), /lost ACK/)
  // 未确认结果：槽内保留原命令；异参不允许换 payload 重试。
  assert.deepEqual(repository.pendingApply('900101001990003'), { reasonCode: 'USER_REQUEST', reasonText: null })
  await assert.rejects(repository.apply('900101001990003', { reasonCode: 'OTHER', reasonText: null }), /PENDING_WRITE_CHANGED/)
  // 同参重试复用同一 X-Request-Id（五元组幂等重放 200）；成功后槽清空。
  const replay = await repository.apply('900101001990003', { reasonCode: 'USER_REQUEST', reasonText: null })
  assert.equal(replay.applicationStatus, 'PENDING_MERCHANT')
  assert.equal(writes.length, 2)
  assert.equal(writes[1]!.requestId, writes[0]!.requestId)
  assert.equal(repository.pendingApply('900101001990003'), null)
})

test('real repository retires the journaled command on a definite 409 with proof only', async () => {
  let attempts = 0
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.method === 'GET') return ok(JSON.parse(JSON.stringify(wireOrderDetail)))
    attempts++
    return failure(409, attempts === 1 ? 'REFUND_APPLICATION_ALREADY_PROCESSED' : 'REFUND_ORDER_ALREADY_EXISTS')
  })
  await restore()
  const repository = new RealRefundApplyRepository(api)
  const inFlight = await repository.apply('900101001990003', { reasonCode: 'USER_REQUEST', reasonText: null }).catch(error => error)
  assert.ok(inFlight instanceof ApiError && inFlight.code === 'REFUND_APPLICATION_ALREADY_PROCESSED')
  // 未经该错误实例（无证据）不得退槽。
  assert.throws(() => repository.retireConflict('900101001990003', new ApiError('REFUND_APPLICATION_ALREADY_PROCESSED', 409)), /UNCONFIRMED_WRITE/)
  repository.retireConflict('900101001990003', inFlight)
  assert.equal(repository.pendingApply('900101001990003'), null)
  // 退槽后新 UUID 新命令；已有退款单 409 同为终局，可凭证据退槽（REJECTED 后再申请同语义换新命令）。
  const blocked = await repository.apply('900101001990003', { reasonCode: 'USER_REQUEST', reasonText: null }).catch(error => error)
  assert.ok(blocked instanceof ApiError && blocked.code === 'REFUND_ORDER_ALREADY_EXISTS')
  repository.retireConflict('900101001990003', blocked)
  assert.equal(repository.pendingApply('900101001990003'), null)
})

test('real repository surfaces the switched-off route and mismatched receipts fail closed', async () => {
  const switchedOff = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    return failure(403, 'COMMON_FORBIDDEN') // 路由未挂载（开关默认关，catch-all deny）
  })
  await switchedOff.restore()
  const denied = await new RealRefundApplyRepository(switchedOff.api).apply('900101001990003', { reasonCode: 'USER_REQUEST', reasonText: null }).catch(error => error)
  assert.ok(denied instanceof ApiError && denied.statusCode === 403)
  // 首回执只可能是两窗口终态配对（REJECTED/APPROVED/错配 route 组合均失败关闭）。
  for (const mutate of [
    (v: Record<string, unknown>) => { v.applicationStatus = 'REJECTED' },
    (v: Record<string, unknown>) => { v.applicationStatus = 'APPROVED' },
    (v: Record<string, unknown>) => { v.route = 'AFTERSALE_DECISION' },
    (v: Record<string, unknown>) => { v.applicationStatus = 'AUTO_APPROVED', v.route = 'MERCHANT_CONFIRM_AFTER_SERVICE' },
  ]) {
    const value = JSON.parse(JSON.stringify(wirePost)) as Record<string, unknown>
    mutate(value)
    const mismatched = authenticatedApi(async request => {
      if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
      if (request.method === 'GET') return ok(JSON.parse(JSON.stringify(wireOrderDetail)))
      return ok(value, 201)
    })
    await mismatched.restore()
    const rejected = await new RealRefundApplyRepository(mismatched.api).apply('900101001990003', { reasonCode: 'USER_REQUEST', reasonText: null }).catch(error => error)
    assert.ok(/INVALID_RESPONSE/.test(String((rejected as Error)?.message)), JSON.stringify(mutate))
  }
})
