import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { detailActionGate, detailReadAllowed } from '../pages/store-services/browse-gate'
import { RealServiceRepository } from '../api/repositories'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { fixtureServices, fixtureStoreId } from '../service/model'

// STR-D8 detail-page gate (2026-09-23 window acceptance): browsing — including the store
// detail and service detail pages — is anonymous; only the 预约/拨打电话 ACTIONS stay
// login-gated (“所有用户浏览；已登录用户可进入预约”). These tests pin the page-level
// decisions and the anonymous wiring the two detail pages consume.

const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', success: true, data } })

test('detail pages stay readable without login: only the preview design cut expires', () => {
  // Real mode never blocks reading on login state (STR-D8); the expired design state is a
  // preview fixture scenario, not a product rule.
  assert.equal(detailReadAllowed(false, 'expired'), true)
  assert.equal(detailReadAllowed(false, 'normal'), true)
  assert.equal(detailReadAllowed(true, 'normal'), true)
  assert.equal(detailReadAllowed(true, 'load-error'), true)
  assert.equal(detailReadAllowed(true, 'expired'), false)
})

test('detail page actions stay login-gated: anonymous or merchant context requires login', () => {
  assert.equal(detailActionGate(null), 'login-required')
  assert.equal(detailActionGate(undefined), 'login-required')
  assert.equal(detailActionGate({ userId: '101', workspace: 'merchant', merchantId: '96103453281046529', storeId: '96103453780168704' }), 'login-required')
  assert.equal(detailActionGate({ userId: '101', workspace: 'consumer', merchantId: null, storeId: null }), 'consumer-ready')
})

test('service detail/list reads flow anonymously through the page repository (no session, no Authorization)', async () => {
  const seen: { method: string; path: string; authorization: unknown }[] = []
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
  const api = new ConsumerApi(async request => {
    seen.push({ method: request.method, path: request.path, authorization: request.headers.Authorization })
    if (request.path === `/api/v1/c/stores/${fixtureStoreId}/services`) {
      return ok({ items: fixtureServices.map(({ description: _d, ...item }) => item), page: 1, pageSize: 20, total: 3 })
    }
    if (request.path === '/api/v1/c/services/20001') return ok(fixtureServices[0])
    return { statusCode: 404, data: { code: 'SERVICE_NOT_FOUND', data: null } }
  }, store, async () => randomUUID())
  assert.equal(api.currentSession, null) // never logged in: no credential, no workspace context
  assert.equal(api.scope.current, null)
  const repository = new RealServiceRepository(api)
  const page = await repository.list(fixtureStoreId, 1, 20)
  assert.equal(page.total, 3)
  const detail = await repository.detail('20001')
  assert.equal(detail.serviceName, '专业美容套餐')
  assert.equal(detail.description, '含洗护、造型、指甲修剪')
  assert.equal(detail.cover?.coverAssetId, '40001')
  assert.deepEqual(seen.map(call => call.method), ['GET', 'GET'])
  assert.ok(seen.every(call => call.authorization === undefined), 'anonymous catalog reads must not carry a Bearer')
})
