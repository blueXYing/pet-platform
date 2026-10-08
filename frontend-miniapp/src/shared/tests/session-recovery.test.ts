import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore } from '../consumer-api'
import { ApiError, type Transport, type WireRequest } from '../request'

// 401 自动恢复（用户 2026-10-08 裁决）：已带凭据的请求遇 401 → 单飞静默重登一次 → 原请求
// 重试；写请求沿用同一 command（同 X-Request-Id）重试；恢复失败/二次 401/工作区坐标变化/
// 显式退出后一律走原错误路径。

const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z', phoneMasked: '138****0100' }
const grant = { ...session, tokenType: 'Bearer', accessToken: 'stale-token' }
const reloginGrant = { ...session, sessionId: '202', tokenType: 'Bearer', accessToken: 'fresh-token' }
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', data } })
const unauthorized = () => ({ statusCode: 401, data: { code: 'COMMON_UNAUTHORIZED', data: null } })

function storage(): { values: Map<string, unknown>; store: LocalStore } {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => { values.set(key, JSON.parse(JSON.stringify(value))) }, remove: key => { values.delete(key) } }
  return { values, store }
}

type Handler = (request: WireRequest) => unknown
function harness(handlers: Record<string, Handler>, withAutoLogin: boolean) {
  const local = storage()
  const calls: WireRequest[] = []
  const transport: Transport = async request => {
    calls.push(structuredClone(request))
    const entry = Object.entries(handlers).find(([suffix]) => request.path.endsWith(suffix))
    if (!entry) throw new Error('UNSCRIPTED ' + request.path)
    const value = entry[1](request)
    return value instanceof Promise ? value : value as never
  }
  let loginCalls = 0
  const api = new ConsumerApi(transport, local.store, async () => randomUUID(), undefined, undefined,
    withAutoLogin ? async () => { loginCalls++; await api.startLogin(async () => ({ code: 'wx-code' })) } : undefined)
  return { api, calls, ...local, loginCalls: () => loginCalls }
}

async function login(api: ConsumerApi) {
  // 本文件的用户均已绑手机号：wechat-login 一步发 SessionGrant（E2E 2026-10-07 同缝）。
  await api.startLogin(async () => ({ code: 'code' }))
  assert.equal(api.scope.current?.userId, '101')
  assert.equal(api.currentSession?.phoneMasked, '138****0100')
}

test('server 401 on a sent request triggers one silent re-login and retries the original request', async () => {
  let profileCalls = 0
  let logins = 0
  const h = harness({
    '/profile': () => { profileCalls++; return profileCalls === 1 ? unauthorized() : ok({ nickname: '回归', phoneMasked: '138****0100', avatarUrl: null }) },
    '/attempts': () => ok({ attemptId: '301', attemptToken: 'attempt-token', nextStep: 'PROVE_IDENTITY' }),
    // 首次登录发 stale-token（随后过期），静默重登发 fresh-token。
    '/wechat-login': () => { logins++; return ok(logins === 1 ? grant : reloginGrant) },
    '/session': () => ok({ ...session, sessionId: logins === 1 ? '201' : '202' }),
  }, true)
  await login(h.api)
  const profile = await h.api.request({ method: 'GET', path: '/api/v1/c/profile' }, value => value as { nickname: string })
  assert.equal(profile.nickname, '回归')
  assert.equal(profileCalls, 2)
  assert.equal(h.loginCalls(), 1)
  // 恢复后重试的原请求带新凭据。
  assert.equal(h.calls.filter(c => c.path.endsWith('/profile'))[0].headers.Authorization, 'Bearer stale-token')
  assert.equal(h.calls.at(-1)!.headers.Authorization, 'Bearer fresh-token')
})

