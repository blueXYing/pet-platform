import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { AfterSaleClient } from '../../shared/aftersale-api'
import { merchantAfterSaleDeps } from '../aftersale/repository'
import type { UploadFiles } from '../../shared/private-asset-upload'

async function setup() {
  const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
  const saved = new Map<string, unknown>([['pet.c.session.v1', { ...session, tokenType: 'Bearer', accessToken: 'test-only-unusable' }]])
  const store: LocalStore = { get: key => saved.get(key), set: (key, value) => { saved.set(key, structuredClone(value)) }, remove: key => { saved.delete(key) } }
  const files: UploadFiles = { choose: async () => null, save: async (_path, requestId) => `/owned/${requestId}`, inspect: async () => ({ sha256: 'a'.repeat(64), bytes: 9 }), owns: (path, requestId) => path === `/owned/${requestId}`, remove: async () => {} }
  const make = (userId = '101') => {
    const currentSession = { ...session, userId }
    saved.set('pet.c.session.v1', { ...currentSession, tokenType: 'Bearer', accessToken: 'test-only-unusable' })
    return new ConsumerApi(async () => ({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data: currentSession, traceId: 'test' } }), store, async () => randomUUID())
  }
  const api = make()
  await api.restore(); api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '501', storeId: '601' })
  return { api, store, files, make, deps: merchantAfterSaleDeps(new AfterSaleClient(api, 'merchant'), store, files) }
}
test('drafts and upload UUID journals are isolated across merchant/store/case and inaccessible while logged out', async () => {
  const { api, deps } = await setup()
  const draft = { opinionCode: 'DISAGREE' as const, text: '商家说明相关真实问题和证据', assetIds: ['801'] }
  const attempt = { filePath: '/owned/00000000-0000-4000-8000-000000000001', requestId: '00000000-0000-4000-8000-000000000001', sha256: 'a'.repeat(64), bytes: 9, attempted: true }
  deps.saveDraft('301', draft); deps.saveUploadAttempt('301', attempt)
  assert.deepEqual(deps.loadDraft('301'), draft); assert.deepEqual(deps.uploadAttempt('301'), attempt)
  assert.equal(deps.loadDraft('302'), null); assert.equal(deps.uploadAttempt('302'), null)
  api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '501', storeId: '602' }); assert.equal(deps.loadDraft('301'), null); assert.equal(deps.uploadAttempt('301'), null)
  api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '502', storeId: '601' }); assert.equal(deps.loadDraft('301'), null)
  api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '501', storeId: '601' }); assert.deepEqual(deps.loadDraft('301'), draft)
  api.cancelLogin(); assert.throws(() => deps.loadDraft('301'), /NO_CONTEXT/)
})
test('consumer identity cannot obtain a merchant-local upload journal', async () => {
  const { api, deps, store, files } = await setup(); api.scope.replace({ userId: '101', workspace: 'consumer', merchantId: null, storeId: null })
  assert.throws(() => deps.uploadAttempt('301'), /MERCHANT_ENTRY_REQUIRED/)
  assert.throws(() => merchantAfterSaleDeps(new AfterSaleClient(api, 'c'), store, files), /WORKSPACE_PATH_MISMATCH/)
})
test('unknown upload survives logout and same-user reauthentication; another user cannot recover or send it', async () => {
  const h = await setup()
  const attempt = { filePath: '/owned/00000000-0000-4000-8000-000000000001', requestId: '00000000-0000-4000-8000-000000000001', sha256: 'a'.repeat(64), bytes: 9, attempted: true }
  h.deps.saveUploadAttempt('301', attempt); h.api.cancelLogin()
  const other = h.make('102'); await other.restore(); other.scope.replace({ userId: '102', workspace: 'merchant', merchantId: '501', storeId: '601' })
  const otherDeps = merchantAfterSaleDeps(new AfterSaleClient(other, 'merchant'), h.store, h.files); assert.equal(otherDeps.uploadAttempt('301'), null)
  const same = h.make('101'); await same.restore(); same.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '501', storeId: '601' })
  const sameDeps = merchantAfterSaleDeps(new AfterSaleClient(same, 'merchant'), h.store, h.files); assert.deepEqual(sameDeps.uploadAttempt('301'), attempt)
})
