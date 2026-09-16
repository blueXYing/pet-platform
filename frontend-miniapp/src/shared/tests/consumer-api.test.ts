import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore } from '../consumer-api'
import { ApiError, type WireRequest, type Transport } from '../request'
import { RealPetRepository, RealProfileRepository } from '../../consumer/api/repositories'
import { fixturePets, emptyDraft } from '../../consumer/pet/model'
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', data } })
const failure = (statusCode: number) => ({ statusCode, data: { code: 'COMMON_UNAUTHORIZED', data: null } })
const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z', phoneMasked: '138****1234' }
const grant = { ...session, tokenType: 'Bearer', accessToken: 'unusable-test-token' }
function storage() {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => { values.set(key, JSON.parse(JSON.stringify(value))) }, remove: key => { values.delete(key) } }
  return { values, store }
}
function setup(extra: Transport = async () => ok(null)) {
  const local = storage(); const calls: WireRequest[] = []
  let fail: Transport | null = null
  const transport: Transport = async request => {
    calls.push(structuredClone(request))
    if (fail) return fail(request)
    if (request.path.endsWith('/attempts')) return ok({ attemptId: '301', attemptToken: 'unusable-attempt-token', nextStep: 'PROVE_IDENTITY' })
    if (request.path.endsWith('/wechat-login')) return ok({ nextStep: 'VERIFY_PHONE' })
    if (request.path.endsWith('/phone-binding')) return ok(grant)
    if (request.path.endsWith('/session')) return ok(session)
    if (request.path.endsWith('/logout')) return ok({ loggedOut: true })
    return extra(request)
  }
  const api = new ConsumerApi(transport, local.store, async () => randomUUID())
  return { api, transport, calls, ...local, fail: (v: Transport | null) => { fail = v } }
}
async function login(api: ConsumerApi) {
  await api.startLogin(async () => ({ code: 'test-code' }))
  assert.equal(api.scope.current, null)
  await api.bindPhone({ code: 'phone-proof', errMsg: 'getPhoneNumber:ok' })
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }

