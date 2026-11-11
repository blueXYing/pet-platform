import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../shared/request'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { MembersController, type MembersState } from '../members/controller'
import {
  MemberDetailController, availableMemberActions,
  type MemberDetailDeps, type MemberDetailState,
} from '../members/detail-controller'
import { RealMembersRepository } from '../members/repository'
import {
  decodeInvitationRow, decodeInvitationsPage, decodeMemberRow, decodeMembersPage,
  invitationTagClass, isValidInviteName, isValidInvitePhone, memberStatusClass,
  membersMessage, type InvitationRow, type MemberRow, type MembersDeps,
} from '../members/model'

const wire = (value: unknown): Record<string, unknown> => JSON.parse(JSON.stringify(value))

const memberRow: MemberRow = {
  merchantId: '910000000000101', storeId: '910000000000102', memberId: '930000000000201',
  memberName: '李小美', phoneMasked: '139****1111', memberStatus: 'ENABLED',
  grantStatus: 'ENABLED', grantedActions: ['merchant.order.verify'],
  memberVersion: '0', grantVersion: '0',
}
const invitationRow: InvitationRow = {
  merchantId: '910000000000101', storeId: '910000000000102', invitationId: '930000000000301',
  memberName: '李小美', phoneMasked: '139****1111', status: 'INVITED', version: '0',
}

class FakeMembersRepository implements MembersDeps {
  invited: Array<[string, string, string]> = []
  disabled: Array<[string, string, string]> = []
  enabled: Array<[string, string, string]> = []
  revoked: Array<[string, string, string]> = []
  granted: Array<[string, string, string[], string]> = []
  failNextWith: Error | null = null
  listMembers(page: number, pageSize: number) { return this.answer({ items: [memberRow], page, pageSize, total: 1 }) }
  listInvitations(page: number, pageSize: number) { return this.answer({ items: [invitationRow], page, pageSize, total: 1 }) }
  async invite(slot: string, phone: string, memberName: string) {
    if (this.failNextWith) { const e = this.failNextWith; this.failNextWith = null; throw e }
    this.invited.push([slot, phone, memberName])
    return { ...invitationRow, invitationId: '930000000000302' }
  }
  async cancelInvitation(slot: string, invitationId: string, expectedVersion: string) {
    if (this.failNextWith) { const e = this.failNextWith; this.failNextWith = null; throw e }
    return { ...invitationRow, invitationId, status: 'CANCELED' as const, version: '1' }
  }
  async disableMember(slot: string, memberId: string, expectedVersion: string) {
    if (this.failNextWith) { const e = this.failNextWith; this.failNextWith = null; throw e }
    this.disabled.push([slot, memberId, expectedVersion])
    return { ...memberRow, memberId, memberStatus: 'DISABLED' as const, memberVersion: '1' }
  }
  async enableMember(slot: string, memberId: string, expectedVersion: string) {
    if (this.failNextWith) { const e = this.failNextWith; this.failNextWith = null; throw e }
    this.enabled.push([slot, memberId, expectedVersion])
    return { ...memberRow, memberId, memberStatus: 'ENABLED' as const, memberVersion: '2' }
  }
  async revokeStoreGrant(slot: string, memberId: string, expectedVersion: string) {
    if (this.failNextWith) { const e = this.failNextWith; this.failNextWith = null; throw e }
    this.revoked.push([slot, memberId, expectedVersion])
    return { ...memberRow, memberId, grantStatus: 'REVOKED' as const, grantedActions: [], grantVersion: '1' }
  }
  async grantActions(slot: string, memberId: string, actions: readonly string[], expectedVersion: string) {
    if (this.failNextWith) { const e = this.failNextWith; this.failNextWith = null; throw e }
    this.granted.push([slot, memberId, [...actions], expectedVersion])
    return { ...memberRow, memberId, grantedActions: [...actions], grantVersion: '2' }
  }
  private answer<T>(value: T): Promise<T> {
    if (this.failNextWith) { const e = this.failNextWith; this.failNextWith = null; return Promise.reject(e) }
    return Promise.resolve(value)
  }
}

async function settled(controller: MembersController): Promise<MembersState> {
  await new Promise(resolve => setTimeout(resolve, 0))
  return controller.getSnapshot()
}

