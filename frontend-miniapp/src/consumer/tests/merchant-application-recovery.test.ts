import assert from 'node:assert/strict'
import test from 'node:test'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { MerchantApplicationRepository, type ApplicationResult } from '../../shared/merchant-repositories'
import { ApiError, type Transport, type WireRequest } from '../../shared/request'
import { ApplicationRecovery } from '../merchant-application/recovery'

const principal = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const saved: ApplicationResult = { applicationId: '301', reservedMerchantId: '401', currentRevisionId: '501', version: '2', status: 'DRAFT', applicationNo: null }
const submitted: ApplicationResult = { ...saved, version: '3', status: 'REVIEWING', applicationNo: 'SQ20260920abcdefgh' }
const draft = { merchantName: '原始资料', storePhotoAssetIds: ['601'] }
const ok = (data: unknown) => ({ statusCode: 200, data: { success: true, code: 'SUCCESS', data } })
async function setup(handler: Transport, values = new Map<string, unknown>([['pet.c.session.v1', { ...principal, accessToken: 'test', tokenType: 'Bearer' }]])) {
  const store: LocalStore = { get: k => values.get(k), set: (k, v) => { values.set(k, structuredClone(v)) }, remove: k => { values.delete(k) } }
  const calls: WireRequest[] = []
  const api = new ConsumerApi(async req => { if (req.path.endsWith('/auth/session')) return ok(principal); calls.push(structuredClone(req)); return handler(req) }, store, async () => randomUUID())
  await api.restore()
  const repository = new MerchantApplicationRepository(api)
  return { api, repository, flow: new ApplicationRecovery(repository, api.scope), values, calls }
}
test('page/process restart after lost save ACK restores original payload/key then submits decoded receipt', async () => {
  const first = await setup(async () => { throw new Error('lost save ACK') })
  first.flow.begin({ ...saved, version: '1', currentRevisionId: '500' }, draft, true)
  await assert.rejects(first.flow.retry(), /lost save ACK/)
  const restarted = await setup(async req => ok(req.path.endsWith('/submit') ? submitted : saved), first.values)
  const intent = restarted.flow.restore()!
  assert.deepEqual(intent.draft, draft); assert.equal(intent.expectedVersion, '1'); assert.equal(intent.submit, true)
  assert.deepEqual(await restarted.flow.retry(), submitted)
  assert.deepEqual(restarted.calls[0], first.calls[0])
  assert.deepEqual(restarted.calls[1].data, { expectedVersion: '2', revisionId: '501' })
  assert.equal(restarted.flow.restore(), null)
})
test('lost submit ACK after restart only retries submit and never saves another revision', async () => {
  const first = await setup(async req => { if (req.path.endsWith('/submit')) throw new Error('lost submit ACK'); return ok(saved) })
  first.flow.begin(null, draft, true)
  await assert.rejects(first.flow.retry(), /lost submit ACK/)
  const restarted = await setup(async () => ok(submitted), first.values)
  assert.equal(restarted.flow.restore()?.stage, 'submit')
  assert.deepEqual(restarted.flow.restore()?.receipt, saved)
  assert.deepEqual(await restarted.flow.retry(), submitted)
  assert.equal(restarted.calls.length, 1)
  assert.deepEqual(restarted.calls[0], first.calls[1])
})
test('lost create ACK restores original create identity and ignores newly edited inputs', async () => {
  const first = await setup(async () => { throw new Error('lost create ACK') })
  first.flow.begin(null, draft, false)
  await assert.rejects(first.flow.retry())
  const restarted = await setup(async () => ok(saved), first.values)
  assert.deepEqual(restarted.flow.begin(null, { merchantName: 'changed' }, true).draft, draft)
  assert.deepEqual(await restarted.flow.retry(), saved)
  assert.deepEqual(restarted.calls[0], first.calls[0])
})
test('ACK checkpoint survives destruction immediately after persistence, before submit starts', async () => {
  const first = await setup(async () => ok(saved))
  first.flow.begin(null, draft, true)
  // Simulate process loss after repository acknowledgement, with no page continuation.
  const original = first.flow.restore()!
  await first.repository.create(draft, { slot: 'merchant-application', value: receipt => ({ ...original, stage: 'submit', receipt, applicationId: receipt.applicationId, expectedVersion: receipt.version, revisionId: receipt.currentRevisionId }) })
  const persisted = first.values.get('pet.c.pending.v1') as Record<string, unknown>
  assert.equal(persisted['merchant-application:create'], undefined)
  assert.ok(persisted['intent:merchant-application'])
  const restarted = await setup(async () => ok(submitted), first.values)
  await restarted.flow.retry()
  assert.equal(restarted.calls.length, 1); assert.match(restarted.calls[0].path, /\/submit$/)
})
test('completed receipt survives restart and does not send another write', async () => {
  const first = await setup(async () => ok(submitted))
  first.repository.saveIntent({ stage: 'done', submit: true, draft, receipt: submitted })
  const restarted = await setup(async () => { throw new Error('unexpected network') }, first.values)
  assert.deepEqual(await restarted.flow.retry(), submitted); assert.equal(restarted.calls.length, 0)
})
test('pre-UI-journal commands recover original save and submit parameters', async () => {
  for (const stage of ['save', 'submit']) {
    const first = await setup(async () => { throw new Error('lost ACK') })
    await assert.rejects(stage === 'save' ? first.repository.save('301', '1', draft) : first.repository.submit('301', '2', '501'))
    const restarted = await setup(async () => ok(stage === 'save' ? saved : submitted), first.values)
    assert.equal(restarted.flow.restore()?.stage, stage)
    await restarted.flow.retry(); assert.deepEqual(restarted.calls[0], first.calls[0])
  }
})
test('unknown rejection and platform cancel retain intent; definite rejection unlocks it', async () => {
  for (const error of [new ApiError('CONFLICT', 409), new ApiError('DOWN', 503), { errMsg: 'request:fail cancel' }, new ApiError('BAD', 422)]) {
    const first = await setup(async () => { throw error })
    first.flow.begin(null, draft, false)
    await assert.rejects(first.flow.retry())
    if (error instanceof ApiError && error.statusCode === 422) assert.equal(first.flow.restore(), null)
    else { assert.ok(first.flow.restore()); assert.throws(() => first.flow.cancel(), /PENDING_WRITE_CHANGED/) }
  }
})
test('logout clears private intent and an old late response cannot checkpoint into a new identity', async () => {
  let finish!: (value: ReturnType<typeof ok>) => void
  const first = await setup(req => req.path.endsWith('/logout') ? Promise.resolve(ok({ loggedOut: true })) : new Promise(resolve => { finish = resolve }))
  first.flow.begin(null, draft, true)
  const flight = first.flow.retry()
  await new Promise(resolve => setTimeout(resolve, 0))
  await first.api.logout()
  finish(ok(saved))
  await assert.rejects(flight, /STALE_CONTEXT/)
  assert.equal(first.values.has('pet.c.pending.v1'), false)
  assert.equal(first.flow.restore(), null)
})
test('a forged workspace identity cannot read another principal journal', async () => {
  const first = await setup(async () => { throw new Error('lost ACK') })
  first.flow.begin(null, draft, true); await assert.rejects(first.flow.retry())
  first.api.scope.replace({ userId: '999', workspace: 'consumer', merchantId: null, storeId: null })
  assert.equal(first.flow.restore(), null)
  assert.throws(() => first.flow.begin(null, draft, true), /COMMON_UNAUTHORIZED/)
})

test('definite submit rejection retains acknowledged save receipt for continued editing', async () => {
  const first = await setup(async req => { if (req.path.endsWith('/submit')) throw new ApiError('INVALID_DRAFT', 422); return ok(saved) })
  first.flow.begin(null, draft, true)
  await assert.rejects(first.flow.retry())
  assert.deepEqual(first.flow.lastReceipt, saved)
  assert.equal(first.flow.restore(), null)
})

test('invalid server receipt cannot advance stage or clear original request', async () => {
  const first = await setup(async () => ok({ ...saved, applicationId: 301 }))
  first.flow.begin(null, draft, true)
  await assert.rejects(first.flow.retry(), /INVALID_RESPONSE/)
  assert.equal(first.flow.restore()?.stage, 'save')
  assert.ok(first.api.pendingCommand('merchant-application:create'))
})
