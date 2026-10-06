import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import {
  actionFor, decodeCredentialView, decodeIssueReceipt, formatCode, formatInstant, isDefiniteVerifyConflict,
  isOrderVerifyScenario, remainingLabel, statusHints, statusLabels, verifyMessage,
  OrderVerifyController, PreviewOrderVerifyRepository, credentialStatuses,
  type CredentialStatus, type CredentialView, type OrderVerifyDeps,
} from '../order-verify/model'
import { RealOrderVerifyRepository } from '../order-verify/repository'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { ApiError } from '../../shared/request'
import { WorkspaceScope } from '../../shared/workspace'

// ---- 解码：#114 线上形状（47号 §4 v0.2：恰七键、仅 ACTIVE 回显码、refreshAfter=expiresAt）。

const wireActive = {
  orderId: '900101001990001', credentialVersion: '1', status: 'ACTIVE',
  code: '7QF3K2M9ZH5T8W1XR4B6YC9D2GH0J3A5',
  expiresAt: '2026-10-06T09:20:00.000Z', refreshAfter: '2026-10-06T09:20:00.000Z', lockedUntil: null,
}
test('view decoder accepts the #114 wire projection for all five statuses', () => {
  assert.equal(decodeCredentialView(JSON.parse(JSON.stringify(wireActive))).code, wireActive.code)
  // EXPIRED：码不回显但旧截止仍在；NONE/INVALIDATED：码与时间为 null。
  const expired = decodeCredentialView({ ...wireActive, status: 'EXPIRED', code: null })
  assert.equal(expired.code, null); assert.equal(expired.expiresAt, wireActive.expiresAt)
  for (const status of ['NONE', 'INVALIDATED'] as const) {
    const view = decodeCredentialView({ ...wireActive, status, code: null, expiresAt: null, refreshAfter: null })
    assert.equal(view.status, status); assert.equal(view.code, null); assert.equal(view.lockedUntil, null)
  }
  // LOCKED：不回显码，必带 lockedUntil（后端 locked(st,now) 判定即解除时间存在）。
  const locked = decodeCredentialView({ ...wireActive, status: 'LOCKED', code: null, lockedUntil: '2026-10-06T09:35:00.000Z' })
  assert.equal(locked.lockedUntil, '2026-10-06T09:35:00.000Z')
  assert.equal(credentialStatuses.length, 5)
  for (const status of credentialStatuses) { assert.ok(statusLabels[status]); assert.ok(statusHints[status].length > 4) }
})

test('view decoder fails closed on contract violations', () => {
  for (const mutate of [
    (v: Record<string, unknown>) => { v.extra = 1 },
    (v: Record<string, unknown>) => { delete v.lockedUntil },
    (v: Record<string, unknown>) => { v.status = 'FROZEN' },
    (v: Record<string, unknown>) => { v.status = 'EXPIRED' },                       // EXPIRED 不回显码
    (v: Record<string, unknown>) => { v.code = null },                              // ACTIVE 无码
    (v: Record<string, unknown>) => { v.code = '7QF3K2M9ZH5T8W1XR4B6YC9D2GH0J3A5I' }, // 字母表含 I
    (v: Record<string, unknown>) => { v.code = '7qf3k2m9zh5t8w1xr4b6yc9d2gl0hj3a' }, // 小写
    (v: Record<string, unknown>) => { v.expiresAt = '2026-10-06T09:20:00Z' },       // 缺毫秒
    (v: Record<string, unknown>) => { v.refreshAfter = '2026-10-06T09:18:00.000Z' },// refreshAfter≠expiresAt
    (v: Record<string, unknown>) => { v.credentialVersion = '01' },
    (v: Record<string, unknown>) => { v.orderId = '0' },
    (v: Record<string, unknown>) => { v.status = 'LOCKED', v.code = null },          // LOCKED 无 lockedUntil
    (v: Record<string, unknown>) => { v.code = null, v.expiresAt = null, v.refreshAfter = null }, // ACTIVE 无时间
  ]) {
    const value = JSON.parse(JSON.stringify(wireActive)) as Record<string, unknown>
    mutate(value)
    assert.throws(() => decodeCredentialView(value), /INVALID_RESPONSE/, JSON.stringify(mutate))
  }
})

