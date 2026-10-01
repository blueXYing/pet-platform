import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, isAfterSalePath, type LocalStore, type AfterSaleAssetTransport } from '../consumer-api'
import { AfterSaleClient, decodeDetail, decodeSummary, amount, instant, version } from '../aftersale-api'
import { ApiError, type Transport, type WireRequest } from '../request'

const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data, traceId: 'fixture-trace' } })
const summary = { afterSaleId: '301', orderId: '401', merchantId: '501', storeId: '601', status: 'PENDING', version: '0', sourceStage: 'VERIFIED', typeCode: 'CONFIGURED_TYPE', demandCode: 'CONFIGURED_DEMAND', requestedAmount: null, createdAt: '2026-10-01T00:00:00.000Z', deadline: '2026-10-08T00:00:00.000Z' }
const receipt = { commandId: '701', orderId: '401', afterSaleId: '301', status: 'PENDING', version: '0', occurredAt: '2026-10-01T00:00:00.000Z', evidenceBatchId: null, supplementRequestId: null, decisionId: null, refundOrderId: null }
const input = { typeCode: 'CONFIGURED_TYPE', demandCode: 'CONFIGURED_DEMAND', description: '真实服务问题说明至少十个字符', evidenceAssetIds: [] }
function setup(handler: Transport = async () => ok(receipt), assets?: AfterSaleAssetTransport, uuid: () => Promise<string> = async () => randomUUID()) {
  const values = new Map<string, unknown>([['pet.c.session.v1', { ...session, tokenType: 'Bearer', accessToken: 'test-only-unusable' }]])
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => { values.set(key, structuredClone(value)) }, remove: key => { values.delete(key) } }
  const calls: WireRequest[] = []
  const transport: Transport = async r => { calls.push(structuredClone(r)); return r.path.endsWith('/auth/session') ? ok(session) : handler(r) }
  const make = () => new ConsumerApi(transport, store, uuid, undefined, assets)
  const api = make(); return { api, calls, values, make, client: new AfterSaleClient(api, 'c') }
}
test('Contract51 uses strict four fields, String IDs and exact whitelisted methods without altering old session protocol', async () => {
  const h = setup(async () => ok({ page: 1, pageSize: 20, total: 1, items: [summary] })); await h.api.restore()
  assert.equal((await h.client.list()).items[0].afterSaleId, '301')
  assert.equal(h.calls.at(-1)?.headers.Authorization, 'Bearer test-only-unusable')
  for (const route of ['/api/v1/c/aftersales/301/opinion', '/api/v1/merchant/aftersales/301/withdraw', '/api/v1/merchant/aftersales/301/decisions']) assert.equal(isAfterSalePath({ path: route, method: 'POST' }), false)
  assert.throws(() => decodeSummary({ ...summary, afterSaleId: 301 }), /INVALID_RESPONSE/)
  assert.throws(() => amount(12.50), /INVALID_RESPONSE/); assert.throws(() => amount('1e2'), /INVALID_RESPONSE/)
  assert.throws(() => instant('2026-02-30T00:00:00.000Z'), /INVALID_RESPONSE/)
  const bad = setup(async () => ({ statusCode: 200, data: { ...ok(receipt).data, success: true } })); await bad.api.restore()
  await assert.rejects(bad.client.create('401', input), /INVALID_RESPONSE/)
})
test('unknown ACK survives reconstruction, retries the original UUID/payload and blocks edits', async () => {
  let lost = true; const h = setup(async () => { if (lost) throw new Error('network'); return ok(receipt) }); await h.api.restore()
  await assert.rejects(h.client.create('401', input), /network/); const original = h.calls.at(-1)
  const recovered = h.make(); await recovered.restore(); const client = new AfterSaleClient(recovered, 'c')
  assert.deepEqual(client.pending('401', 'create'), input)
  await assert.rejects(client.create('401', { ...input, description: '改变原请求内容时必须阻断提交' }), /PENDING_WRITE_CHANGED/)
  lost = false; await client.create('401', input)
  assert.deepEqual(h.calls.at(-1), original); assert.equal(client.pending('401', 'create'), undefined)
})
test('merchant queries bind current store while command DTO carries no client identity or coordinates', async () => {
  const h = setup(async r => r.method === 'GET' ? ok({ page: 1, pageSize: 20, total: 0, items: [] }) : ok(receipt)); await h.api.restore()
  h.api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '501', storeId: '601' })
  const client = new AfterSaleClient(h.api, 'merchant'); await client.list()
  assert.deepEqual(h.calls.at(-1)?.data, { page: 1, pageSize: 20, merchantId: '501', storeId: '601' })
  assert.throws(() => client.list({ storeId: '602' }), /WORKSPACE_PATH_MISMATCH/)
  await client.opinion('301', { expectedVersion: '0', opinionCode: 'DISAGREE', explanation: '商家说明实际履约情况至少十字符', evidenceAssetIds: [] })
  assert.equal(h.calls.at(-1)?.data?.merchantId, undefined)
  await assert.rejects(h.api.request({ path: '/api/v1/merchant/aftersales/301/decisions', method: 'POST', requestId: randomUUID(), data: {} }, x => x), /INVALID_PATH/)
})
test('409 CAS retires only definite rejection; idempotency conflict remains pending', async () => {
  let code = 'AFTERSALE_SUPPLEMENT_STALE'; const h = setup(async () => ({ statusCode: 409, data: { code, message: 'conflict', data: null, traceId: 't' } })); await h.api.restore()
  let error: unknown; try { await h.client.withdraw('301', '0') } catch (e) { error = e }
  h.client.retireConflict('301', 'withdraw', error); assert.equal(h.client.pending('301', 'withdraw'), undefined)
  code = 'COMMON_IDEMPOTENCY_CONFLICT'; try { await h.client.withdraw('301', '0') } catch (e) { error = e }
  assert.throws(() => h.client.retireConflict('301', 'withdraw', error), /UNCONFIRMED_WRITE/); assert.ok(h.client.pending('301', 'withdraw'))
})
test('scope changes during UUID generation cannot sign a grant as the later identity', async () => {
  let finish!: (v: string) => void; const promise = new Promise<string>(resolve => { finish = resolve })
  const h = setup(undefined, undefined, () => promise); await h.api.restore()
  const client = new AfterSaleClient(h.api, 'c', { read: async () => '', clear() {} })
  const reading = client.readEvidence('301', '701', '801', '核对本人售后证据')
  h.api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '501', storeId: '601' }); finish(randomUUID())
  await assert.rejects(reading, /STALE_CONTEXT/); assert.equal(h.calls.length, 1)
})
test('typed private upload uses four fields; binary grants reject cross-party/absolute URLs and stale returns', async () => {
  let reads = 0
  const assets: AfterSaleAssetTransport = { upload: async () => ok({ assetId: '801', status: 'READY', objectSha256: 'a'.repeat(64), mediaType: 'image/png', bytes: 8 }), read: async () => { reads++; return { statusCode: 410, data: new ArrayBuffer(0) } } }
  const h = setup(undefined, assets); await h.api.restore()
  assert.equal((await h.client.upload('/tmp/test.png', randomUUID())).assetId, '801')
  for (const path of ['https://evil.invalid/a', `/api/v1/merchant/aftersale-evidence-read-grants/${'a'.repeat(43)}`]) await assert.rejects(h.api.readAfterSaleEvidence(path), /INVALID_PATH/)
  assert.equal(reads, 0)
  await assert.rejects(h.api.readAfterSaleEvidence(`/api/v1/c/aftersale-evidence-read-grants/${'a'.repeat(43)}`), error => error instanceof ApiError && error.statusCode === 410)
  assert.equal(reads, 1)
})
test('malformed detail never publishes an incomplete or numeric business record', () => {
  assert.throws(() => decodeDetail(summary), /INVALID_RESPONSE/)
})

