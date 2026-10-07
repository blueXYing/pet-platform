import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../shared/request'
import { StaleContextError } from '../../shared/workspace'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import {
  codeProblem, decodeVerificationReceipt, isOrderId, normalizeCode,
  verificationAvailability, verificationMessage, verifyTimeText,
  type VerificationDeps, type VerificationReceipt,
} from '../verify/model'
import { RealVerificationRepository } from '../verify/repository'
import { VerificationController } from '../verify/controller'

async function rejects(call: () => Promise<unknown> | unknown): Promise<unknown> {
  try { await call(); return new Error('NO_THROW') } catch (error) { return error }
}

const verifiedReceipt: VerificationReceipt = {
  orderId: '777001', attemptId: '777002', resultCode: 'VERIFIED',
  verificationId: '777003', verifiedAt: '2026-10-06T02:00:00.000Z', orderVersion: '5',
}

// ---------------------------------------------------------------------------
// Model: receipt decoder, form syntax, error mapping, fail-closed classification.
// ---------------------------------------------------------------------------

test('decode: VERIFIED carries the tail trio; negative results carry null tails', () => {
  const receipt = decodeVerificationReceipt({ ...verifiedReceipt })
  assert.equal(receipt.orderId, '777001')
  assert.equal(receipt.resultCode, 'VERIFIED')
  assert.equal(receipt.verificationId, '777003')
  const invalid = decodeVerificationReceipt({
    orderId: '777001', attemptId: '777004', resultCode: 'VERIFICATION_CODE_INVALID',
    verificationId: null, verifiedAt: null, orderVersion: null,
  })
  assert.equal(invalid.resultCode, 'VERIFICATION_CODE_INVALID')
  assert.equal(invalid.verificationId, null)
  assert.equal(invalid.verifiedAt, null)
  assert.equal(invalid.orderVersion, null)
})

test('decode rejects contract-foreign shapes', async () => {
  // Unknown field.
  assert.equal(((await rejects(() => decodeVerificationReceipt({ ...verifiedReceipt, extra: 1 }))) as Error).message, 'INVALID_RESPONSE')
  // Missing field.
  assert.equal(((await rejects(() => decodeVerificationReceipt({ ...verifiedReceipt, orderVersion: undefined }))) as Error).message, 'INVALID_RESPONSE')
  // VERIFIED with a null tail (K2 all-or-nothing invariant).
  assert.equal(((await rejects(() => decodeVerificationReceipt({ ...verifiedReceipt, verificationId: null }))) as Error).message, 'INVALID_RESPONSE')
  // Negative result with a non-null tail.
  assert.equal(((await rejects(() => decodeVerificationReceipt({
    orderId: '777001', attemptId: '777004', resultCode: 'VERIFICATION_RISK_LOCKED',
    verificationId: '777003', verifiedAt: null, orderVersion: null,
  }))) as Error).message, 'INVALID_RESPONSE')
  // Non-millisecond timestamp and bad ids.
  assert.equal(((await rejects(() => decodeVerificationReceipt({ ...verifiedReceipt, verifiedAt: '2026-10-06T02:00:00Z' }))) as Error).message, 'INVALID_RESPONSE')
  assert.equal(((await rejects(() => decodeVerificationReceipt({ ...verifiedReceipt, orderId: '0777001' }))) as Error).message, 'INVALID_RESPONSE')
  // Unknown resultCode never reaches the UI.
  assert.equal(((await rejects(() => decodeVerificationReceipt({ ...verifiedReceipt, resultCode: 'VERIFICATION_OK' }))) as Error).message, 'INVALID_RESPONSE')
})

test('form syntax: order id is a Snowflake decimal string; code is 1-128 uppercase alnum', () => {
  assert.equal(isOrderId('777001'), true)
  assert.equal(isOrderId('9007199254740993'), true)
  assert.equal(isOrderId('0777'), false)
  assert.equal(isOrderId('abc'), false)
  assert.equal(isOrderId(''), false)
  assert.equal(normalizeCode('  ab-c123 '), 'AB-C123')
  assert.equal(codeProblem('ABC123'), null)
  assert.equal(codeProblem('A'.repeat(128)), null)
  assert.notEqual(codeProblem('A'.repeat(129)), null)
  assert.notEqual(codeProblem(''), null)
  assert.notEqual(codeProblem('ABC-123'), null)
})