const wireReceipt = {
  orderId: '900101001990001', credentialId: '910101001990002', credentialVersion: '2',
  code: '4TBM8Q2XK7RN3WZH5JD9PF6VWSAYC2GE',
  issuedAt: '2026-10-06T09:15:00.000Z', expiresAt: '2026-10-06T09:20:00.000Z', refreshAfter: '2026-10-06T09:20:00.000Z',
}
test('issue receipt decoder enforces the seven-key shape and refreshAfter=expiresAt', () => {
  assert.equal(decodeIssueReceipt(JSON.parse(JSON.stringify(wireReceipt))).credentialVersion, '2')
  for (const mutate of [
    (v: Record<string, unknown>) => { v.issuedAt = null },
    (v: Record<string, unknown>) => { delete v.credentialId },
    (v: Record<string, unknown>) => { v.refreshAfter = '2026-10-06T09:18:00.000Z' },
    (v: Record<string, unknown>) => { v.code = '4TBM8Q2XK7RN3WZH5JD9PF6VWSAYC2GELO' }, // 33 位
    (v: Record<string, unknown>) => { v.credentialId = '0' },
  ]) {
    const value = JSON.parse(JSON.stringify(wireReceipt)) as Record<string, unknown>
    mutate(value)
    assert.throws(() => decodeIssueReceipt(value), /INVALID_RESPONSE/, JSON.stringify(mutate))
  }
})

// ---- 状态 → 操作映射与文案（47号内核规则：INITIAL 仅无当前码、AUTO 仅过期、MANUAL 滚动限频）。

test('action mapping follows the kernel refresh-kind rules and LOCKED has no write', () => {
  assert.deepEqual(actionFor('NONE'), { kind: 'INITIAL', label: '获取核销码' })
  assert.deepEqual(actionFor('INVALIDATED'), { kind: 'INITIAL', label: '获取新核销码' })
  assert.deepEqual(actionFor('ACTIVE'), { kind: 'MANUAL', label: '刷新核销码' })
  assert.deepEqual(actionFor('EXPIRED'), { kind: 'AUTO', label: '刷新已过期核销码' })
  assert.equal(actionFor('LOCKED'), null)
})

test('verify message maps the 47号/12号 error surface for the page', () => {
  assert.equal(verifyMessage(new ApiError('COMMON_UNAUTHORIZED', 401)), '登录已失效，请重新登录后查看')
  assert.equal(verifyMessage(new ApiError('VERIFICATION_BLOCKED_BY_REFUND', 409)), '该订单已创建退款单，核销码不可用')
  assert.equal(verifyMessage(new ApiError('VERIFICATION_ALREADY_DONE', 409)), '该订单已核销完成，无需再出示核销码')
  assert.equal(verifyMessage(new ApiError('COMMON_RATE_LIMITED', 429)), '刷新太频繁：每60秒最多刷新5次，请稍后重试原操作')
  assert.equal(verifyMessage(new ApiError('VERIFICATION_RISK_LOCKED', 409)), '多次无效尝试已触发安全锁定，暂时无法获取核销码，请稍后重试原操作')
  assert.equal(verifyMessage(new ApiError('COMMON_CONFLICT', 409)), '核销码状态已变化（可能已刷新或改期），请重新读取后操作')
  assert.equal(verifyMessage(new ApiError('COMMON_FORBIDDEN', 403)), '仅订单本人可查看核销码，或订单不存在')
  assert.equal(verifyMessage(new ApiError('COMMON_NOT_FOUND', 404)), '核销码服务未开放或订单不存在，请稍后再试')
  assert.equal(verifyMessage(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), '服务暂不可用，请稍后重试')
  assert.equal(verifyMessage(new Error('PENDING_WRITE_CHANGED')), '上次取码/刷新结果尚未确认，请先重试原操作')
  assert.equal(isDefiniteVerifyConflict(new ApiError('COMMON_CONFLICT', 409)), true)
  assert.equal(isDefiniteVerifyConflict(new ApiError('VERIFICATION_RISK_LOCKED', 409)), false)
  assert.equal(isDefiniteVerifyConflict(new ApiError('COMMON_CONFLICT', 500)), false)
})

