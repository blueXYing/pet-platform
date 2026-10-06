import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ApiError } from '../../shared/request'
import { ConsumerApi, isStaffInvitationPath, type LocalStore } from '../../shared/consumer-api'
import { StaffInvitationController, type StaffInvitationState } from '../staff/controller'
import { StaffWorkbenchController } from '../staff/workbench'
import {
  decodeStaffConfirmReceipt, decodeStaffInvitationDetail, invitationTagClass, isInvitationId,
  memberStateClass, staffActionText, staffInvitationAvailability, staffInvitationMessage,
  type StaffConfirmReceipt, type StaffInvitationDetail, type StaffInvitationDeps,
} from '../staff/model'
import type { MerchantAdmission } from '../../shared/merchant-repositories'

const wire = (value: unknown): Record<string, unknown> => JSON.parse(JSON.stringify(value))

const detail: StaffInvitationDetail = {
  invitationId: '930000000000301', merchantId: '910000000000101', merchantName: '小李宠物店',
  storeId: '910000000000102', storeName: '小李宠物店·总店', memberName: '李小美',
  grantedActions: ['merchant.order.verify'], status: 'INVITED',
}
const receipt: StaffConfirmReceipt = {
  memberId: '930000000000201', merchantId: '910000000000101', storeId: '910000000000102',
  memberStatus: 'ENABLED', grantedActions: ['merchant.order.verify'], replayed: false,
}

class FakeStaffInvitationRepository implements StaffInvitationDeps {
  reads: string[] = []
  confirms: Array<[string, string]> = []
  failReadWith: Error | null = null
  failConfirmWith: Error | null = null
  confirmResult: StaffConfirmReceipt = receipt
  detailResult: StaffInvitationDetail = detail
  async read(invitationId: string) {
    if (this.failReadWith) { const e = this.failReadWith; this.failReadWith = null; throw e }
    this.reads.push(invitationId)
    return this.detailResult
  }
  async confirm(slot: string, invitationId: string) {
    if (this.failConfirmWith) { const e = this.failConfirmWith; this.failConfirmWith = null; throw e }
    this.confirms.push([slot, invitationId])
    return this.confirmResult
  }
}

async function settled(controller: StaffInvitationController): Promise<StaffInvitationState> {
  await new Promise(resolve => setTimeout(resolve, 0))
  return controller.getSnapshot()
}

test('decoders enforce the exact contract-54 employee-side shapes', () => {
  assert.ok(decodeStaffInvitationDetail(wire(detail)))
  assert.ok(decodeStaffConfirmReceipt(wire(receipt)))
  for (const mutate of [
    (v: Record<string, any>) => { v.phoneMasked = '139****1111' },
    (v: Record<string, any>) => { delete v.storeName },
    (v: Record<string, any>) => { v.status = 'EXPIRED' },
    (v: Record<string, any>) => { v.merchantId = 'abc' },
    (v: Record<string, any>) => { v.grantedActions = ['merchant.order.fulfill'] },
    (v: Record<string, any>) => { v.merchantName = '' },
  ]) {
    const value = wire(detail)
    mutate(value)
    assert.throws(() => decodeStaffInvitationDetail(value), /INVALID_RESPONSE/)
  }
  for (const mutate of [
    (v: Record<string, any>) => { v.replayed = 'yes' },
    (v: Record<string, any>) => { v.memberStatus = 'PAUSED' },
    (v: Record<string, any>) => { v.extra = 1 },
  ]) {
    const value = wire(receipt)
    mutate(value)
    assert.throws(() => decodeStaffConfirmReceipt(value), /INVALID_RESPONSE/)
  }
})

test('status styles are explicit class-name variants, never dynamic data-*', () => {
  assert.equal(invitationTagClass('INVITED'), 'msi-tag msi-tag-invited')
  assert.equal(invitationTagClass('CANCELED'), 'msi-tag msi-tag-canceled')
  assert.equal(invitationTagClass('CONFIRMED'), 'msi-tag msi-tag-confirmed')
  assert.equal(memberStateClass('ENABLED'), 'msi-member-state msi-member-state-enabled')
  assert.equal(memberStateClass('DISABLED'), 'msi-member-state')
})

test('invitation ids and the frozen action catalog follow the contract lexicons', () => {
  assert.ok(isInvitationId('930000000000301'))
  for (const bad of ['', 'abc', '0', '930000000000301930000000000301', '-1']) {
    assert.equal(isInvitationId(bad), false, bad)
  }
  assert.equal(staffActionText['merchant.order.verify'], '订单核销')
})

