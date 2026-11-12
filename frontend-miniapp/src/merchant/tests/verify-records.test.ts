import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../shared/request'
import { WorkspaceScope } from '../../shared/workspace'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import {
  decodeVerificationRecordItem, decodeVerificationRecordPage,
  verifiedAtLabel, verificationAmountText, verificationCustomerInitial, verificationCustomerName,
  verificationOperatorClass, verificationOperatorText, verificationOrderNo, verificationServiceName,
  verificationRecordListAvailability, verificationRecordListMessage,
  type VerificationRecordPage,
} from '../verify-records/model'
import { RealVerificationRecordRepository } from '../verify-records/repository'
import { VerificationRecordListController } from '../verify-records/controller'

async function rejects(call: () => Promise<unknown> | unknown): Promise<unknown> {
  try { await call(); return new Error('NO_THROW') } catch (error) { return error }
}

const record = {
  verificationId: '777101', orderId: '777001', serviceName: '猫咪洗澡+美容', payAmount: '128.00',
  customerNickname: '李小姐', customerAvatarUrl: 'https://cdn.example.com/a.png',
  verifiedAt: '2030-01-01T02:32:00.000Z', operatorKind: 'STAFF', operatorName: '王小明',
}

// ---------------------------------------------------------------------------
// Model: strict decoder (CCR §3 exact keys), display copy, error mapping.
// ---------------------------------------------------------------------------

test('decode: item accepts the contract shape only; nullable projections read as null', () => {
  const item = decodeVerificationRecordItem(record)
  assert.equal(item.verificationId, '777101')
  assert.equal(item.operatorKind, 'STAFF')
  const owner = decodeVerificationRecordItem({ ...record, operatorKind: 'OWNER', operatorName: '店长' })
  assert.equal(owner.operatorKind, 'OWNER')
  // Service/customer/amount are nullable projections (order-side snapshot facts).
  const sparse = decodeVerificationRecordItem({
    ...record, serviceName: null, payAmount: null, customerNickname: null, customerAvatarUrl: null,
  })
  assert.equal(sparse.serviceName, null)
  assert.equal(sparse.payAmount, null)
  assert.equal(sparse.customerNickname, null)
  assert.equal(sparse.customerAvatarUrl, null)
  const page = decodeVerificationRecordPage({ items: [record], page: 1, pageSize: 20, total: 1 })
  assert.equal(page.total, 1)
  assert.equal(page.items[0]!.orderId, '777001')
})

