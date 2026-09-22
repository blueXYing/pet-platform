import assert from 'node:assert/strict'
import test from 'node:test'
import { randomUUID } from 'node:crypto'
import {
  PreviewServiceRepository, ServiceMockError, decodeServiceDetail, decodeServiceItem, decodeServicePage,
  designSamples, fixtureServices, fixtureStoreId, formatSalePrice,
} from '../service/model'
import { WorkspaceScope, StaleContextError } from '../../shared/workspace'
import { RealServiceRepository } from '../api/repositories'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'

const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', data } })
const failure = (code: string, statusCode: number) => ({ statusCode, data: { code, data: null } })
const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const grant = { ...session, tokenType: 'Bearer', accessToken: 'unusable-test-token' }

function harness(transport: import('../../shared/request').Transport) {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
  const api = new ConsumerApi(async request => {
    if (request.path.endsWith('/attempts')) return ok({ attemptId: '301', attemptToken: 'unusable-attempt-token', nextStep: 'PROVE_IDENTITY' })
    if (request.path.endsWith('/wechat-login')) return ok(grant)
    if (request.path.endsWith('/session')) return ok(session)
    return transport(request)
  }, store, async () => randomUUID())
  return api
}
async function loggedInApi(transport: import('../../shared/request').Transport) {
  const api = harness(transport)
  await api.startLogin(async () => ({ code: 'test-code' }))
  return api
}

test('service decoders enforce the frozen field set, ID strings and two-decimal prices', () => {
  const { description: _description, ...itemSource } = fixtureServices[0]
  const item = decodeServiceItem(itemSource)
  assert.equal(item.salePrice, '80.00')
  assert.equal(item.fulfillmentType, 'IN_STORE')
  // unknown extra field (e.g. a smuggled bookability object) is rejected
  assert.throws(() => decodeServiceItem({ ...itemSource, bookable: true }), /INVALID_RESPONSE/)
  // missing field rejected
  assert.throws(() => { const { categoryName: _dropped, ...rest } = itemSource; decodeServiceItem(rest) }, /INVALID_RESPONSE/)
  for (const bad of ['80', '80.0', '80.000', 80, null]) assert.throws(() => decodeServiceItem({ ...itemSource, salePrice: bad }), /INVALID_RESPONSE/)
  for (const bad of ['', 'SERVICE', 'IN_STORE ', 1]) assert.throws(() => decodeServiceItem({ ...itemSource, fulfillmentType: bad }), /INVALID_RESPONSE/)
  for (const bad of ['0', 'x1', 20001, '9223372036854775808']) assert.throws(() => decodeServiceItem({ ...itemSource, serviceId: bad }), /INVALID_RESPONSE/)
})

test('detail view is the item shape plus description; list items carry no description key', () => {
  const detail = decodeServiceDetail({ ...fixtureServices[2] })
  assert.equal(detail.description, '深层清洁 + 精油护理')
  const { description: _missing, ...itemOnly } = fixtureServices[0]
  assert.throws(() => decodeServiceDetail(itemOnly), /INVALID_RESPONSE/) // no description key
  const page = decodeServicePage({ items: fixtureServices.map(({ description: _d, ...item }) => item), page: 1, pageSize: 20, total: 3 })
  assert.equal(page.items.length, 3)
  for (const item of page.items) assert.equal('description' in item, false)
})

test('page decoder enforces the approved pagination bounds', () => {
  const base = { items: [], page: 1, pageSize: 20, total: 0 }
  for (const patch of [{ page: 0 }, { page: 10001 }, { pageSize: 0 }, { pageSize: 51 }, { total: -1 }, { items: {} }, { extra: 1 }])
    assert.throws(() => decodeServicePage({ ...base, ...patch }), /INVALID_RESPONSE/)
  assert.deepEqual(decodeServicePage({ ...base, page: 10000, pageSize: 50 }), { items: [], page: 10000, pageSize: 50, total: 0 })
})

test('sale price display keeps the design integer shape without changing the contract value', () => {
  assert.equal(formatSalePrice('80.00'), '80')
  assert.equal(formatSalePrice('128.00'), '128')
  assert.equal(formatSalePrice('80.50'), '80.5')
  assert.equal(formatSalePrice('0.01'), '0.01')
})

test('contract mock lists a visible store, hides an unknown one as an empty page, and never mixes in description', async () => {
  const repo = new PreviewServiceRepository()
  const page = await repo.list(fixtureStoreId, 1, 20)
  assert.equal(page.total, 3)
  assert.deepEqual(page.items.map(item => item.serviceName), ['专业美容套餐', '家庭寄养·天', '洗护SPA'])
  assert.equal(page.items.every(item => !('description' in item)), true)
  const unknown = await repo.list('999999999', 1, 20)
  assert.deepEqual(unknown, { items: [], page: 1, pageSize: 20, total: 0 })
})

