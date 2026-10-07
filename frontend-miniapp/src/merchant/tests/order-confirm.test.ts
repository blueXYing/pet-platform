import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../shared/request'
import { StaleContextError } from '../../shared/workspace'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import {
  decodeOrderDecisionReceipt, decisionTimeText, definitiveNoWrite, internalNoteProblem,
  isOrderId, orderDecisionAvailability, orderDecisionMessage, reasonTextProblem,
  type OrderDecisionDeps, type OrderDecisionReceipt, type RejectReasonCode,
} from '../order/model'
import { RealMerchantOrderRepository } from '../order/repository'
import { OrderDecisionController } from '../order/controller'

async function rejects(call: () => Promise<unknown> | unknown): Promise<unknown> {
  try { await call(); return new Error('NO_THROW') } catch (error) { return error }
}

const confirmReceipt: OrderDecisionReceipt = {
  orderId: '777001', decisionId: '777002', confirmRound: 0, action: 'CONFIRM',
  orderStageAtCommit: 'PENDING_SERVICE', decidedAt: '2026-10-07T02:00:00.000Z', refundOrderId: null,
}
const rejectReceipt: OrderDecisionReceipt = {
  orderId: '777001', decisionId: '777003', confirmRound: 0, action: 'REJECT',
  orderStageAtCommit: 'CANCELED', decidedAt: '2026-10-07T02:00:00.000Z', refundOrderId: '777004',
}

// ---------------------------------------------------------------------------
// Model: receipt decoder, reason/note syntax, error mapping, fail-closed classification.
// ---------------------------------------------------------------------------

test('decode: confirm and reject receipts carry their contract tails all-or-nothing', () => {
  const confirmed = decodeOrderDecisionReceipt({ ...confirmReceipt })
  assert.equal(confirmed.action, 'CONFIRM')
  assert.equal(confirmed.refundOrderId, null)
  assert.equal(confirmed.orderStageAtCommit, 'PENDING_SERVICE')
  const rejected = decodeOrderDecisionReceipt({ ...rejectReceipt })
  assert.equal(rejected.action, 'REJECT')
  assert.equal(rejected.refundOrderId, '777004')
  assert.equal(rejected.orderStageAtCommit, 'CANCELED')
})

