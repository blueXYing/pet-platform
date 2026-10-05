import { ApiError } from '../../shared/request'
import {
  membersMessage, type InvitationRow, type MemberRow, type MembersDeps,
} from './model'

export type MembersTab = 'members' | 'invitations'

export type MembersState = Readonly<{
  status: 'idle' | 'loading' | 'ready' | 'load-error' | 'entry'
  tab: MembersTab
  members: readonly MemberRow[]
  memberTotal: number
  invitations: readonly InvitationRow[]
  invitationTotal: number
  busy: boolean
  notice: string
}>

/**
 * One controller per mounted member-management page. Members and invitations load together
 * (the invitation list is the owner's only view of pending invites); stale responses are
 * dropped via run counters. Actions update the affected row in place and surface server
 * conflicts as a refresh suggestion instead of pretending success.
 */
export class MembersController {
  private state: MembersState = {
    status: 'idle', tab: 'members', members: [], memberTotal: 0,
    invitations: [], invitationTotal: 0, busy: false, notice: '',
  }
  private listeners = new Set<() => void>()
  private active = true
  private run = 0
  constructor(private deps: MembersDeps) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  private publish(patch: Partial<MembersState>) {
    this.state = Object.freeze({ ...this.state, ...patch })
    this.listeners.forEach(listener => listener())
  }
  entry() { this.publish({ status: 'entry', notice: '请从商家工作台进入成员管理。' }) }

  async load() {
    if (!this.active) return
    const run = ++this.run
    this.publish({ status: 'loading', notice: '' })
    try {
      const [members, invitations] = await Promise.all([
        this.deps.listMembers(1, 50), this.deps.listInvitations(1, 50),
      ])
      if (!this.active || run !== this.run) return
      this.publish({
        status: 'ready', members: members.items, memberTotal: members.total,
        invitations: invitations.items, invitationTotal: invitations.total,
      })
    } catch (error) {
      if (!this.active || run !== this.run) return
      this.publish({ status: 'load-error', notice: membersMessage(error) })
    }
  }

  switchTab(tab: MembersTab) {
    if (tab !== this.state.tab) this.publish({ tab })
  }

  private touchMember(next: MemberRow) {
    this.publish({
      members: this.state.members.map(row => row.memberId === next.memberId ? next : row),
    })
  }

  private touchInvitation(next: InvitationRow) {
    this.publish({
      invitations: this.state.invitations.map(row => row.invitationId === next.invitationId ? next : row),
    })
  }

  private static conflict(error: unknown): boolean {
    return error instanceof ApiError && error.code === 'CONFLICT'
  }

  async invite(slot: string, phone: string, memberName: string): Promise<boolean> {
    if (this.state.busy) return false
    this.publish({ busy: true, notice: '' })
    try {
      const row = await this.deps.invite(slot, phone, memberName)
      // Newest-first list keeps the fresh INVITED row on top of the invitation tab.
      this.publish({
        busy: false, tab: 'invitations',
        invitations: [row, ...this.state.invitations],
        invitationTotal: this.state.invitationTotal + 1,
        notice: `已发出邀请，等待 ${row.phoneMasked} 的员工本人确认。`,
      })
      return true
    } catch (error) {
      this.publish({ busy: false, notice: MembersController.conflict(error)
        ? '该手机号已有待确认邀请。' : membersMessage(error) })
      return false
    }
  }

  async cancelInvitation(slot: string, row: InvitationRow): Promise<boolean> {
    if (this.state.busy || row.status !== 'INVITED') return false
    this.publish({ busy: true, notice: '' })
    try {
      const next = await this.deps.cancelInvitation(slot, row.invitationId, row.version)
      this.touchInvitation(next)
      this.publish({ busy: false, notice: '邀请已撤销。' })
      return true
    } catch (error) {
      this.publish({ busy: false, notice: MembersController.conflict(error)
        ? '邀请状态已变化，请刷新后重试。' : membersMessage(error) })
      return false
    }
  }

  async toggleMember(slot: string, row: MemberRow): Promise<boolean> {
    if (this.state.busy) return false
    if (row.grantStatus !== 'ENABLED') {
      this.publish({ notice: '该成员的门店授权已撤回，无需启停。' })
      return false
    }
    const disabling = row.memberStatus === 'ENABLED'
    this.publish({ busy: true, notice: '' })
    try {
      const next = disabling
        ? await this.deps.disableMember(slot, row.memberId, row.memberVersion)
        : await this.deps.enableMember(slot, row.memberId, row.memberVersion)
      this.touchMember(next)
      this.publish({ busy: false, notice: disabling ? '成员已停用，其核销动作立即失效。' : '成员已恢复启用。' })
      return true
    } catch (error) {
      this.publish({ busy: false, notice: MembersController.conflict(error)
        ? '成员状态已变化，请刷新后重试。' : membersMessage(error) })
      return false
    }
  }

  dispose() {
    this.active = false
    this.listeners.clear()
  }
}