test('concurrent 401s share one recovery flight; a failed recovery keeps the original 401 path', async () => {
  let authOk = true
  let businessOk = false
  const h = harness({
    '/pets': () => (businessOk ? ok([]) : unauthorized()),
    '/messages': () => (businessOk ? ok([]) : unauthorized()),
    '/attempts': () => (authOk ? ok({ attemptId: '301', attemptToken: 't', nextStep: 'PROVE_IDENTITY' }) : unauthorized()),
    '/wechat-login': () => (authOk ? ok(reloginGrant) : unauthorized()),
    '/session': () => ok({ ...session, sessionId: '202' }),
  }, true)
  await login(h.api)
  authOk = false // 恢复链路同样被拒：静默重登失败，两条原请求都按原错误路径拒绝
  // 既有语义：首个 401 在恢复分支里 clear() 使 scope 失效，随后完成的并发请求按 STALE_CONTEXT
  // 拒绝（与无恢复时并发 401 的原行为一致）；防重入保证只触发一次静默重登。
  await Promise.all([
    assert.rejects(h.api.request({ method: 'GET', path: '/api/v1/c/pets' }, v => v)),
    assert.rejects(h.api.request({ method: 'GET', path: '/api/v1/c/messages' }, v => v)),
  ])
  assert.equal(h.loginCalls(), 1) // 防重入：并发只触发一次重登
  assert.equal(h.api.currentSession, null)
  assert.equal(h.values.has('pet.c.session.v1'), false)
  // 二次 401 不再恢复：恢复成功但服务端仍拒绝（businessOk 仍 false）时走原错误路径。
  authOk = true
  await login(h.api)
  await assert.rejects(h.api.request({ method: 'GET', path: '/api/v1/c/pets' }, v => v), ApiError)
  assert.equal(h.loginCalls(), 2)
})

test('a 401 write recovers the session and replays the exact journaled command (same X-Request-Id)', async () => {
  let puts = 0
  const h = harness({
    '/profile': (request) => {
      if (request.method === 'PUT') { puts++; return puts === 1 ? unauthorized() : ok({ nickname: '保存值', phoneMasked: '138****0100', avatarUrl: null }) }
      return ok({ nickname: '', phoneMasked: '', avatarUrl: null })
    },
    '/attempts': () => ok({ attemptId: '301', attemptToken: 'attempt-token', nextStep: 'PROVE_IDENTITY' }),
    '/wechat-login': () => ok(reloginGrant),
    '/session': () => ok({ ...session, sessionId: '202' }),
  }, true)
  await login(h.api)
  const spec = { method: 'PUT' as const, path: '/api/v1/c/profile', data: { nickname: '保存值' } }
  // 静默重登内部的 clear() 使在途 write 的 scope 票据失效（既有语义：in-flight 工作失效），
  // 但 command 已入日志：同一槽位重试沿用原 X-Request-Id 幂等重放，不二次创建。
  await assert.rejects(h.api.write('profile', spec, value => value as { nickname: string }), /STALE_CONTEXT/)
  const saved = await h.api.write('profile', spec, value => value as { nickname: string })
  assert.equal(saved.nickname, '保存值')
  assert.equal(puts, 3) // 401 一次 + 恢复后重试一次 + 幂等重放一次
  const putRequests = h.calls.filter(c => c.path.endsWith('/profile') && c.method === 'PUT')
  assert.equal(putRequests[0].headers['X-Request-Id'], putRequests[1].headers['X-Request-Id'])
  assert.equal(putRequests[1].headers['X-Request-Id'], putRequests[2].headers['X-Request-Id'])
  assert.equal(h.api.pendingCommand('profile'), undefined)
})

test('explicit logout blocks auto recovery for this process; unauthenticated preflight 401 never recovers', async () => {
  const h = harness({
    '/profile': () => ok({ nickname: 'x', phoneMasked: '', avatarUrl: null }),
    '/logout': () => ok({ loggedOut: true }),
    '/attempts': () => ok({ attemptId: '301', attemptToken: 't', nextStep: 'PROVE_IDENTITY' }),
    '/wechat-login': () => ok(reloginGrant),
    '/session': () => ok({ ...session, sessionId: '202' }),
  }, true)
  await login(h.api)
  await h.api.logout()
  // 既有语义：退出后 scope 已清空，未登录的预检先抛 NO_CONTEXT（无凭据可送，不触发恢复）。
  await assert.rejects(h.api.request({ method: 'GET', path: '/api/v1/c/profile' }, v => v), /NO_CONTEXT/)
  assert.equal(h.loginCalls(), 0)
})

test('without autoLogin wiring the original 401 semantics are byte-for-byte preserved', async () => {
  const h = harness({
    '/profile': () => unauthorized(),
    '/attempts': () => ok({ attemptId: '301', attemptToken: 't', nextStep: 'PROVE_IDENTITY' }),
    '/wechat-login': () => ok(reloginGrant),
    '/session': () => ok({ ...session, sessionId: '202' }),
  }, false)
  await login(h.api)
  await assert.rejects(h.api.request({ method: 'GET', path: '/api/v1/c/profile' }, v => v), ApiError)
  assert.equal(h.loginCalls(), 0)
  assert.equal(h.api.currentSession, null)
})