test('decode rejects contract-foreign shapes (red-line keys never reach the UI)', async () => {
  for (const bad of [
    { ...record, extra: 1 },
    { ...record, payAmount: undefined },
    { ...record, phone: '13800000000' },
    { ...record, operatorId: '777001' },
    { ...record, operatorKind: 'ADMIN' },
    { ...record, operatorKind: null },
    { ...record, operatorName: '' },
    { ...record, operatorName: null },
    { ...record, orderId: '0777001' },
    { ...record, verificationId: '777101x' },
    { ...record, verifiedAt: '2030-01-01T02:32:00Z' },
    { ...record, verifiedAt: null },
    { ...record, payAmount: '128' },
    { ...record, payAmount: '12.3' },
    { ...record, serviceName: '' },
  ]) {
    assert.equal(((await rejects(() => decodeVerificationRecordItem(bad))) as Error).message, 'INVALID_RESPONSE', JSON.stringify(bad))
  }
  assert.equal(((await rejects(() => decodeVerificationRecordPage({ items: [], page: 0, pageSize: 20, total: 0 }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeVerificationRecordPage({ items: [record], page: 1, pageSize: 101, total: 1 }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeVerificationRecordPage({ items: new Array(3).fill(record), page: 1, pageSize: 2, total: 3 }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeVerificationRecordPage({ items: [record], page: 1, pageSize: 20, total: -1 }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeVerificationRecordPage({ items: [record], page: 1, pageSize: 20, total: 1, cursor: 'x' }))) as Error).message, 'INVALID_RESPONSE')
})

test('display copy: operator 店长/真名 split, customer placeholder, compact UTC+8 time', () => {
  const staff = decodeVerificationRecordItem(record)
  assert.equal(verificationOperatorText(staff), '王小明')
  assert.equal(verificationOperatorClass(staff), 'msvr-op msvr-op-staff')
  const owner = decodeVerificationRecordItem({ ...record, operatorKind: 'OWNER', operatorName: '店主真名' })
  // CCR §9.1: OWNER renders the fixed 店长 label client-side even if the payload deviates.
  assert.equal(verificationOperatorText(owner), '店长')
  assert.equal(verificationOperatorClass(owner), 'msvr-op msvr-op-owner')
  assert.equal(verificationOrderNo(staff), '订单 777001')
  assert.equal(verificationServiceName(staff), '猫咪洗澡+美容')
  assert.equal(verificationServiceName({ ...staff, serviceName: null }), '已下架服务')
  assert.equal(verificationAmountText(staff), '128.00')
  assert.equal(verificationAmountText({ ...staff, payAmount: null }), '--')
  assert.equal(verificationCustomerName(staff), '李小姐')
  assert.equal(verificationCustomerName({ ...staff, customerNickname: null }), '顾客')
  assert.equal(verificationCustomerInitial({ ...staff, customerNickname: null }), '客')
  assert.equal(verificationCustomerInitial(staff), '李')
  // Far dates render MM-DD HH:mm in UTC+8; the product clock ignores device settings.
  assert.equal(verifiedAtLabel('2030-01-01T02:32:00.000Z'), '01-01 10:32')
  // Today renders the 今天 prefix (fixed instant, no wall-clock race).
  const at = Date.now() - 1000
  const local = new Date(at + 8 * 60 * 60 * 1000)
  assert.equal(verifiedAtLabel(new Date(at).toISOString()),
    `今天 ${local.toISOString().slice(11, 16)}`)
})

test('error mapping: 403 reads 仅商家店长 (guidance, not fail-closed); 503 fails closed', () => {
  assert.match(verificationRecordListMessage(new ApiError('COMMON_FORBIDDEN', 403)), /仅商家店长可查看核销记录/)
  assert.match(verificationRecordListMessage(new ApiError('COMMON_UNAUTHORIZED', 401)), /登录已失效/)
  assert.match(verificationRecordListMessage(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), /暂时不可用/)
  assert.match(verificationRecordListMessage(new Error('WORKSPACE_PATH_MISMATCH')), /工作区已切换/)
  assert.match(verificationRecordListMessage(new Error('MERCHANT_ENTRY_REQUIRED')), /请从商家工作台进入核销记录/)
  assert.equal(verificationRecordListAvailability(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), 'closed')
  // 403/401 route to entry in the controller (staff landing this tab gets guidance),
  // so availability keeps them 'ok' here — only 503 is the closed bucket.
  assert.equal(verificationRecordListAvailability(new ApiError('COMMON_FORBIDDEN', 403)), 'ok')
  assert.equal(verificationRecordListAvailability(new ApiError('COMMON_UNAUTHORIZED', 401)), 'ok')
})

// ---------------------------------------------------------------------------
// Controller: paging with dedupe, 403/401 entry gating, retry, fail-closed 503.
// ---------------------------------------------------------------------------

class FakeDeps {
  queries: Array<{ merchantId: string; storeId: string; page: number; pageSize: number }> = []
  pages: Map<number, VerificationRecordPage> = new Map()
  error: unknown = null
  constructor(readonly scope: WorkspaceScope) {}
  async list(query: { merchantId: string; storeId: string; page: number; pageSize: number }): Promise<VerificationRecordPage> {
    this.queries.push(query)
    if (this.error) throw this.error
    const found = this.pages.get(query.page)
    if (!found) throw new Error('NO_PAGE_SCRIPTED')
    return found
  }
}

function paged(total: number, size = 20): Map<number, VerificationRecordPage> {
  const pages = new Map<number, VerificationRecordPage>()
  const count = Math.ceil(total / size)
  for (let index = 1; index <= count; index++) {
    const items = Array.from({ length: Math.min(size, total - (index - 1) * size) }, (_, offset) => ({
      ...record, verificationId: String(777100 + (index - 1) * size + offset + 1), operatorKind: 'STAFF' as const,
    }))
    pages.set(index, { items, page: index, pageSize: size, total })
  }
  return pages
}

function scopeWith(merchant = true) {
  const scope = new WorkspaceScope()
  scope.replace(merchant
    ? { userId: '101', workspace: 'merchant', merchantId: '958001', storeId: '958002' }
    : { userId: '101', workspace: 'consumer', merchantId: null, storeId: null })
  return scope
}

test('controller: single verified bucket — load queries page 1 with the workspace coordinates', async () => {
  const scope = scopeWith()
  const deps = new FakeDeps(scope)
  deps.pages = paged(1)
  const controller = new VerificationRecordListController(deps)
  await controller.load()
  assert.deepEqual(deps.queries, [{ merchantId: '958001', storeId: '958002', page: 1, pageSize: 20 }])
  assert.equal(controller.getSnapshot().status, 'ready')
  controller.dispose()
})

test('controller: loadMore pages with verificationId dedupe and stops at the total', async () => {
  const scope = scopeWith()
  const deps = new FakeDeps(scope)
  deps.pages = paged(45)
  const controller = new VerificationRecordListController(deps)
  await controller.load()
  assert.equal(controller.getSnapshot().items.length, 20)
  await controller.loadMore()
  await controller.loadMore()
  let state = controller.getSnapshot()
  assert.equal(state.items.length, 45)
  assert.equal(state.page, 3)
  assert.equal(new Set(state.items.map(item => item.verificationId)).size, 45, 'stable sort pages never duplicate a card')
  assert.deepEqual(deps.queries.map(query => query.page), [1, 2, 3])
  await controller.loadMore()
  assert.equal(deps.queries.length, 3, 'no fetch past the total')
  state = controller.getSnapshot()
  assert.equal(state.loadingMore, false)
  controller.dispose()
})

test('controller: entry without merchant coordinates; 401 and 403 (非 OWNER) also re-entry', async () => {
  const scope = scopeWith(false)
  const deps = new FakeDeps(scope)
  const controller = new VerificationRecordListController(deps)
  await controller.load()
  assert.equal(controller.getSnapshot().status, 'entry')
  controller.dispose()

  for (const error of [new ApiError('COMMON_UNAUTHORIZED', 401), new ApiError('COMMON_FORBIDDEN', 403)]) {
    const scope2 = scopeWith()
    const deps2 = new FakeDeps(scope2)
    deps2.error = error
    const controller2 = new VerificationRecordListController(deps2)
    await controller2.load()
    const state = controller2.getSnapshot()
    assert.equal(state.status, 'entry', String(error))
    assert.match(state.notice, error.statusCode === 403 ? /仅商家店长/ : /登录已失效/)
    controller2.dispose()
  }
})

test('controller: 503 fails the page closed; other faults stay retryable load-error then reload', async () => {
  const scope = scopeWith()
  const deps = new FakeDeps(scope)
  deps.error = new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)
  const controller = new VerificationRecordListController(deps)
  await controller.load()
  assert.equal(controller.getSnapshot().status, 'closed')
  controller.dispose()

  const scope2 = scopeWith()
  const deps2 = new FakeDeps(scope2)
  deps2.error = new ApiError('COMMON_INTERNAL_ERROR', 500)
  const controller2 = new VerificationRecordListController(deps2)
  await controller2.load()
  assert.equal(controller2.getSnapshot().status, 'load-error')
  // Failed-retry path: the same controller recovers once the fault clears.
  deps2.error = null
  deps2.pages = paged(1)
  await controller2.load()
  assert.equal(controller2.getSnapshot().status, 'ready')
  assert.equal(controller2.getSnapshot().items.length, 1)
  controller2.dispose()
})

test('controller: loadMore failures keep the list; 403 mid-pagination re-entries', async () => {
  const scope = scopeWith()
  const deps = new FakeDeps(scope)
  deps.pages = paged(25)
  const controller = new VerificationRecordListController(deps)
  await controller.load()
  deps.error = new ApiError('COMMON_INTERNAL_ERROR', 500)
  await controller.loadMore()
  let state = controller.getSnapshot()
  assert.equal(state.status, 'ready')
  assert.equal(state.items.length, 20)
  assert.match(state.notice, /加载更多失败/)
  deps.error = new ApiError('COMMON_FORBIDDEN', 403)
  await controller.loadMore()
  state = controller.getSnapshot()
  assert.equal(state.status, 'entry')
  assert.equal(state.items.length, 0)
  controller.dispose()
})

test('controller: workspace switch invalidates the list immediately', async () => {
  const scope = scopeWith()
  const deps = new FakeDeps(scope)
  deps.pages = paged(1)
  const controller = new VerificationRecordListController(deps)
  await controller.load()
  assert.equal(controller.getSnapshot().status, 'ready')
  scope.replace({ userId: '101', workspace: 'merchant', merchantId: '958009', storeId: '958010' })
  const state = controller.getSnapshot()
  assert.equal(state.status, 'entry')
  assert.equal(state.items.length, 0)
  controller.dispose()
})

// ---------------------------------------------------------------------------
// Real wiring over a scripted transport: client-side coordinate/query discipline.
// NOTE: the live GET /api/v1/merchant/verifications additionally needs the shared
// ConsumerApi path allowlist entry (outside this task's editable scope) — the checks
// below run before any transport call and hold regardless.
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
  return { api, repository: new RealVerificationRecordRepository(api), seen }
}

test('real repository: coordinates must match the workspace; paging is strictly family-shaped', async () => {
  const { repository } = await wiredApi(() => undefined)
  assert.equal(((await rejects(() => repository.list({ merchantId: '958009', storeId: '958002', page: 1, pageSize: 20 }))) as Error).message, 'WORKSPACE_PATH_MISMATCH')
  assert.equal(((await rejects(() => repository.list({ merchantId: '958001', storeId: '958010', page: 1, pageSize: 20 }))) as Error).message, 'WORKSPACE_PATH_MISMATCH')
  assert.equal(((await rejects(() => repository.list({ merchantId: '958001', storeId: '958002', page: 0, pageSize: 20 }))) as Error).message, 'INVALID_QUERY')
  assert.equal(((await rejects(() => repository.list({ merchantId: '958001', storeId: '958002', page: 1, pageSize: 19 }))) as Error).message, 'INVALID_QUERY')
})
