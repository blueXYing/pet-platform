import test from 'node:test'
import assert from 'node:assert/strict'
import { WorkspaceScope, StaleContextError, type Workspace } from '../workspace'
import { consumerFixture } from '../fixture'
const merchant: Workspace = { userId: consumerFixture.userId, workspace: 'merchant', merchantId: '2', storeId: '3' }
for (const [name, next] of Object.entries({ workspace: merchant, user: { ...consumerFixture, userId: '4' },
  merchant: { ...merchant, merchantId: '5' }, store: { ...merchant, storeId: '6' }, logout: null, revocation: null,
  sameContextReentry: consumerFixture })) {
  test(`MINI-003 ${name}: old success and cache cannot cross context`, async () => {
    const scope = new WorkspaceScope(); scope.replace(name === 'store' || name === 'merchant' ? merchant : consumerFixture)
    await scope.run('cached', async () => 'private')
    assert.equal(scope.read('cached'), 'private')
    let release!: (value: string) => void
    const pending = scope.run('late', () => new Promise<string>(resolve => { release = resolve }))
    scope.replace(next); release('old-secret')
    await assert.rejects(pending, StaleContextError)
    assert.equal(scope.read('cached'), undefined); assert.equal(scope.read('late'), undefined)
  })
}
test('MINI-003 delayed rejection is stale; current context success remains available', async () => {
  const scope = new WorkspaceScope(); scope.replace(consumerFixture)
  let reject!: (error: Error) => void
  const pending = scope.run('late', () => new Promise((_r, fail) => { reject = fail }))
  scope.replace(merchant); reject(new Error('old failure'))
  await assert.rejects(pending, StaleContextError)
  assert.equal(await scope.run('current', async () => 'new'), 'new')
  assert.equal(scope.read('current'), 'new')
})
test('MINI-003 logout then same user login cannot revive pending response', async () => {
  const scope = new WorkspaceScope(); scope.replace(consumerFixture)
  let release!: () => void
  const pending = scope.run('late', () => new Promise<void>(resolve => { release = resolve }))
  scope.replace(null); scope.replace(consumerFixture); release()
  await assert.rejects(pending, StaleContextError)
})