test('real client uses approved two-step login and server session; no client principal in requests', async () => {
  const h = setup(); await login(h.api)
  assert.equal(h.api.scope.current?.userId, session.userId)
  assert.deepEqual(h.calls.map(c => c.path), ['/api/v1/c/auth/attempts', '/api/v1/c/auth/wechat-login', '/api/v1/c/account/phone-binding', '/api/v1/c/auth/session'])
  for (const call of h.calls.filter(c => c.method !== 'GET')) assert.match(call.headers['X-Request-Id'], /^[a-f0-9-]{36}$/)
  assert.ok(h.calls[1].headers['X-Auth-Attempt'])
  assert.equal(h.calls[2].headers.Authorization, undefined)
  assert.ok(h.calls[3].headers.Authorization)
  assert.equal(h.calls.some(c => c.data?.userId), false)
})
test('platform cancellation/empty code/network failure and phone denial never log in', async () => {
  for (const code of [async () => { throw new Error('cancel') }, async () => ({ code: '' })]) {
    const h = setup(); await assert.rejects(h.api.startLogin(code)); assert.equal(h.calls.length, 0); assert.equal(h.api.scope.current, null)
  }
  const h = setup(); await h.api.startLogin(async () => ({ code: 'test' }))
  await assert.rejects(h.api.bindPhone({ errMsg: 'getPhoneNumber:fail user deny' }), /PHONE_AUTH_DENIED/)
  assert.equal(h.api.authStep, 'phone'); assert.equal(h.api.scope.current, null)
  h.fail(async () => { throw new Error('network') })
  await assert.rejects(h.api.bindPhone({ code: 'proof', errMsg: 'getPhoneNumber:ok' }))
  const original = h.calls.at(-1)
  await assert.rejects(h.api.retryLogin())
  assert.deepEqual(h.calls.at(-1), original); assert.equal(h.api.scope.current, null)
})
test('expired code is rejected and a cancelled in-flight login cannot publish a principal', async () => {
  const h = setup(); h.fail(async () => failure(401))
  await assert.rejects(h.api.startLogin(async () => ({ code: 'expired' }))); assert.equal(h.api.scope.current, null)
  h.fail(null)
  const proof = deferred<{ code: string }>(); const running = h.api.startLogin(() => proof.promise)
  h.api.cancelLogin(); proof.resolve({ code: 'late' })
  await assert.rejects(running, /STALE_CONTEXT/); assert.equal(h.api.scope.current, null)
})
test('login duplicate clicks are single flight; grant is not trusted when session query fails', async () => {
  const h = setup(); const proof = deferred<{ code: string }>()
  const first = h.api.startLogin(() => proof.promise)
  const second = h.api.startLogin(async () => { throw new Error('must not call twice') })
  assert.equal(first, second); proof.resolve({ code: 'once' }); await first
  h.fail(async request => request.path.endsWith('/phone-binding') ? ok(grant) : failure(503))
  await assert.rejects(h.api.bindPhone({ code: 'proof', errMsg: 'getPhoneNumber:ok' }))
  assert.equal(h.api.scope.current, null)
})
test('401 clears secrets/context/cache; stale 401 cannot clear a later identity', async () => {
  const h = setup(); await login(h.api)
  const response = deferred<ReturnType<typeof ok>>()
  h.fail(() => response.promise)
  const running = h.api.request({ method: 'GET', path: '/api/v1/c/profile' }, v => v)
  h.api.scope.replace({ userId: '102', workspace: 'consumer', merchantId: null, storeId: null })
  response.resolve(failure(401)); await assert.rejects(running, /STALE_CONTEXT/)
  assert.equal(h.api.scope.current?.userId, '102')
  h.fail(async () => failure(401)); await assert.rejects(h.api.restore())
  assert.equal(h.api.scope.current, null); assert.equal(h.values.has('pet.c.session.v1'), false)
})
test('logout invalidates immediately, persists original revocation request for explicit retry, and blocks new login', async () => {
  const h = setup(); await login(h.api)
  h.fail(async () => { throw new Error('lost logout ACK') })
  await assert.rejects(h.api.logout()); const original = h.calls.at(-1)
  assert.equal(h.api.scope.current, null); assert.equal(h.values.has('pet.c.session.v1'), false)
  const restarted = new ConsumerApi(h.transport, h.store, async () => randomUUID())
  await assert.rejects(restarted.startLogin(async () => ({ code: 'new' })), /LOGOUT_PENDING/)
  h.fail(null); await restarted.logout(); assert.deepEqual(h.calls.at(-1), original)
  assert.equal(h.values.size, 0)
})
test('late business success after logout cannot update data', async () => {
  const response = deferred<ReturnType<typeof ok>>()
  const h = setup(() => response.promise); await login(h.api)
  const running = h.api.request({ method: 'GET', path: '/api/v1/c/pets' }, v => v)
  await h.api.logout(); response.resolve(ok(fixturePets)); await assert.rejects(running, /STALE_CONTEXT/)
})
test('lost write ACK preserves original key/body across process restart; changed payload cannot be resubmitted', async () => {
  let lost = true
  const h = setup(async () => { if (lost) throw new Error('lost ACK'); return ok({ persisted: true }) }); await login(h.api)
  const spec = { method: 'PUT' as const, path: '/api/v1/c/profile', data: { nickname: '保存值' } }
  await assert.rejects(h.api.write('profile', spec, v => v))
  const original = h.calls.at(-1)
  const restarted = new ConsumerApi(h.transport, h.store, async () => randomUUID()); await restarted.restore()
  await assert.rejects(restarted.write('profile', { ...spec, data: { nickname: '另一个值' } }, v => v), /PENDING_WRITE_CHANGED/)
  lost = false; await restarted.write('profile', spec, v => v)
  assert.deepEqual(h.calls.at(-1), original); assert.equal(restarted.pendingCommand('profile'), undefined)
})
test('duplicate business clicks share one write; different in-flight payload is rejected', async () => {
  const response = deferred<ReturnType<typeof ok>>()
  const h = setup(() => response.promise); await login(h.api)
  const spec = { method: 'POST' as const, path: '/api/v1/c/pets', data: { name: 'one' } }
  const a = h.api.write('pet:create', spec, v => v); const b = h.api.write('pet:create', spec, v => v)
  assert.equal(a, b)
  await assert.rejects(h.api.write('pet:create', { ...spec, data: { name: 'two' } }, v => v), /PENDING_WRITE_CHANGED/)
  response.resolve(ok({ petId: '501' })); await a
  assert.equal(h.calls.filter(c => c.path === '/api/v1/c/pets').length, 1)
})
test('profile sends nickname only; real pet edit preserves non-form fields, string weight and immutable type', async () => {
  const h = setup(async request => {
    if (request.path === '/api/v1/c/profile') return ok({ userId: '101', nickname: '昵称', avatarUrl: null, phoneMasked: '138****1234', passwordEnabled: false })
    return ok(fixturePets[0])
  }); await login(h.api)
  const profile = new RealProfileRepository(h.api); const loaded = await profile.load()
  await profile.save({ ...loaded, nickname: '新昵称' }, '')
  assert.deepEqual(h.calls.at(-1)?.data, { nickname: '新昵称' })
  assert.throws(() => profile.save({ ...loaded, signature: 'not approved' }, ''), /PROFILE_FIELDS_NOT_APPROVED/)
  const pets = new RealPetRepository(h.api, async () => { throw new Error('cannot choose type on edit') })
  await pets.get('30001'); await pets.save('30001', { ...emptyDraft, name: '修改', weightInput: '28.5kg' }, '')
  const data = h.calls.at(-1)?.data
  assert.equal(data?.petType, undefined); assert.equal(data?.weightKg, '28.50'); assert.equal(data?.vaccineStatus, 'COMPLETE'); assert.equal(data?.sterilizationStatus, 'NEUTERED'); assert.equal(data?.isDefault, true)
})
test('real pet creation requires explicit approved type, never inherits design samples; no fallback on bad responses', async () => {
  const h = setup(async () => ok(fixturePets[0])); await login(h.api)
  const pets = new RealPetRepository(h.api, async () => 'CAT')
  await pets.save(null, { ...emptyDraft, name: '真实猫' }, '')
  assert.equal(h.calls.at(-1)?.data?.petType, 'CAT'); assert.equal(h.calls.at(-1)?.data?.chipNumber, undefined)
  assert.equal(Object.values(h.calls.at(-1)!.data!).includes(null), false, 'C HTTP request parser rejects explicit null')
  h.fail(async () => ok({ petId: 9007199254740992 }))
  await assert.rejects(pets.get('30001'), /INVALID_RESPONSE/)
})
test('another server identity cannot restore stored credentials or recover another user pending command', async () => {
  const h = setup(async () => { throw new Error('lost ACK') }); await login(h.api)
  await assert.rejects(h.api.write('profile', { method: 'PUT', path: '/api/v1/c/profile', data: { nickname: 'A' } }, v => v))
  h.api.scope.replace({ userId: '102', workspace: 'consumer', merchantId: null, storeId: null })
  assert.equal(h.api.pendingCommand('profile'), undefined)
  const restarted = new ConsumerApi(h.transport, h.store, async () => randomUUID())
  h.fail(async () => ok({ ...session, userId: '102' }))
  await assert.rejects(restarted.restore(), /INVALID_RESPONSE/); assert.equal(restarted.scope.current, null)
})
