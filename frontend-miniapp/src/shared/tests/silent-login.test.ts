import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore } from '../consumer-api'
import type { Transport } from '../request'
import { createSilentLogin } from '../silent-login'

// 静默登录状态机（用户 2026-10-08 裁决）：launch 先恢复持久化会话，无有效凭据才静默登录；
// 无手机号用户停在校验手机号一步（不发会话）；ensure 单飞；退出未决/进行中不静默复活。

const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z', phoneMasked: '138****0100' }
const grant = { ...session, tokenType: 'Bearer', accessToken: 'access-token' }
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', data } })
const unauthorized = () => ({ statusCode: 401, data: { code: 'COMMON_UNAUTHORIZED', data: null } })

function storage(): { values: Map<string, unknown>; store: LocalStore } {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => { values.set(key, JSON.parse(JSON.stringify(value))) }, remove: key => { values.delete(key) } }
  return { values, store }
}

type Script = Record<string, (request: { path: string; method: string }) => unknown>
function scriptedTransport(script: Script): Transport {
  return async request => {
    const entry = Object.entries(script).find(([suffix]) => request.path.endsWith(suffix))
    if (!entry) throw new Error('UNSCRIPTED ' + request.path)
    const value = entry[1](request)
    if (value instanceof Promise) return value
    return value as never
  }
}

test('launch validates the persisted grant first and never mints a fresh login over it', async () => {
  const { values, store } = storage()
  values.set('pet.c.session.v1', grant)
  const calls: string[] = []
  const api = new ConsumerApi(scriptedTransport({
    '/session': () => { calls.push('session'); return ok(session) },
  }), store, async () => randomUUID())
  const silent = createSilentLogin(api, async () => { throw new Error('login must not run') })
  assert.equal(await silent.launch(), 'authenticated')
  assert.deepEqual(calls, ['session'])
})

test('launch with no valid grant silent-logins; a phone-less identity stops at phone-required', async () => {
  const { store } = storage()
  const calls: string[] = []
  const api = new ConsumerApi(scriptedTransport({
    '/session': () => unauthorized(),
    '/attempts': () => { calls.push('attempts'); return ok({ attemptId: '301', attemptToken: 'attempt-token', nextStep: 'PROVE_IDENTITY' }) },
    '/wechat-login': () => { calls.push('wechat-login'); return ok({ nextStep: 'VERIFY_PHONE' }) },
  }), store, async () => randomUUID())
  const silent = createSilentLogin(api, async () => { calls.push('wx.login'); return { code: 'wx-code' } })
  assert.equal(await silent.launch(), 'phone-required')
  assert.deepEqual(calls, ['wx.login', 'attempts', 'wechat-login'])
  assert.equal(api.authStep, 'phone')
  assert.equal(api.currentSession, null)
})

test('launch with no stored grant goes straight to silent login and authenticates a phone-bound user', async () => {
  const { store } = storage()
  const api = new ConsumerApi(scriptedTransport({
    '/attempts': () => ok({ attemptId: '301', attemptToken: 'attempt-token', nextStep: 'PROVE_IDENTITY' }),
    '/wechat-login': () => ok(grant),
    '/session': () => ok(session),
  }), store, async () => randomUUID())
  const silent = createSilentLogin(api, async () => ({ code: 'wx-code' }))
  assert.equal(await silent.launch(), 'authenticated')
  assert.equal(api.currentSession?.phoneMasked, '138****0100')
  // 已认证后 ensure 直接短路，不再发起登录链。
  assert.equal(await silent.ensure(), 'authenticated')
})

test('ensure is single-flight: concurrent callers share one silent login', async () => {
  const { store } = storage()
  let attempts = 0
  const api = new ConsumerApi(scriptedTransport({
    '/attempts': () => { attempts++; return ok({ attemptId: '301', attemptToken: 'attempt-token', nextStep: 'PROVE_IDENTITY' }) },
    '/wechat-login': () => ok(grant),
    '/session': () => ok(session),
  }), store, async () => randomUUID())
  const silent = createSilentLogin(api, async () => ({ code: 'wx-code' }))
  const [a, b, c] = await Promise.all([silent.ensure(), silent.ensure(), silent.ensure()])
  assert.deepEqual([a, b, c], ['authenticated', 'authenticated', 'authenticated'])
  assert.equal(attempts, 1)
})

test('pending logout blocks silent login (never resurrects an explicit logout); network faults stay unavailable', async () => {
  // 显式退出且回执丢失：重启后 logout 命令仍持久化，静默登录必须拒绝。
  const { values, store } = storage()
  values.set('pet.c.session.v1', grant)
  values.set('pet.c.logout.v1', { token: 'access-token', command: { method: 'POST', path: '/api/v1/c/auth/logout', data: {}, requestId: randomUUID() } })
  let logins = 0
  const api = new ConsumerApi(scriptedTransport({}), store, async () => randomUUID())
  const silent = createSilentLogin(api, async () => { logins++; return { code: 'wx-code' } })
  assert.equal(await silent.launch(), 'unavailable')
  assert.equal(logins, 0)
  assert.equal(api.currentSession, null)

  // 网络/服务故障（有已存凭据但 session 校验不通）：不静默登录，页面按原错误路径承接。
  const faultStore = storage()
  faultStore.values.set('pet.c.session.v1', grant)
  const offline = new ConsumerApi(async () => { throw new Error('network down') }, faultStore.store, async () => randomUUID())
  const offlineSilent = createSilentLogin(offline, async () => { throw new Error('login must not run') })
  assert.equal(await offlineSilent.launch(), 'unavailable')
})