test('code grouping, countdown and UTC+8 display are deterministic', () => {
  assert.equal(formatCode('7QF3K2M9ZH5T8W1XR4B6YC9D2GH0J3A5'), '7QF3K2M9 ZH5T8W1X R4B6YC9D 2GH0J3A5')
  assert.equal(formatCode(null), '—')
  const expiresAt = '2026-10-06T09:20:00.000Z'
  assert.equal(remainingLabel(Date.parse(expiresAt) - 65000, expiresAt), '1分05秒')
  assert.equal(remainingLabel(Date.parse(expiresAt) - 5000, expiresAt), '5秒')
  assert.equal(remainingLabel(Date.parse(expiresAt), expiresAt), '')
  assert.equal(remainingLabel(Date.now(), null), '')
  assert.equal(formatInstant('2026-10-06T09:20:00.000Z'), '2026-10-06 17:20:00（北京时间）')
  assert.equal(formatInstant(null), '—')
  assert.equal(isOrderVerifyScenario('locked'), true)
  assert.equal(isOrderVerifyScenario('frozen'), false)
})

// ---- preview 夹具通道：本地状态机模拟内核规则（设计验收不发网络请求）。

test('preview repository walks the credential lifecycle by kernel rules with an injected clock', async () => {
  let clock = Date.parse('2026-10-06T09:00:00.000Z')
  const repository = new PreviewOrderVerifyRepository('none', () => clock)
  const none = await repository.view('900101001990001')
  assert.equal(none.status, 'NONE'); assert.equal(none.credentialVersion, '0')
  // INITIAL 仅无当前码：先按错误版本发 → 版本冲突；正确版本 → 取码成功。
  await assert.rejects(repository.issue('900101001990001', '1', 'INITIAL'), /COMMON_CONFLICT/)
  const first = await repository.issue('900101001990001', '0', 'INITIAL')
  assert.equal(first.credentialVersion, '1'); assert.ok(/^[0-9A-HJKMNP-TVWXYZ]{32}$/.test(first.code))
  const active = await repository.view('900101001990001')
  assert.equal(active.status, 'ACTIVE'); assert.equal(active.code, first.code)
  // INITIAL 在已有当前码时被内核拒绝；AUTO 仅过期后允许。
  await assert.rejects(repository.issue('900101001990001', '1', 'INITIAL'), /COMMON_CONFLICT/)
  await assert.rejects(repository.issue('900101001990001', '1', 'AUTO'), /COMMON_CONFLICT/)
  // MANUAL 滚动 60 秒最多 5 次，第 6 次 429（每次成功 version+1，expected 携带当前 version）。
  for (let round = 0; round < 5; round++) {
    clock += 1000
    await repository.issue('900101001990001', String(1 + round), 'MANUAL')
  }
  clock += 1000
  await assert.rejects(repository.issue('900101001990001', '6', 'MANUAL'), (error: unknown) => error instanceof ApiError && error.code === 'COMMON_RATE_LIMITED')
  // 窗口滑出后可再次刷新；码滚动、旧码不复活。
  clock += 61000
  const next = await repository.issue('900101001990001', '6', 'MANUAL')
  assert.equal(next.credentialVersion, '7'); assert.notEqual(next.code, first.code)
  // 过期推进：ACTIVE 越过截止即 EXPIRED（码隐藏、旧截止保留），随后 AUTO 刷新。
  clock = Date.parse(next.expiresAt) + 1
  const expired = await repository.view('900101001990001')
  assert.equal(expired.status, 'EXPIRED'); assert.equal(expired.code, null); assert.equal(expired.expiresAt, next.expiresAt)
  const renewed = await repository.issue('900101001990001', '7', 'AUTO')
  assert.equal(renewed.credentialVersion, '8')
  // 非订单号形状按同一失败路径拒绝。
  await assert.rejects(repository.view('not-an-id'), /COMMON_NOT_FOUND/)
})

test('preview scenarios cover locked (code hidden) and invalidated (reschedule fence)', async () => {
  const locked = new PreviewOrderVerifyRepository('locked', () => Date.parse('2026-10-06T09:00:00.000Z'))
  const lockedView = await locked.view('900101001990001')
  assert.equal(lockedView.status, 'LOCKED'); assert.equal(lockedView.code, null); assert.ok(lockedView.lockedUntil)
  await assert.rejects(locked.issue('900101001990001', '3', 'MANUAL'), (error: unknown) => error instanceof ApiError && error.code === 'VERIFICATION_RISK_LOCKED')
  const invalidated = new PreviewOrderVerifyRepository('invalidated')
  const view = await invalidated.view('900101001990001')
  assert.equal(view.status, 'INVALIDATED'); assert.equal(view.code, null)
  // 改期后的新 epoch：INITIAL 重新取码。
  const reissued = await invalidated.issue('900101001990001', '2', 'INITIAL')
  assert.equal(reissued.credentialVersion, '3')
})

