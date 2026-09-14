import test from 'node:test'
import assert from 'node:assert/strict'
import { WorkspaceScope } from '../../shared/workspace'
import { consumerFixture, loadEngineeringFixture } from '../../shared/fixture'
import { fixtureAdmission, realAdmission, type AdmissionResult } from '../admission'
import { MerchantWorkspace } from '../workspace'

function setup() {
  const scope = new WorkspaceScope(); scope.replace(consumerFixture)
  return { scope, page: new MerchantWorkspace(scope) }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}
test('MINI-002 allow preserves user; return clears merchant context and cache', async () => {
  const { page, scope } = setup()
  await page.enter(fixtureAdmission('allow'))
  assert.equal(page.getSnapshot().status, 'allowed')
  assert.equal(scope.current?.userId, consumerFixture.userId)
  await page.loadSample()
  assert.equal(scope.read<{ amount: string }>('merchant-engineering')?.amount, '128.00')
  page.leave()
  assert.deepEqual(scope.current, consumerFixture)
  assert.equal(scope.read('merchant-engineering'), undefined)
  assert.equal(page.getSnapshot().sample, undefined)
})
for (const scenario of ['deny', 'error'] as const) {
  test(`MINI-002 ${scenario} after allow removes admission and data; retry rechecks`, async () => {
    const { page, scope } = setup()
    await page.enter(fixtureAdmission('allow')); await page.loadSample()
    await page.enter(fixtureAdmission(scenario))
    assert.equal(page.getSnapshot().status, scenario === 'deny' ? 'denied' : 'error')
    assert.equal(page.getSnapshot().sample, undefined)
    assert.equal(scope.current?.workspace, 'consumer')
    assert.equal(scope.read('merchant-engineering'), undefined)
    let sampleCalls = 0
    await page.loadSample(async () => { sampleCalls++; return loadEngineeringFixture() })
    assert.equal(sampleCalls, 0)
    await page.enter(fixtureAdmission('allow'))
    assert.equal(page.getSnapshot().status, 'allowed')
  })
}
test('MINI-002 every entry checks afresh and hides admitted content during query', async () => {
  const { page, scope } = setup()
  await page.enter(fixtureAdmission('allow')); await page.loadSample()
  const next = deferred<AdmissionResult>()
  const entering = page.enter(() => next.promise)
  assert.equal(page.getSnapshot().status, 'checking')
  assert.equal(page.getSnapshot().sample, undefined)
  assert.equal(scope.current?.workspace, 'consumer')
  next.resolve({ allowed: false }); await entering
  assert.equal(page.getSnapshot().status, 'denied')
})
for (const action of ['return', 'logout', 'revocation', 'user-switch', 'dispose'] as const) {
  test(`MINI-003 pending admission cannot revive after ${action}`, async () => {
    const { page, scope } = setup()
    const old = deferred<AdmissionResult>()
    const entering = page.enter(() => old.promise)
    if (action === 'return') page.leave()
    else if (action === 'dispose') page.dispose()
    else scope.replace(action === 'user-switch' ? { ...consumerFixture, userId: '42' } : null)
    const expected = scope.current
    old.resolve({ allowed: true, merchantId: 'old-merchant', storeId: 'old-store' })
    await entering
    assert.deepEqual(scope.current, expected)
    assert.notEqual(page.getSnapshot().status, 'allowed')
  })
}
test('MINI-003 older successful or failing admission cannot replace newest verdict', async () => {
  for (const failure of [false, true]) {
    const { page, scope } = setup()
    const old = deferred<AdmissionResult>()
    const entering = page.enter(() => old.promise)
    await page.enter(fixtureAdmission('deny'))
    if (failure) old.reject(new Error('old-query-failure'))
    else old.resolve({ allowed: true, merchantId: 'old', storeId: 'old' })
    await entering
    assert.equal(page.getSnapshot().status, 'denied')
    assert.equal(scope.current?.workspace, 'consumer')
  }
})
for (const action of ['return', 'store-switch', 'deny', 'error', 'logout', 'revocation', 'dispose'] as const) {
  test(`MINI-003 delayed merchant result/cache cannot survive ${action}`, async () => {
    for (const failure of [false, true]) {
      const { page, scope } = setup()
      await page.enter(fixtureAdmission('allow')); await page.loadSample()
      const old = deferred<Awaited<ReturnType<typeof loadEngineeringFixture>>>()
      const pending = page.loadSample(() => old.promise)
      if (action === 'return') page.leave()
      else if (action === 'dispose') page.dispose()
      else if (action === 'logout' || action === 'revocation') scope.replace(null)
      else await page.enter(fixtureAdmission(action === 'store-switch' ? 'other-store' : action))
      if (failure) old.reject(new Error('old-result-failure'))
      else old.resolve(await loadEngineeringFixture())
      await pending
      assert.equal(page.getSnapshot().sample, undefined)
      assert.equal(scope.read('merchant-engineering'), undefined)
      if (action === 'store-switch') {
        assert.equal(scope.current?.storeId, '9007199254740997')
        await page.loadSample()
        assert.equal(page.getSnapshot().sample?.id, '9007199254740993')
      }
    }
  })
}
test('MINI-002 missing shared session does not query or invent login', async () => {
  const { page, scope } = setup(); scope.replace(null)
  let called = false
  await page.enter(async () => { called = true; return { allowed: false } })
  assert.equal(called, false); assert.equal(scope.current, null)
  assert.equal(page.getSnapshot().status, 'error')
})
test('MINI-003 old page hide/dispose does not revoke a newer page admission or query', async () => {
  for (const pending of [false, true]) {
    const { page: old, scope } = setup()
    await old.enter(fixtureAdmission('allow'))
    const next = new MerchantWorkspace(scope)
    const query = deferred<AdmissionResult>()
    const entering = next.enter(pending ? () => query.promise : fixtureAdmission('other-store'))
    if (!pending) await entering
    const revision = scope.revision
    old.leave(); old.dispose()
    assert.equal(scope.revision, revision)
    if (pending) query.resolve({ allowed: true, merchantId: 'next', storeId: 'next' })
    await entering
    assert.equal(next.getSnapshot().status, 'allowed')
    assert.equal(scope.current?.workspace, 'merchant')
  }
})
test('MINI-004 real admission is blocked by CCR; internal data preserves string values and actions', async () => {
  const { page, scope } = setup()
  await assert.rejects(realAdmission()(consumerFixture), /CCR_ACR_001_NOT_APPROVED/)
  await page.enter(realAdmission())
  assert.equal(page.getSnapshot().status, 'error')
  assert.equal(scope.current?.workspace, 'consumer')
  await page.enter(fixtureAdmission('allow')); await page.loadSample()
  assert.deepEqual(page.getSnapshot().sample, { id: '9007199254740993', amount: '128.00',
    displayStatus: 'INTERNAL_SAMPLE', actions: { inspect: true } })
})