test('error mapping: per-code Chinese copy, 404 anti-enumeration stays single', () => {
  assert.match(verificationMessage(new ApiError('COMMON_FORBIDDEN', 403)), /未获核销授权或核销通道未开放/)
  assert.match(verificationMessage(new ApiError('COMMON_NOT_FOUND', 404)), /订单不存在或不属于当前门店/)
  assert.equal(verificationMessage(new ApiError('COMMON_NOT_FOUND', 404)), verificationMessage(new ApiError('VERIFICATION_STORE_MISMATCH', 404)))
  assert.match(verificationMessage(new ApiError('VERIFICATION_ALREADY_DONE', 409)), /已核销，不可重复核销/)
  assert.match(verificationMessage(new ApiError('VERIFICATION_BLOCKED_BY_REFUND', 409)), /已创建退款单/)
  assert.match(verificationMessage(new ApiError('VERIFICATION_NOT_ALLOWED', 409)), /订单状态不允许核销/)
  assert.match(verificationMessage(new ApiError('COMMON_CONFLICT', 409)), /核销条件已变化/)
  assert.match(verificationMessage(new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409)), /请求编号已用于其他内容/)
  assert.match(verificationMessage(new ApiError('COMMON_INVALID_ARGUMENT', 400)), /提交内容无效/)
  assert.match(verificationMessage(new ApiError('COMMON_UNAUTHORIZED', 401)), /登录已失效/)
  assert.match(verificationMessage(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), /暂不可用/)
  assert.match(verificationMessage(new Error('PENDING_WRITE_CHANGED')), /尚未确认/)
  assert.equal(verificationAvailability(new ApiError('COMMON_FORBIDDEN', 403)), 'closed')
  assert.equal(verificationAvailability(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), 'closed')
  assert.equal(verificationAvailability(new ApiError('COMMON_NOT_FOUND', 404)), 'ok')
  assert.equal(verifyTimeText('2026-10-06T02:00:00.000Z'), '2026-10-06 10:00:00')
  assert.equal(verifyTimeText(null), '—')
})

// ---------------------------------------------------------------------------
// Controller: entry gating, validation, receipt/already/closed panels, replay flag.
// ---------------------------------------------------------------------------

class FakeDeps implements VerificationDeps {
  calls: Array<{ orderId: string; code: string }> = []
  receipt: VerificationReceipt = { ...verifiedReceipt }
  error: unknown = null
  pendingValue: { orderId: string; verificationCode: string } | null = null
  failPending = false
  async verify(orderId: string, code: string): Promise<VerificationReceipt> {
    this.calls.push({ orderId, code })
    if (this.error) throw this.error
    return this.receipt
  }
  pending() { if (this.failPending) throw new Error('WORKSPACE_PATH_MISMATCH'); return this.pendingValue }
}
const merchantContext = { workspace: 'merchant', merchantId: '958001', storeId: '958002' } as const

test('controller: entry gating and pending journal surface on load', () => {
  const deps = new FakeDeps()
  const controller = new VerificationController(deps)
  controller.load(null)
  assert.equal(controller.getSnapshot().status, 'entry')
  deps.pendingValue = { orderId: '777001', verificationCode: 'ABC123' }
  controller.load(merchantContext)
  const state = controller.getSnapshot()
  assert.equal(state.status, 'form')
  assert.deepEqual(state.pending, { orderId: '777001', verificationCode: 'ABC123' })
  controller.dispose()
})