test('per-error-code Chinese mapping for the employee read+confirm channel', () => {
  assert.match(staffInvitationMessage(new ApiError('COMMON_UNAUTHORIZED', 401)), /登录已失效/)
  assert.match(staffInvitationMessage(new ApiError('COMMON_INVALID_ARGUMENT', 400)), /邀请编号格式/)
  assert.match(staffInvitationMessage(new ApiError('COMMON_NOT_FOUND', 404)), /未找到可确认的邀请/)
  assert.match(staffInvitationMessage(new ApiError('COMMON_CONFLICT', 409)), /状态已变化/)
  assert.match(staffInvitationMessage(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), /暂不可用/)
  assert.match(staffInvitationMessage(new Error('PENDING_WRITE_CHANGED')), /上次确认结果尚未确认/)
  assert.match(staffInvitationMessage(new Error('WORKSPACE_PATH_MISMATCH')), /工作区已切换/)
  // Fail-closed classification: switch-off 404 and dependency 503 close the page; business
  // conflicts and auth faults do not (they render inside a successfully loaded page).
  assert.equal(staffInvitationAvailability(new ApiError('COMMON_NOT_FOUND', 404)), 'closed')
  assert.equal(staffInvitationAvailability(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), 'closed')
  assert.equal(staffInvitationAvailability(new ApiError('COMMON_CONFLICT', 409)), 'ok')
  assert.equal(staffInvitationAvailability(new ApiError('COMMON_UNAUTHORIZED', 401)), 'ok')
  assert.equal(staffInvitationAvailability(new Error('INVALID_RESPONSE')), 'ok')
})

test('controller loads one invitation, confirms it and folds to the terminal state', async () => {
  const deps = new FakeStaffInvitationRepository()
  const controller = new StaffInvitationController(deps)
  await controller.load('930000000000301')
  let state = await settled(controller)
  assert.equal(state.status, 'ready')
  assert.equal(state.detail?.status, 'INVITED')

  assert.ok(await controller.confirm('staff-invitation:930000000000301:confirm'))
  state = await settled(controller)
  assert.deepEqual(deps.confirms, [['staff-invitation:930000000000301:confirm', '930000000000301']])
  assert.equal(state.detail?.status, 'CONFIRMED')
  assert.equal(state.receipt?.memberId, '930000000000201')
  assert.equal(state.receipt?.replayed, false)
  assert.match(state.notice, /确认成功/)
  controller.dispose()
})

test('confirm is refused client-side on terminal invitations and never fakes success', async () => {
  for (const status of ['CANCELED', 'CONFIRMED'] as const) {
    const deps = new FakeStaffInvitationRepository()
    deps.detailResult = { ...detail, status }
    const controller = new StaffInvitationController(deps)
    await controller.load('930000000000301')
    await settled(controller)
    // Terminal invitations never issue a confirm command (54 §2).
    assert.equal(await controller.confirm('slot-x'), false)
    assert.deepEqual(deps.confirms, [])
    assert.equal(controller.getSnapshot().receipt, null)
    controller.dispose()
  }
  // After a successful confirm the local fold is CONFIRMED: a second tap is refused the same way.
  const deps = new FakeStaffInvitationRepository()
  const controller = new StaffInvitationController(deps)
  await controller.load('930000000000301')
  await settled(controller)
  assert.equal(await controller.confirm('slot-a'), true)
  assert.equal(await controller.confirm('slot-b'), false)
  assert.deepEqual(deps.confirms.length, 1)
  controller.dispose()
})

test('load failures fail closed with the whole-page panel; confirm conflicts keep the invitation', async () => {
  const deps = new FakeStaffInvitationRepository()
  const controller = new StaffInvitationController(deps)
  deps.failReadWith = new ApiError('COMMON_NOT_FOUND', 404)
  await controller.load('930000000000301')
  let state = await settled(controller)
  assert.equal(state.status, 'load-error')
  assert.equal(state.closed, true)
  assert.match(state.notice, /未找到可确认的邀请/)

  deps.failReadWith = new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)
  await controller.load('930000000000301')
  state = await settled(controller)
  assert.equal(state.closed, true)

  await controller.load('930000000000301')
  state = await settled(controller)
  assert.equal(state.status, 'ready')
  deps.failConfirmWith = new ApiError('COMMON_CONFLICT', 409)
  assert.equal(await controller.confirm('staff-invitation:930000000000301:confirm'), false)
  state = await settled(controller)
  assert.equal(state.detail?.status, 'INVITED')
  assert.equal(state.receipt, null)
  assert.match(state.notice, /状态已变化/)
  controller.dispose()
})

