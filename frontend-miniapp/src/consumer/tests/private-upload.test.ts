import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID, createHash } from 'node:crypto'
import { sha256 } from '@noble/hashes/sha256'
import { bytesToHex } from '@noble/hashes/utils'
import { ConsumerApi, type LocalStore, type PrivateUploadTransport } from '../../shared/consumer-api'
import { PrivateMaterialUpload, type UploadFiles } from '../merchant-application/upload'
import { createPrivateUploadTransport, type MultipartSender } from '../../shared/private-upload-transport'

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
  let chosen = 0
  const data = new Map<string, Uint8Array>([['original', new Uint8Array([1, 2, 3])]])
  const removed: string[] = []
  const files: UploadFiles = {
    choose: async () => { chosen++; return 'original' },
    save: async (path, key) => { const target = `/private/${key}`; data.set(target, data.get(path)!.slice()); return target },
    inspect: async path => { const bytes = data.get(path)!; return { sha256: bytesToHex(sha256(bytes)), bytes: bytes.byteLength } },
    owns: (path, key) => path === `/private/${key}`,
    remove: async path => { removed.push(path); data.delete(path) },
  }
  const upload = new PrivateMaterialUpload(api, store, files)
  return { api, store, values, calls, data, files, removed, upload, transport, chosen: () => chosen, respond: (next: PrivateUploadTransport) => { response = next } }
}

