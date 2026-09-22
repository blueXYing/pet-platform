import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import {
  PreviewServiceManageRepository, ServiceManageMockError, decodeCategoryList, decodeCommandReceipt,
  decodeManagedServiceDetail, decodeManagedServicePage, draftFromDetail, draftInputProblems, emptyDraft,
  fixtureCategories, fixtureManagedServices, missingSubmitFields, type ServiceDraftInput,
} from '../services/model'
import { RealServiceManageRepository } from '../services/repository'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { WorkspaceScope } from '../../shared/workspace'

const complete: ServiceDraftInput = {
  ...emptyDraft(),
  serviceName: '测试洗护服务', categoryId: '957003', fulfillmentType: 'IN_STORE',
  price: '99.00', listPrice: null, durationMinutes: 45, coverAssetId: '40099',
  applicablePetTypes: ['CAT'], staffRequirement: null, verificationRequired: true,
  description: '测试描述', aftersaleNote: null, remark: null,
}
const draftOnly: ServiceDraftInput = { ...emptyDraft(), serviceName: '未完成草稿' }
async function rejects(promise: Promise<unknown>): Promise<ServiceManageMockError | Error> {
  try { await promise; return new Error('NO_THROW') } catch (error) { return error as ServiceManageMockError }
}

// Fixtures carry a mock-only submittedAt bookkeeping field the wire detail does not include.
const wire = (detail: Record<string, any>): Record<string, unknown> => {
  const clone = JSON.parse(JSON.stringify(detail))
  delete clone.submittedAt
  return clone
}

test('decoders enforce the exact approved shapes (IDs/prices/status/decision invariants)', () => {
  const base = fixtureManagedServices[0]!
  assert.ok(decodeManagedServiceDetail(wire(base)))
  assert.equal(decodeManagedServiceDetail(wire(base)).serviceId, base.serviceId)
  // extra key, bad price lexeme, non-snowflake id and unknown status all fail
  for (const mutate of [
    (v: Record<string, unknown>) => { v.extra = 1 },
    (v: Record<string, unknown>) => { v.price = '99.0' },
    (v: Record<string, unknown>) => { v.serviceId = 'abc' },
    (v: Record<string, unknown>) => { v.status = 'PAUSED' },
    (v: Record<string, unknown>) => { v.listPrice = '1.00'; v.price = '2.00' },
    (v: Record<string, unknown>) => { v.applicablePetTypes = ['ALL', 'CAT'] },
    (v: Record<string, unknown>) => { v.status = 'REJECTED'; v.latestDecision = null },
  ]) {
    const value = wire(base as Record<string, any>)
    mutate(value)
    assert.throws(() => decodeManagedServiceDetail(value), /INVALID_RESPONSE/)
  }
  const rejected = fixtureManagedServices.find(service => service.status === 'REJECTED')!
  const badDecision = wire(rejected as Record<string, any>)
  ;(badDecision.latestDecision as Record<string, unknown>).opinion = '短'
  assert.throws(() => decodeManagedServiceDetail(badDecision), /INVALID_RESPONSE/)
  const page = decodeManagedServicePage({ items: [JSON.parse(JSON.stringify({ serviceId: base.serviceId, serviceName: base.serviceName, price: base.price, status: base.status, version: base.version }))], page: 1, pageSize: 20, total: 1 })
  assert.equal(page.items[0]!.serviceName, base.serviceName)
  assert.throws(() => decodeManagedServicePage({ items: [], page: 0, pageSize: 20, total: 0 }), /INVALID_RESPONSE/)
  assert.equal(decodeCommandReceipt({ serviceId: '30001', status: 'REVIEWING', version: '4' }).status, 'REVIEWING')
  assert.equal(decodeCategoryList(JSON.parse(JSON.stringify(fixtureCategories))).length, fixtureCategories.length)
  assert.throws(() => decodeCategoryList([{ id: '1', name: 'a', sortNo: 2 }, { id: '2', name: 'b', sortNo: 1 }]), /INVALID_RESPONSE/)
})

test('mock list paginates all statuses newest-first and isolates snapshots', async () => {
  const repository = new PreviewServiceManageRepository()
  const first = await repository.list(1, 5)
  assert.equal(first.total, fixtureManagedServices.length)
  assert.equal(first.items.length, 5)
  assert.ok(BigInt(first.items[0]!.serviceId) > BigInt(first.items[1]!.serviceId))
  const detail = await repository.detail(first.items[0]!.serviceId)
  const mutated = { ...detail, serviceName: 'mutated' }
  const again = await repository.detail(first.items[0]!.serviceId)
  assert.notEqual(again.serviceName, mutated.serviceName)
  assert.notEqual(again.serviceName, 'mutated')
  const error = await rejects(repository.detail('8888888888888')) as ServiceManageMockError
  assert.equal(error.statusCode, 404)
  const empty = new PreviewServiceManageRepository(undefined, 'empty')
  assert.equal((await empty.list(1, 20)).total, 0)
})

