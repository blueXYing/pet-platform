import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore, integrationMessage } from '../consumer-api'
import { ApiError, type Transport, type WireRequest } from '../request'
import { MerchantApplicationRepository, MerchantAgreementRepository, decodeApplicationDetail, decodeApplicationResult, decodeDraft, decodeAgreement, decodeVersion } from '../merchant-repositories'
const time = '2026-09-17T00:00:00.000Z'
const principal = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const result = { applicationId: '301', reservedMerchantId: '401', currentRevisionId: '501', version: '0', status: 'DRAFT', applicationNo: null }
const detail = { ...result, currentRevision: { revisionId: '501', revisionNo: '1', draft: {}, createdAt: time }, submittedAt: null, reviewedAt: null, latestDecision: null, subjectVerificationStatus: 'PENDING' }
const agreement = { merchantId: '401', agreementVersion: 'v1', content: '协议正文', contentSha256: 'a'.repeat(64), signingStatus: 'NOT_SIGNED' }
const consent = { merchantId: '401', agreementVersion: 'v1', signingStatus: 'SIGNED', acceptedAt: time }
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', success: true, data } })
async function setup(handler: Transport, existing?: Map<string, unknown>) {
  const values = existing || new Map<string, unknown>([['pet.c.session.v1', { ...principal, accessToken: 'test-secret', tokenType: 'Bearer' }]])
  const store: LocalStore = { get: k => values.get(k), set: (k, v) => { values.set(k, structuredClone(v)) }, remove: k => { values.delete(k) } }
  const calls: WireRequest[] = []
  const api = new ConsumerApi(async req => { calls.push(structuredClone(req)); return req.path.endsWith('/auth/session') ? ok(principal) : handler(req) }, store, async () => randomUUID())
  await api.restore()
  return { api, calls, values, application: new MerchantApplicationRepository(api), agreement: new MerchantAgreementRepository(api) }
}
function merchant(api: ConsumerApi) { api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '401', storeId: null }) }

