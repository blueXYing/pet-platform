import test from 'node:test'
import assert from 'node:assert/strict'
import { SigningController, signingMessage, unavailableSigningDependencies, type ConsentPayload, type SigningDependencies } from '../merchant-application/signing'
import { ApiError } from '../../shared/request'
import { WorkspaceScope } from '../../shared/workspace'
import type { Agreement } from '../../shared/merchant-repositories'

const time = '2026-09-21T08:00:00.000Z'
const hash = (seed: string) => seed.replace(/./g, c => ((c.charCodeAt(0) % 16).toString(16))).padEnd(64, '0').slice(0, 64)
const notSigned = (version: string): Agreement => ({ merchantId: '401', agreementVersion: version, content: `协议正文 ${version}`, contentSha256: hash(version), signingStatus: 'NOT_SIGNED' })
const alreadySigned = (version: string): Agreement => ({ ...notSigned(version), signingStatus: 'SIGNED', acceptedVersion: version, acceptedAt: time })

class Harness {
  journal = new Map<string, ConsentPayload>()
  retired: ConsentPayload[] = []
  sent: ConsentPayload[] = []
  readCalls = 0
  consentAttempts = 0
  retries = 0
  readImpl: (merchantId: string) => Promise<Agreement> = async () => { throw new Error('read not wired') }
  outcome: (payload: ConsentPayload) => Promise<{ merchantId: string; agreementVersion: string; acceptedAt: string; signingStatus: 'SIGNED' }> = async () => { throw new Error('outcome not wired') }
  readonly deps: SigningDependencies = {
    read: async merchantId => { this.readCalls++; return this.readImpl(merchantId) },
    pendingConsent: merchantId => this.journal.get(merchantId) ?? null,
    consent: (view, accepted) => {
      if (view.signingStatus !== 'NOT_SIGNED' || !accepted) return Promise.reject(new Error('EXPLICIT_AGREEMENT_REQUIRED'))
      this.consentAttempts++
      return this.send({ merchantId: view.merchantId, agreementVersion: view.agreementVersion, contentSha256: view.contentSha256, accepted: true })
    },
    retryConsent: merchantId => {
      const pending = this.journal.get(merchantId)
      if (!pending) return Promise.reject(new Error('NO_PENDING_CONSENT'))
      this.retries++
      return this.send(pending)
    },
    retireConsent: (merchantId, rejected) => {
      const pending = this.journal.get(merchantId)
      if (!pending || pending.agreementVersion !== rejected.agreementVersion || pending.contentSha256 !== rejected.contentSha256) throw new Error('PENDING_WRITE_CHANGED')
      this.retired.push(pending)
      this.journal.delete(merchantId)
    },
  }
  private async send(payload: ConsentPayload) {
    this.sent.push(payload)
    this.journal.set(payload.merchantId, payload)
    try {
      const receipt = await this.outcome(payload)
      this.journal.delete(payload.merchantId)
      return receipt
    } catch (error) {
      if (error instanceof ApiError && [400, 401, 403, 404, 422].includes(error.statusCode)) this.journal.delete(payload.merchantId)
      throw error
    }
  }
}
async function entered(harness: Harness, agreement: Agreement) {
  harness.readImpl = async () => agreement
  const scope = new WorkspaceScope()
  scope.replace({ userId: '7', workspace: 'consumer', merchantId: null, storeId: null })
  const controller = new SigningController(scope, harness.deps)
  await controller.enter('401')
  return { scope, controller }
}

test('enter switches to the merchant candidate workspace and loads the unsigned agreement', async () => {
  const harness = new Harness()
  const { scope, controller } = await entered(harness, notSigned('v1'))
  assert.equal(scope.current?.workspace, 'merchant')
  assert.equal(scope.current?.merchantId, '401')
  assert.equal(scope.current?.userId, '7')
  const state = controller.getSnapshot()
  assert.equal(state.phase, 'unsigned')
  assert.equal(state.agreement?.agreementVersion, 'v1')
  assert.equal(state.checked, false)
  assert.equal(harness.readCalls, 1)
})

test('consent requires the explicit checkbox and signs from the command receipt only', async () => {
  const harness = new Harness()
  harness.outcome = async payload => ({ merchantId: payload.merchantId, agreementVersion: payload.agreementVersion, acceptedAt: time, signingStatus: 'SIGNED' })
  const { controller } = await entered(harness, notSigned('v1'))
  await controller.submit()
  assert.equal(controller.getSnapshot().phase, 'unsigned')
  assert.equal(harness.consentAttempts, 0)
  controller.toggle()
  assert.equal(controller.getSnapshot().checked, true)
  await controller.submit()
  const signed = controller.getSnapshot()
  assert.equal(signed.phase, 'signed')
  assert.equal(signed.receipt?.acceptedAt, time)
  assert.equal(signed.receipt?.agreementVersion, 'v1')
  await controller.submit()
  assert.equal(harness.consentAttempts, 1)
  controller.dispose()
})

test('unknown consent outcome stays journaled and recovers by replaying the original command', async () => {
  const harness = new Harness()
  harness.outcome = async () => { throw new Error('network') }
  const { controller } = await entered(harness, notSigned('v1'))
  controller.toggle()
  await controller.submit()
  assert.equal(controller.getSnapshot().phase, 'recovering')
  assert.equal(harness.journal.size, 1)
  assert.equal(controller.getSnapshot().pending?.agreementVersion, 'v1')
  harness.outcome = async payload => ({ merchantId: payload.merchantId, agreementVersion: payload.agreementVersion, acceptedAt: time, signingStatus: 'SIGNED' })
  await controller.retryPending()
  assert.equal(controller.getSnapshot().phase, 'signed')
  assert.equal(harness.journal.size, 0)
  assert.equal(harness.consentAttempts, 1)
  assert.equal(harness.retries, 1)
  assert.deepEqual(harness.sent[0], harness.sent[1])
  controller.dispose()
})

