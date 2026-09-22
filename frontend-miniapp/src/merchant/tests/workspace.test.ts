import test from 'node:test'
import assert from 'node:assert/strict'
import { WorkspaceScope } from '../../shared/workspace'
import { consumerFixture } from '../../shared/fixture'
import { fixtureDeps, type AdmissionDeps, type AdmissionFixture } from '../admission'
import { MerchantWorkspace } from '../workspace'
import type { MerchantAdmission, MerchantMembership } from '../../shared/merchant-repositories'

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
const singleStore: MerchantMembership = { merchantId: '9007199254740995', merchantName: '样本商家一', storeId: '9007199254740996', storeName: '样本门店一', membershipKind: 'OWNER' }
const allowedView: MerchantAdmission = {
  merchantId: '9007199254740995', storeId: '9007199254740996', membershipKind: 'OWNER',
  admission: 'ALLOWED', checkedAt: '2026-09-22T00:00:00.000Z', authzVersion: '0123456789abcdef',
  facts: { application: { status: 'APPROVED' }, signing: { status: 'SIGNED' }, storeStatus: 'ACTIVE', merchantStatus: 'ACTIVE', staffEnabled: null },
  allowedActions: ['merchant.order.read'], reasonCodes: [], nextSteps: [],
}
function depsWith(overrides: Partial<AdmissionDeps>): AdmissionDeps {
  const base = fixtureDeps('allowed')
  return { ...base, ...overrides }
}

test('MINI-002 single accessible store auto-selects and switches to merchant coordinates', async () => {
  const { page, scope } = setup()
  await page.enter(fixtureDeps('allowed'))
  assert.equal(page.getSnapshot().status, 'allowed')
  assert.equal(page.getSnapshot().view?.admission, 'ALLOWED')
  assert.equal(scope.current?.workspace, 'merchant')
  assert.equal(scope.current?.merchantId, singleStore.merchantId)
  assert.equal(scope.current?.storeId, singleStore.storeId)
  assert.equal(scope.current?.userId, consumerFixture.userId)
  page.leave()
  assert.deepEqual(scope.current, consumerFixture)
})

test('MINI-002 multiple stores require an explicit choice; no default-first', async () => {
  const { page, scope } = setup()
  await page.enter(fixtureDeps('multi-store'))
  assert.equal(page.getSnapshot().status, 'choose-store')
  assert.equal(page.getSnapshot().stores?.length, 2)
  assert.equal(scope.current?.workspace, 'consumer')
  await page.select('9007199254740995', '9007199254740997', fixtureDeps('allowed'))
  assert.equal(page.getSnapshot().status, 'allowed')
  assert.equal(scope.current?.storeId, '9007199254740997')
})

test('MINI-002 no-stores keeps consumer coordinates and offers guidance', async () => {
  const { page, scope } = setup()
  await page.enter(fixtureDeps('no-stores'))
  assert.equal(page.getSnapshot().status, 'no-stores')
  assert.deepEqual(scope.current, consumerFixture)
})

for (const scenario of ['limited', 'denied'] as const) {
  test(`MINI-002 ${scenario} admission renders facts without inventing access`, async () => {
    const { page } = setup()
    await page.enter(fixtureDeps(scenario))
    const state = page.getSnapshot()
    assert.equal(state.status, scenario)
    assert.equal(state.view?.admission, scenario === 'limited' ? 'LIMITED' : 'DENIED')
    if (scenario === 'denied') {
      assert.deepEqual(state.view?.nextSteps, [{ type: 'COMPLETE_SIGNING' }])
      assert.deepEqual(state.view?.reasonCodes, ['SIGNING_REQUIRED'])
    }
  })
}

for (const scenario of ['error', 'store-error'] as const) {
  test(`MINI-002 ${scenario} fails closed and can retry`, async () => {
    const { page } = setup()
    await page.enter(fixtureDeps(scenario as AdmissionFixture))
    assert.equal(page.getSnapshot().status, 'error')
    page.leave()
    await page.enter(fixtureDeps('allowed'))
    assert.equal(page.getSnapshot().status, 'allowed')
  })
}

test('MINI-002 every entry re-checks; admitted view hidden during query', async () => {
  const { page, scope } = setup()
  await page.enter(fixtureDeps('allowed'))
  const next = deferred<{ items: MerchantMembership[] }>()
  const entering = page.enter(depsWith({ memberships: () => next.promise }))
  assert.equal(page.getSnapshot().status, 'checking')
  assert.equal(page.getSnapshot().view, undefined)
  assert.equal(scope.current?.workspace, 'consumer')
  next.resolve({ items: [singleStore] }); await entering
  assert.equal(page.getSnapshot().status, 'allowed')
})

test('MINI-002 missing shared session does not query or invent login', async () => {
  const { page, scope } = setup(); scope.replace(null)
  let called = false
  await page.enter(depsWith({ memberships: async () => { called = true; return { items: [] } } }))
  assert.equal(called, false); assert.equal(scope.current, null)
  assert.equal(page.getSnapshot().status, 'error')
})

for (const action of ['return', 'logout', 'user-switch', 'dispose'] as const) {
  test(`MINI-003 pending admission cannot revive after ${action}`, async () => {
    const { page, scope } = setup()
    const old = deferred<{ items: MerchantMembership[] }>()
    const entering = page.enter(depsWith({ memberships: () => old.promise }))
    if (action === 'return') page.leave()
    else if (action === 'dispose') page.dispose()
    else scope.replace(action === 'user-switch' ? { ...consumerFixture, userId: '42' } : null)
    const expected = scope.current
    old.resolve({ items: [singleStore] })
    await entering
    assert.deepEqual(scope.current, expected)
    assert.notEqual(page.getSnapshot().status, 'allowed')
  })
}

test('MINI-003 older successful or failing admission cannot replace newest verdict', async () => {
  for (const failure of [false, true]) {
    const { page, scope } = setup()
    const old = deferred<{ items: MerchantMembership[] }>()
    const entering = page.enter(depsWith({ memberships: () => old.promise }))
    await page.enter(fixtureDeps('denied'))
    if (failure) old.reject(new Error('old-query-failure'))
    else old.resolve({ items: [singleStore] })
    await entering
    // The denied verdict stays rendered; the selected candidate coordinates remain until leave.
    assert.equal(page.getSnapshot().status, 'denied')
    assert.equal(scope.current?.workspace, 'merchant')
    page.leave()
    assert.equal(scope.current?.workspace, 'consumer')
  }
})

test('MINI-003 old page hide/dispose does not revoke a newer page admission', async () => {
  const { page: old, scope } = setup()
  await old.enter(fixtureDeps('allowed'))
  const next = new MerchantWorkspace(scope)
  await next.enter(fixtureDeps('allowed'))
  const revision = scope.revision
  old.leave(); old.dispose()
  assert.equal(scope.revision, revision)
  assert.equal(scope.current?.workspace, 'merchant')
  assert.equal(next.getSnapshot().status, 'allowed')
})
