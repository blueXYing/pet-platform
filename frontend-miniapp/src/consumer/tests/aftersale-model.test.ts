import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { AfterSaleClient, type CaseDetail, type CasePage, type CommandReceipt, type Eligibility } from '../../shared/aftersale-api'
import { WorkspaceScope } from '../../shared/workspace'
import { ApiError } from '../../shared/request'
import { ConsumerAfterSaleController, activeCase, createInput, decisionLabel, emptyCreateDraft, isId, statusLabel, validateCreate, validateEvidence, type ConsumerAfterSaleDeps } from '../aftersale/model'
import { catalogSelectionErrors, consumerController, restorePending } from '../aftersale/runtime'

const at = '2026-10-01T01:00:00.000Z'
const draft = () => ({ ...emptyCreateDraft(), typeCode: 'TEST_ONLY_QUALITY', demandCode: 'TEST_ONLY_RESERVICE', description: '用户说明真实服务中出现的问题' })
const detail = (overrides: Partial<CaseDetail> = {}): CaseDetail => ({ afterSaleId: '501', orderId: '401', status: 'PENDING', version: '0', sourceStage: 'VERIFIED', typeCode: 'TEST_ONLY_QUALITY', demandCode: 'TEST_ONLY_RESERVICE', requestedAmount: null, createdAt: at, deadline: at, description: '用户说明真实服务中出现的问题', supplementRequestId: null, supplementTarget: null, supplementDeadline: null, supplementReason: null, finalSetVersion: 'a'.repeat(64), priorFinalCaseIds: [], newProblemStatement: null, decisionType: null, refundAmount: null, decisionReason: null, evidence: [], ...overrides })
const receipt = (overrides: Partial<CommandReceipt> = {}): CommandReceipt => ({ commandId: '601', orderId: '401', afterSaleId: '501', status: 'PENDING', version: '0', occurredAt: at, evidenceBatchId: null, supplementRequestId: null, decisionId: null, refundOrderId: null, ...overrides })
const eligible: Eligibility = { eligible: true, sourceStage: 'VERIFIED', deadline: at, blockingReason: null, activeAfterSaleId: null }
const page: CasePage = { page: 1, pageSize: 20, total: 0, items: [] }
function setup(overrides: Partial<ConsumerAfterSaleDeps> = {}) {
  const scope = new WorkspaceScope(); scope.replace({ userId: '101', workspace: 'consumer', merchantId: null, storeId: null })
  const deps: ConsumerAfterSaleDeps = { list: async () => page, detail: async () => detail(), eligibility: async () => eligible, create: async () => receipt(), evidence: async () => receipt({ version: '1' }), withdraw: async () => receipt({ version: '1', status: 'WITHDRAWN' }), ...overrides }
  return { scope, deps, controller: new ConsumerAfterSaleController(deps, scope) }
}
test('creation validation keeps exact string ID, money and canonical text constraints', () => {
  assert.equal(isId('9223372036854775807'), true); assert.equal(isId('9223372036854775808'), false); assert.equal(isId('0401'), false)
  const valid = { ...draft(), requestedAmount: '9007199254740993.00' }
  assert.deepEqual(validateCreate(valid, ['701']), {})
  assert.equal(createInput(valid, []).requestedAmount, '9007199254740993.00')
  for (const requestedAmount of ['35', '35.0', '035.00', '1e2', '35.001']) assert.ok(validateCreate({ ...draft(), requestedAmount }, []).requestedAmount)
  assert.ok(validateCreate({ ...draft(), description: '     ' }, []).description)
  assert.ok(validateCreate(draft(), ['701', '701']).evidence)
  assert.ok(validateCreate({ ...draft(), newProblemStatement: '太短' }, []).newProblemStatement)
  const spaced = { ...draft(), description: ' 用户说明真实服务中出现的问题 ' }
  assert.equal(createInput(spaced, []).description, spaced.description)
})
test('evidence requires text or valid unique images and uses the public six-image ceiling', () => {
  assert.ok(validateEvidence('', []).text)
  assert.deepEqual(validateEvidence('', ['701']), {})
  assert.ok(validateEvidence('太短', ['701']).text)
  assert.ok(validateEvidence('', ['1', '2', '3', '4', '5', '6', '7']).evidence)
  assert.deepEqual(validateEvidence('用户追加说明真实的服务问题', []), {})
})
test('missing catalog and stale codes fail closed; test-only injected catalog admits its own options', () => {
  assert.ok(catalogSelectionErrors(null, draft()).typeCode)
  const catalog = { types: [{ code: draft().typeCode, label: '测试专用问题' }], demands: [{ code: draft().demandCode, label: '测试专用诉求' }] }
  assert.deepEqual(catalogSelectionErrors(catalog, draft()), {})
  assert.ok(catalogSelectionErrors(catalog, { typeCode: 'RETIRED_CODE', demandCode: draft().demandCode }).typeCode)
  assert.ok(catalogSelectionErrors({ ...catalog, demands: [] }, draft()).demandCode)
})
test('ineligible and already-active orders never create a parallel case', async () => {
  let writes = 0
  const h = setup({ eligibility: async () => ({ ...eligible, eligible: false, activeAfterSaleId: '502' }), create: async () => { writes++; return receipt() } })
  await h.controller.loadEligibility('401'); await h.controller.create('401', draft(), [])
  assert.equal(writes, 0); assert.equal(h.controller.getSnapshot().eligibility?.activeAfterSaleId, '502')
})
test('unknown create locks original payload; only explicit retry resends; success rereads detail', async () => {
  const calls: unknown[] = []; let fail = true
  const h = setup({ create: async (_, input) => { calls.push(structuredClone(input)); if (fail) throw new Error('timeout'); return receipt() } })
  await h.controller.loadEligibility('401')
  const original = draft(); await h.controller.create('401', original, ['701'])
  original.description = '更改输入不得改变已提交请求'
  assert.equal(h.controller.getSnapshot().locked, true); assert.equal(calls.length, 1)
  await h.controller.create('401', original, [])
  assert.equal(calls.length, 1)
  fail = false; await h.controller.retry()
  assert.deepEqual(calls[1], calls[0]); assert.equal(h.controller.getSnapshot().locked, false); assert.equal(h.controller.getSnapshot().detail?.afterSaleId, '501')
})
test('duplicate clicks share one in-flight action', async () => {
  let resolve!: (value: CommandReceipt) => void, calls = 0
  const h = setup({ withdraw: () => { calls++; return new Promise(ok => { resolve = ok }) } })
  await h.controller.loadDetail('501')
  const first = h.controller.withdraw(); await h.controller.withdraw(); assert.equal(calls, 1)
  resolve(receipt({ status: 'WITHDRAWN' })); await first
})
test('supplement uses exact current round ID even when the target is merchant', async () => {
  let input: unknown
  const h = setup({ detail: async () => detail({ status: 'WAITING_SUPPLEMENT', version: '9', supplementRequestId: '801', supplementTarget: 'MERCHANT', supplementDeadline: at }), evidence: async (_, value) => { input = value; return receipt({ version: '10' }) } })
  await h.controller.loadDetail('501'); await h.controller.evidence('用户追加说明真实的服务问题', [])
  assert.deepEqual(input, { expectedVersion: '9', evidenceAssetIds: [], text: '用户追加说明真实的服务问题', supplementRequestId: '801' })
})
test('CAS/supplement conflicts retire definite request then reread the latest version', async () => {
  let reads = 0; const retired: string[] = []
  const h = setup({ detail: async () => detail({ version: String(reads++) }), evidence: async () => { throw new ApiError('AFTERSALE_SUPPLEMENT_STALE', 409) }, retireConflict: (id, action) => { retired.push(`${id}:${action}`) } })
  await h.controller.loadDetail('501'); await h.controller.evidence('用户追加说明真实的服务问题', [])
  assert.deepEqual(retired, ['501:evidence']); assert.equal(reads, 2); assert.equal(h.controller.getSnapshot().detail?.version, '1'); assert.equal(h.controller.getSnapshot().locked, false)
})
test('idempotency in-progress/conflict is kept locked instead of rotating request IDs', async () => {
  for (const code of ['IDEMPOTENCY_KEY_CONFLICT', 'IDEMPOTENCY_IN_PROGRESS']) {
    let retires = 0
    const h = setup({ withdraw: async () => { throw new ApiError(code, 409) }, retireConflict: () => { retires++ } })
    await h.controller.loadDetail('501'); await h.controller.withdraw()
    assert.equal(h.controller.getSnapshot().locked, true); assert.equal(retires, 0)
  }
})
test('frozen/forbidden writes become read-only while authorized history remains readable', async () => {
  let writes = 0
  const h = setup({ evidence: async () => { writes++; throw new ApiError('USER_FROZEN', 403) } })
  await h.controller.loadDetail('501'); await h.controller.evidence('用户追加说明真实的服务问题', [])
  assert.equal(h.controller.getSnapshot().readOnly, true)
  await h.controller.withdraw(); await h.controller.loadDetail('501')
  assert.equal(writes, 1); assert.equal(h.controller.getSnapshot().detail?.afterSaleId, '501')
})
test('terminal cases expose their actual aftersale state and cannot withdraw or append', async () => {
  for (const status of ['RESOLVED', 'CLOSED', 'INVALIDATED', 'WITHDRAWN'] as const) {
    let writes = 0; const h = setup({ detail: async () => detail({ status }), withdraw: async () => { writes++; return receipt() }, evidence: async () => { writes++; return receipt() } })
    await h.controller.loadDetail('501'); await h.controller.withdraw(); await h.controller.evidence('用户追加说明真实的服务问题', [])
    assert.equal(activeCase(h.controller.getSnapshot().detail), false); assert.equal(writes, 0)
  }
  assert.equal(statusLabel('INVALIDATED'), '已失效'); assert.equal(decisionLabel('RESERVICE'), '重新服务')
})
test('identity switch clears old state and ignores delayed response', async () => {
  let resolve!: (value: CaseDetail) => void
  const h = setup({ detail: () => new Promise(ok => { resolve = ok }) })
  const loading = h.controller.loadDetail('501')
  h.scope.replace({ userId: '102', workspace: 'consumer', merchantId: null, storeId: null })
  resolve(detail()); await loading
  assert.equal(h.controller.getSnapshot().detail, null); assert.equal(h.controller.getSnapshot().phase, 'idle')
})
test('latest read wins; disposed controller refuses new requests', async () => {
  let resolve!: (value: CaseDetail) => void, calls = 0
  const h = setup({ detail: () => ++calls === 1 ? new Promise(ok => { resolve = ok }) : Promise.resolve(detail({ afterSaleId: '502' })) })
  const first = h.controller.loadDetail('501'); await h.controller.loadDetail('502'); resolve(detail()); await first
  assert.equal(h.controller.getSnapshot().detail?.afterSaleId, '502')
  h.controller.dispose(); await h.controller.loadDetail('501'); assert.equal(calls, 2)
})
test('logout during a write clears details and rejects late receipt publication', async () => {
  let resolve!: (value: CommandReceipt) => void
  const h = setup({ withdraw: () => new Promise(ok => { resolve = ok }) })
  await h.controller.loadDetail('501'); const write = h.controller.withdraw()
  h.scope.replace(null); resolve(receipt({ status: 'WITHDRAWN' })); await write
  assert.equal(h.controller.getSnapshot().detail, null); assert.equal(h.controller.getSnapshot().receipt, null); assert.equal(h.controller.getSnapshot().locked, false)
})
test('page remount restores actual durable command and replays its original UUID', async () => {
  const saved = new Map<string, unknown>(), writes: string[] = []; let failed = true
  const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
  const store: LocalStore = { get: key => saved.get(key), set: (key, value) => saved.set(key, structuredClone(value)), remove: key => { saved.delete(key) } }
  store.set('pet.c.session.v1', { ...session, accessToken: 'test-only-token', tokenType: 'Bearer' })
  const api = new ConsumerApi(async request => {
    if (request.method === 'POST') { writes.push(request.requestId!); if (failed) throw new Error('response-lost'); return { statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data: receipt(), traceId: 'test' } } }
    return { statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data: request.path.includes('/auth/session') ? session : request.path.includes('eligibility') ? eligible : detail(), traceId: 'test' } }
  }, store, async () => randomUUID())
  await api.restore(); const client = new AfterSaleClient(api, 'c'), original = consumerController(client)
  await original.loadEligibility('401'); await original.create('401', draft(), [])
  original.dispose(); const remount = consumerController(client), pending = restorePending(client, '401', true)
  assert.equal(pending?.kind, 'create'); remount.restore(pending!); assert.equal(remount.getSnapshot().locked, true)
  failed = false; await remount.retry()
  assert.equal(writes.length, 2); assert.equal(writes[0], writes[1]); assert.equal(remount.getSnapshot().receipt?.afterSaleId, '501')
})
