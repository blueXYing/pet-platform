import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, isAfterSalePath, type LocalStore, type AfterSaleAssetTransport } from '../consumer-api'
import { AfterSaleClient, decodeDetail, decodeSummary, decodeOptions, amount, instant, version } from '../aftersale-api'
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
  const api = make(); return { api, calls, values, store, make, client: new AfterSaleClient(api, 'c') }
}
const options = () => ({ typeOptions: [{ code: 'A', label: '服务问题' }], demandOptions: [{ code: 'B', label: '协助处理' }] })
test('options use authenticated exact GET with no query/body and strict four-field envelope', async () => {
  const h = setup(async () => ok(options())); await h.api.restore()
  assert.deepEqual(await h.client.options(), options())
  assert.equal(h.calls.at(-1)?.path, '/api/v1/c/aftersale-options')
  assert.equal(h.calls.at(-1)?.headers.Authorization, 'Bearer test-only-unusable')
  assert.equal(h.calls.at(-1)?.data, undefined)
  for (const spec of [{ path: '/api/v1/c/aftersale-options', method: 'POST' as const }, { path: '/api/v1/c/aftersale-options', method: 'GET' as const, data: {} }, { path: '/api/v1/c/aftersale-options?extra=true', method: 'GET' as const }, { path: '/api/v1/merchant/aftersale-options', method: 'GET' as const }]) {
    assert.equal(isAfterSalePath(spec), false)
    await assert.rejects(h.api.request(spec, x => x), /INVALID_PATH/)
  }
  await assert.rejects(h.api.anonymousRequest({ path: '/api/v1/c/aftersale-options', method: 'GET' }, x => x), /INVALID_PATH/)
  const malformed = setup(async () => ({ statusCode: 200, data: { ...ok(options()).data, success: true } })); await malformed.api.restore()
  await assert.rejects(malformed.client.options(), /INVALID_RESPONSE/)
  h.api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '501', storeId: '601' })
  assert.throws(() => new AfterSaleClient(h.api, 'merchant').options(), /WORKSPACE_PATH_MISMATCH/)
  await assert.rejects(h.client.options(), /WORKSPACE_PATH_MISMATCH/)
})
test('options reject incomplete, duplicate, unsorted and over-limit catalogs while accepting 64 Unicode scalar labels', () => {
  const valid = options()
  assert.equal(decodeOptions({ ...valid, typeOptions: [{ code: 'A'.repeat(64), label: '😀'.repeat(64) }] }).typeOptions[0].label.length, 128)
  assert.equal(decodeOptions({ ...valid, typeOptions: Array.from({ length: 100 }, (_, i) => ({ code: `A${String(i).padStart(3, '0')}`, label: '已配置问题' })) }).typeOptions.length, 100)
  for (const malformed of [null, {}, { ...valid, extra: true }, { ...valid, typeOptions: [] }, { ...valid, demandOptions: [] }, { ...valid, typeOptions: [{ code: 'A', label: '问题', internal: 'hidden' }] }, { ...valid, typeOptions: [{ code: 'A', label: '问题' }, { code: 'A', label: '重复' }] }, { ...valid, typeOptions: [{ code: 'B', label: '后项' }, { code: 'A', label: '前项' }] }, { ...valid, typeOptions: Array.from({ length: 101 }, (_, i) => ({ code: `A${String(i).padStart(3, '0')}`, label: '已配置问题' })) }]) assert.throws(() => decodeOptions(malformed), /INVALID_RESPONSE/)
  for (const code of ['', 'a', '0A', 'A-B', 'A'.repeat(65), 'A\n', ' A', 1]) assert.throws(() => decodeOptions({ ...valid, typeOptions: [{ code, label: '问题' }] }), /INVALID_RESPONSE/)
  for (const label of ['', ' ', '😀'.repeat(65), '\uD800', '\uDC00', '问题\uD800', '\uD800\uD800\uDC00', 1]) assert.throws(() => decodeOptions({ ...valid, typeOptions: [{ code: 'A', label }] }), /INVALID_RESPONSE/)
  for (const whitespace of ['\t', '\n', '\v', '\f', '\r', ' ', '\u00A0', '\u1680', '\u2000', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF']) {
    for (const label of [`${whitespace}问题`, `问题${whitespace}`, whitespace]) assert.throws(() => decodeOptions({ ...valid, typeOptions: [{ code: 'A', label }] }), /INVALID_RESPONSE/)
  }
  assert.equal(decodeOptions({ ...valid, typeOptions: [{ code: 'A', label: '\u001C问题\u001C' }] }).typeOptions[0].label, '\u001C问题\u001C')
})
test('options cannot publish after identity replacement or credential expiry', async () => {
  let finish!: (value: ReturnType<typeof ok>) => void
  const h = setup(() => new Promise(resolve => { finish = resolve })); await h.api.restore()
  const reading = h.client.options()
  h.api.scope.replace({ userId: '102', workspace: 'consumer', merchantId: null, storeId: null }); finish(ok(options()))
  await assert.rejects(reading, /STALE_CONTEXT/)
  const expired = setup(async () => ({ statusCode: 401, data: { code: 'COMMON_UNAUTHORIZED', message: 'expired', data: null, traceId: 't' } })); await expired.api.restore()
  await assert.rejects(expired.client.options(), error => error instanceof ApiError && error.statusCode === 401)
  assert.equal(expired.api.currentSession, null); assert.equal(expired.api.scope.current, null)
})
test('new definite conflict codes retire only the matching actual failure, then require an explicit new command', async () => {
  for (const code of ['AFTERSALE_VERSION_CONFLICT', 'AFTERSALE_FINAL_SET_CONFLICT']) {
    let conflict = true
    const h = setup(async () => conflict ? { statusCode: 409, data: { code, message: 'changed', data: null, traceId: 't' } } : ok(receipt)); await h.api.restore()
    let error: unknown; try { await h.client.withdraw('301', '0') } catch (caught) { error = caught }
    const original = h.calls.at(-1)!
    assert.throws(() => h.client.retireConflict('301', 'withdraw', new ApiError(code, 409)), /UNCONFIRMED_WRITE/)
    assert.throws(() => h.client.retireConflict('302', 'withdraw', error), /UNCONFIRMED_WRITE/)
    assert.throws(() => new AfterSaleClient(h.api, 'c').retireConflict('301', 'withdraw', error), /UNCONFIRMED_WRITE/)
    assert.ok(h.client.pending('301', 'withdraw'))
    h.client.retireConflict('301', 'withdraw', error)
    assert.equal(h.client.pending('301', 'withdraw'), undefined)
    assert.equal(h.calls.at(-1), original)
    conflict = false; await h.client.withdraw('301', '1')
    assert.notEqual(h.calls.at(-1)?.requestId, original.requestId)
    assert.deepEqual(h.calls.at(-1)?.data, { expectedVersion: '1' })
  }
})
test('late handling of an old definite rejection cannot retire a newer journal with identical payload', async () => {
  let mode = 'conflict'
  const h = setup(async () => { if (mode === 'conflict') return { statusCode: 409, data: { code: 'AFTERSALE_VERSION_CONFLICT', message: 'changed', data: null, traceId: 't' } }; throw new Error('response-lost') }); await h.api.restore()
  let oldError: unknown; try { await h.client.withdraw('301', '0') } catch (caught) { oldError = caught }
  const old = h.api.pendingCommands('aftersale:')[0]
  // Another authorized handler already retired the old exact rejection before this
  // page handles it; the newer journal deliberately has the same body, a different UUID.
  h.api.retireRejectedCommand(old.slot, old.command)
  mode = 'lost'; await assert.rejects(h.client.withdraw('301', '0'), /response-lost/)
  const current = h.api.pendingCommands('aftersale:')[0]
  assert.notEqual(current.command.requestId, old.command.requestId)
  assert.throws(() => h.client.retireConflict('301', 'withdraw', oldError), /PENDING_WRITE_CHANGED/)
  assert.deepEqual(h.api.pendingCommands('aftersale:')[0], current)
})
test('retirement storage failure keeps the same command in memory and durable storage for explicit retry', async () => {
  let mode = 'conflict'
  const h = setup(async () => mode === 'conflict' ? { statusCode: 409, data: { code: 'AFTERSALE_FINAL_SET_CONFLICT', message: 'changed', data: null, traceId: 't' } } : ok(receipt)); await h.api.restore()
  let error: unknown; try { await h.client.withdraw('301', '0') } catch (caught) { error = caught }
  const original = h.calls.at(-1)!, durable = structuredClone(h.values.get('pet.c.pending.v1'))
  const set = h.store.set; h.store.set = () => { throw new Error('storage-write-failed') }
  assert.throws(() => h.client.retireConflict('301', 'withdraw', error), /storage-write-failed/)
  assert.deepEqual(h.client.pending('301', 'withdraw'), { expectedVersion: '0' }); assert.deepEqual(h.values.get('pet.c.pending.v1'), durable)
  h.store.set = set; mode = 'ready'; await h.client.withdraw('301', '0')
  assert.deepEqual(h.calls.at(-1), original)
})
test('ambiguous conflict and rate limiting retain exactly the original UUID and body through remount', async () => {
  for (const [code, statusCode] of [['COMMON_CONFLICT', 409], ['IDEMPOTENCY_IN_PROGRESS', 409], ['IDEMPOTENCY_KEY_CONFLICT', 409], ['AFTERSALE_VERSION_CONFLICT', 429], ['COMMON_RATE_LIMITED', 429]] as const) {
    let failed = true
    const h = setup(async () => failed ? { statusCode, data: { code, message: 'busy', data: null, traceId: 't' } } : ok(receipt)); await h.api.restore()
    let error: unknown; try { await h.client.withdraw('301', '0') } catch (caught) { error = caught }
    const original = h.calls.at(-1)!
    assert.throws(() => h.client.retireConflict('301', 'withdraw', error), /UNCONFIRMED_WRITE/)
    const remount = h.make(); await remount.restore(); failed = false
    await new AfterSaleClient(remount, 'c').withdraw('301', '0')
    assert.deepEqual(h.calls.at(-1), original)
  }
})
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

test('authorization and resource-visibility failures cannot erase an earlier unknown aftersale receipt', async () => {
  for (const statusCode of [403, 404]) {
    let mode = 'lost'
    const h = setup(async () => { if (mode === 'lost') throw new Error('network'); if (mode === 'hidden') return { statusCode, data: { code: statusCode === 403 ? 'COMMON_FORBIDDEN' : 'COMMON_NOT_FOUND', message: 'unavailable', data: null, traceId: 't' } }; return ok(receipt) })
    await h.api.restore(); await assert.rejects(h.client.withdraw('301', '0'), /network/)
    const original = h.calls.at(-1)!
    mode = 'hidden'; await assert.rejects(h.client.withdraw('301', '0'), error => error instanceof ApiError && error.statusCode === statusCode)
    const fresh = h.make(); await fresh.restore(); const client = new AfterSaleClient(fresh, 'c')
    assert.deepEqual(client.pending('301', 'withdraw'), { expectedVersion: '0' })
    mode = 'ready'; await client.withdraw('301', '0')
    assert.deepEqual(h.calls.at(-1), original)
  }
})