test('create/update obey the state machine, CAS, idempotency replay and payload lock', async () => {
  const repository = new PreviewServiceManageRepository()
  const created = await repository.create('slot-create', draftOnly)
  assert.equal(created.status, 'DRAFT')
  assert.equal(created.version, '1')
  // Same slot + same payload replays the journaled receipt without a second create.
  const replay = await repository.create('slot-create', draftOnly)
  assert.deepEqual(replay, created)
  assert.equal((await repository.list(1, 100)).total, fixtureManagedServices.length + 1)
  // Same slot with a changed payload refuses (PENDING_WRITE_CHANGED mirrors the real client).
  const changed = await rejects(repository.create('slot-create', complete))
  assert.equal((changed as Error).message, 'PENDING_WRITE_CHANGED')
  // Update with a stale version conflicts.
  const stale = await rejects(repository.update('slot-u1', created.serviceId, '99', complete)) as ServiceManageMockError
  assert.equal(stale.code, 'COMMON_CONFLICT')
  const updated = await repository.update('slot-u1', created.serviceId, created.version, complete)
  assert.equal(updated.version, '2')
  const saved = await repository.detail(created.serviceId)
  assert.equal(saved.serviceName, complete.serviceName)
  // ACTIVE/REVIEWING services refuse edits with 409 SERVICE_STATE_NOT_ALLOWED.
  const active = fixtureManagedServices.find(service => service.status === 'ACTIVE')!
  const reviewing = fixtureManagedServices.find(service => service.status === 'REVIEWING')!
  for (const item of [active, reviewing]) {
    const locked = await rejects(repository.update(`slot-x-${item.serviceId}`, item.serviceId, item.version, complete)) as ServiceManageMockError
    assert.equal(locked.code, 'SERVICE_STATE_NOT_ALLOWED')
    assert.equal(locked.statusCode, 409)
  }
})

test('online submits for review with required-field precheck; offline only from ACTIVE', async () => {
  const repository = new PreviewServiceManageRepository()
  // Draft with missing required fields cannot submit: 400 SERVICE_REVIEW_REASON_REQUIRED.
  const created = await repository.create('slot-c2', draftOnly)
  const incomplete = await rejects(repository.submitOnline('slot-o2', created.serviceId, created.version)) as ServiceManageMockError
  assert.equal(incomplete.code, 'SERVICE_REVIEW_REASON_REQUIRED')
  assert.equal(incomplete.statusCode, 400)
  // Complete draft submits → REVIEWING; editing it afterwards is rejected.
  const filled = await repository.update('slot-u2', created.serviceId, (await repository.detail(created.serviceId)).version, complete)
  const submitted = await repository.submitOnline('slot-o2', created.serviceId, filled.version)
  assert.equal(submitted.status, 'REVIEWING')
  assert.equal((await repository.detail(created.serviceId)).status, 'REVIEWING')
  const reEdit = await rejects(repository.update('slot-u3', created.serviceId, submitted.version, complete)) as ServiceManageMockError
  assert.equal(reEdit.code, 'SERVICE_STATE_NOT_ALLOWED')
  // offline: only ACTIVE transitions; replay of the same slot is idempotent.
  const active = fixtureManagedServices.find(service => service.status === 'ACTIVE')!
  const offline = await repository.takeOffline('slot-f1', active.serviceId, active.version)
  assert.equal(offline.status, 'OFFLINE')
  assert.deepEqual(await repository.takeOffline('slot-f1', active.serviceId, active.version), offline)
  const again = await rejects(repository.takeOffline('slot-f2', active.serviceId, offline.version)) as ServiceManageMockError
  assert.equal(again.code, 'SERVICE_STATE_NOT_ALLOWED')
})

test('non-operable stores reject every write with 409; reads stay available', async () => {
  const repository = new PreviewServiceManageRepository(undefined, 'not-operable')
  assert.ok((await repository.list(1, 20)).total > 0)
  for (const error of [
    await rejects(repository.create('s', draftOnly)),
    await rejects(repository.update('s', '30001', '3', complete)),
    await rejects(repository.submitOnline('s', '30001', '3')),
    await rejects(repository.takeOffline('s', '30001', '3')),
  ] as ServiceManageMockError[]) {
    assert.equal(error.code, 'SERVICE_STATE_NOT_ALLOWED')
    assert.equal(error.statusCode, 409)
  }
})

