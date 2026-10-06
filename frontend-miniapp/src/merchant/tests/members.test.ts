import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../shared/request'
import { MembersController, type MembersState } from '../members/controller'
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
    return { ...memberRow, memberId, memberStatus: 'DISABLED' as const, memberVersion: '1' }
  }
  async enableMember(slot: string, memberId: string, expectedVersion: string) {
    if (this.failNextWith) { const e = this.failNextWith; this.failNextWith = null; throw e }
    return { ...memberRow, memberId, memberStatus: 'ENABLED' as const, memberVersion: '2' }
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
