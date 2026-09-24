import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore, type PrivateUploadTransport } from '../../shared/consumer-api'
import type { UploadFiles } from '../../shared/private-asset-upload'
import { ServiceCoverUpload } from '../services/cover-upload'
import { PrivateMaterialUpload } from '../../consumer/merchant-application/upload'
import { createPrivateUploadTransport } from '../../shared/private-upload-transport'
import { usableCoverUrl } from '../../consumer/service/cover'

const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00Z' }
const receipt = { assetId: '900', status: 'READY', objectSha256: 'c'.repeat(64), mediaType: 'image/png', bytes: 9 }
const ok = (data: unknown, statusCode = 200) => ({ statusCode, data: { success: true, code: 'SUCCESS', data } })
async function setup() {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => { values.set(key, structuredClone(value)) }, remove: key => { values.delete(key) } }
  values.set('pet.c.session.v1', { ...session, accessToken: 'test-only-token', tokenType: 'Bearer' })
  let response: PrivateUploadTransport = async () => ok(receipt, 201)
  const calls: Parameters<PrivateUploadTransport>[0][] = []
  const transport: PrivateUploadTransport = async input => { calls.push(input); return response(input) }
  const api = new ConsumerApi(async () => ok(session), store, async () => randomUUID(), transport)
  await api.restore()
  api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '301', storeId: '401' })
  let chosen = 0
  const removed: string[] = []
  const files: UploadFiles = {
    choose: async () => { chosen++; return 'original' }, save: async (_, id) => `/owned/${id}`,
    owns: (path, id) => path === `/owned/${id}`, inspect: async () => ({ sha256: 'a'.repeat(64), bytes: 9 }),
    remove: async path => { removed.push(path) },
  }
  return { api, store, files, calls, removed, chosen: () => chosen, respond: (next: PrivateUploadTransport) => { response = next } }
}

test('service cover sends approved purpose and original UUID; READY alone is not binding acknowledgement', async () => {
  const h = await setup()
  const upload = new ServiceCoverUpload(h.api, h.store, h.files, 'new')
  assert.deepEqual(await upload.upload('cover'), receipt)
  assert.equal(h.calls[0].purpose, 'SERVICE_COVER')
  assert.equal(upload.pending()?.receipt?.assetId, '900')
  assert.equal(h.removed.length, 0)
  await assert.rejects(upload.acknowledge('901'), /UPLOAD_PENDING/)
  await upload.acknowledge('900')
  assert.equal(upload.pending(), null); assert.equal(h.removed.length, 1)
  let form: unknown
  const send = createPrivateUploadTransport('https://api.example.invalid', async opts => { form = opts.formData; return ok(receipt) })
  await send(h.calls[0]); assert.deepEqual(form, { purpose: 'SERVICE_COVER' })
})
test('timeout survives uploader restart with exact file and request ID, without choosing another image', async () => {
  const h = await setup(); h.respond(async () => { throw new Error('lost ACK') })
  const first = new ServiceCoverUpload(h.api, h.store, h.files, '501')
  await assert.rejects(first.upload('cover'), /lost ACK/)
  await assert.rejects(first.discardRejected(), /UPLOAD_PENDING/)
  const second = new ServiceCoverUpload(h.api, h.store, h.files, '501')
  h.respond(async () => ok(receipt))
  await second.upload('cover')
  assert.deepEqual(h.calls[0], h.calls[1]); assert.equal(h.chosen(), 1)
})
test('journal isolated by editor target/store and application purpose; consumer cannot upload covers', async () => {
  const h = await setup()
  await new ServiceCoverUpload(h.api, h.store, h.files, '501').upload('cover')
  assert.equal(new ServiceCoverUpload(h.api, h.store, h.files, '502').pending(), null)
  h.api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '301', storeId: '402' })
  assert.equal(new ServiceCoverUpload(h.api, h.store, h.files, '501').pending(), null)
  h.api.scope.replace({ userId: '101', workspace: 'consumer', merchantId: null, storeId: null })
  assert.equal(new PrivateMaterialUpload(h.api, h.store, h.files).pending(), null)
  await assert.rejects(h.api.uploadPrivateAsset({ ownerUserId: '101', filePath: 'x', requestId: randomUUID(), purpose: 'SERVICE_COVER' }), /WORKSPACE_PATH_MISMATCH/)
  assert.equal(h.calls.length, 1)
})
test('scope change while cover is uploading cannot attach old receipt to another store', async () => {
  const h = await setup()
  let finish!: (value: ReturnType<typeof ok>) => void
  let began!: () => void
  const started = new Promise<void>(resolve => { began = resolve })
  h.respond(async () => { began(); return new Promise(resolve => { finish = resolve }) })
  const upload = new ServiceCoverUpload(h.api, h.store, h.files, 'new')
  const flight = upload.upload('cover'); await started
  h.api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '301', storeId: '402' })
  finish(ok(receipt)); await assert.rejects(flight, /STALE_CONTEXT/)
  assert.equal(upload.pending(), null)
  h.api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '301', storeId: '401' })
  assert.equal(upload.pending()?.receipt, undefined)
})
test('terminal scanner rejection allows replacement; unknown server failure preserves original attempt', async () => {
  const h = await setup(); const upload = new ServiceCoverUpload(h.api, h.store, h.files, 'new')
  h.respond(async () => ({ statusCode: 503, data: { code: 'PRIVATE_ASSET_DEPENDENCY_UNAVAILABLE' } }))
  await assert.rejects(upload.upload('cover')); await assert.rejects(upload.discardRejected())
  const original = upload.pending()!.requestId
  h.respond(async () => ({ statusCode: 422, data: { code: 'PRIVATE_ASSET_REJECTED' } }))
  await assert.rejects(upload.upload('cover')); await upload.discardRejected()
  h.respond(async () => ok(receipt)); await upload.upload('cover')
  assert.notEqual(upload.pending()!.requestId, original)
})
test('consumer cover rendering only accepts unexpired HTTPS signatures', () => {
  const cover = { coverAssetId: '900', coverUrl: 'https://cdn.example.invalid/signed', coverUrlExpiresAt: '2026-09-24T09:00:00Z' }
  assert.equal(usableCoverUrl(cover, Date.parse('2026-09-24T08:59:59Z')), cover.coverUrl)
  assert.equal(usableCoverUrl(cover, Date.parse(cover.coverUrlExpiresAt)), null)
  assert.equal(usableCoverUrl({ ...cover, coverUrl: 'http://cdn.example.invalid/public' }, 0), null)
  assert.equal(usableCoverUrl({ ...cover, coverUrl: 'javascript:alert(1)' }, 0), null)
  assert.equal(usableCoverUrl(null, 0), null)
})