test('decoders enforce the exact contract-54 shapes (ids, masked phone, catalog codes)', () => {
  assert.ok(decodeMemberRow(wire(memberRow)))
  assert.ok(decodeInvitationRow(wire(invitationRow)))
  assert.equal(decodeMembersPage({ items: [wire(memberRow)], page: 1, pageSize: 50, total: 1 }).total, 1)
  assert.equal(decodeInvitationsPage({ items: [wire(invitationRow)], page: 1, pageSize: 50, total: 1 }).total, 1)
  for (const mutate of [
    (v: Record<string, any>) => { v.extra = 1 },
    (v: Record<string, any>) => { v.memberId = 'abc' },
    (v: Record<string, any>) => { v.phoneMasked = '13900001111' },
    (v: Record<string, any>) => { v.memberStatus = 'PAUSED' },
    (v: Record<string, any>) => { v.memberVersion = -1 },
    (v: Record<string, any>) => { v.grantedActions = ['merchant.order.fulfill'] },
    (v: Record<string, any>) => { delete v.grantVersion },
    // REVOKED grant cannot keep actions (server deletes them; decoder mirrors the invariant).
    (v: Record<string, any>) => { v.grantStatus = 'REVOKED' },
  ]) {
    const value = wire(memberRow)
    mutate(value)
    assert.throws(() => decodeMemberRow(value), /INVALID_RESPONSE/)
  }
  for (const mutate of [
    (v: Record<string, any>) => { v.status = 'EXPIRED' },
    (v: Record<string, any>) => { v.invitationId = '' },
    (v: Record<string, any>) => { v.memberName = null },
  ]) {
    const value = wire(invitationRow)
    mutate(value)
    assert.throws(() => decodeInvitationRow(value), /INVALID_RESPONSE/)
  }
})

test('status classes are explicit class-name variants, never dynamic data-*', () => {
  assert.equal(memberStatusClass('ENABLED'), 'mmb-member-state mmb-member-state-enabled')
  assert.equal(memberStatusClass('DISABLED'), 'mmb-member-state mmb-member-state-disabled')
  assert.equal(memberStatusClass('REVOKED'), 'mmb-member-state')
  assert.equal(invitationTagClass('INVITED'), 'mmb-inv-tag mmb-inv-tag-invited')
  assert.equal(invitationTagClass('CANCELED'), 'mmb-inv-tag mmb-inv-tag-canceled')
  assert.equal(invitationTagClass('CONFIRMED'), 'mmb-inv-tag mmb-inv-tag-confirmed')
})

test('invite input validation mirrors the server rules (11-digit mobile, 1-64 code points)', () => {
  assert.ok(isValidInvitePhone('13900001111'))
  for (const bad of ['1390000111', '23900001111', '139000011122', '', '1390000111a']) {
    assert.equal(isValidInvitePhone(bad), false, bad)
  }
  assert.ok(isValidInviteName('李小美'))
  assert.ok(isValidInviteName('  王小明  '))
  assert.equal(isValidInviteName('   '), false)
  assert.equal(isValidInviteName('好'.repeat(65)), false)
})

test('controller loads both lists, invites into the invitation tab and toggles members', async () => {
  const deps = new FakeMembersRepository()
  const controller = new MembersController(deps)
  await controller.load()
  let state = await settled(controller)
  assert.equal(state.status, 'ready')
  assert.equal(state.memberTotal, 1)
  assert.equal(state.invitationTotal, 1)
  assert.equal(state.tab, 'members')

  assert.ok(await controller.invite('staff-member:invite:13900001111', '13900001111', '李小美'))
  state = await settled(controller)
  assert.deepEqual(deps.invited, [['staff-member:invite:13900001111', '13900001111', '李小美']])
  assert.equal(state.tab, 'invitations')
  assert.equal(state.invitationTotal, 2)
  assert.equal(state.invitations[0]!.invitationId, '930000000000302')

  controller.switchTab('members')
  state = await settled(controller)
  assert.equal(state.tab, 'members')
  assert.ok(await controller.toggleMember('staff-member:930000000000201:disable', state.members[0]!))
  state = await settled(controller)
  assert.equal(state.members[0]!.memberStatus, 'DISABLED')
  assert.equal(state.members[0]!.memberVersion, '1')
  assert.ok(await controller.toggleMember('staff-member:930000000000201:enable', state.members[0]!))
  state = await settled(controller)
  assert.equal(state.members[0]!.memberStatus, 'ENABLED')
  controller.dispose()
})