test('SHA256 matches standard vectors including large image-sized input', () => {
  for (const bytes of [new Uint8Array(), new TextEncoder().encode('abc'), new Uint8Array(10485760).fill(21)]) assert.equal(bytesToHex(sha256(bytes)), createHash('sha256').update(bytes).digest('hex'))
})
test('multipart transport fixes endpoint/purpose/file field and preserves header UUID without JSON Content-Type', async () => {
  const calls: Parameters<MultipartSender>[0][] = []
  const send = createPrivateUploadTransport('https://api.example.invalid', async options => { calls.push(options); return ok(receipt) })
  const requestId = randomUUID()
  await send({ filePath: '/private/copy', authorization: 'Bearer test-only', requestId })
  assert.deepEqual(calls[0], { url: 'https://api.example.invalid/api/v1/c/private-assets', filePath: '/private/copy', name: 'file', formData: { purpose: 'MERCHANT_APPLICATION_MATERIAL' }, header: { Authorization: 'Bearer test-only', 'X-Request-Id': requestId }, timeout: 60000 })
  assert.throws(() => createPrivateUploadTransport('http://api.example.invalid', async () => ok(receipt)), /HTTPS_ORIGIN_REQUIRED/)
})
test('saved copy and UUID survive restart and timeout; object hash may differ after sanitization', async () => {
  const h = await setup()
  h.respond(async () => { throw new Error('timeout') })
  await assert.rejects(h.upload.upload('businessLicenseAssetId'), /timeout/)
  const pending = h.upload.pending()!
  assert.equal(h.chosen(), 1); assert.equal(pending.bytes, 3)
  assert.notEqual(pending.filePath, 'original')
  assert.equal(h.removed.length, 0)
  const api = new ConsumerApi(async () => ok(session), h.store, async () => randomUUID(), h.transport)
  await api.restore()
  const restarted = new PrivateMaterialUpload(api, h.store, h.files)
  h.respond(async () => ok(receipt))
  assert.deepEqual(await restarted.upload('businessLicenseAssetId'), receipt)
  assert.deepEqual(h.calls[0], h.calls[1]); assert.equal(h.chosen(), 1)
  assert.equal(h.calls[0].authorization, 'Bearer test-only-token')
  assert.equal(h.calls[0].requestId, pending.requestId)
  await restarted.acknowledge(receipt.assetId)
  assert.deepEqual(h.removed, [pending.filePath]); assert.equal(h.data.has('original'), true)
  assert.equal(restarted.pending(), null)
})
test('double tap coalesces selection and network; different material cannot replace unknown intent', async () => {
  const h = await setup()
  const first = h.upload.upload('idCardFrontAssetId')
  assert.equal(h.upload.upload('idCardFrontAssetId'), first)
  await assert.rejects(h.upload.upload('idCardBackAssetId'), /UPLOAD_PENDING/)
  await first
  assert.equal(h.chosen(), 1); assert.equal(h.calls.length, 1)
  await assert.rejects(h.upload.upload('idCardBackAssetId'), /UPLOAD_PENDING/)
  assert.equal(h.calls.length, 1)
})
test('changed saved bytes cannot be sent with a previously bound request ID', async () => {
  const h = await setup(); h.respond(async () => { throw new Error('timeout') })
  await assert.rejects(h.upload.upload('storePhotoAssetIds'))
  const pending = h.upload.pending()!
  h.data.set(pending.filePath, new Uint8Array([9, 8, 7]))
  await assert.rejects(h.upload.upload('storePhotoAssetIds'), /UPLOAD_FILE_CHANGED/)
  assert.equal(h.calls.length, 1); assert.equal(h.upload.pending()!.requestId, pending.requestId)
})
test('401 expires session without deleting owner upload journal; new identity cannot access it', async () => {
  const h = await setup(); h.respond(async () => ({ statusCode: 401, data: { code: 'COMMON_UNAUTHORIZED' } }))
  await assert.rejects(h.upload.upload('idCardBackAssetId'))
  assert.equal(h.api.currentSession, null)
  assert.equal(h.values.has('pet.private-upload.v1:101'), true)
  assert.equal(h.removed.length, 0)
  h.values.set('pet.c.session.v1', { ...session, userId: '102', sessionId: '202', accessToken: 'other-test-token', tokenType: 'Bearer' })
  const other = new ConsumerApi(async () => ok({ ...session, userId: '102', sessionId: '202' }), h.store, async () => randomUUID(), h.transport)
  await other.restore()
  assert.equal(new PrivateMaterialUpload(other, h.store, h.files).pending(), null)
  await assert.rejects(other.uploadPrivateAsset({ ownerUserId: '101', filePath: '/private/other', requestId: randomUUID() }))
  assert.equal(h.calls.length, 1)
})
test('late response after scope replacement never creates a receipt', async () => {
  const h = await setup()
  let complete!: (value: ReturnType<typeof ok>) => void
  let started!: () => void
  const began = new Promise<void>(resolve => { started = resolve })
  h.respond(async () => { started(); return new Promise(resolve => { complete = resolve }) })
  const attempt = h.upload.upload('industryLicenseAssetId')
  await began
  h.api.scope.replace({ userId: '101', workspace: 'consumer', merchantId: null, storeId: null })
  complete(ok(receipt))
  await assert.rejects(attempt, /STALE_CONTEXT/)
  assert.equal(h.upload.pending()!.receipt, undefined)
})
test('forged response fields, IDs, hashes, status and envelope cannot publish materials', async () => {
  for (const invalid of [{ ...receipt, url: 'https://example.invalid/private' }, { ...receipt, assetId: 900 }, { ...receipt, objectSha256: 'invalid' }, { ...receipt, status: 'SCANNING' }, { ...receipt, bytes: 10485761 }, { ...receipt, mediaType: 'text/html' }]) {
    const h = await setup(); h.respond(async () => ok(invalid))
    await assert.rejects(h.upload.upload('businessLicenseAssetId'), /INVALID_RESPONSE/)
    assert.equal(h.upload.pending()!.receipt, undefined); assert.equal(h.removed.length, 0)
  }
  const h = await setup(); h.respond(async () => ({ statusCode: 201, data: { code: 'SUCCESS', data: receipt } }))
  await assert.rejects(h.upload.upload('businessLicenseAssetId'), /INVALID_RESPONSE/)
})
test('cancel chooser creates no journal/network; invalid saved path cannot be retried or deleted', async () => {
  const h = await setup(); h.files.choose = async () => null
  assert.equal(await h.upload.upload('storePhotoAssetIds'), null)
  assert.equal(h.calls.length, 0); assert.equal(h.upload.pending(), null)
  h.values.set('pet.private-upload.v1:101', { ownerUserId: '101', kind: 'storePhotoAssetIds', requestId: randomUUID(), filePath: 'original', sha256: 'a'.repeat(64), bytes: 3 })
  await assert.rejects(h.upload.upload('storePhotoAssetIds'), /UPLOAD_JOURNAL_INVALID/)
  assert.equal(h.calls.length, 0); assert.equal(h.removed.length, 0)
})
test('explicit new selection is available only after definite rejection; unknown outcomes cannot discard', async () => {
  const h = await setup(); h.respond(async () => { throw new Error('timeout') })
  await assert.rejects(h.upload.upload('storePhotoAssetIds'))
  await assert.rejects(h.upload.discardRejected(), /UPLOAD_PENDING/)
  h.respond(async () => ({ statusCode: 422, data: { code: 'PRIVATE_ASSET_INVALID' } }))
  await assert.rejects(h.upload.upload('storePhotoAssetIds'))
  assert.equal(h.upload.pending()!.rejected, false)
  await assert.rejects(h.upload.discardRejected(), /UPLOAD_PENDING/)
  const firstRejection = await setup()
  firstRejection.respond(async () => ({ statusCode: 400, data: { code: 'COMMON_INVALID_ARGUMENT' } }))
  await assert.rejects(firstRejection.upload.upload('storePhotoAssetIds'))
  const original = firstRejection.upload.pending()!
  assert.equal(original.rejected, true)
  await firstRejection.upload.discardRejected()
  assert.equal(firstRejection.upload.pending(), null); assert.deepEqual(firstRejection.removed, [original.filePath])
  firstRejection.respond(async () => ok(receipt))
  await firstRejection.upload.upload('storePhotoAssetIds')
  assert.notEqual(firstRejection.upload.pending()!.requestId, original.requestId)
})
test('5xx retry preserves exact descriptor and original UUID until confirmed READY', async () => {
  const h = await setup(); h.respond(async () => ({ statusCode: 503, data: { code: 'PRIVATE_ASSET_DEPENDENCY_UNAVAILABLE' } }))
  await assert.rejects(h.upload.upload('idCardFrontAssetId'))
  const first = h.upload.pending()
  await assert.rejects(h.upload.upload('idCardFrontAssetId'))
  assert.deepEqual(h.upload.pending(), first); assert.deepEqual(h.calls[0], h.calls[1])
  assert.equal(h.removed.length, 0); await assert.rejects(h.upload.discardRejected(), /UPLOAD_PENDING/)
})
test('unknown upload then explicit terminal rejection permits new file and UUID; status or code alone does not', async () => {
  const h = await setup(); h.respond(async () => { throw new Error('lost ACK') })
  await assert.rejects(h.upload.upload('businessLicenseAssetId'))
  const original = h.upload.pending()!
  for (const [statusCode, code] of [[400, 'PRIVATE_ASSET_REJECTED'], [422, 'COMMON_INVALID_ARGUMENT']] as const) {
    h.respond(async () => ({ statusCode, data: { code } }))
    await assert.rejects(h.upload.upload('businessLicenseAssetId'))
    await assert.rejects(h.upload.discardRejected(), /UPLOAD_PENDING/)
    assert.equal(h.upload.pending()!.requestId, original.requestId)
  }
  h.respond(async () => ({ statusCode: 422, data: { code: 'PRIVATE_ASSET_REJECTED' } }))
  await assert.rejects(h.upload.upload('businessLicenseAssetId'))
  assert.equal(h.upload.pending()!.rejected, true)
  await h.upload.discardRejected()
  assert.deepEqual(h.removed, [original.filePath])
  h.data.set('original', new Uint8Array([4, 5, 6]))
  h.respond(async () => ok(receipt))
  await h.upload.upload('businessLicenseAssetId')
  assert.notEqual(h.upload.pending()!.requestId, original.requestId)
  assert.notEqual(h.upload.pending()!.sha256, original.sha256)
  assert.equal(h.chosen(), 2)
})
test('old epoch 401 cannot clear the new identity or delete the old upload journal', async () => {
  const h = await setup()
  let complete!: (value: { statusCode: number; data: unknown }) => void
  let started!: () => void
  const began = new Promise<void>(resolve => { started = resolve })
  h.respond(async () => { started(); return new Promise(resolve => { complete = resolve }) })
  const attempt = h.upload.upload('businessLicenseAssetId')
  await began
  const newSession = { ...session, userId: '102', sessionId: '202' }
  h.api.currentSession = { ...newSession, audience: 'MINIAPP' }
  h.api.scope.replace({ userId: '102', workspace: 'consumer', merchantId: null, storeId: null })
  complete({ statusCode: 401, data: { code: 'COMMON_UNAUTHORIZED' } })
  await assert.rejects(attempt, /STALE_CONTEXT/)
  assert.equal(h.api.currentSession?.userId, '102'); assert.equal(h.api.scope.current?.userId, '102')
  assert.equal(h.values.has('pet.private-upload.v1:101'), true); assert.equal(h.removed.length, 0)
})
