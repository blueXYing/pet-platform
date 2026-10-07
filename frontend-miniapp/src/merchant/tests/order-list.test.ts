import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../shared/request'
import { WorkspaceScope } from '../../shared/workspace'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import {
  decodeMerchantOrderPage, decodeMerchantOrderSummary, merchantAppointmentWindow,
  merchantDisplayStatusLabels, merchantOrderListAvailability, merchantOrderListMessage,
  merchantOrderTimeText, type MerchantOrderPage,
} from '../order/model'
import { RealMerchantOrderListRepository } from '../order/repository'
import { MerchantOrderListController } from '../order/list-controller'

async function rejects(call: () => Promise<unknown> | unknown): Promise<unknown> {
  try { await call(); return new Error('NO_THROW') } catch (error) { return error }
}

const summary = {
  orderId: '777001', orderNo: '2001', displayStatus: 'PENDING_CONFIRM', payAmount: '128.00',
  appointmentStart: '2030-01-01T02:00:00.000Z', appointmentEnd: '2030-01-01T03:30:00.000Z',
  paidAt: '2030-01-01T01:00:00.000Z',
}

// ---------------------------------------------------------------------------
// Model: page/summary decoders (exact contract keys), display copy, error mapping.
// ---------------------------------------------------------------------------

test('decode: page and summary accept the contract shape only', () => {
  const page = decodeMerchantOrderPage({ items: [summary], page: 1, pageSize: 20, total: 1 })
  assert.equal(page.total, 1)
  assert.equal(page.items[0]!.orderNo, '2001')
  assert.equal(page.items[0]!.displayStatus, 'PENDING_CONFIRM')
  // Nullable tails read as null (unpaid orders have no paidAt).
  const unpaid = decodeMerchantOrderSummary({ ...summary, displayStatus: 'PENDING_PAYMENT', paidAt: null })
  assert.equal(unpaid.paidAt, null)
  assert.equal(unpaid.displayStatus, 'PENDING_PAYMENT')
})