// ---- 控制器：读取失败关闭、写幂等槽恢复、终局 409 退槽重读、429 保留原命令。

type ScriptedDeps = OrderVerifyDeps & { issued: { orderId: string; version: string; kind: string }[]; retired: unknown[]; views: number }

function controllerHarness(script: { view?: CredentialView; viewError?: unknown; issueError?: unknown }) {
  const scope = new WorkspaceScope()
  scope.replace({ userId: '957001', workspace: 'consumer', merchantId: null, storeId: null })
  let pendingIssue: { expectedCredentialVersion: string; refreshKind: 'INITIAL' | 'AUTO' | 'MANUAL' } | null = null
  let retired = false
  const deps: ScriptedDeps = {
    views: 0,
    issued: [],
    retired: [],
    async view(orderId) { deps.views++; if (script.viewError) throw script.viewError; return { ...script.view!, orderId } as CredentialView },
    async issue(orderId, expectedCredentialVersion, refreshKind) {
      deps.issued.push({ orderId, version: expectedCredentialVersion, kind: refreshKind })
      if (script.issueError) {
        if (isDefiniteVerifyConflict(script.issueError) && !retired) throw script.issueError
        throw script.issueError
      }
      pendingIssue = null
      return { orderId, credentialId: '910101001990002', credentialVersion: String(Number(expectedCredentialVersion) + 1),
        code: '4TBM8Q2XK7RN3WZH5JD9PF6VWSAYC2GE', issuedAt: '2026-10-06T09:15:00.000Z',
        expiresAt: '2026-10-06T09:20:00.000Z', refreshAfter: '2026-10-06T09:20:00.000Z' }
    },
    pendingIssue: () => pendingIssue,
    retireConflict(orderId, error) {
      if (retired || !isDefiniteVerifyConflict(error)) throw new Error('UNCONFIRMED_WRITE')
      retired = true; deps.retired.push(error); pendingIssue = null
    },
  }
  // 模拟 ConsumerApi.write：发起写即挂起未确认命令，直到成功/退槽。
  const originalIssue = deps.issue
  deps.issue = async (orderId, version, kind) => {
    pendingIssue = { expectedCredentialVersion: version, refreshKind: kind }
    return originalIssue(orderId, version, kind)
  }
  const controller = new OrderVerifyController(deps, scope)
  return { controller, deps, scope }
}

const activeView: CredentialView = {
  orderId: '900101001990001', credentialVersion: '1', status: 'ACTIVE', code: '7QF3K2M9ZH5T8W1XR4B6YC9D2GH0J3A5',
  expiresAt: '2099-01-01T00:00:00.000Z', refreshAfter: '2099-01-01T00:00:00.000Z', lockedUntil: null,
}

test('controller loads the view, issues by state, and re-reads after success', async () => {
  const { controller, deps } = controllerHarness({ view: activeView })
  await controller.load('900101001990001')
  assert.equal(controller.getSnapshot().phase, 'ready')
  assert.equal(controller.getSnapshot().view!.code, activeView.code)
  await controller.issue()
  assert.deepEqual(deps.issued, [{ orderId: '900101001990001', version: '1', kind: 'MANUAL' }])
  // 成功后自动重读完整视图（no-store）。
  assert.equal(deps.views, 2)
  assert.equal(controller.getSnapshot().pending, null)
  controller.dispose()
})

test('controller fails closed on read errors and 401 routes to login state', async () => {
  const forbidden = controllerHarness({ viewError: new ApiError('COMMON_FORBIDDEN', 403) })
  await forbidden.controller.load('900101001990001')
  assert.equal(forbidden.controller.getSnapshot().phase, 'load-error')
  assert.equal(forbidden.controller.getSnapshot().notice, '仅订单本人可查看核销码，或订单不存在')
  const unauthorized = controllerHarness({ viewError: new ApiError('COMMON_UNAUTHORIZED', 401) })
  await unauthorized.controller.load('900101001990001')
  assert.equal(unauthorized.controller.getSnapshot().phase, 'unauthorized')
  forbidden.controller.dispose(); unauthorized.controller.dispose()
})

