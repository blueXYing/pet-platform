// Invoked only by the real isolated Boot lifecycle test, never by application code.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore } from '../consumer-api'
import { MerchantApplicationRepository, MerchantAgreementRepository, MerchantAdmissionRepository } from '../merchant-repositories'
import { NotificationRepository, type InboxNotification } from '../notification-repositories'

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
  // CCR-W2-ADMISSION-001 live chain: owner memberships on consumer coordinates, then the
  // five-condition ALLOWED admission on the selected merchant coordinates.
  api.scope.replace({ userId: api.currentSession!.userId, workspace: 'consumer', merchantId: null, storeId: null })
  const admissionClient = new MerchantAdmissionRepository(api)
  const memberships = await admissionClient.memberships(1, 20)
  assert.equal(memberships.total, 1)
  assert.equal(memberships.items.length, 1)
  const entry = memberships.items[0]!
  assert.equal(entry.membershipKind, 'OWNER')
  assert.ok(entry.merchantName)
  api.scope.replace({ userId: api.currentSession!.userId, workspace: 'merchant', merchantId: entry.merchantId, storeId: entry.storeId })
  const admission = await admissionClient.admission(entry.merchantId, entry.storeId)
  assert.equal(admission.admission, 'ALLOWED')
  assert.equal(admission.membershipKind, 'OWNER')
  assert.equal(admission.facts.application?.status, 'APPROVED')
  assert.equal(admission.facts.signing.status, 'SIGNED')
  assert.equal(admission.facts.storeStatus, 'ACTIVE')
  assert.equal(admission.facts.merchantStatus, 'ACTIVE')
  assert.equal(admission.facts.staffEnabled, null)
  assert.deepEqual(admission.reasonCodes, [])
  assert.deepEqual(admission.nextSteps, [])
  assert.ok(admission.allowedActions.length >= 1)
  api.scope.replace({ userId: api.currentSession!.userId, workspace: 'consumer', merchantId: null, storeId: null })
  // CCR-W2-NOTIFICATION-001 live chain: the approval notification is visible, readable and
  // read-marking is idempotent for the owner. Outbox delivery is asynchronous and a second
  // owner notification follows the consent, so locate the reviewed message by type instead of
  // assuming the inbox holds exactly one item, and tolerate delivery latency with a bounded poll.
  const inbox = new NotificationRepository(api)
  const deadline = Date.now() + 15_000
  let page = await inbox.list(1, 20)
  const isReviewed = (candidate: InboxNotification) => candidate.messageType === 'MERCHANT_APPLICATION_REVIEWED'
  while (!page.items.some(isReviewed) && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 250))
    page = await inbox.list(1, 20)
  }
  const message = page.items.find(isReviewed)
  if (!message) throw new Error('REVIEWED_NOTIFICATION_NOT_DELIVERED')
  assert.equal(message.bizType, 'MERCHANT_APPLICATION')
  assert.equal(message.readAt, null)
  const messageDetail = await inbox.detail(message.id)
  assert.equal(messageDetail.title, message.title)
  const readOnce = await inbox.markRead(message.id)
  assert.ok(readOnce.readAt)
  const readAgain = await inbox.markRead(message.id)
  assert.equal(readAgain.readAt, readOnce.readAt)
  const afterRead = await inbox.list(1, 20)
  const sameMessage = (candidate: InboxNotification) => candidate.id === message.id
  assert.equal(afterRead.items.find(sameMessage)?.readAt, readOnce.readAt)
  console.log('MER frontend decoders passed against real authorized HTTP application/city/agreement/admission/inbox responses')
}

void verify().catch(error => {
  console.error('MER frontend HTTP integration failed:', error instanceof Error ? error.name : 'unknown')
  process.exitCode = 1
})