test('controller: form validation blocks submit; code is normalized before send', async () => {
  const deps = new FakeDeps()
  const controller = new VerificationController(deps)
  controller.load(merchantContext)
  await controller.submit()
  assert.equal(deps.calls.length, 0, 'empty form never sends')
  assert.match(controller.getSnapshot().notice, /订单编号/)
  controller.setOrderId('777001')
  controller.setCode(' abc123 ')
  await controller.submit()
  assert.deepEqual(deps.calls, [{ orderId: '777001', code: 'ABC123' }], 'trimmed + upper-cased payload')
  assert.equal(controller.getSnapshot().status, 'receipt')
  controller.dispose()
})

test('controller: replayed flag mirrors the journaled same-payload command', async () => {
  const deps = new FakeDeps()
  const controller = new VerificationController(deps)
  controller.load(merchantContext)
  controller.setOrderId('777001')
  controller.setCode('ABC123')
  deps.pendingValue = { orderId: '777001', verificationCode: 'ABC123' }
  await controller.submit()
  assert.equal(controller.getSnapshot().replayed, true)
  assert.equal(controller.getSnapshot().pending, null, 'journal cleared after the 200 receipt')
  // A different journaled payload is not this submission's replay.
  controller.reset()
  controller.setOrderId('777001')
  controller.setCode('XYZ789')
  await controller.submit()
  assert.equal(controller.getSnapshot().replayed, false)
  controller.dispose()
})

test('controller: committed negative business result keeps the receipt panel', async () => {
  const deps = new FakeDeps()
  deps.receipt = { orderId: '777001', attemptId: '777004', resultCode: 'VERIFICATION_CODE_EXPIRED', verificationId: null, verifiedAt: null, orderVersion: null }
  const controller = new VerificationController(deps)
  controller.load(merchantContext)
  controller.setOrderId('777001')
  controller.setCode('ABC123')
  await controller.submit()
  const state = controller.getSnapshot()
  assert.equal(state.status, 'receipt')
  assert.equal(state.receipt?.resultCode, 'VERIFICATION_CODE_EXPIRED')
  assert.equal(state.receipt?.verificationId, null)
  controller.dispose()
})

test('controller: ALREADY_DONE is its own terminal panel; other 409s stay form notices', async () => {
  const deps = new FakeDeps()
  const controller = new VerificationController(deps)
  controller.load(merchantContext)
  controller.setOrderId('777001')
  controller.setCode('ABC123')
  deps.error = new ApiError('VERIFICATION_ALREADY_DONE', 409)
  await controller.submit()
  assert.equal(controller.getSnapshot().status, 'already')
  controller.reset()
  deps.error = new ApiError('VERIFICATION_BLOCKED_BY_REFUND', 409)
  await controller.submit()
  const blocked = controller.getSnapshot()
  assert.equal(blocked.status, 'form')
  assert.match(blocked.notice, /退款单/)
  controller.dispose()
})

test('controller: 403/503 fail the whole page closed; 404 stays a form notice', async () => {
  const deps = new FakeDeps()
  const controller = new VerificationController(deps)
  controller.load(merchantContext)
  controller.setOrderId('777001')
  controller.setCode('ABC123')
  deps.error = new ApiError('COMMON_FORBIDDEN', 403)
  await controller.submit()
  assert.equal(controller.getSnapshot().status, 'closed')
  controller.reset()
  deps.error = new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)
  await controller.submit()
  assert.equal(controller.getSnapshot().status, 'closed')
  controller.reset()
  deps.error = new ApiError('COMMON_NOT_FOUND', 404)
  await controller.submit()
  const missing = controller.getSnapshot()
  assert.equal(missing.status, 'form')
  assert.match(missing.notice, /订单不存在或不属于当前门店/)
  controller.dispose()
})

// ---------------------------------------------------------------------------
// Real wiring over a scripted transport: route/body/request id, slot retirement and
// replay journal semantics (23号 §5) including the hidden-receipt 401/403/404 case.
// ---------------------------------------------------------------------------
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', success: true, data } })
const failure = (code: string, statusCode: number) => ({ statusCode, data: { code, data: null } })
type Call = { method: string; path: string; requestId?: string; data?: Record<string, unknown> }