test('application decoder enforces string IDs, version range, number format and unknown field rejection', () => {
  assert.deepEqual(decodeApplicationResult(result), result)
  for (const patch of [{ applicationId: 301 }, { version: '9223372036854775808' }, { status: 'SIGNED' }, { applicationNo: 'SQ20260917abcdefgh' }, { internalNote: 'secret' }]) assert.throws(() => decodeApplicationResult({ ...result, ...patch }), /INVALID_RESPONSE/)
  assert.equal(decodeVersion('9223372036854775807'), '9223372036854775807')
})
test('owner detail rejects sensitive fields, inconsistent revision and unsupported decision states', () => {
  assert.deepEqual(decodeApplicationDetail(detail), detail)
  for (const patch of [{ currentRevision: { ...detail.currentRevision, revisionId: '502' } }, { latestDecision: {} }, { status: 'APPROVED', applicationNo: 'SQ20260917abcdefgh', submittedAt: time, reviewedAt: time }, { internalNote: 'secret' }, { subjectVerificationStatus: 'OCR_PASSED' }]) assert.throws(() => decodeApplicationDetail({ ...detail, ...patch }), /INVALID_RESPONSE/)
})
test('draft preserves clear semantics while rejecting duplicate/private invented inputs and invalid decimal coordinates', () => {
  assert.deepEqual(decodeDraft({ merchantName: null, storePhotoAssetIds: [], longitude: '120.12' }), { merchantName: null, storePhotoAssetIds: [], longitude: '120.12' })
  for (const value of [{ storePhotoAssetIds: ['1', '1'] }, { storePhotoAssetIds: null }, { longitude: 120 }, { latitude: '90.0000001' }, { merchantTypeCode: 'GARDEN' }, { businessLicenseUrl: 'https://private' }, { identifier: 'private' }]) assert.throws(() => decodeDraft(value), /INVALID_RESPONSE/)
})
test('application read/create/save/submit uses exact candidate endpoints and optimistic version payloads', async () => {
  const h = await setup(async req => ok(req.method === 'GET' ? detail : req.path.endsWith('/submit') ? { ...result, status: 'REVIEWING', applicationNo: 'SQ20260917abcdefgh' } : result))
  await h.application.current(); await h.application.create(); await h.application.save('301', '0', { merchantName: null }); await h.application.submit('301', '1', '501')
  assert.deepEqual(h.calls.slice(1).map(c => [c.method, c.path]), [['GET', '/api/v1/c/merchant-applications/current'], ['POST', '/api/v1/c/merchant-applications'], ['PUT', '/api/v1/c/merchant-applications/301/draft'], ['POST', '/api/v1/c/merchant-applications/301/submit']])
  assert.deepEqual(h.calls.at(-1)?.data, { expectedVersion: '1', revisionId: '501' })
  for (const c of h.calls.slice(1)) { assert.equal(c.headers.Authorization, 'Bearer test-secret'); if (c.method !== 'GET') assert.match(c.headers['X-Request-Id'], /^[a-f0-9-]{36}$/) }
})
test('unknown write survives restart with identical requestId and blocks modified intent', async () => {
  const h = await setup(async () => { throw new Error('ack lost') })
  await assert.rejects(h.application.save('301', '0', { merchantName: 'original' }), /ack lost/)
  const original = h.calls.at(-1)
  const restored = await setup(async () => ok(result), h.values)
  await assert.rejects(restored.application.save('301', '0', { merchantName: 'changed' }), /PENDING_WRITE_CHANGED/)
  await restored.application.save('301', '0', { merchantName: 'original' })
  assert.deepEqual(restored.calls.at(-1), original)
})
test('draft mutation after command start cannot change persisted/sent payload', async () => {
  const h = await setup(async () => ok(result))
  const draft = { merchantName: 'original', storePhotoAssetIds: ['601'] }
  const pending = h.application.create(draft)
  draft.merchantName = 'changed'; draft.storePhotoAssetIds.push('602')
  await pending
  assert.deepEqual(h.calls.at(-1)?.data, { merchantName: 'original', storePhotoAssetIds: ['601'] })
})
test('agreement decoder checks signed evidence, hash, version, unknown fields and UTF-8 byte limit', () => {
  assert.deepEqual(decodeAgreement(agreement), agreement)
  for (const patch of [{ signingStatus: 'SIGNED' }, { contentSha256: 'A'.repeat(64) }, { agreementVersion: 'v 1' }, { content: '中'.repeat(21846) }, { acceptedAt: null }, { url: 'https://external' }]) assert.throws(() => decodeAgreement({ ...agreement, ...patch }))
})
test('agreement requires merchant workspace, matching merchant and explicit consent; server receives bearer', async () => {
  const h = await setup(async req => ok(req.method === 'GET' ? agreement : consent))
  await assert.rejects(h.agreement.read('401'), /WORKSPACE_PATH_MISMATCH/)
  merchant(h.api)
  await assert.rejects(h.agreement.read('402'), /WORKSPACE_PATH_MISMATCH/)
  const view = await h.agreement.read('401')
  assert.throws(() => h.agreement.consent(view, false), /EXPLICIT_AGREEMENT_REQUIRED/)
  assert.deepEqual(await h.agreement.consent(view, true), consent)
  assert.deepEqual(h.calls.at(-1)?.data, { merchantId: '401', agreementVersion: 'v1', contentSha256: 'a'.repeat(64), accepted: true })
  assert.equal(h.calls.at(-1)?.headers.Authorization, 'Bearer test-secret')
})
test('merchant access is narrowly path/method bound; session user cannot be replaced with fixture identity', async () => {
  const h = await setup(async () => ok(agreement)); merchant(h.api)
  for (const path of ['/api/v1/merchant/staff', '/api/v1/merchant/agreement?merchantId=401', '/api/v1/merchant/agreement/../staff']) await assert.rejects(h.api.request({ path, method: 'GET', data: { merchantId: '401' } }, x => x), /INVALID_PATH/)
  await assert.rejects(h.api.request({ path: '/api/v1/merchant/agreement/consent', method: 'GET', data: { merchantId: '401' } }, x => x), /INVALID_PATH/)
  h.api.scope.replace({ userId: '999', workspace: 'merchant', merchantId: '401', storeId: null })
  await assert.rejects(h.agreement.read('401'), e => e instanceof ApiError && e.statusCode === 401)
  assert.equal(h.calls.length, 1)
})
test('old merchant response cannot enter switched workspace', async () => {
  let finish!: (v: ReturnType<typeof ok>) => void
  const h = await setup(() => new Promise(resolve => { finish = resolve })); merchant(h.api)
  const pending = h.agreement.read('401')
  h.api.scope.replace({ userId: '101', workspace: 'consumer', merchantId: null, storeId: null })
  finish(ok(agreement)); await assert.rejects(pending, /STALE_CONTEXT/)
})
test('403 never becomes consent success; 409/503 preserve original consent for explicit retry', async () => {
  for (const code of [403, 409, 503]) {
    const h = await setup(async () => ({ statusCode: code, data: { code: 'SERVER_REJECTED', data: null } })); merchant(h.api)
    const view = decodeAgreement(agreement)
    await assert.rejects(h.agreement.consent(view, true), e => e instanceof ApiError && e.statusCode === code)
    assert.equal(!!h.api.pendingCommand('merchant-agreement:401:consent'), code !== 403)
    assert.ok(integrationMessage(new ApiError('SERVER_REJECTED', code)).length)
  }
})
test('agreement wrong-merchant receipt is invalid and remains unresolved', async () => {
  const h = await setup(async () => ok({ ...consent, merchantId: '402' })); merchant(h.api)
  await assert.rejects(h.agreement.consent(decodeAgreement(agreement), true), /INVALID_RESPONSE/)
  assert.ok(h.api.pendingCommand('merchant-agreement:401:consent'))
})


test('application success envelope must be true; malformed success keeps write unresolved', async () => {
  for (const success of [false, undefined]) {
    const h = await setup(async () => ({ statusCode: 200, data: { code: 'SUCCESS', success, data: result } }))
    await assert.rejects(h.application.create(), /INVALID_RESPONSE/)
    assert.ok(h.api.pendingCommand('merchant-application:create'))
  }
})