test('server conflicts and failures surface as refresh-suggestion notices, never fake success', async () => {
  const deps = new FakeMembersRepository()
  const controller = new MembersController(deps)
  await controller.load()
  let state = await settled(controller)
  // Toggle on a revoked-grant member is refused client-side without a server call.
  assert.equal(await controller.toggleMember('x', { ...memberRow, grantStatus: 'REVOKED' }), false)
  state = await settled(controller)
  assert.match(state.notice, /门店授权已撤回/)

  deps.failNextWith = new ApiError('CONFLICT', 409)
  assert.equal(await controller.toggleMember('slot', state.members[0]!), false)
  state = await settled(controller)
  assert.match(state.notice, /状态已变化/)

  deps.failNextWith = new ApiError('CONFLICT', 409)
  assert.equal(await controller.invite('slot2', '13900001111', '李小美'), false)
  state = await settled(controller)
  assert.match(state.notice, /已有待确认邀请/)

  deps.failNextWith = new ApiError('COMMON_UNAUTHORIZED', 401)
  assert.equal(await controller.cancelInvitation('slot3', invitationRow), false)
  state = await settled(controller)
  assert.match(state.notice, /登录已失效/)
  assert.equal(membersMessage(new Error('WORKSPACE_PATH_MISMATCH')), '工作区已切换，请重新从商家工作台进入。')
  controller.dispose()
})

// ---------------------------------------------------------------------------
// P-12 member detail: 54号 has no single-member read — the detail controller walks the owner
// member list for the row (fresh member/grant versions) and drives the four owner lifecycle
// commands with the version each command pins (§3: member version for disable/enable, grant
// version for revoke-store/grant-actions).
// ---------------------------------------------------------------------------
async function detailSettled(controller: MemberDetailController): Promise<MemberDetailState> {
  await new Promise(resolve => setTimeout(resolve, 0))
  return controller.getSnapshot()
}

test('availableMemberActions keeps 停用/启用 mutually exclusive and D3-locks revoked grants', () => {
  assert.deepEqual(availableMemberActions(null), [])
  // Revoked store grant is terminal until ruling D3: no owner command may be offered.
  assert.deepEqual(availableMemberActions({ ...memberRow, grantStatus: 'REVOKED', grantedActions: [] }), [])
  assert.deepEqual(availableMemberActions({ ...memberRow, memberStatus: 'ENABLED' }),
    ['disable', 'revoke-store', 'grant-actions'])
  assert.deepEqual(availableMemberActions({ ...memberRow, memberStatus: 'DISABLED' }),
    ['enable', 'revoke-store', 'grant-actions'])
  // A REVOKED member row (decoder keeps it forward-safe) still allows grant-scoped commands.
  assert.deepEqual(availableMemberActions({ ...memberRow, memberStatus: 'REVOKED' }),
    ['revoke-store', 'grant-actions'])
})

test('detail controller walks the member list pages to resolve the id, else reports not-found', async () => {
  const other = { ...memberRow, memberId: '930000000000211' }
  let calls: Array<[number, number]> = []
  const deps: MemberDetailDeps = {
    async listMembers(page, pageSize) {
      calls.push([page, pageSize])
      if (page === 1) return { items: [other], page, pageSize, total: 60 }
      return { items: [memberRow], page, pageSize, total: 60 }
    },
    async disableMember() { throw new Error('unused') },
    async enableMember() { throw new Error('unused') },
    async revokeStoreGrant() { throw new Error('unused') },
    async grantActions() { throw new Error('unused') },
  }
  const controller = new MemberDetailController(deps)
  await controller.load(memberRow.memberId)
  let state = await detailSettled(controller)
  assert.equal(state.status, 'ready')
  assert.equal(state.member?.memberId, memberRow.memberId)
  // Page 1 (50 rows, total 60) does not cover the id: the walk continues to page 2.
  assert.deepEqual(calls, [[1, 50], [2, 50]])
  calls = []
  await controller.load('930000000000999')
  state = await detailSettled(controller)
  assert.equal(state.status, 'not-found')
  assert.match(state.notice, /不存在或已不在本店成员列表/)
  controller.dispose()
})

test('detail lifecycle actions pin the correct version per command and refresh the row in place', async () => {
  const deps = new FakeMembersRepository()
  const controller = new MemberDetailController(deps)
  await controller.load(memberRow.memberId)
  let state = await detailSettled(controller)
  assert.equal(state.status, 'ready')

  // Disable/enable pin memberVersion and use the row refreshed by the previous receipt.
  assert.ok(await controller.toggle('staff-member:930000000000201:disable'))
  state = await detailSettled(controller)
  assert.deepEqual(deps.disabled, [['staff-member:930000000000201:disable', '930000000000201', '0']])
  assert.equal(state.member?.memberStatus, 'DISABLED')
  assert.ok(await controller.toggle('staff-member:930000000000201:enable'))
  assert.deepEqual(deps.enabled, [['staff-member:930000000000201:enable', '930000000000201', '1']])
  state = await detailSettled(controller)
  assert.equal(state.member?.memberStatus, 'ENABLED')

  // Grant-actions pins grantVersion and sends the frozen D2 catalog only.
  assert.ok(await controller.grantVerify('staff-member:930000000000201:grant-actions'))
  assert.deepEqual(deps.granted,
    [['staff-member:930000000000201:grant-actions', '930000000000201', ['merchant.order.verify'], '0']])
  state = await detailSettled(controller)
  assert.match(state.notice, /已授予核销操作权限/)

  // Revoke-store pins grantVersion (refreshed to '2' by the grant receipt above); the
  // terminal REVOKED grant then locks all further commands.
  assert.ok(await controller.revokeStore('staff-member:930000000000201:revoke-store'))
  state = await detailSettled(controller)
  assert.deepEqual(deps.revoked, [['staff-member:930000000000201:revoke-store', '930000000000201', '2']])
  assert.equal(state.member?.grantStatus, 'REVOKED')
  assert.equal(await controller.toggle('x-disable'), false)
  assert.equal(await controller.grantVerify('x-grant'), false)
  assert.equal(deps.disabled.length, 1)
  assert.equal(deps.granted.length, 1)
  controller.dispose()
})