async function wiredApi(handlers: (call: Call) => { statusCode: number; data: unknown } | undefined) {
  const seen: Call[] = []
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
  const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
  const grant = { ...session, tokenType: 'Bearer', accessToken: 'unusable-test-token' }
  let uuidCall = 0
  const api = new ConsumerApi(async request => {
    if (request.path.endsWith('/attempts')) return ok({ attemptId: '301', attemptToken: 't', nextStep: 'PROVE_IDENTITY' })
    if (request.path.endsWith('/wechat-login')) return ok(grant)
    if (request.path.endsWith('/session')) return ok(session)
    const call: Call = { method: request.method, path: request.path, requestId: (request as { requestId?: string }).requestId, data: request.data }
    seen.push(call)
    return handlers(call) || failure('COMMON_DEPENDENCY_UNAVAILABLE', 503)
  }, store, async () => `00000000-0000-4000-8000-${String(++uuidCall).padStart(12, '0')}`)
  await api.startLogin(async () => ({ code: 'test-code' }))
  api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '958001', storeId: '958002' })
  const repository = new RealVerificationRepository(api)
  return { api, repository, seen, store }
}

test('real repository wires the 48 K2 route: POST only, strict body, journaled UUID, no coordinates', async () => {
  const { repository, seen, api } = await wiredApi(call => {
    if (call.method === 'POST' && call.path === '/api/v1/merchant/orders/777001/verification') return ok({ ...verifiedReceipt })
    return undefined
  })
  const receipt = await repository.verify(' 777001 ', ' abc123 ')
  assert.equal(receipt.resultCode, 'VERIFIED')
  assert.equal(receipt.orderId, '777001')
  const sent = seen.find(call => call.path === '/api/v1/merchant/orders/777001/verification')!
  assert.equal(sent.method, 'POST')
  // Body is strictly {verificationCode} — no merchantId/storeId/staffId is ever declared.
  assert.deepEqual(Object.keys(sent.data!), ['verificationCode'])
  assert.equal(sent.data?.verificationCode, 'ABC123')
  assert.match(String(sent.requestId), /^[0-9a-f-]{36}$/)
  // The family is POST-only (the GET fails the path whitelist after passing the workspace
  // guard) and the form gate rejects bad ids/codes before any send.
  const wrongMethod = await rejects(() => api.request({ method: 'GET', path: '/api/v1/merchant/orders/777001/verification', data: { merchantId: '958001' } }, value => value))
  assert.equal((wrongMethod as Error).message, 'INVALID_PATH')
  const badOrder = await rejects(() => repository.verify('0777', 'ABC123'))
  assert.equal((badOrder as Error).message, 'VERIFY_FORM_INVALID')
  const badCode = await rejects(() => repository.verify('777001', 'AB-'))
  assert.equal((badCode as Error).message, 'VERIFY_FORM_INVALID')
})

test('real repository: receipt orderId must echo the command target', async () => {
  const { repository } = await wiredApi(call => {
    if (call.method === 'POST') return ok({ ...verifiedReceipt, orderId: '777009' })
    return undefined
  })
  const mismatched = await rejects(() => repository.verify('777001', 'ABC123'))
  assert.equal((mismatched as Error).message, 'INVALID_RESPONSE')
})

test('real repository: definitive 409 retires the slot; a corrected payload gets a fresh requestId', async () => {
  let mode: 'blocked' | 'ok' = 'blocked'
  const { repository, seen } = await wiredApi(() => mode === 'blocked' ? failure('VERIFICATION_BLOCKED_BY_REFUND', 409) : ok({ ...verifiedReceipt }))
  const blocked = await rejects(() => repository.verify('777001', 'ABC123'))
  assert.ok(blocked instanceof ApiError)
  mode = 'ok'
  await repository.verify('777001', 'ABC123')
  const posts = seen.filter(call => call.method === 'POST' && call.path === '/api/v1/merchant/orders/777001/verification')
  assert.equal(posts.length, 2)
  assert.notEqual(posts[0]!.requestId, posts[1]!.requestId)
})