test('controller retires the journal on a definite conflict and reloads; 429 keeps the pending command', async () => {
  const conflict = controllerHarness({ view: activeView, issueError: new ApiError('COMMON_CONFLICT', 409) })
  await conflict.controller.load('900101001990001')
  await conflict.controller.issue()
  // 终局 409：退槽 + 重新读取，页面不再卡在“重试原操作”。
  assert.equal(conflict.deps.retired.length, 1)
  assert.equal(conflict.controller.getSnapshot().pending, null)
  assert.equal(conflict.deps.views, 2)
  const rateLimited = controllerHarness({ view: activeView, issueError: new ApiError('COMMON_RATE_LIMITED', 429) })
  await rateLimited.controller.load('900101001990001')
  await rateLimited.controller.issue()
  assert.equal(rateLimited.controller.getSnapshot().pending!.refreshKind, 'MANUAL')
  assert.ok(/60秒最多刷新5次/.test(rateLimited.controller.getSnapshot().notice))
  // 保留的命令只能按原 payload 重试（同 X-Request-Id 语义）。
  await rateLimited.controller.issue()
  assert.deepEqual(rateLimited.deps.issued.map(entry => entry.version), ['1', '1'])
  conflict.controller.dispose(); rateLimited.controller.dispose()
})

test('controller restores a journaled command instead of issuing a new one', async () => {
  const scope = new WorkspaceScope()
  scope.replace({ userId: '957001', workspace: 'consumer', merchantId: null, storeId: null })
  const calls: string[] = []
  let pending: { expectedCredentialVersion: string; refreshKind: 'MANUAL' } | null = { expectedCredentialVersion: '3', refreshKind: 'MANUAL' }
  const deps: ScriptedDeps = {
    views: 0, issued: [], retired: [],
    async view() { deps.views++; return activeView },
    async issue(orderId, version, kind) { calls.push(`${version}:${kind}`); pending = null; return decodeIssueReceipt(JSON.parse(JSON.stringify(wireReceipt))) },
    pendingIssue: () => pending,
    retireConflict() { throw new Error('UNCONFIRMED_WRITE') },
  }
  const controller = new OrderVerifyController(deps, scope)
  await controller.load('900101001990001')
  controller.restore('900101001990001')
  assert.deepEqual(controller.getSnapshot().pending, { expectedCredentialVersion: '3', refreshKind: 'MANUAL' })
  // 有未确认命令时 issue() 退化为重试原操作（版本 3，而非视图版本 1）。
  await controller.issue()
  assert.deepEqual(calls, ['3:MANUAL'])
  controller.dispose()
})

// ---- 真实 repository：经 ConsumerApi 白名单/会话门禁/幂等槽的 47号 §4 两条路由。

const sessionView = { userId: '957001', sessionId: '957101', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data, traceId: 'test' } })
const failure = (statusCode: number, code: string) => ({ statusCode, data: { code, message: 'x', data: null, traceId: 'test' } })

function authenticatedApi(transport: (request: { method: string; path: string; data?: Record<string, unknown>; requestId?: string; headers: Record<string, string> }) => Promise<{ statusCode: number; data: unknown }>) {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, JSON.parse(JSON.stringify(value))), remove: key => { values.delete(key) } }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'test-only', tokenType: 'Bearer' })
  const api = new ConsumerApi(transport, store, async () => randomUUID())
  return { api, restore: () => api.restore() }
}

test('real repository reads the view without query and posts the strict two-field body', async () => {
  const seen: { method: string; path: string; data?: Record<string, unknown>; requestId?: string; headers: Record<string, string> }[] = []
  const { api, restore } = authenticatedApi(async request => {
    seen.push({ method: request.method, path: request.path, data: request.data, requestId: request.requestId, headers: request.headers })
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.method === 'GET') return ok(JSON.parse(JSON.stringify(wireActive)))
    return ok(JSON.parse(JSON.stringify(wireReceipt)))
  })
  await restore()
  const repository = new RealOrderVerifyRepository(api)
  const view = await repository.view('900101001990001')
  assert.equal(view.status, 'ACTIVE')
  const read = seen.find(entry => entry.path === '/api/v1/c/orders/900101001990001/verification-code' && entry.method === 'GET')!
  assert.equal(read.data, undefined)           // GET 不带 query（带参 400）
  assert.equal(read.requestId, undefined)      // GET 无 X-Request-Id
  assert.ok(read.headers.Authorization!.startsWith('Bearer '))
  const receipt = await repository.issue('900101001990001', '1', 'MANUAL')
  assert.equal(receipt.credentialVersion, '2')
  const write = seen.find(entry => entry.method === 'POST')!
  assert.deepEqual(write.data, { expectedCredentialVersion: '1', refreshKind: 'MANUAL' }) // 严格 JSON 恰两键
  assert.match(write.requestId!, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)
  assert.equal(write.headers['X-Request-Id'], write.requestId)
  // 成功后幂等槽清空。
  assert.equal(repository.pendingIssue('900101001990001'), null)
})