test('strict primitive and route boundaries reject trailing line terminators', async () => {
  for (const suffix of ['\n', '\r', '\r\n']) {
    assert.throws(() => amount(`12.50${suffix}`), /INVALID_RESPONSE/)
    assert.throws(() => version(`0${suffix}`), /INVALID_RESPONSE/)
    assert.equal(isAfterSalePath({ path: `/api/v1/c/aftersales${suffix}`, method: 'GET' }), false)
    const h = setup(undefined, { upload: async () => { throw new Error('must not send') }, read: async () => { throw new Error('must not send') } }); await h.api.restore()
    await assert.rejects(h.client.upload('/tmp/test.png', `${randomUUID()}${suffix}`), /REQUEST_ID_REQUIRED/)
    await assert.rejects(h.api.request({ path: '/api/v1/c/aftersales/301/withdraw', method: 'POST', requestId: `${randomUUID()}${suffix}`, data: { expectedVersion: '0' } }, x => x), /REQUEST_ID_REQUIRED/)
    assert.equal(h.calls.length, 1)
    await assert.rejects(h.api.readAfterSaleEvidence(`/api/v1/c/aftersale-evidence-read-grants/${'a'.repeat(43)}${suffix}`), /INVALID_PATH/)
  }
})

test('list rejects response coordinates, filter and paging that differ from the request', async () => {
  for (const page of [{ page: 2, pageSize: 20, total: 0, items: [] }, { page: 1, pageSize: 20, total: 1, items: [{ ...summary, storeId: '602' }] }, { page: 1, pageSize: 20, total: 1, items: [{ ...summary, status: 'CLOSED' }] }]) {
    const h = setup(async () => ok(page)); await h.api.restore(); h.api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '501', storeId: '601' })
    await assert.rejects(new AfterSaleClient(h.api, 'merchant').list({ status: 'PENDING' }), /INVALID_RESPONSE/)
  }
})