test('workbench controller discriminates staff from owner and fails closed on admission faults', async () => {
  const staffView: MerchantAdmission = {
    merchantId: '910000000000101', storeId: '910000000000102', membershipKind: 'STAFF',
    admission: 'ALLOWED', checkedAt: '2026-10-06T00:00:00.000Z', authzVersion: '0123456789abcdef',
    facts: {
      application: null, signing: { status: 'SIGNED' }, storeStatus: 'ACTIVE',
      merchantStatus: 'ACTIVE', staffEnabled: true,
    },
    allowedActions: ['merchant.order.verify'], reasonCodes: [], nextSteps: [],
  }
  const deps = {
    admission: async () => staffView,
  }
  const controller = new StaffWorkbenchController(deps)
  controller.load(null)
  assert.equal(controller.getSnapshot().status, 'entry')
  controller.load({ userId: '101', workspace: 'consumer', merchantId: null, storeId: null })
  assert.equal(controller.getSnapshot().status, 'unbound')
  controller.load({ userId: '101', workspace: 'merchant', merchantId: '910000000000101', storeId: '910000000000102' })
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(controller.getSnapshot().status, 'ready')
  assert.equal(controller.getSnapshot().view?.membershipKind, 'STAFF')

  const owner = new StaffWorkbenchController({ admission: async () => ({ ...staffView, membershipKind: 'OWNER', facts: { ...staffView.facts, staffEnabled: null } }) })
  owner.load({ userId: '101', workspace: 'merchant', merchantId: '910000000000101', storeId: '910000000000102' })
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(owner.getSnapshot().status, 'owner')

  const failing = new StaffWorkbenchController({ admission: async () => { throw new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503) } })
  failing.load({ userId: '101', workspace: 'merchant', merchantId: '910000000000101', storeId: '910000000000102' })
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(failing.getSnapshot().status, 'error')
  assert.match(failing.getSnapshot().notice, /功能未开放/)
  controller.dispose(); owner.dispose(); failing.dispose()
})

// ---------------------------------------------------------------------------
// Client isolation gate (shared/consumer-api): the identity-scoped contract-54 employee
// routes stay callable from the staff workbench pages that run on merchant coordinates,
// while every other /c route keeps consumer-only access.
// ---------------------------------------------------------------------------
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', data } })
const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z', phoneMasked: '138****1234' }

function apiHarness(extra: (path: string) => { statusCode: number; data: unknown } | Promise<{ statusCode: number; data: unknown }>) {
  const calls: Array<{ method: string; path: string; data?: Record<string, unknown> }> = []
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => values.delete(key) }
  // Seed a stored grant (as the boot lifecycle test does) so restore() validates it live.
  values.set('pet.c.session.v1', { ...session, tokenType: 'Bearer', accessToken: 'unusable-test-token' })
  const api = new ConsumerApi(async request => {
    calls.push({ method: request.method, path: request.path, data: request.data })
    if (request.path === '/api/v1/c/auth/session') return ok(session)
    return extra(request.path)
  }, store, async () => randomUUID())
  return { api, calls }
}

test('staff invitation routes are identity-scoped and callable from merchant coordinates; other /c routes are not', async () => {
  const h = apiHarness(path => {
    if (path === '/api/v1/c/staff/invitations/930000000000301') return ok(wire(detail))
    if (path === '/api/v1/c/staff/invitations/930000000000301/confirm') return ok(wire(receipt))
    return ok(null)
  })
  await h.api.restore()
  h.api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '910000000000101', storeId: '910000000000102' })
  const loaded = await h.api.request({ method: 'GET', path: '/api/v1/c/staff/invitations/930000000000301' }, decodeStaffInvitationDetail)
  assert.equal(loaded.storeName, detail.storeName)
  const confirmed = await h.api.write('staff-invitation:930000000000301:confirm',
    { method: 'POST', path: '/api/v1/c/staff/invitations/930000000000301/confirm', data: {} }, decodeStaffConfirmReceipt)
  assert.equal(confirmed.memberId, receipt.memberId)
  const readCall = h.calls.find(call => call.path === '/api/v1/c/staff/invitations/930000000000301')!
  const confirmCall = h.calls.find(call => call.path === '/api/v1/c/staff/invitations/930000000000301/confirm')!
  // The read carries no query bytes and the confirm carries no merchant coordinates — identity only.
  assert.equal(readCall.data, undefined)
  assert.deepEqual(confirmCall.data, {})
  await assert.rejects(h.api.request({ method: 'GET', path: '/api/v1/c/profile' }, v => v), /WORKSPACE_PATH_MISMATCH/)
  assert.equal(isStaffInvitationPath('/api/v1/c/staff/invitations/930000000000301'), true)
  assert.equal(isStaffInvitationPath('/api/v1/c/staff/invitations/930000000000301/confirm'), true)
  assert.equal(isStaffInvitationPath('/api/v1/c/staff/invitations/abc'), false)
  assert.equal(isStaffInvitationPath('/api/v1/c/staff/invitations/930000000000301/cancel'), false)
  assert.equal(isStaffInvitationPath('/api/v1/c/profile'), false)
})
