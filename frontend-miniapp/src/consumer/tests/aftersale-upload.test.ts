import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore, type PrivateAssetReceipt } from '../../shared/consumer-api'
import type { UploadFiles } from '../../shared/private-asset-upload'
import { ApiError } from '../../shared/request'
import { AfterSaleEvidenceUpload } from '../aftersale/upload'

const receipt: PrivateAssetReceipt = { assetId: '701', status: 'READY', objectSha256: 'b'.repeat(64), mediaType: 'image/png', bytes: 9 }
async function setup() {
  const saved = new Map<string, unknown>(), originals = new Set(['original']), copies = new Set<string>(), calls: { path: string; uuid: string }[] = []
  const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
  const store: LocalStore = { get: key => saved.get(key), set: (key, value) => saved.set(key, structuredClone(value)), remove: key => { saved.delete(key) } }
  store.set('pet.c.session.v1', { ...session, accessToken: 'test-only', tokenType: 'Bearer' })
  const api = new ConsumerApi(async () => ({ statusCode: 200, data: { code: 'SUCCESS', data: session } }), store, async () => randomUUID()); await api.restore()
  let chosen = 0, changed = false, cancelled = false, response: () => Promise<PrivateAssetReceipt> = async () => receipt
  const files: UploadFiles = {
    choose: async () => { chosen++; return cancelled ? null : 'original' }, save: async (_, key) => { copies.add(`/saved/${key}.png`); return `/saved/${key}.png` },
    inspect: async () => ({ sha256: (changed ? 'c' : 'a').repeat(64), bytes: 9 }), owns: (path, key) => path === `/saved/${key}.png`, remove: async path => { copies.delete(path) },
  }
  const client = { api, upload: async (path: string, uuid: string) => { calls.push({ path, uuid }); return response() } }
  const upload = new AfterSaleEvidenceUpload(client, store, files, 'evidence:501')
  return { upload, client, store, files, calls, originals, copies, chosen: () => chosen, respond: (value: typeof response) => { response = value }, change: () => { changed = true }, cancel: () => { cancelled = true } }
}
test('upload timeout survives remount with exact file and UUID; ACK removes only saved copy', async () => {
  const h = await setup(); h.respond(async () => { throw new Error('timeout') })
  await assert.rejects(h.upload.upload(), /timeout/); assert.equal(h.chosen(), 1)
  const original = h.upload.pending()!
  const remount = new AfterSaleEvidenceUpload(h.client, h.store, h.files, 'evidence:501')
  h.respond(async () => receipt); assert.deepEqual(await remount.upload(), receipt)
  assert.deepEqual(h.calls[0], h.calls[1]); assert.equal(h.chosen(), 1); assert.equal(original.requestId, h.calls[1].uuid)
  await remount.acknowledge(receipt.assetId); assert.equal(h.copies.size, 0); assert.equal(h.originals.has('original'), true); assert.equal(remount.pending(), null)
})
test('cancelled chooser has no upload or durable network intent', async () => {
  const h = await setup(); h.cancel(); assert.equal(await h.upload.upload(), null); assert.equal(h.upload.pending(), null); assert.equal(h.calls.length, 0)
})
test('changed saved image cannot be uploaded under the original key', async () => {
  const h = await setup(); h.respond(async () => { throw new Error('timeout') }); await assert.rejects(h.upload.upload())
  h.change(); await assert.rejects(h.upload.upload(), /UPLOAD_FILE_CHANGED/); assert.equal(h.calls.length, 1)
})
test('a definite first media rejection can be discarded; unknown earlier result stays journaled', async () => {
  const h = await setup(); h.respond(async () => { throw new ApiError('PRIVATE_ASSET_UNSUPPORTED_MEDIA', 415) }); await assert.rejects(h.upload.upload())
  assert.equal(h.upload.pending()?.rejected, true); await h.upload.discardRejected(); assert.equal(h.copies.size, 0)
  h.respond(async () => { throw new Error('timeout') }); await assert.rejects(h.upload.upload())
  h.respond(async () => { throw new ApiError('COMMON_INVALID_ARGUMENT', 400) }); await assert.rejects(h.upload.upload())
  assert.equal(h.upload.pending()?.rejected, false); await assert.rejects(h.upload.discardRejected(), /UPLOAD_PENDING/)
})
test('upload recovery is isolated by principal and form target', async () => {
  const h = await setup(); h.respond(async () => { throw new Error('timeout') }); await assert.rejects(h.upload.upload())
  assert.equal(new AfterSaleEvidenceUpload(h.client, h.store, h.files, 'evidence:502').pending(), null)
  h.client.api.scope.replace({ userId: '102', workspace: 'consumer', merchantId: null, storeId: null })
  assert.throws(() => h.upload.pending(), /COMMON_UNAUTHORIZED/)
})