test('decode rejects contract-foreign shapes', async () => {
  // Unknown or missing field.
  assert.equal(((await rejects(() => decodeOrderDecisionReceipt({ ...confirmReceipt, extra: 1 }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeOrderDecisionReceipt({ ...confirmReceipt, refundOrderId: undefined }))) as Error).message, 'INVALID_RESPONSE')
  // A confirm receipt claiming a refund or a closed order never reaches the UI.
  assert.equal(((await rejects(() => decodeOrderDecisionReceipt({ ...confirmReceipt, refundOrderId: '777004' }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeOrderDecisionReceipt({ ...confirmReceipt, orderStageAtCommit: 'CANCELED' }))) as Error).message, 'INVALID_RESPONSE')
  // A reject receipt without the refund or with the service stage is contract-foreign.
  assert.equal(((await rejects(() => decodeOrderDecisionReceipt({ ...rejectReceipt, refundOrderId: null }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeOrderDecisionReceipt({ ...rejectReceipt, orderStageAtCommit: 'PENDING_SERVICE' }))) as Error).message, 'INVALID_RESPONSE')
  // Bad ids, rounds, actions, and timestamps.
  assert.equal(((await rejects(() => decodeOrderDecisionReceipt({ ...confirmReceipt, orderId: '0777001' }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeOrderDecisionReceipt({ ...confirmReceipt, confirmRound: 2 }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeOrderDecisionReceipt({ ...confirmReceipt, action: 'CANCEL' }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeOrderDecisionReceipt({ ...confirmReceipt, decidedAt: '2026-10-07T02:00:00' }))) as Error).message, 'INVALID_RESPONSE')
})

test('form syntax: order id, reason text 5-200 code points, optional note 0-200', () => {
  assert.equal(isOrderId('777001'), true)
  assert.equal(isOrderId('9007199254740993'), true)
  assert.equal(isOrderId('0777'), false)
  assert.equal(isOrderId(''), false)
  assert.equal(reasonTextProblem('门店临时无法履约'), null)
  assert.equal(reasonTextProblem('狗'.repeat(100)), null, '100 code points pass')
  assert.notEqual(reasonTextProblem('1234'), null, 'four characters fail')
  assert.notEqual(reasonTextProblem('   '), null, 'all-whitespace fails')
  assert.notEqual(reasonTextProblem('a'.repeat(201)), null)
  assert.equal(internalNoteProblem(''), null)
  assert.equal(internalNoteProblem('店内备注'), null)
  assert.notEqual(internalNoteProblem('a'.repeat(201)), null)
  assert.equal(decisionTimeText('2026-10-07T02:00:00.000Z'), '2026-10-07 10:00:00')
})

test('error mapping: per-code Chinese copy; 403 anti-enumeration stays single', () => {
  const missing = orderDecisionMessage(new ApiError('COMMON_FORBIDDEN', 403))
  assert.match(missing, /订单不存在或不属于当前商家，或当前身份无处理权限/)
  assert.equal(missing, orderDecisionMessage(new ApiError('COMMON_NOT_FOUND', 404)))
  assert.match(orderDecisionMessage(new ApiError('ORDER_STATE_NOT_ALLOWED', 409)), /已接单、已拒单或已取消/)
  assert.match(orderDecisionMessage(new ApiError('ORDER_CONFIRM_DEADLINE_PASSED', 409)), /30分钟确认期限/)
  assert.match(orderDecisionMessage(new ApiError('ORDER_REFUND_ALREADY_CREATED', 409)), /已创建退款单/)
  assert.match(orderDecisionMessage(new ApiError('ORDER_OPERATION_BUSY', 409)), /正在被其他操作处理/)
  assert.match(orderDecisionMessage(new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409)), /请求编号已用于其他内容/)
  assert.match(orderDecisionMessage(new ApiError('COMMON_INVALID_ARGUMENT', 400)), /提交内容无效/)
  assert.match(orderDecisionMessage(new ApiError('COMMON_UNAUTHORIZED', 401)), /登录已失效/)
  assert.match(orderDecisionMessage(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), /暂不可用/)
  assert.match(orderDecisionMessage(new Error('PENDING_WRITE_CHANGED')), /尚未确认/)
  assert.equal(orderDecisionAvailability(new ApiError('COMMON_FORBIDDEN', 403)), 'closed')
  assert.equal(orderDecisionAvailability(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), 'closed')
  assert.equal(orderDecisionAvailability(new ApiError('ORDER_STATE_NOT_ALLOWED', 409)), 'ok')
  // Only the definitive no-write 409s retire the journal; busy conflicts keep the key.
  assert.equal(definitiveNoWrite(new ApiError('ORDER_STATE_NOT_ALLOWED', 409)), true)
  assert.equal(definitiveNoWrite(new ApiError('ORDER_CONFIRM_DEADLINE_PASSED', 409)), true)
  assert.equal(definitiveNoWrite(new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409)), true)
  assert.equal(definitiveNoWrite(new ApiError('ORDER_OPERATION_BUSY', 409)), false)
  assert.equal(definitiveNoWrite(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), false)
})

// ---------------------------------------------------------------------------
// Controller: entry gating, validation, receipt/closed panels, replay flag, reason binding.
// ---------------------------------------------------------------------------

class FakeDeps implements OrderDecisionDeps {
  confirms: Array<{ orderId: string; internalNote: string | null }> = []
  rejectsIn: Array<{ orderId: string; reasonCode: RejectReasonCode; reasonText: string }> = []
  confirmReceipt: OrderDecisionReceipt = { ...confirmReceipt }
  rejectReceipt: OrderDecisionReceipt = { ...rejectReceipt }
  error: unknown = null
  pendingValue: { action: 'confirm' | 'reject'; orderId: string } | null = null
  failPending = false
  async confirm(orderId: string, internalNote: string | null): Promise<OrderDecisionReceipt> {
    this.confirms.push({ orderId, internalNote })
    if (this.error) throw this.error
    return this.confirmReceipt
  }
  async reject(orderId: string, reasonCode: RejectReasonCode, reasonText: string): Promise<OrderDecisionReceipt> {
    this.rejectsIn.push({ orderId, reasonCode, reasonText })
    if (this.error) throw this.error
    return this.rejectReceipt
  }
  pending() { if (this.failPending) throw new Error('WORKSPACE_PATH_MISMATCH'); return this.pendingValue }
}
const merchantContext = { workspace: 'merchant', merchantId: '958001', storeId: '958002' } as const

test('controller: entry gating and pending journal surface on load', () => {
  const deps = new FakeDeps()
  const controller = new OrderDecisionController(deps)
  controller.load(null)
  assert.equal(controller.getSnapshot().status, 'entry')
  deps.pendingValue = { action: 'reject', orderId: '777001' }
  controller.load(merchantContext)
  const state = controller.getSnapshot()
  assert.equal(state.status, 'form')
  assert.deepEqual(state.pending, { action: 'reject', orderId: '777001' })
  controller.dispose()
})

test('controller: confirm path requires the order id and forwards the note verbatim', async () => {
  const deps = new FakeDeps()
  const controller = new OrderDecisionController(deps)
  controller.load(merchantContext)
  await controller.submit()
  assert.equal(deps.confirms.length, 0, 'empty form never sends')
  assert.match(controller.getSnapshot().notice, /订单编号/)
  controller.setOrderId('777001')
  controller.setInternalNote(' 老客优先排班 ')
  await controller.submit()
  assert.deepEqual(deps.confirms, [{ orderId: '777001', internalNote: ' 老客优先排班 ' }], 'note is never trimmed or rewritten')
  assert.equal(controller.getSnapshot().status, 'receipt')
  assert.equal(controller.getSnapshot().receipt?.action, 'CONFIRM')
  controller.dispose()
})

test('controller: reject path binds reason code and text; unknown codes are ignored', async () => {
  const deps = new FakeDeps()
  const controller = new OrderDecisionController(deps)
  controller.load(merchantContext)
  controller.setOrderId('777001')
  controller.setAction('reject')
  await controller.submit()
  assert.equal(deps.rejectsIn.length, 0, 'blank reason blocks submit')
  assert.match(controller.getSnapshot().notice, /拒单原因/)
  controller.setReasonCode('SCHEDULE_CONFLICT')
  controller.setReasonCode('NOT_A_CODE')
  controller.setReasonText('当日排期冲突无法履约')
  await controller.submit()
  assert.deepEqual(deps.rejectsIn, [{ orderId: '777001', reasonCode: 'SCHEDULE_CONFLICT', reasonText: '当日排期冲突无法履约' }])
  assert.equal(controller.getSnapshot().receipt?.refundOrderId, '777004')
  controller.dispose()
})

test('controller: replayed flag mirrors the journaled same-action command', async () => {
  const deps = new FakeDeps()
  const controller = new OrderDecisionController(deps)
  controller.load(merchantContext)
  controller.setOrderId('777001')
  deps.pendingValue = { action: 'confirm', orderId: '777001' }
  await controller.submit()
  assert.equal(controller.getSnapshot().replayed, true)
  assert.equal(controller.getSnapshot().pending, null, 'journal cleared after the 200 receipt')
  // A journaled command for the OTHER action is not this submission's replay; one for the
  // same action on a DIFFERENT order is neither.
  controller.reset()
  deps.pendingValue = { action: 'reject', orderId: '777009' }
  controller.setAction('reject')
  controller.setOrderId('777001')
  controller.setReasonText('当日排期冲突无法履约')
  await controller.submit()
  assert.equal(controller.getSnapshot().replayed, false)
  // Same action on the same journaled order counts as this send's replay.
  controller.reset()
  deps.pendingValue = { action: 'reject', orderId: '777001' }
  await controller.submit()
  assert.equal(controller.getSnapshot().replayed, true)
  controller.dispose()
})

test('controller: 403/503 fail the whole page closed; 409s stay form notices', async () => {
  const deps = new FakeDeps()
  const controller = new OrderDecisionController(deps)
  controller.load(merchantContext)
  controller.setOrderId('777001')
  deps.error = new ApiError('COMMON_FORBIDDEN', 403)
  await controller.submit()
  assert.equal(controller.getSnapshot().status, 'closed')
  controller.reset()
  deps.error = new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)
  await controller.submit()
  assert.equal(controller.getSnapshot().status, 'closed')
  controller.reset()
  deps.error = new ApiError('ORDER_STATE_NOT_ALLOWED', 409)
  await controller.submit()
  const decided = controller.getSnapshot()
  assert.equal(decided.status, 'form')
  assert.match(decided.notice, /已接单、已拒单或已取消/)
  controller.dispose()
})

// ---------------------------------------------------------------------------
// Real wiring over a scripted transport: route/body/request id, slot retirement and
// replay journal semantics (23号 §5) including the hidden-receipt 401/403/404 case.
// ---------------------------------------------------------------------------
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', success: true, data } })
const failure = (code: string, statusCode: number) => ({ statusCode, data: { code, data: null } })
type Call = { method: string; path: string; requestId?: string; data?: Record<string, unknown> }

async function wiredApi(handlers: (call: Call) => { statusCode: number; data: unknown } | undefined) {
  const seen: Call[] = []
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
  const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
  const grant = { ...session, tokenType: 'Bearer', accessToken: 'unusable-test-token' }
  let uuidCall = 0
  const api = new ConsumerApi(async request => {
    if (request.path.endsWith('/attempts')) return ok({ attemptId: '301', attemptToken: 't', nextStep: 'PROVE_IDENTITY' })
    if (request.path.endsWith('/wechat-login')) return ok(grant)
    if (request.path.endsWith('/session')) return ok(session)
    const call: Call = { method: request.method, path: request.path, requestId: (request as { requestId?: string }).requestId, data: request.data }
    seen.push(call)
    return handlers(call) || failure('COMMON_DEPENDENCY_UNAVAILABLE', 503)
  }, store, async () => `00000000-0000-4000-8000-${String(++uuidCall).padStart(12, '0')}`)
  await api.startLogin(async () => ({ code: 'test-code' }))
  api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '958001', storeId: '958002' })
  const repository = new RealMerchantOrderRepository(api)
  return { api, repository, seen, store }
}

test('real repository wires the 45号 routes: strict bodies, journaled UUID, no coordinates', async () => {
  const { repository, seen, api } = await wiredApi(call => {
    if (call.method === 'POST' && call.path === '/api/v1/merchant/orders/777001/confirm') return ok({ ...confirmReceipt })
    if (call.method === 'POST' && call.path === '/api/v1/merchant/orders/777002/reject') return ok({ ...rejectReceipt, orderId: '777002' })
    return undefined
  })
  const confirmed = await repository.confirm(' 777001 ', null)
  assert.equal(confirmed.action, 'CONFIRM')
  const rejected = await repository.reject('777002', 'TEMPORARY_CLOSURE', '门店设备故障临时停业')
  assert.equal(rejected.refundOrderId, '777004')
  const confirmSent = seen.find(call => call.path === '/api/v1/merchant/orders/777001/confirm')!
  assert.equal(confirmSent.method, 'POST')
  assert.deepEqual(confirmSent.data, { expectedConfirmRound: 0 }, 'omitted note sends the round only — no merchantId/storeId')
  const rejectSent = seen.find(call => call.path === '/api/v1/merchant/orders/777002/reject')!
  assert.deepEqual(Object.keys(rejectSent.data!), ['expectedConfirmRound', 'reasonCode', 'reasonText'])
  assert.match(String(confirmSent.requestId), /^[0-9a-f-]{36}$/)
  // The family is POST-only and the form gate rejects bad ids/reasons before any send.
  const wrongMethod = await rejects(() => api.request({ method: 'GET', path: '/api/v1/merchant/orders/777001/confirm', data: { merchantId: '958001' } }, value => value))
  assert.equal((wrongMethod as Error).message, 'INVALID_PATH')
  const badOrder = await rejects(() => repository.confirm('0777', null))
  assert.equal((badOrder as Error).message, 'ORDER_FORM_INVALID')
  const badReason = await rejects(() => repository.reject('777002', 'OTHER', '1234'))
  assert.equal((badReason as Error).message, 'ORDER_FORM_INVALID')
  // Typed spaces are sent verbatim — only an untouched field omits the note (45号).
  await repository.confirm('777001', '   ')
  const blankNote = seen.filter(call => call.path === '/api/v1/merchant/orders/777001/confirm').at(-1)!
  assert.deepEqual(blankNote.data, { expectedConfirmRound: 0, internalNote: '   ' })
})

test('real repository: receipt orderId must echo the command target', async () => {
  const { repository } = await wiredApi(call => {
    if (call.method === 'POST') return ok({ ...confirmReceipt, orderId: '777009' })
    return undefined
  })
  const mismatched = await rejects(() => repository.confirm('777001', null))
  assert.equal((mismatched as Error).message, 'INVALID_RESPONSE')
})

test('real repository: definitive 409 retires the slot; a corrected payload gets a fresh requestId', async () => {
  let mode: 'decided' | 'ok' = 'decided'
  const { repository, seen } = await wiredApi(() => mode === 'decided' ? failure('ORDER_STATE_NOT_ALLOWED', 409) : ok({ ...confirmReceipt }))
  const decided = await rejects(() => repository.confirm('777001', null))
  assert.ok(decided instanceof ApiError)
  mode = 'ok'
  await repository.confirm('777001', null)
  const posts = seen.filter(call => call.method === 'POST' && call.path === '/api/v1/merchant/orders/777001/confirm')
  assert.equal(posts.length, 2)
  assert.notEqual(posts[0]!.requestId, posts[1]!.requestId)
  // A busy conflict is NOT a definitive no-write: the journal keeps the terminal key.
  let busyMode: 'busy' | 'ok' = 'busy'
  const busy = await wiredApi(() => busyMode === 'busy' ? failure('ORDER_OPERATION_BUSY', 409) : ok({ ...rejectReceipt, orderId: '777005' }))
  const conflicted = await rejects(() => busy.repository.reject('777005', 'OTHER', '并发占用重试 QA'))
  assert.ok(conflicted instanceof ApiError)
  assert.equal(busy.api.pendingCommands('merchant-reject:').length, 1, 'busy keeps the journal')
  busyMode = 'ok'
  const retried = await busy.repository.reject('777005', 'OTHER', '并发占用重试 QA')
  assert.equal(retried.action, 'REJECT')
  const busyPosts = busy.seen.filter(call => call.method === 'POST' && call.path === '/api/v1/merchant/orders/777005/reject')
  assert.equal(busyPosts.length, 2)
  assert.equal(busyPosts[0]!.requestId, busyPosts[1]!.requestId, '23号 busy retry keeps the UUID')
})

test('real repository: unknown outcome keeps the journal; retry replays the SAME requestId', async () => {
  let mode: 'unavailable' | 'ok' = 'unavailable'
  const { repository, seen } = await wiredApi(() => mode === 'unavailable' ? failure('COMMON_DEPENDENCY_UNAVAILABLE', 503) : ok({ ...rejectReceipt }))
  const first = await rejects(() => repository.reject('777001', 'OTHER', '网络丢失结果未确认 QA'))
  assert.ok(first instanceof ApiError)
  assert.equal((first as ApiError).statusCode, 503)
  // The journaled command is visible to the page before the retry.
  assert.deepEqual(repository.pending(), { action: 'reject', orderId: '777001' })
  // A different payload on the same slot locks (unknown result outranks a new intent).
  const locked = await rejects(() => repository.reject('777001', 'OTHER', '换一个原因文本 QA'))
  assert.equal((locked as Error).message, 'PENDING_WRITE_CHANGED')
  mode = 'ok'
  const replayed = await repository.reject('777001', 'OTHER', '网络丢失结果未确认 QA')
  assert.equal(replayed.refundOrderId, '777004')
  const posts = seen.filter(call => call.method === 'POST' && call.path === '/api/v1/merchant/orders/777001/reject')
  assert.equal(posts.length, 2)
  assert.equal(posts[0]!.requestId, posts[1]!.requestId, '23号 replay keeps the terminal UUID')
})

test('real repository: 401/403/404 keep the journal (hidden first receipt), 400 retires it', async () => {
  for (const [status, code] of [[403, 'COMMON_FORBIDDEN'], [404, 'COMMON_NOT_FOUND']] as const) {
    const { repository, api } = await wiredApi(() => failure(code, status))
    const denied = await rejects(() => repository.confirm('777001', null))
    assert.ok(denied instanceof ApiError)
    // 45号 replays re-prove the OWNER authority before any old receipt: these cannot prove
    // the earlier unknown send never decided, so the journaled requestId must survive.
    assert.equal(api.pendingCommands('merchant-confirm:').length, 1, `${status} keeps the journal`)
  }
  {
    const { repository, store } = await wiredApi(() => failure('COMMON_UNAUTHORIZED', 401))
    const expired = await rejects(() => repository.confirm('777001', null))
    assert.ok(expired instanceof StaleContextError)
    const saved = store.get('pet.c.pending.v1') as Record<string, unknown>
    assert.equal(Object.keys(saved).filter(slot => slot.startsWith('merchant-confirm:')).length, 1, '401 keeps the journaled slot in storage')
    // The controller folds the cleared workspace to the entry state, outcome unknown.
    const deps401 = new FakeDeps()
    deps401.error = expired
    const controller401 = new OrderDecisionController(deps401)
    controller401.load(merchantContext)
    controller401.setOrderId('777001')
    await controller401.submit()
    assert.equal(controller401.getSnapshot().status, 'entry')
    controller401.dispose()
  }
  const { repository, api } = await wiredApi(() => failure('COMMON_INVALID_ARGUMENT', 400))
  const invalid = await rejects(() => repository.confirm('777001', null))
  assert.ok(invalid instanceof ApiError)
  assert.equal(api.pendingCommands('merchant-confirm:').length, 0, '400 retires the journal')
})

test('real repository: pending() reads only the current merchant workspace; slots are action-scoped', async () => {
  const { repository, api } = await wiredApi(call =>
    call.path.endsWith('/confirm') ? failure('COMMON_DEPENDENCY_UNAVAILABLE', 503) : failure('COMMON_DEPENDENCY_UNAVAILABLE', 503))
  await rejects(() => repository.confirm('777001', null))
  await rejects(() => repository.reject('777002', 'OTHER', '两单同时未确认 QA'))
  assert.deepEqual(repository.pending(), { action: 'confirm', orderId: '777001' }, 'confirm is scanned first')
  api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '958009', storeId: '958010' })
  assert.equal(repository.pending(), null, 'another merchant\'s journal is not this page\'s business')
  api.scope.replace(null)
  assert.equal(repository.pending(), null)
})