test('real repository journals the write, replays the same key, and rejects changed payloads', async () => {
  const writes: { requestId: string }[] = []
  let attempts = 0
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.method === 'GET') return ok(JSON.parse(JSON.stringify(wireActive)))
    writes.push({ requestId: request.requestId! }); attempts++
    if (attempts === 1) throw new Error('test-only lost ACK after real commit')
    return ok(JSON.parse(JSON.stringify(wireReceipt)))
  })
  await restore()
  const repository = new RealOrderVerifyRepository(api)
  await assert.rejects(repository.issue('900101001990001', '1', 'MANUAL'), /lost ACK/)
  // 未确认结果：槽内保留原命令；异参（版本变化）不允许换 payload 重试。
  assert.deepEqual(repository.pendingIssue('900101001990001'), { expectedCredentialVersion: '1', refreshKind: 'MANUAL' })
  await assert.rejects(repository.issue('900101001990001', '2', 'MANUAL'), /PENDING_WRITE_CHANGED/)
  // 同参重试复用同一 X-Request-Id（五元组幂等）；成功后槽清空。
  await repository.issue('900101001990001', '1', 'MANUAL')
  assert.equal(writes.length, 2)
  assert.equal(writes[1]!.requestId, writes[0]!.requestId)
  assert.equal(repository.pendingIssue('900101001990001'), null)
})

test('real repository retires the journaled command on a definite 409 with proof only', async () => {
  let attempts = 0
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.method === 'GET') return ok(JSON.parse(JSON.stringify(wireActive)))
    attempts++
    return failure(409, attempts === 1 ? 'COMMON_CONFLICT' : 'VERIFICATION_BLOCKED_BY_REFUND')
  })
  await restore()
  const repository = new RealOrderVerifyRepository(api)
  const conflict = await repository.issue('900101001990001', '1', 'MANUAL').catch(error => error)
  assert.ok(conflict instanceof ApiError && conflict.code === 'COMMON_CONFLICT')
  // 未经该错误实例（无证据）不得退槽。
  assert.throws(() => repository.retireConflict('900101001990001', new ApiError('COMMON_CONFLICT', 409)), /UNCONFIRMED_WRITE/)
  repository.retireConflict('900101001990001', conflict)
  assert.equal(repository.pendingIssue('900101001990001'), null)
  // 退槽后可按新版本重发（新 UUID 新命令）。
  const blocked = await repository.issue('900101001990001', '2', 'MANUAL').catch(error => error)
  assert.ok(blocked instanceof ApiError && blocked.code === 'VERIFICATION_BLOCKED_BY_REFUND')
  // 退款单 409 同为终局：可凭证据退槽。
  repository.retireConflict('900101001990001', blocked)
  assert.equal(repository.pendingIssue('900101001990001'), null)
})

test('real repository surfaces the switched-off route and dependency failures as-is', async () => {
  const switchedOff = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    return failure(404, 'COMMON_NOT_FOUND') // 路由未挂载（开关默认关）
  })
  await switchedOff.restore()
  const missing = await new RealOrderVerifyRepository(switchedOff.api).view('900101001990001').catch(error => error)
  assert.ok(missing instanceof ApiError && missing.statusCode === 404)
  const degraded = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    return failure(503, 'COMMON_DEPENDENCY_UNAVAILABLE')
  })
  await degraded.restore()
  const unavailable = await new RealOrderVerifyRepository(degraded.api).view('900101001990001').catch(error => error)
  assert.ok(unavailable instanceof ApiError && unavailable.statusCode === 503)
  // 视图回执不回显路径订单号 → 解码失败关闭。
  const mismatched = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    return ok({ ...wireActive, orderId: '900101001990999' })
  })
  await mismatched.restore()
  const rejected = await new RealOrderVerifyRepository(mismatched.api).view('900101001990001').catch(error => error)
  assert.ok(/INVALID_RESPONSE/.test(String((rejected as Error)?.message)))
})

test('status hint for the C-004 gap keeps the entry honest (no invented order fields)', () => {
  // 页面仅核销码视图：不出现任何订单服务/门店/金额字段（订单详情属 C-004 后续切片）。
  const hints = Object.values(statusHints).join('')
  for (const status of credentialStatuses) assert.ok(status in statusLabels)
  assert.ok(!/门店|服务名|金额/.test(hints))
})