test('contract mock paginates within the approved bounds', async () => {
  const repo = new PreviewServiceRepository()
  const first = await repo.list(fixtureStoreId, 1, 2)
  assert.deepEqual(first.items.map(item => item.serviceId), ['20001', '20002'])
  const second = await repo.list(fixtureStoreId, 2, 2)
  assert.deepEqual(second.items.map(item => item.serviceId), ['20003'])
  assert.equal(second.total, 3)
  await assert.rejects(repo.list('abc', 1, 20), (error: unknown) => error instanceof ServiceMockError && error.statusCode === 400)
})

test('contract mock answers unknown or ineligible services with the indistinguishable 404, facts failure with 503', async () => {
  const repo = new PreviewServiceRepository()
  await assert.rejects(repo.detail('88888888'), (error: unknown) => error instanceof ServiceMockError && error.code === 'SERVICE_NOT_FOUND' && error.statusCode === 404)
  await assert.rejects(repo.detail('12x'), (error: unknown) => error instanceof ServiceMockError && error.statusCode === 400)
  const failingList = new PreviewServiceRepository(undefined, 'load-error')
  await assert.rejects(failingList.list(fixtureStoreId, 1, 20), (error: unknown) => error instanceof ServiceMockError && error.code === 'COMMON_DEPENDENCY_UNAVAILABLE' && error.statusCode === 503)
  const failingDetail = new PreviewServiceRepository(undefined, 'load-error')
  await assert.rejects(failingDetail.detail('20001'), (error: unknown) => error instanceof ServiceMockError && error.statusCode === 503)
  // fail-once semantics: an explicit retry succeeds
  assert.equal((await failingDetail.list(fixtureStoreId, 1, 20)).total, 3)
})

test('returned snapshots are value copies: mutating a result never rewrites the catalog', async () => {
  const repo = new PreviewServiceRepository()
  const first = await repo.detail('20001')
  const mutable = first as unknown as { serviceName: string; salePrice: string }
  mutable.serviceName = '被篡改的名称'
  mutable.salePrice = '0.01'
  const again = await repo.detail('20001')
  assert.equal(again.serviceName, '专业美容套餐')
  assert.equal(again.salePrice, '80.00')
})

test('late service responses are rejected after a workspace revision change', async () => {
  const scope = new WorkspaceScope()
  scope.replace({ userId: '9007199254740993', workspace: 'consumer', merchantId: null, storeId: null })
  let release!: () => void
  const repo = new PreviewServiceRepository(undefined, 'normal', () => new Promise(resolve => { release = resolve }))
  const pending = scope.run(undefined, () => repo.list(fixtureStoreId, 1, 20))
  scope.replace({ userId: '9007199254740994', workspace: 'consumer', merchantId: null, storeId: null })
  release()
  await assert.rejects(pending, StaleContextError)
})

test('real repository targets the frozen routes with query paging and strict decoders', async () => {
  const calls: import('../../shared/request').RequestSpec[] = []
  const api = await loggedInApi(async request => {
    calls.push(structuredClone(request))
    if (request.path === `/api/v1/c/stores/${fixtureStoreId}/services`) {
      assert.deepEqual(request.data, { page: 1, pageSize: 20 })
      return ok({ items: fixtureServices.map(({ description: _d, ...item }) => item), page: 1, pageSize: 20, total: 3 })
    }
    if (request.path === '/api/v1/c/services/20001') return ok(fixtureServices[0])
    return failure('COMMON_NOT_FOUND', 404)
  })
  const repo = new RealServiceRepository(api)
  const page = await repo.list(fixtureStoreId, 1, 20)
  assert.equal(page.total, 3)
  const detail = await repo.detail('20001')
  assert.equal(detail.serviceName, '专业美容套餐')
  assert.equal(detail.description, '含洗护、造型、指甲修剪')
  assert.deepEqual(calls.map(call => call.method), ['GET', 'GET'])
  await assert.rejects(repo.detail('88888888'), /COMMON_NOT_FOUND/)
  await assert.rejects(async () => repo.list('not-an-id', 1, 20), /INVALID_RESPONSE/)
})

test('real repository surfaces the frozen fail-closed 503 without conflating it with 404', async () => {
  const api = await loggedInApi(async () => failure('COMMON_DEPENDENCY_UNAVAILABLE', 503))
  const repo = new RealServiceRepository(api)
  await assert.rejects(repo.list(fixtureStoreId, 1, 20), (error: unknown) => (error as { statusCode?: number }).statusCode === 503)
  await assert.rejects(repo.detail('20001'), (error: unknown) => (error as { statusCode?: number }).statusCode === 503)
})

test('design samples stay keyed to fixture ids and never become contract fields', () => {
  assert.deepEqual(Object.keys(designSamples.sold), ['20001', '20002', '20003'])
  assert.deepEqual(Object.keys(designSamples.listDescription), ['20001', '20002', '20003'])
  assert.equal(designSamples.store.name, '萌宠之家宠物店')
})