test('submit/draft validators mirror the contract rules', () => {
  const categories = fixtureCategories
  const missing = missingSubmitFields(draftOnly, categories)
  for (const field of ['服务分类', '履约方式', '销售价格', '服务时长', '封面图', '适用宠物类型']) {
    assert.ok(missing.some(item => item.startsWith(field)), field)
  }
  // draftOnly's five-character name is valid, so the name itself is not reported missing...
  assert.ok(!missing.some(item => item.startsWith('服务名称')))
  // ...but a too-short name is.
  assert.ok(missingSubmitFields({ ...draftOnly, serviceName: '未' }, categories).some(item => item.startsWith('服务名称')))
  assert.equal(missingSubmitFields(complete, categories).length, 0)
  const rejected = fixtureManagedServices.find(service => service.status === 'REJECTED')!
  const fromDetail = draftFromDetail(rejected)
  assert.equal(missingSubmitFields(fromDetail, categories).length, 0)
  const problems = draftInputProblems({ ...complete, price: '10.00', listPrice: '9.00' }, categories)
  assert.ok(problems.some(item => item.includes('划线价')))
  assert.ok(draftInputProblems({ ...complete, serviceName: 'x' }, categories).some(item => item.includes('服务名称')))
})

const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', success: true, data } })
const failure = (code: string, statusCode: number) => ({ statusCode, data: { code, data: null } })

test('real repository wires the six merchant routes with journaled request ids', async () => {
  const seen: { method: string; path: string; requestId?: string; data?: Record<string, unknown>; query?: Record<string, string> }[] = []
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
  const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
  const grant = { ...session, tokenType: 'Bearer', accessToken: 'unusable-test-token' }
  const api = new ConsumerApi(async request => {
    if (request.path.endsWith('/attempts')) return ok({ attemptId: '301', attemptToken: 'unusable-attempt-token', nextStep: 'PROVE_IDENTITY' })
    if (request.path.endsWith('/wechat-login')) return ok(grant)
    if (request.path.endsWith('/session')) return ok(session)
    seen.push({ method: request.method, path: request.path, requestId: (request as { requestId?: string }).requestId, data: request.data, query: (request as { query?: Record<string, string> }).query })
    if (request.method === 'GET' && request.path === '/api/v1/merchant/service-categories') {
      return ok(fixtureCategories.map(category => ({ id: category.id, name: category.name, sortNo: category.sortNo })))
    }
    if (request.method === 'GET' && request.path === '/api/v1/merchant/services') return ok({ items: [], page: 1, pageSize: 20, total: 0 })
    if (request.method === 'POST' && request.path === '/api/v1/merchant/services') {
      return { statusCode: 201, data: { code: 'SUCCESS', success: true, data: { serviceId: '30101', status: 'DRAFT', version: '1' } } }
    }
    if (request.path.endsWith('/online')) return ok({ serviceId: '30101', status: 'REVIEWING', version: '2' })
    if (request.path.endsWith('/offline')) return failure('SERVICE_STATE_NOT_ALLOWED', 409)
    return failure('COMMON_DEPENDENCY_UNAVAILABLE', 503)
  }, store, async () => randomUUID())
  await api.startLogin(async () => ({ code: 'test-code' }))
  api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '957001', storeId: '957002' })
  const repository = new RealServiceManageRepository(api, () => '957001', () => '957002')
  assert.equal((await repository.categories()).length, fixtureCategories.length)
  await repository.list(1, 20)
  const created = await repository.create('merchant-service:create', complete)
  assert.equal(created.status, 'DRAFT')
  const submitted = await repository.submitOnline('merchant-service:30101:online', '30101', created.version)
  assert.equal(submitted.status, 'REVIEWING')
  const categoriesCall = seen.find(call => call.path === '/api/v1/merchant/service-categories')!
  const listCall = seen.find(call => call.path === '/api/v1/merchant/services' && call.method === 'GET')!
  const createCall = seen.find(call => call.method === 'POST' && call.path === '/api/v1/merchant/services')!
  const onlineCall = seen.find(call => call.path === '/api/v1/merchant/services/30101/online')!
  assert.ok(categoriesCall)
  assert.equal(listCall.data?.merchantId, '957001')
  assert.equal(listCall.data?.storeId, '957002')
  // Each journaled command carries its own UUID request id on the wire.
  assert.match(String(createCall.requestId), /^[0-9a-f-]{36}$/)
  assert.match(String(onlineCall.requestId), /^[0-9a-f-]{36}$/)
  assert.notEqual(createCall.requestId, onlineCall.requestId)
  assert.equal(onlineCall.query?.merchantId, '957001')
  assert.equal(onlineCall.query?.storeId, '957002')
  assert.equal(onlineCall.data?.expectedVersion, '1')
  await assert.rejects(repository.takeOffline('merchant-service:30101:offline', '30101', '2'))
  // A consumer-coordinate scope must not reach merchant routes.
  const scope = new WorkspaceScope()
  scope.replace({ userId: '101', workspace: 'consumer', merchantId: null, storeId: null })
  const wrongScope = new RealServiceManageRepository(api, () => scope.current?.merchantId || '', () => scope.current?.storeId || '')
  // list() throws synchronously on bad coordinates, so wrap in an async fn to assert it.
  await assert.rejects(async () => wrongScope.list(1, 20), /WORKSPACE_PATH_MISMATCH/)
})