test('unknown aftersale write survives 401/logout and is visible only to its freshly verified owner', async () => {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => { values.set(key, structuredClone(value)) }, remove: key => { values.delete(key) } }
  let userId = '101', mode = 'lost'
  const calls: WireRequest[] = []
  const transport: Transport = async request => {
    calls.push(structuredClone(request))
    if (request.path.endsWith('/attempts')) return ok({ attemptId: '301', attemptToken: 'test-only', nextStep: 'PROVE_IDENTITY' })
    if (request.path.endsWith('/wechat-login')) return ok({ ...session, userId, tokenType: 'Bearer', accessToken: `test-only-${userId}` })
    if (request.path.endsWith('/auth/session')) return ok({ ...session, userId })
    if (request.path.endsWith('/logout')) return ok({ loggedOut: true })
    if (mode === 'expired') return { statusCode: 401, data: { code: 'COMMON_UNAUTHORIZED', message: 'expired', data: null, traceId: 't' } }
    if (mode === 'lost') throw new Error('network')
    return ok(receipt)
  }
  const api = new ConsumerApi(transport, store, async () => randomUUID()), client = new AfterSaleClient(api, 'c')
  await api.startLogin(async () => ({ code: 'test' }))
  await assert.rejects(client.create('401', input), /network/); const original = calls.at(-1)!
  mode = 'expired'; await assert.rejects(client.list(), error => error instanceof ApiError && error.statusCode === 401)
  assert.equal(api.currentSession, null)
  mode = 'ready'; userId = '102'; await api.startLogin(async () => ({ code: 'other' }))
  assert.equal(client.pending('401', 'create'), undefined)
  await api.logout(); userId = '101'; await api.startLogin(async () => ({ code: 'owner' }))
  assert.deepEqual(client.pending('401', 'create'), input)
  const exposed = client.pending('401', 'create') as typeof input; exposed.description = '试图修改已保存请求引用的内容'
  assert.deepEqual(client.pending('401', 'create'), input)
  await client.create('401', input)
  assert.deepEqual(calls.at(-1), original)
  assert.equal(client.pending('401', 'create'), undefined)
})

test('malformed failure envelopes preserve unknown commands instead of declaring a rejection', async () => {
  for (const data of [{ code: 'COMMON_INVALID_ARGUMENT' }, { code: 'COMMON_INVALID_ARGUMENT', message: 'invalid', data: {}, traceId: 't' }, { code: 'COMMON_INVALID_ARGUMENT', message: 'invalid', data: null, traceId: 't', success: false }]) {
    const h = setup(async () => ({ statusCode: 400, data })); await h.api.restore()
    await assert.rejects(h.client.create('401', input), /INVALID_RESPONSE/)
    assert.deepEqual(h.client.pending('401', 'create'), input)
  }
})

test('mismatched receipts never retire a command or navigate to another order/case', async () => {
  const wrongOrder = setup(async () => ok({ ...receipt, orderId: '402' })); await wrongOrder.api.restore()
  await assert.rejects(wrongOrder.client.create('401', input), /INVALID_RESPONSE/)
  assert.ok(wrongOrder.client.pending('401', 'create'))
  const wrongCase = setup(async () => ok({ ...receipt, afterSaleId: '302' })); await wrongCase.api.restore()
  await assert.rejects(wrongCase.client.withdraw('301', '0'), /INVALID_RESPONSE/)
  assert.ok(wrongCase.client.pending('301', 'withdraw'))
})
