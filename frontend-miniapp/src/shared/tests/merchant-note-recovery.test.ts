import test from 'node:test'
import assert from 'node:assert/strict'
import { ConsumerApi, type LocalStore } from '../consumer-api'
import type { RequestSpec } from '../request'
import { MerchantCustomerNoteClient } from '../merchant-storefront-api'
import type { Workspace } from '../workspace'

/** 2026-11-17 客户备注保存失败根因回归：会话过期静默重登不得冲掉商家工作区坐标。
 *  场景：商家坐标下的 PUT 备注遇 401 → clear() 复位 scope → 静默重登（attempts→
 *  wechat-login→session，restore 把 scope 落回消费者默认态）→ 恢复后必须还原原商家
 *  坐标并按原请求重试成功；期间用户主动切到别的非默认工作区则不得回写。 */
const sessionView = { sessionId: '1161', userId: '710100', audience: 'MINIAPP', expiresAt: '2026-11-17T10:00:00.000Z', phoneMasked: '138****0100' }
const ok = (x: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data: x, success: true } })
const unauthorized = () => ({ statusCode: 401, data: { code: 'COMMON_UNAUTHORIZED', message: 'unauthorized', data: null, success: false } })
const grantView = { ...sessionView, sessionId: '1162', accessToken: 'fresh-token', tokenType: 'Bearer' }
const noteReceipt = { userId: '710100', note: { content: '客户怕吹风机', version: '4', updatedAt: '2026-11-17T09:50:00.000Z' } }

function build(transport: (request: { method: string; path: string; data?: Record<string, unknown>; requestId?: string; headers: Record<string, string> }) => Promise<{ statusCode: number; data: unknown }>) {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, JSON.parse(JSON.stringify(value))), remove: key => { values.delete(key) } }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'stale-token', tokenType: 'Bearer' })
  // autoLogin 是构造器第 6 参（uploadTransport/afterSaleAssets 之后）
  const api: ConsumerApi = new ConsumerApi(transport, store, async () => crypto.randomUUID(), undefined, undefined,
    () => api.startLogin(() => Promise.resolve({ code: 'wx-code' })))
  return { api, store }
}

test('silent relogin restores merchant coordinates and retries the original note write', async () => {
  const seen: { method: string; path: string; auth?: string }[] = []
  let noteAttempts = 0
  const { api, store } = build(async request => {
    seen.push({ method: request.method, path: request.path, auth: request.headers.Authorization })
    if (request.path === '/api/v1/c/auth/session') return ok({ ...sessionView, sessionId: request.headers.Authorization === 'Bearer fresh-token' ? '1162' : sessionView.sessionId })
    if (request.path === '/api/v1/c/auth/attempts') return ok({ attemptId: '9001', attemptToken: 't1', nextStep: 'PROVE_IDENTITY' })
    if (request.path === '/api/v1/c/auth/wechat-login') return ok(grantView)
    if (request.path === '/api/v1/merchant/customers/710100/note' && request.method === 'PUT') {
      noteAttempts++
      if (noteAttempts === 1) return unauthorized()
      return ok(noteReceipt)
    }
    throw new Error('unexpected ' + request.method + ' ' + request.path)
  })
  const merchant: Workspace = { userId: '710100', workspace: 'merchant', merchantId: '710301', storeId: '710302' }
  api.scope.replace(merchant)
  await api.restore()
  const spec: RequestSpec = { path: '/api/v1/merchant/customers/710100/note', method: 'PUT', requestId: '11111111-2222-4333-8444-555555555555', data: { merchantId: '710301', storeId: '710302', note: '客户怕吹风机' } }
  const slot = 'merchant-customer-note:710100:710301:710302:710100'
  // 恢复链抬升 revision：在途 write 的 ticket 失效（既有语义），先以 STALE_CONTEXT 收口
  await assert.rejects(api.write(slot, spec, value => value), /STALE_CONTEXT/)
  // 恢复后 scope 仍是原商家坐标（没有被静默重登复位成消费者默认态）——这是本次修复点
  const context = api.scope.current
  assert.equal(context && context.workspace, 'merchant')
  assert.equal(context && context.merchantId, '710301')
  // 同 command 重放（同 X-Request-Id）：无需重新进入工作台即可完成保存
  const saved = await api.write(slot, spec, value => {
    assert.equal((value as typeof noteReceipt).note.version, '4'); return value as typeof noteReceipt
  })
  assert.ok(saved)
  // 第2次=恢复链内按原 command 重试（服务端已成功）；第3次=页面同 command 重放拿回收据
  assert.equal(noteAttempts, 3)
  assert.equal(seen.at(-1)!.auth, 'Bearer fresh-token')
  // 幂等槽位成功后清空（空对象即无在途命令）
  assert.deepEqual(store.get('pet.c.pending.v1'), {})
})

