// Invoked only by the real isolated Boot lifecycle test, never by application code.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore } from '../consumer-api'
import { MerchantApplicationRepository, MerchantAgreementRepository } from '../merchant-repositories'

async function verify() {
  const origin = process.env.MERCHANT_TEST_ORIGIN || ''
  assert.match(origin, /^http:\/\/127\.0\.0\.1:[0-9]+$/)
  const grant = JSON.parse(process.env.MERCHANT_TEST_GRANT || 'null')
  const values = new Map<string, unknown>([['pet.c.session.v1', grant]])
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => { values.set(key, structuredClone(value)) }, remove: key => { values.delete(key) } }
  const api = new ConsumerApi(async request => {
    const url = new URL(request.path, origin)
    if (request.method === 'GET' && request.data) for (const [key, value] of Object.entries(request.data)) url.searchParams.set(key, String(value))
    const response = await fetch(url, { method: request.method, headers: request.headers,
      body: request.method === 'GET' ? undefined : JSON.stringify(request.data) })
    return { statusCode: response.status, data: await response.json() }
  }, store, async () => randomUUID())
  await api.restore()
  const repository = new MerchantApplicationRepository(api)
  const detail = await repository.current()
  assert.equal(detail.status, 'APPROVED')
  assert.equal(detail.latestDecision?.decisionType, 'APPROVE')
  assert.equal(detail.subjectVerificationStatus, 'VERIFIED')
  assert.equal(detail.currentRevision.draft.cityCode, 'chengdu')
  const cities = await repository.cities()
  assert.deepEqual(cities, [{ cityCode: 'chengdu', cityName: '成都' }])
  api.scope.replace({ userId: api.currentSession!.userId, workspace: 'merchant', merchantId: detail.reservedMerchantId, storeId: null })
  const agreements = new MerchantAgreementRepository(api)
  const agreement = await agreements.read(detail.reservedMerchantId)
  assert.equal(agreement.signingStatus, 'SIGNED')
  assert.equal(agreement.acceptedVersion, agreement.agreementVersion)
  assert.ok(agreement.acceptedAt)
  // A signed agreement can never be re-signed from this client, even with the checkbox path.
  // The repository guard throws synchronously; wrap it so assert.rejects validates the rejection.
  await assert.rejects(async () => agreements.consent(agreement, true), /EXPLICIT_AGREEMENT_REQUIRED/)
  api.scope.replace({ userId: api.currentSession!.userId, workspace: 'consumer', merchantId: null, storeId: null })
  console.log('MER frontend decoders passed against real authorized HTTP application/city/agreement responses')
}

void verify().catch(error => {
  console.error('MER frontend HTTP integration failed:', error instanceof Error ? error.name : 'unknown')
  process.exitCode = 1
})