test('detail conflicts surface as refresh suggestions, never fake success', async () => {
  const deps = new FakeMembersRepository()
  const controller = new MemberDetailController(deps)
  await controller.load(memberRow.memberId)
  deps.failNextWith = new ApiError('CONFLICT', 409)
  assert.equal(await controller.revokeStore('slot-r'), false)
  let state = await detailSettled(controller)
  assert.match(state.notice, /状态已变化，请刷新后重试/)
  assert.equal(state.busy, false)
  assert.equal(state.member?.grantStatus, 'ENABLED')
  deps.failNextWith = new ApiError('COMMON_UNAUTHORIZED', 401)
  assert.equal(await controller.toggle('slot-d'), false)
  state = await detailSettled(controller)
  assert.match(state.notice, /登录已失效/)
  controller.dispose()
})

test('real repository wires the 54号 revoke-store/grant-actions routes with grant versions', async () => {
  const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', success: true, data } })
  const failure = (code: string, statusCode: number) => ({ statusCode, data: { code, data: null } })
  type Call = { method: string; path: string; requestId?: string; data?: Record<string, unknown> }
  const seen: Call[] = []
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
  const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
  const grant = { ...session, tokenType: 'Bearer', accessToken: 'unusable-test-token' }
  let uuidCall = 0
  const api = new ConsumerApi(async request => {
    if (request.path.endsWith('/attempts')) return ok({ attemptId: '301', attemptToken: 't', nextStep: 'PROVE_IDENTITY' })
    if (request.path.endsWith('/wechat-login')) return ok(grant)
    if (request.path.endsWith('/session')) return ok(session)
    const call: Call = { method: request.method, path: request.path, requestId: (request as { requestId?: string }).requestId, data: request.data as Record<string, unknown> }
    seen.push(call)
    if (call.method === 'POST' && call.path === '/api/v1/merchant/staff-members/930000000000201/revoke-store') {
      return ok(wire({ ...memberRow, grantStatus: 'REVOKED', grantedActions: [], grantVersion: '1' }))
    }
    if (call.method === 'POST' && call.path === '/api/v1/merchant/staff-members/930000000000201/grant-actions') {
      return ok(wire({ ...memberRow, grantedActions: ['merchant.order.verify'], grantVersion: '1' }))
    }
    return failure('COMMON_DEPENDENCY_UNAVAILABLE', 503)
  }, store, async () => `00000000-0000-4000-8000-${String(++uuidCall).padStart(12, '0')}`)
  await api.startLogin(async () => ({ code: 'test-code' }))
  api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '910000000000101', storeId: '910000000000102' })
  const repository = new RealMembersRepository(api, () => '910000000000101', () => '910000000000102')

  const revoked = await repository.revokeStoreGrant('slot-r', '930000000000201', '3')
  assert.equal(revoked.grantStatus, 'REVOKED')
  const revokeCall = seen.find(call => call.path.endsWith('/revoke-store'))!
  assert.equal(revokeCall.method, 'POST')
  assert.deepEqual(revokeCall.data,
    { merchantId: '910000000000101', storeId: '910000000000102', expectedVersion: '3' })

  const granted = await repository.grantActions('slot-g', '930000000000201', ['merchant.order.verify'], '4')
  assert.deepEqual(granted.grantedActions, ['merchant.order.verify'])
  const grantCall = seen.find(call => call.path.endsWith('/grant-actions'))!
  assert.equal(grantCall.method, 'POST')
  assert.deepEqual(grantCall.data,
    { merchantId: '910000000000101', storeId: '910000000000102', actions: ['merchant.order.verify'], expectedVersion: '4' })
  // Writes journal a per-slot X-Request-Id (23号 idempotency) visible on the wire call.
  assert.match(String(grantCall.requestId), /^[0-9a-f-]{36}$/)
})
