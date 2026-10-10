import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../shared/request'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import {
  decodeDetail, decodePage, decodeReceipt, decodeSummary, definitiveRefundNoWrite,
  MerchantRefundClient, type RefundApplicationDetail, type RefundApplicationSummary,
  type RefundDecisionReceipt,
} from '../../shared/merchant-refund-api'
import type { MerchantAdmission } from '../../shared/merchant-repositories'
import { WorkspaceScope } from '../../shared/workspace'
import { MerchantRefundController, merchantRefundMessage } from '../refund/controller'
import { merchantRefundDeps } from '../refund/repository'
import { canDecide, deadlinePassed, rejectReasonProblem, refundOwnerAccess, refundStatusTagClass, type RejectDraft } from '../refund/model'

async function rejects(call: () => Promise<unknown> | unknown): Promise<unknown> {
  try { await call(); return new Error('NO_THROW') } catch (error) { return error }
}

const summary: RefundApplicationSummary = {
  applicationId: '881001', applicationNo: '881002', orderId: '777001', status: 'PENDING_MERCHANT',
  applicationVersion: '0', reasonCode: 'QA_REASON', refundAmount: '128.00',
  createdAt: '2026-10-10T02:00:00.000Z', merchantDeadline: '2026-10-11T02:00:00.000Z',
}
const detail: RefundApplicationDetail = { ...summary, decidedAt: null, decisionId: null, refundOrderId: null }
const receipt: RefundDecisionReceipt = {
  orderId: '777001', applicationId: '881001', applicationStatus: 'APPROVED',
  applicationVersion: '1', merchantDeadline: '2026-10-11T02:00:00.000Z',
  decidedAt: '2026-10-10T03:00:00.000Z', decisionId: '881003',
}

// ---------------------------------------------------------------------------
// Shared client decoders: contract-56 shapes all-or-nothing.
// ---------------------------------------------------------------------------

test('decode: summary/detail/page/receipt accept the exact wire fields', () => {
  assert.deepEqual(decodeSummary({ ...summary }), { ...summary })
  assert.deepEqual(decodeDetail({ ...detail }), { ...detail })
  const page = decodePage({ page: 1, pageSize: 20, total: 1, items: [{ ...summary }] })
  assert.equal(page.total, 1)
  assert.deepEqual(decodeReceipt({ ...receipt }), { ...receipt })
})