test('a definitive 409 retires exactly the rejected payload, then the new version can be confirmed', async () => {
  const harness = new Harness()
  let version = 'v1'
  harness.readImpl = async () => notSigned(version)
  harness.outcome = async () => { throw new ApiError('COMMON_CONFLICT', 409) }
  const scope = new WorkspaceScope()
  scope.replace({ userId: '7', workspace: 'consumer', merchantId: null, storeId: null })
  const controller = new SigningController(scope, harness.deps)
  await controller.enter('401')
  controller.toggle()
  await controller.submit()
  const conflicted = controller.getSnapshot()
  assert.equal(conflicted.phase, 'conflict')
  assert.equal(conflicted.checked, false)
  assert.deepEqual(harness.retired, [harness.sent[0]])
  assert.equal(harness.journal.size, 0)
  version = 'v2'
  harness.outcome = async payload => ({ merchantId: payload.merchantId, agreementVersion: payload.agreementVersion, acceptedAt: time, signingStatus: 'SIGNED' })
  await controller.reread()
  const reloaded = controller.getSnapshot()
  assert.equal(reloaded.phase, 'unsigned')
  assert.equal(reloaded.agreement?.agreementVersion, 'v2')
  assert.equal(reloaded.checked, false)
  controller.toggle()
  await controller.submit()
  assert.equal(controller.getSnapshot().phase, 'signed')
  assert.equal(controller.getSnapshot().receipt?.agreementVersion, 'v2')
  assert.equal(harness.retired.length, 1)
  controller.dispose()
})

test('a signed read snapshot never resolves a journaled consent; only replay may', async () => {
  const harness = new Harness()
  const pending: ConsentPayload = { merchantId: '401', agreementVersion: 'v1', contentSha256: hash('v1'), accepted: true }
  harness.journal.set('401', pending)
  harness.readImpl = async () => alreadySigned('v1')
  harness.outcome = async payload => ({ merchantId: payload.merchantId, agreementVersion: payload.agreementVersion, acceptedAt: time, signingStatus: 'SIGNED' })
  const { controller } = await entered(harness, alreadySigned('v1'))
  assert.equal(controller.getSnapshot().phase, 'recovering')
  assert.equal(harness.readCalls, 0)
  assert.equal(harness.journal.size, 1)
  await controller.retryPending()
  assert.equal(controller.getSnapshot().phase, 'signed')
  assert.equal(harness.journal.size, 0)
  controller.dispose()
})

test('malformed merchant ids never reach the repository; ownership and dependency faults map to states', async () => {
  for (const bad of [undefined, 'abc', '0', '401x']) {
    const harness = new Harness()
    const scope = new WorkspaceScope()
    scope.replace({ userId: '7', workspace: 'consumer', merchantId: null, storeId: null })
    const controller = new SigningController(scope, harness.deps)
    await controller.enter(bad)
    assert.equal(controller.getSnapshot().phase, 'invalid-merchant', `merchantId=${String(bad)}`)
    assert.equal(harness.readCalls, 0)
    assert.equal(scope.current?.workspace, 'consumer')
    controller.dispose()
  }
  for (const [error, phase] of [[new ApiError('COMMON_FORBIDDEN', 403), 'denied'], [new ApiError('COMMON_UNAUTHORIZED', 401), 'unauthorized'], [new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503), 'load-failed']] as [ApiError, string][]) {
    const harness = new Harness()
    harness.readImpl = async () => { throw error }
    const scope = new WorkspaceScope()
    scope.replace({ userId: '7', workspace: 'consumer', merchantId: null, storeId: null })
    const controller = new SigningController(scope, harness.deps)
    await controller.enter('401')
    assert.equal(controller.getSnapshot().phase, phase)
    assert.equal(controller.getSnapshot().notice, signingMessage(error))
    controller.dispose()
  }
})

test('leave restores consumer coordinates only while this page owns the current revision', async () => {
  const harness = new Harness()
  const { scope, controller } = await entered(harness, notSigned('v1'))
  controller.leave()
  assert.equal(scope.current?.workspace, 'consumer')
  assert.equal(scope.current?.merchantId, null)
  await controller.enter('401')
  scope.replace({ userId: '7', workspace: 'merchant', merchantId: '402', storeId: null })
  controller.leave()
  assert.equal(scope.current?.merchantId, '402')
  controller.dispose()
})

test('preview mode shows the interaction only and the unavailable runtime fails closed', () => {
  const scope = new WorkspaceScope()
  scope.replace({ userId: '7', workspace: 'consumer', merchantId: null, storeId: null })
  const controller = new SigningController(scope, unavailableSigningDependencies())
  controller.enterPreview('401')
  assert.equal(controller.getSnapshot().phase, 'unsigned')
  assert.equal(controller.getSnapshot().agreement, null)
  controller.enterPreview('abc')
  assert.equal(controller.getSnapshot().phase, 'invalid-merchant')
  controller.dispose()
})

test('signing messages stay fail-closed and never claim success', () => {
  assert.match(signingMessage(new ApiError('COMMON_CONFLICT', 409)), /重新阅读/)
  assert.match(signingMessage(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), /尚未确认/)
  assert.match(signingMessage(new Error('SIGNING_NOT_CONNECTED')), /暂未接通/)
  assert.match(signingMessage(new Error('EXPLICIT_AGREEMENT_REQUIRED')), /勾选同意/)
  assert.match(signingMessage(new Error('unknown')), /不会显示已签署/)
})
