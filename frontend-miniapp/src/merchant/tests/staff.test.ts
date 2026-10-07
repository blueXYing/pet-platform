import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ApiError } from '../../shared/request'
import { ConsumerApi, isStaffInvitationPath, type LocalStore } from '../../shared/consumer-api'
import {
  StaffInvitationController, StaffInvitationListController, type StaffInvitationListState,
  type StaffInvitationState,
} from '../staff/controller'
import { StaffWorkbenchController } from '../staff/workbench'
import {
  decodeStaffConfirmReceipt, decodeStaffInvitationDetail, decodeStaffInvitationPage,
  invitationTagClass, isInvitationId, memberStateClass, staffActionText,
  staffInvitationAvailability, staffInvitationListAvailability, staffInvitationListMessage,
  staffInvitationMessage,
  type StaffConfirmReceipt, type StaffInvitationDetail, type StaffInvitationDeps,
  type StaffInvitationListDeps, type StaffInvitationPage, type StaffInvitationSummary,
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

const summary: StaffInvitationSummary = {
  invitationId: '930000000000301', merchantId: '910000000000101', merchantName: '小李宠物店',
  storeId: '910000000000102', storeName: '小李宠物店·总店', memberName: '李小美',
  grantedActions: ['merchant.order.verify'], status: 'INVITED',
  invitedAt: '2026-10-06T08:00:00.000Z', updatedAt: '2026-10-06T08:00:00.000Z',
}
const page: StaffInvitationPage = { items: [summary], page: 1, pageSize: 20, total: 1 }

class FakeStaffInvitationListRepository implements StaffInvitationListDeps {
  pages: Array<[number, number]> = []
  results: StaffInvitationPage[] = [page]
  failWith: Error | null = null
  async list(requestedPage: number, requestedPageSize: number) {
    if (this.failWith) { const e = this.failWith; this.failWith = null; throw e }
    this.pages.push([requestedPage, requestedPageSize])
    return this.results[Math.min(this.pages.length - 1, this.results.length - 1)]
  }
}

async function settledList(controller: StaffInvitationListController): Promise<StaffInvitationListState> {
  await new Promise(resolve => setTimeout(resolve, 0))
  return controller.getSnapshot()
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

test('decoders enforce the exact contract-54 §7 list shapes (no phone in any form)', () => {
  assert.ok(decodeStaffInvitationPage(wire(page)))
  for (const mutate of [
    (v: Record<string, any>) => { (v.items[0] as Record<string, any>).phoneMasked = '139****1111' },
    (v: Record<string, any>) => { (v.items[0] as Record<string, any>).phone = '13900001111' },
    (v: Record<string, any>) => { delete (v.items[0] as Record<string, any>).invitedAt },
    (v: Record<string, any>) => { (v.items[0] as Record<string, any>).invitedAt = '2026-10-06T08:00Z' },
    (v: Record<string, any>) => { (v.items[0] as Record<string, any>).status = 'EXPIRED' },
    (v: Record<string, any>) => { (v.items[0] as Record<string, any>).merchantId = 'abc' },
    (v: Record<string, any>) => { (v.items[0] as Record<string, any>).grantedActions = [] },
    (v: Record<string, any>) => { v.total = -1 },
    (v: Record<string, any>) => { v.pageSize = 51 },
    (v: Record<string, any>) => { v.extra = 1 },
  ]) {
    const value = wire(page)
    mutate(value)
    assert.throws(() => decodeStaffInvitationPage(value), /INVALID_RESPONSE/)
  }
})

test('list message mapping and fail-closed classification for the §7 channel', () => {
  assert.match(staffInvitationListMessage(new ApiError('COMMON_UNAUTHORIZED', 401)), /登录已失效/)
  assert.match(staffInvitationListMessage(new ApiError('COMMON_INVALID_ARGUMENT', 400)), /分页参数/)
  assert.match(staffInvitationListMessage(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), /暂不可用/)
  assert.match(staffInvitationListMessage(new Error('INVALID_RESPONSE')), /加载失败/)
  // Switch-off 404 (route absent) and dependency 503 close the block; 401/400 stay retryable.
  assert.equal(staffInvitationListAvailability(new ApiError('COMMON_NOT_FOUND', 404)), 'closed')
  assert.equal(staffInvitationListAvailability(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)), 'closed')
  assert.equal(staffInvitationListAvailability(new ApiError('COMMON_UNAUTHORIZED', 401)), 'ok')
  assert.equal(staffInvitationListAvailability(new Error('INVALID_RESPONSE')), 'ok')
})

test('list controller loads the first page, appends more and keeps the fixed order', async () => {
  const deps = new FakeStaffInvitationListRepository()
  const confirmed: StaffInvitationSummary = { ...summary, invitationId: '930000000000291', status: 'CONFIRMED' }
  deps.results = [{ items: [summary], page: 1, pageSize: 1, total: 2 }, { items: [confirmed], page: 2, pageSize: 1, total: 2 }]
  const controller = new StaffInvitationListController(deps)
  await controller.load(1)
  let state = await settledList(controller)
  assert.equal(state.status, 'ready')
  assert.deepEqual(state.items.map(item => item.invitationId), ['930000000000301'])
  assert.equal(state.total, 2)

  await controller.loadMore()
  state = await settledList(controller)
  assert.deepEqual(state.items.map(item => item.invitationId), ['930000000000301', '930000000000291'])
  assert.deepEqual(deps.pages, [[1, 1], [2, 1]])
  // No more requests once the matched subset is exhausted.
  await controller.loadMore()
  await settledList(controller)
  assert.deepEqual(deps.pages, [[1, 1], [2, 1]])
  controller.dispose()
})

test('list controller fails closed on switch-off/dependency faults and recovers on retry', async () => {
  const deps = new FakeStaffInvitationListRepository()
  const controller = new StaffInvitationListController(deps)
  deps.failWith = new ApiError('COMMON_NOT_FOUND', 404)
  await controller.load()
  let state = await settledList(controller)
  assert.equal(state.status, 'error')
  assert.equal(state.closed, true)
  assert.deepEqual(state.items, [])

  deps.failWith = new ApiError('COMMON_UNAUTHORIZED', 401)
  await controller.load()
  state = await settledList(controller)
  assert.equal(state.status, 'error')
  assert.equal(state.closed, false)
  assert.match(state.notice, /登录已失效/)

  await controller.load()
  state = await settledList(controller)
  assert.equal(state.status, 'ready')
  assert.equal(state.total, 1)
  controller.dispose()
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
    if (path === '/api/v1/c/staff/invitations') return ok(wire(page))
    if (path === '/api/v1/c/staff/invitations/930000000000301') return ok(wire(detail))
    if (path === '/api/v1/c/staff/invitations/930000000000301/confirm') return ok(wire(receipt))
    return ok(null)
  })
  await h.api.restore()
  h.api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '910000000000101', storeId: '910000000000102' })
  const listed = await h.api.request({ method: 'GET', path: '/api/v1/c/staff/invitations', data: { page: 1, pageSize: 20 } }, decodeStaffInvitationPage)
  assert.equal(listed.total, 1)
  assert.equal(listed.items[0].invitationId, summary.invitationId)
  const loaded = await h.api.request({ method: 'GET', path: '/api/v1/c/staff/invitations/930000000000301' }, decodeStaffInvitationDetail)
  assert.equal(loaded.storeName, detail.storeName)
  const confirmed = await h.api.write('staff-invitation:930000000000301:confirm',
    { method: 'POST', path: '/api/v1/c/staff/invitations/930000000000301/confirm', data: {} }, decodeStaffConfirmReceipt)
  assert.equal(confirmed.memberId, receipt.memberId)
  const readCall = h.calls.find(call => call.path === '/api/v1/c/staff/invitations/930000000000301')!
  const confirmCall = h.calls.find(call => call.path === '/api/v1/c/staff/invitations/930000000000301/confirm')!
  const listCall = h.calls.find(call => call.path === '/api/v1/c/staff/invitations')!
  // The list carries paging only, the read no query bytes and the confirm no merchant
  // coordinates — identity stays server-side on all three routes.
  assert.deepEqual(listCall.data, { page: 1, pageSize: 20 })
  assert.equal(readCall.data, undefined)
  assert.deepEqual(confirmCall.data, {})
  await assert.rejects(h.api.request({ method: 'GET', path: '/api/v1/c/profile' }, v => v), /WORKSPACE_PATH_MISMATCH/)
  assert.equal(isStaffInvitationPath('/api/v1/c/staff/invitations'), true)
  assert.equal(isStaffInvitationPath('/api/v1/c/staff/invitations/930000000000301'), true)
  assert.equal(isStaffInvitationPath('/api/v1/c/staff/invitations/930000000000301/confirm'), true)
  assert.equal(isStaffInvitationPath('/api/v1/c/staff/invitations/abc'), false)
  assert.equal(isStaffInvitationPath('/api/v1/c/staff/invitations/930000000000301/cancel'), false)
  assert.equal(isStaffInvitationPath('/api/v1/c/staff/invitations/930000000000301/confirm/x'), false)
  assert.equal(isStaffInvitationPath('/api/v1/c/profile'), false)
})