test('a user-driven workspace switch during recovery is never clobbered back', async () => {
  let switched = false
  const { api, store } = build(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok({ ...sessionView, sessionId: request.headers.Authorization === 'Bearer fresh-token' ? '1162' : sessionView.sessionId })
    if (request.path === '/api/v1/c/auth/attempts') {
      // 恢复进行中用户切换了工作区（非默认态）——恢复完成后不得回写成原商家坐标
      switched = true
      api.scope.replace({ userId: '710100', workspace: 'merchant', merchantId: '999301', storeId: '999302' })
      return { statusCode: 201, data: { attemptId: '9001', attemptToken: 't1', nextStep: 'PROVE_IDENTITY' } }
    }
    if (request.path === '/api/v1/c/auth/wechat-login') return ok(grantView)
    if (request.path === '/api/v1/merchant/customers/710100/note' && request.method === 'PUT') return unauthorized()
    throw new Error('unexpected ' + request.method + ' ' + request.path)
  })
  api.scope.replace({ userId: '710100', workspace: 'merchant', merchantId: '710301', storeId: '710302' })
  await api.restore()
  const spec: RequestSpec = { path: '/api/v1/merchant/customers/710100/note', method: 'PUT', requestId: '11111111-2222-4333-8444-555555555556', data: { merchantId: '710301', storeId: '710302', note: 'x' } }
  await assert.rejects(api.write('merchant-customer-note:710100:710301:710302:710100', spec, value => value), /STALE_CONTEXT|COMMON_UNAUTHORIZED/)
  assert.ok(switched)
  const context = api.scope.current
  assert.equal(context && context.merchantId, '999301')
})

test('note save settles a drifted journal by replaying the original command, then saves the new intent', async () => {
  const seen: { requestId?: string; note?: string; expectedVersion?: string }[] = []
  // 预置毒槽位：未确认成功的旧命令（文本 A / requestId 旧值）
  const staleSlot = 'merchant-customer-note:710100:710301:710302:710100'
  const staleCommand = { path: '/api/v1/merchant/customers/710100/note', method: 'PUT', data: { merchantId: '710301', storeId: '710302', note: 'A' }, requestId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee' }
  const values = new Map<string, unknown>([['pet.c.pending.v1', { [staleSlot]: { userId: '710100', command: staleCommand } }], ['pet.c.session.v1', { ...sessionView, accessToken: 'live-token', tokenType: 'Bearer' }]])
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, JSON.parse(JSON.stringify(value))), remove: key => { values.delete(key) } }
  const api = new ConsumerApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok({ ...sessionView })
    if (request.path === '/api/v1/merchant/customers/710100/note' && request.method === 'PUT') {
      const data = request.data as { note: string }
      seen.push({ requestId: request.requestId, note: data.note })
      // 重放旧 requestId=领取服务端缓存的 v5 收据；新 requestId=正常保存到 v6
      return ok({ userId: '710100', note: { content: data.note, version: request.requestId === staleCommand.requestId ? '5' : '6', updatedAt: '2026-11-18T01:00:00.000Z' } })
    }
    throw new Error('unexpected ' + request.method + ' ' + request.path)
  }, store, async () => crypto.randomUUID())
  api.scope.replace({ userId: '710100', workspace: 'merchant', merchantId: '710301', storeId: '710302' })
  await api.restore()
  // 用户改文本 B 再保存——漂移结算后应一次成功
  const client = new MerchantCustomerNoteClient(api)
  const saved = await client.save('710100', 'B')
  assert.equal(saved.note.version, '6')
  assert.equal(seen.length, 2)
  assert.equal(seen[0]!.requestId, staleCommand.requestId) // 结算重放沿用原 X-Request-Id
  assert.equal(seen[0]!.note, 'A')
  assert.equal(seen[1]!.note, 'B')
  assert.deepEqual(store.get('pet.c.pending.v1'), {})
})