test('decode rejects contract-foreign shapes', async () => {
  assert.equal(((await rejects(() => decodeSummary({ ...summary, buyerPhone: '138' }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeSummary({ ...summary, refundAmount: '128' }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeSummary({ ...summary, status: 'REFUNDING' }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeDetail({ ...detail, decidedAt: '2026-10-10T03:00:00' }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodePage({ page: 0, pageSize: 20, total: 0, items: [] }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodePage({ page: 1, pageSize: 101, total: 0, items: [] }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeReceipt({ ...receipt, applicationStatus: 'AUTO_APPROVED' }))) as Error).message, 'INVALID_RESPONSE')
})

test('only the definitive no-write 409s retire the journal; busy/dependency keep the key', () => {
  assert.equal(definitiveRefundNoWrite(new ApiError('REFUND_APPLICATION_ALREADY_PROCESSED', 409)), true)
  assert.equal(definitiveRefundNoWrite(new ApiError('REFUND_MERCHANT_DEADLINE_PASSED', 409)), true)
  assert.equal(definitiveRefundNoWrite(new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409)), true)
  assert.equal(definitiveRefundNoWrite(new ApiError('ORDER_OPERATION_BUSY', 409)), false)
  assert.equal(definitiveRefundNoWrite(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), false)
  assert.equal(definitiveRefundNoWrite(new ApiError('REFUND_MERCHANT_REASON_REQUIRED', 400)), false)
})

// ---------------------------------------------------------------------------
// Model: admission gate, decision window, reason bounds, status classes.
// ---------------------------------------------------------------------------

const admission = (over: Partial<MerchantAdmission>): MerchantAdmission => ({
  merchantId: '958001', storeId: '958002', membershipKind: 'OWNER', admission: 'ALLOWED',
  checkedAt: '2026-10-10T00:00:00.000Z', authzVersion: '0123456789abcdef',
  facts: { application: { status: 'APPROVED' }, signing: { status: 'SIGNED' }, storeStatus: 'ACTIVE', merchantStatus: 'ACTIVE', staffEnabled: null },
  allowedActions: ['merchant.aftersale.read', 'merchant.order.read', 'merchant.penalty.read', 'merchant.refund.handle', 'merchant.refund.read', 'merchant.schedule.manage', 'merchant.service.manage', 'merchant.staff.manage'],
  reasonCodes: [], nextSteps: [], ...over,
})

test('model: OWNER + refund.read gates readable; FROZEN keeps read-only; wrong store never passes', () => {
  const allowed = refundOwnerAccess(admission({}), '958001', '958002')
  assert.deepEqual(allowed, { readable: true, writable: true, frozen: false })
  const frozen = refundOwnerAccess(admission({ admission: 'LIMITED', reasonCodes: ['STORE_FROZEN'], allowedActions: ['merchant.aftersale.read', 'merchant.order.read', 'merchant.penalty.appeal', 'merchant.penalty.read', 'merchant.refund.read'], facts: { application: { status: 'APPROVED' }, signing: { status: 'SIGNED' }, storeStatus: 'FROZEN', merchantStatus: 'ACTIVE', staffEnabled: null } }), '958001', '958002')
  assert.deepEqual(frozen, { readable: true, writable: false, frozen: true })
  const foreign = refundOwnerAccess(admission({}), '958001', '958009')
  assert.equal(foreign.readable, false)
  const withoutAction = refundOwnerAccess(admission({ allowedActions: ['merchant.order.read'] }), '958001', '958002')
  assert.equal(withoutAction.readable, false)
})

test('model: decide window is PENDING_MERCHANT before the deadline only', () => {
  const pending = { ...detail }
  assert.equal(canDecide(pending, true, Date.parse('2026-10-10T10:00:00.000Z')), true)
  assert.equal(canDecide(pending, false, Date.parse('2026-10-10T10:00:00.000Z')), false, 'frozen/read-only never decides')
  assert.equal(canDecide(pending, true, Date.parse('2026-10-11T02:00:00.000Z')), false, 'deadline reached hands over to the system')
  assert.equal(deadlinePassed(pending, Date.parse('2026-10-11T02:00:01.000Z')), true)
  assert.equal(deadlinePassed({ ...detail, status: 'APPROVED' }, Date.parse('2026-10-11T02:00:01.000Z')), false)
  assert.equal(canDecide({ ...detail, status: 'REJECTED' }, true, Date.parse('2026-10-10T10:00:00.000Z')), false)
  assert.equal(refundStatusTagClass('PENDING_MERCHANT'), 'mrf-tag')
  assert.equal(refundStatusTagClass('APPROVED'), 'mrf-tag mrf-tag-approved')
  assert.equal(refundStatusTagClass('REJECTED'), 'mrf-tag mrf-tag-closed')
})

test('model: reject reason 1..500 code points, non-blank', () => {
  assert.equal(rejectReasonProblem('服务人员已按约到店等待'), null)
  assert.equal(rejectReasonProblem('狗'.repeat(500)), null)
  assert.notEqual(rejectReasonProblem(''), null)
  assert.notEqual(rejectReasonProblem('   '), null)
  assert.notEqual(rejectReasonProblem('a'.repeat(501)), null)
})

// ---------------------------------------------------------------------------
// Controller: entry gating, list/detail load, decisions, conflicts, read-only.
// ---------------------------------------------------------------------------

class FakeDeps implements ReturnType<typeof merchantRefundDeps> {
  admissions: MerchantAdmission[] = [admission({})]
  lists: Array<{ merchantId: string; storeId: string; page: number; pageSize: number }> = []
  details: string[] = []
  approves: string[] = []
  rejectsIn: Array<{ applicationId: string; reasonText: string }> = []
  pageValue = { page: 1, pageSize: 20, total: 1, items: [{ ...summary }] }
  detailValue = { ...detail }
  receiptValue = { ...receipt }
  error: unknown = null
  pendingValue: { action: 'approve' | 'reject'; applicationId: string; reasonText: string | null } | null = null
  drafts = new Map<string, RejectDraft | null>()
  async admission() { return this.admissions[0]! }
  async list(query: { merchantId: string; storeId: string; page: number; pageSize: number }) { this.lists.push(query); if (this.error) throw this.error; return this.pageValue }
  async detail(applicationId: string) { this.details.push(applicationId); if (this.error) throw this.error; return this.detailValue }
  async approve(applicationId: string) { this.approves.push(applicationId); if (this.error) throw this.error; return this.receiptValue }
  async reject(applicationId: string, reasonText: string) { this.rejectsIn.push({ applicationId, reasonText }); if (this.error) throw this.error; return { ...this.receiptValue, applicationStatus: 'REJECTED' as const } }
  pending() { return this.pendingValue }
  loadDraft(applicationId: string) { return this.drafts.get(applicationId) ?? null }
  saveDraft(applicationId: string, draft: RejectDraft | null) { this.drafts.set(applicationId, draft) }
}
const merchantContext = { workspace: 'merchant', merchantId: '958001', storeId: '958002' } as const

test('controller: entry gating on missing workspace; list load carries the coordinates', async () => {
  const deps = new FakeDeps()
  const scope = new WorkspaceScope()
  const controller = new MerchantRefundController(deps, scope)
  await controller.load(null)
  assert.equal(controller.getSnapshot().status, 'entry')
  await controller.load({ workspace: 'consumer', merchantId: '958001', storeId: '958002' })
  assert.equal(controller.getSnapshot().status, 'entry')
  await controller.load(merchantContext)
  const state = controller.getSnapshot()
  assert.equal(state.status, 'ready')
  assert.equal(state.items.length, 1)
  assert.deepEqual(deps.lists, [{ merchantId: '958001', storeId: '958002', page: 1, pageSize: 20 }])
  // A workspace switch invalidates the page.
  scope.replace({ userId: '101', workspace: 'merchant', merchantId: '958001', storeId: '958099' })
  assert.equal(controller.getSnapshot().status, 'entry')
  controller.dispose()
})

test('controller: detail load; approve submits and re-reads the current application', async () => {
  const deps = new FakeDeps()
  const controller = new MerchantRefundController(deps, new WorkspaceScope(), '881001')
  await controller.load(merchantContext)
  let state = controller.getSnapshot()
  assert.equal(state.status, 'ready')
  assert.equal(state.detail!.applicationId, '881001')
  assert.equal(controller.canDecide(Date.parse('2026-10-10T10:00:00.000Z')), true)
  await controller.submit('approve')
  assert.deepEqual(deps.approves, ['881001'])
  state = controller.getSnapshot()
  assert.equal(state.busy, false)
  assert.equal(state.receipt!.applicationStatus, 'APPROVED')
  assert.deepEqual(deps.details.slice(-1), ['881001'], 'display re-reads after the receipt')
  controller.dispose()
})

test('controller: reject requires a reason; the draft is persisted and cleared on success', async () => {
  const deps = new FakeDeps()
  const controller = new MerchantRefundController(deps, new WorkspaceScope(), '881001')
  await controller.load(merchantContext)
  await controller.submit('reject')
  assert.equal(deps.rejectsIn.length, 0, 'blank reason blocks submit')
  assert.match(controller.getSnapshot().notice, /拒绝原因/)
  controller.setDraft({ reasonText: '服务人员已按约到店等待，暂不同意退款' })
  assert.equal(deps.drafts.get('881001')!.reasonText, '服务人员已按约到店等待，暂不同意退款')
  await controller.submit('reject')
  assert.deepEqual(deps.rejectsIn, [{ applicationId: '881001', reasonText: '服务人员已按约到店等待，暂不同意退款' }])
  assert.equal(deps.drafts.get('881001'), null, 'success clears the reject draft')
  controller.dispose()
})

test('controller: journaled unknown outcome replays its exact payload; foreign pending locks out', async () => {
  const deps = new FakeDeps()
  const controller = new MerchantRefundController(deps, new WorkspaceScope(), '881001')
  await controller.load(merchantContext)
  deps.pendingValue = { action: 'reject', applicationId: '881001', reasonText: '原样重试 QA' }
  controller.setDraft({ reasonText: '后来修改的内容' })
  await controller.submit('reject')
  assert.deepEqual(deps.rejectsIn, [{ applicationId: '881001', reasonText: '原样重试 QA' }], 'replay never rewrites the journaled payload')
  deps.pendingValue = { action: 'approve', applicationId: '881009', reasonText: null }
  await controller.submit('approve')
  assert.equal(deps.approves.length, 0, 'a pending command for another application is not this page business')
  assert.match(controller.getSnapshot().notice, /PENDING_WRITE_CHANGED|已变化/)
  controller.dispose()
})

test('controller: definitive 409 retires and re-reads; 403 fails the page closed', async () => {
  const deps = new FakeDeps()
  const controller = new MerchantRefundController(deps, new WorkspaceScope(), '881001')
  await controller.load(merchantContext)
  deps.error = new ApiError('REFUND_MERCHANT_DEADLINE_PASSED', 409)
  await controller.submit('approve')
  let state = controller.getSnapshot()
  assert.match(state.notice, /24小时/)
  assert.equal(state.busy, false)
  assert.deepEqual(deps.details.slice(-1), ['881001'], 'conflict re-reads the current application')
  deps.error = new ApiError('COMMON_FORBIDDEN', 403)
  await controller.submit('approve')
  state = controller.getSnapshot()
  assert.equal(state.status, 'denied')
  controller.dispose()
})

test('controller: frozen admission stays read-only (no decision reaches the wire)', async () => {
  const deps = new FakeDeps()
  deps.admissions = [admission({ admission: 'LIMITED', reasonCodes: ['STORE_FROZEN'], allowedActions: ['merchant.penalty.read', 'merchant.refund.read'], facts: { application: { status: 'APPROVED' }, signing: { status: 'SIGNED' }, storeStatus: 'FROZEN', merchantStatus: 'ACTIVE', staffEnabled: null } })]
  const controller = new MerchantRefundController(deps, new WorkspaceScope(), '881001')
  await controller.load(merchantContext)
  assert.equal(controller.getSnapshot().frozen, true)
  await controller.submit('approve')
  assert.equal(deps.approves.length, 0, 'FROZEN never decides client-side; the kernel stays the final gate')
  assert.match(controller.getSnapshot().notice, /只读/)
  controller.dispose()
})

test('messages: per-code Chinese copy and entry guidance', () => {
  assert.match(merchantRefundMessage(new ApiError('REFUND_MERCHANT_REASON_REQUIRED', 400)), /拒绝原因/)
  assert.match(merchantRefundMessage(new ApiError('REFUND_MERCHANT_DEADLINE_PASSED', 409)), /24小时/)
  assert.match(merchantRefundMessage(new ApiError('REFUND_APPLICATION_ALREADY_PROCESSED', 409)), /已处理/)
  assert.match(merchantRefundMessage(new ApiError('COMMON_UNAUTHORIZED', 401)), /重新登录/)
  assert.match(merchantRefundMessage(new Error('MERCHANT_ENTRY_REQUIRED')), /商家工作台/)
  assert.match(merchantRefundMessage(new Error('READ_ONLY')), /只读/)
})

// ---------------------------------------------------------------------------
// Real wiring over a scripted transport: routes/bodies/request id, coordinate gate,
// slot retirement and replay journal semantics (23号 §5) incl. hidden receipts.
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
  const client = new MerchantRefundClient(api)
  return { api, client, deps: merchantRefundDeps(client), seen, store }
}

test('real wiring: list/detail carry the workspace coordinates; decisions address the application only', async () => {
  const { client, seen } = await wiredApi(call => {
    if (call.method === 'GET' && call.path === '/api/v1/merchant/refund-applications') return ok({ page: 1, pageSize: 20, total: 1, items: [{ ...summary }] })
    if (call.method === 'GET' && call.path === '/api/v1/merchant/refund-applications/881001') return ok({ ...detail })
    if (call.method === 'POST' && call.path === '/api/v1/merchant/refund-applications/881001/approve') return ok({ ...receipt })
    if (call.method === 'POST' && call.path === '/api/v1/merchant/refund-applications/881001/reject') return ok({ ...receipt, applicationStatus: 'REJECTED' })
    return undefined
  })
  const page = await client.list({ merchantId: '958001', storeId: '958002', page: 1, pageSize: 20 })
  assert.equal(page.total, 1)
  const listCall = seen.find(call => call.method === 'GET' && call.path === '/api/v1/merchant/refund-applications')!
  assert.deepEqual(listCall.data, { merchantId: '958001', storeId: '958002', page: 1, pageSize: 20 })
  const read = await client.detail('881001')
  assert.equal(read.applicationId, '881001')
  const detailCall = seen.find(call => call.method === 'GET' && call.path === '/api/v1/merchant/refund-applications/881001')!
  assert.deepEqual(detailCall.data, { merchantId: '958001', storeId: '958002' })
  await client.approve('881001')
  const approveCall = seen.find(call => call.method === 'POST' && call.path === '/api/v1/merchant/refund-applications/881001/approve')!
  assert.deepEqual(approveCall.data, {}, 'approve sends an empty object — no amount, no coordinates')
  assert.match(String(approveCall.requestId), /^[0-9a-f-]{36}$/)
  await client.reject('881001', '服务人员已按约到店等待')
  const rejectCall = seen.filter(call => call.method === 'POST' && call.path === '/api/v1/merchant/refund-applications/881001/reject').at(-1)!
  assert.deepEqual(rejectCall.data, { reasonText: '服务人员已按约到店等待' })
})

test('real wiring: foreign coordinates never leave the client; malformed ids and reasons fail closed', async () => {
  const { client } = await wiredApi(() => undefined)
  const foreign = await rejects(() => client.list({ merchantId: '958001', storeId: '958099', page: 1, pageSize: 20 }))
  assert.equal((foreign as Error).message, 'WORKSPACE_PATH_MISMATCH')
  const badPage = await rejects(() => client.list({ merchantId: '958001', storeId: '958002', page: 0, pageSize: 20 }))
  assert.equal((badPage as Error).message, 'INVALID_QUERY')
  const badId = await rejects(() => client.approve('0881001'))
  assert.equal((badId as Error).message, 'INVALID_RESPONSE')
  const blankReason = await rejects(() => client.reject('881001', '   '))
  assert.equal((blankReason as Error).message, 'REJECT_REASON_INVALID')
  const longReason = await rejects(() => client.reject('881001', 'a'.repeat(501)))
  assert.equal((longReason as Error).message, 'REJECT_REASON_INVALID')
})

test('real wiring: definitive 409 retires the slot with a fresh key; unknown outcome keeps the journal', async () => {
  let mode: 'deadline' | 'ok' = 'deadline'
  const { client, seen, store } = await wiredApi(call =>
    call.method === 'POST' && call.path === '/api/v1/merchant/refund-applications/881001/approve'
      ? mode === 'deadline' ? failure('REFUND_MERCHANT_DEADLINE_PASSED', 409) : ok({ ...receipt })
      : undefined)
  const decided = await rejects(() => client.approve('881001'))
  assert.ok(decided instanceof ApiError)
  assert.equal(Object.keys(store.get('pet.c.pending.v1') as Record<string, unknown>).filter(key => key.startsWith('merchant-refund:')).length, 0, 'definitive conflict retires the slot')
  mode = 'ok'
  await client.approve('881001')
  const posts = seen.filter(call => call.method === 'POST' && call.path === '/api/v1/merchant/refund-applications/881001/approve')
  assert.equal(posts.length, 2)
  assert.notEqual(posts[0]!.requestId, posts[1]!.requestId, 'a corrected payload mints a fresh key')
  // An unknown outcome (503) keeps the journaled command; the next approve replays the same id.
  let unknownMode: 'unknown' | 'ok' = 'unknown'
  const unknown = await wiredApi(call =>
    call.method === 'POST' && call.path === '/api/v1/merchant/refund-applications/881001/approve'
      ? unknownMode === 'unknown' ? failure('COMMON_DEPENDENCY_UNAVAILABLE', 503) : ok({ ...receipt })
      : undefined)
  await rejects(() => unknown.client.approve('881001'))
  unknownMode = 'ok'
  await unknown.client.approve('881001')
  const unknownPosts = unknown.seen.filter(call => call.method === 'POST' && call.path === '/api/v1/merchant/refund-applications/881001/approve')
  assert.equal(unknownPosts[0]!.requestId, unknownPosts[1]!.requestId, 'the unknown outcome replays the original X-Request-Id')
  // A hidden first receipt (403 on replay) keeps the journal too.
  let hiddenMode: 'hidden' | 'ok' = 'hidden'
  const hidden = await wiredApi(call =>
    call.method === 'POST' && call.path === '/api/v1/merchant/refund-applications/881001/approve'
      ? hiddenMode === 'hidden' ? failure('COMMON_FORBIDDEN', 403) : ok({ ...receipt })
      : undefined)
  await rejects(() => hidden.client.approve('881001'))
  assert.equal(Object.keys(hidden.store.get('pet.c.pending.v1') as Record<string, unknown>).filter(key => key.startsWith('merchant-refund:')).length, 1, 'identity rejections never disprove an earlier unknown write')
})