test('decode rejects contract-foreign shapes', async () => {
  for (const bad of [
    { ...summary, extra: 1 },
    { ...summary, paidAt: undefined },
    { ...summary, displayStatus: 'PENDING' },
    { ...summary, displayStatus: null },
    { ...summary, orderId: '0777001' },
    { ...summary, payAmount: '128' },
    { ...summary, payAmount: '12.3' },
    { ...summary, appointmentStart: '2030-01-01T02:00:00Z' },
  ]) {
    assert.equal(((await rejects(() => decodeMerchantOrderSummary(bad))) as Error).message, 'INVALID_RESPONSE', JSON.stringify(bad))
  }
  assert.equal(((await rejects(() => decodeMerchantOrderPage({ items: [], page: 0, pageSize: 20, total: 0 }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeMerchantOrderPage({ items: [summary], page: 1, pageSize: 101, total: 1 }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeMerchantOrderPage({ items: new Array(3).fill(summary), page: 1, pageSize: 2, total: 3 }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeMerchantOrderPage({ items: [summary], page: 1, pageSize: 20, total: -1 }))) as Error).message, 'INVALID_RESPONSE')
})

test('display copy: merchant-side PENDING_CONFIRM reads 待接单; times render UTC+8', () => {
  assert.equal(merchantDisplayStatusLabels.PENDING_CONFIRM, '待接单')
  assert.equal(merchantDisplayStatusLabels.PENDING_SERVICE, '待服务')
  assert.equal(merchantOrderTimeText('2030-01-01T01:00:00.000Z'), '2030-01-01 09:00')
  assert.equal(merchantOrderTimeText(null), '')
  const item = decodeMerchantOrderSummary(summary)
  assert.equal(merchantAppointmentWindow(item), '2030-01-01 10:00 ~ 2030-01-01 11:30')
  assert.equal(merchantAppointmentWindow({ ...item, appointmentStart: null, appointmentEnd: null }), '时间待定')
})

test('error mapping: 403 stays one anti-enumeration copy; 403/503 fail closed', () => {
  const forbidden = merchantOrderListMessage(new ApiError('COMMON_FORBIDDEN', 403))
  assert.match(forbidden, /无权查看该门店订单/)
  assert.match(merchantOrderListMessage(new ApiError('COMMON_UNAUTHORIZED', 401)), /登录已失效/)
  assert.match(merchantOrderListMessage(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), /暂时不可用/)
  assert.match(merchantOrderListMessage(new Error('WORKSPACE_PATH_MISMATCH')), /工作区已切换/)
  assert.equal(merchantOrderListAvailability(new ApiError('COMMON_FORBIDDEN', 403)), 'closed')
  assert.equal(merchantOrderListAvailability(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), 'closed')
  assert.equal(merchantOrderListAvailability(new ApiError('COMMON_UNAUTHORIZED', 401)), 'ok')
})

// ---------------------------------------------------------------------------
// Controller: tabs, paging, dedupe, entry gating, fail-closed classification.
// ---------------------------------------------------------------------------

class FakeDeps {
  queries: Array<{ merchantId: string; storeId: string; displayStatus?: string; page: number; pageSize: number }> = []
  pages: Map<number, MerchantOrderPage> = new Map()
  error: unknown = null
  constructor(readonly scope: WorkspaceScope) {}
  async list(query: { merchantId: string; storeId: string; displayStatus?: string; page: number; pageSize: number }): Promise<MerchantOrderPage> {
    this.queries.push(query)
    if (this.error) throw this.error
    const found = this.pages.get(query.page)
    if (!found) throw new Error('NO_PAGE_SCRIPTED')
    return found
  }
}

function paged(total: number, size = 20): Map<number, MerchantOrderPage> {
  const pages = new Map<number, MerchantOrderPage>()
  const count = Math.ceil(total / size)
  for (let index = 1; index <= count; index++) {
    const items = Array.from({ length: Math.min(size, total - (index - 1) * size) }, (_, offset) => ({
      ...summary, orderId: String(777000 + (index - 1) * size + offset + 1), orderNo: String(2000 + (index - 1) * size + offset + 1),
      displayStatus: 'PENDING_CONFIRM' as const,
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

test('controller: default tab is 待接单 (displayStatus=PENDING_CONFIRM); 全部 omits the filter', async () => {
  const scope = scopeWith()
  const deps = new FakeDeps(scope)
  deps.pages = paged(1)
  const controller = new MerchantOrderListController(deps)
  await controller.load()
  assert.deepEqual(deps.queries, [{
    merchantId: '958001', storeId: '958002', displayStatus: 'PENDING_CONFIRM', page: 1, pageSize: 20,
  }])
  assert.equal(controller.getSnapshot().status, 'ready')
  controller.chooseTab(null)
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.deepEqual(deps.queries.at(-1), { merchantId: '958001', storeId: '958002', page: 1, pageSize: 20 })
  controller.dispose()
})

test('controller: loadMore pages with dedupe and stops at the total', async () => {
  const scope = scopeWith()
  const deps = new FakeDeps(scope)
  deps.pages = paged(45)
  const controller = new MerchantOrderListController(deps)
  await controller.load()
  assert.equal(controller.getSnapshot().items.length, 20)
  await controller.loadMore()
  await controller.loadMore()
  let state = controller.getSnapshot()
  assert.equal(state.items.length, 45)
  assert.equal(state.page, 3)
  assert.equal(new Set(state.items.map(item => item.orderId)).size, 45, 'stable sort pages never duplicate a card')
  assert.deepEqual(deps.queries.map(query => query.page), [1, 2, 3])
  await controller.loadMore()
  assert.equal(deps.queries.length, 3, 'no fetch past the total')
  state = controller.getSnapshot()
  assert.equal(state.loadingMore, false)
  controller.dispose()
})

test('controller: entry without merchant coordinates; 401 also re-entries', async () => {
  const scope = scopeWith(false)
  const deps = new FakeDeps(scope)
  const controller = new MerchantOrderListController(deps)
  await controller.load()
  assert.equal(controller.getSnapshot().status, 'entry')
  controller.dispose()

  const scope2 = scopeWith()
  const deps2 = new FakeDeps(scope2)
  deps2.error = new ApiError('COMMON_UNAUTHORIZED', 401)
  const controller2 = new MerchantOrderListController(deps2)
  await controller2.load()
  assert.equal(controller2.getSnapshot().status, 'entry')
  controller2.dispose()
})

test('controller: 403/503 fail the page closed; other faults stay retryable load-error', async () => {
  for (const [error, expected] of [
    [new ApiError('COMMON_FORBIDDEN', 403), 'closed'],
    [new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503), 'closed'],
    [new ApiError('COMMON_INTERNAL_ERROR', 500), 'load-error'],
  ] as const) {
    const scope = scopeWith()
    const deps = new FakeDeps(scope)
    deps.error = error
    const controller = new MerchantOrderListController(deps)
    await controller.load()
    assert.equal(controller.getSnapshot().status, expected, String(error))
    controller.dispose()
  }
})

test('controller: workspace switch invalidates the list immediately', async () => {
  const scope = scopeWith()
  const deps = new FakeDeps(scope)
  deps.pages = paged(1)
  const controller = new MerchantOrderListController(deps)
  await controller.load()
  assert.equal(controller.getSnapshot().status, 'ready')
  scope.replace({ userId: '101', workspace: 'merchant', merchantId: '958009', storeId: '958010' })
  const state = controller.getSnapshot()
  assert.equal(state.status, 'entry')
  assert.equal(state.items.length, 0)
  controller.dispose()
})

// ---------------------------------------------------------------------------
// Real wiring over a scripted transport: GET query shape, path discipline, coordinates.
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
  return { api, repository: new RealMerchantOrderListRepository(api), seen }
}

test('real repository: list GET carries the merchant coordinates and the tab filter only', async () => {
  const { repository, seen } = await wiredApi(call => {
    if (call.method === 'GET' && call.path === '/api/v1/merchant/orders') {
      return ok({ items: [summary], page: 1, pageSize: 20, total: 1 })
    }
    return undefined
  })
  const page = await repository.list({ merchantId: '958001', storeId: '958002', displayStatus: 'PENDING_CONFIRM', page: 1, pageSize: 20 })
  assert.equal(page.total, 1)
  const sent = seen.find(call => call.path === '/api/v1/merchant/orders')!
  assert.equal(sent.method, 'GET')
  assert.deepEqual(sent.data, {
    merchantId: '958001', storeId: '958002', displayStatus: 'PENDING_CONFIRM', page: 1, pageSize: 20,
  })
  assert.equal(sent.requestId, undefined, 'GET is not an idempotent write — no X-Request-Id')
  const all = await repository.list({ merchantId: '958001', storeId: '958002', page: 1, pageSize: 20 })
  assert.deepEqual(seen.at(-1)!.data, { merchantId: '958001', storeId: '958002', page: 1, pageSize: 20 }, '全部 tab omits displayStatus')
  assert.equal(all.items.length, 1)
})

test('real repository: coordinates must match the workspace; wrong method/path fail closed', async () => {
  const { repository, api } = await wiredApi(() => undefined)
  assert.equal(((await rejects(() => repository.list({ merchantId: '958009', storeId: '958002', page: 1, pageSize: 20 }))) as Error).message, 'WORKSPACE_PATH_MISMATCH')
  assert.equal(((await rejects(() => repository.list({ merchantId: '958001', storeId: '958010', page: 1, pageSize: 20 }))) as Error).message, 'WORKSPACE_PATH_MISMATCH')
  assert.equal(((await rejects(() => repository.list({ merchantId: '958001', storeId: '958002', page: 0, pageSize: 20 }))) as Error).message, 'INVALID_QUERY')
  assert.equal(((await rejects(() => repository.list({ merchantId: '958001', storeId: '958002', page: 1, pageSize: 19 }))) as Error).message, 'INVALID_QUERY')
  // Foreign methods on the exact path are not the list family.
  assert.equal(((await rejects(() => api.request({ method: 'POST', path: '/api/v1/merchant/orders', data: { merchantId: '958001' } }, value => value))) as Error).message, 'INVALID_PATH')
})

test('real repository: echo mismatch and foreign filter results fail closed', async () => {
  const { repository } = await wiredApi(call => {
    if (call.method === 'GET' && call.path === '/api/v1/merchant/orders') {
      return ok({ items: [{ ...summary, displayStatus: 'PENDING_SERVICE' }], page: 2, pageSize: 20, total: 1 })
    }
    return undefined
  })
  const wrongTab = await rejects(() => repository.list({ merchantId: '958001', storeId: '958002', displayStatus: 'PENDING_CONFIRM', page: 1, pageSize: 20 }))
  assert.equal((wrongTab as Error).message, 'INVALID_RESPONSE', 'filtered page must carry only the requested display state')
  const wrongEcho = await rejects(() => repository.list({ merchantId: '958001', storeId: '958002', page: 1, pageSize: 20 }))
  assert.equal((wrongEcho as Error).message, 'INVALID_RESPONSE', 'server must echo the requested paging')
})