test('real repository: unknown outcome keeps the journal; retry replays the SAME requestId and first receipt', async () => {
  let mode: 'unavailable' | 'ok' = 'unavailable'
  const { repository, seen } = await wiredApi(() => mode === 'unavailable' ? failure('COMMON_DEPENDENCY_UNAVAILABLE', 503) : ok({ ...verifiedReceipt }))
  const first = await rejects(() => repository.verify('777001', 'ABC123'))
  assert.ok(first instanceof ApiError)
  assert.equal((first as ApiError).statusCode, 503)
  // The journaled command is visible to the page before the retry.
  const pending = repository.pending()
  assert.deepEqual(pending, { orderId: '777001', verificationCode: 'ABC123' })
  // A different payload on the same slot locks (unknown result outranks a new intent).
  const locked = await rejects(() => repository.verify('777001', 'XYZ789'))
  assert.equal((locked as Error).message, 'PENDING_WRITE_CHANGED')
  mode = 'ok'
  const replayed = await repository.verify('777001', 'ABC123')
  assert.equal(replayed.verificationId, '777003')
  const posts = seen.filter(call => call.method === 'POST' && call.path === '/api/v1/merchant/orders/777001/verification')
  assert.equal(posts.length, 2)
  assert.equal(posts[0]!.requestId, posts[1]!.requestId, '23号 replay keeps the terminal UUID')
})

test('real repository: 401/403/404 keep the journal (hidden first receipt), 400 retires it', async () => {
  // 403/404: session intact, the journaled command stays visible to the page.
  for (const [status, code] of [[403, 'COMMON_FORBIDDEN'], [404, 'COMMON_NOT_FOUND']] as const) {
    const { repository, api } = await wiredApi(() => failure(code, status))
    const denied = await rejects(() => repository.verify('777001', 'ABC123'))
    assert.ok(denied instanceof ApiError)
    // K1 replays re-prove identity before any old receipt: these cannot prove the earlier
    // unknown send never verified, so the journaled requestId must survive.
    assert.equal(api.pendingCommands('merchant-verify:').length, 1, `${status} keeps the journal`)
  }
  // 401 clears the session mid-command (the stale workspace ticket surfaces as
  // StaleContextError — established write() machinery behavior); the journal must still
  // survive in storage so a post-relogin retry keeps the terminal key instead of minting
  // a new one (23号 §5.7). It just stays invisible until a fresh session is verified.
  {
    const { repository, store } = await wiredApi(() => failure('COMMON_UNAUTHORIZED', 401))
    const expired = await rejects(() => repository.verify('777001', 'ABC123'))
    assert.ok(expired instanceof StaleContextError)
    const saved = store.get('pet.c.pending.v1') as Record<string, unknown>
    assert.equal(Object.keys(saved).filter(slot => slot.startsWith('merchant-verify:')).length, 1, '401 keeps the journaled slot in storage')
    // The controller folds the cleared workspace to the entry state, outcome unknown.
    const deps401 = new FakeDeps()
    deps401.error = expired
    const controller401 = new VerificationController(deps401)
    controller401.load(merchantContext)
    controller401.setOrderId('777001')
    controller401.setCode('ABC123')
    await controller401.submit()
    assert.equal(controller401.getSnapshot().status, 'entry')
    controller401.dispose()
  }
  const { repository, api } = await wiredApi(() => failure('COMMON_INVALID_ARGUMENT', 400))
  const invalid = await rejects(() => repository.verify('777001', 'ABC123'))
  assert.ok(invalid instanceof ApiError)
  assert.equal(api.pendingCommands('merchant-verify:').length, 0, '400 retires the journal')
})

test('real repository: pending() reads only the current workspace coordinates', async () => {
  const { repository, api } = await wiredApi(() => failure('COMMON_DEPENDENCY_UNAVAILABLE', 503))
  await rejects(() => repository.verify('777001', 'ABC123'))
  assert.notEqual(repository.pending(), null)
  api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '958009', storeId: '958010' })
  assert.equal(repository.pending(), null, 'another store\'s journal is not this page\'s business')
  api.scope.replace(null)
  assert.equal(repository.pending(), null)
})
