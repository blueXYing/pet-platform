import { ApiError } from '../../shared/request'
import { APPROVED_ACTIONS, membersMessage, type MemberRow, type MembersPage } from './model'

/**
 * Contract 54 offers no single-member read endpoint (§4 owner queries are the two list routes
 * only), so the detail page re-reads the owner member list and picks the row by memberId. That
 * keeps the member/grant versions fresh for the optimistic-concurrency writes below — 54 §3:
 * disable/enable pin memberVersion, revoke-store/grant-actions pin grantVersion.
 */
export interface MemberDetailDeps {
  listMembers(page: number, pageSize: number): Promise<MembersPage>
  disableMember(slot: string, memberId: string, expectedVersion: string): Promise<MemberRow>
  enableMember(slot: string, memberId: string, expectedVersion: string): Promise<MemberRow>
  revokeStoreGrant(slot: string, memberId: string, expectedVersion: string): Promise<MemberRow>
  grantActions(slot: string, memberId: string, actions: readonly string[],
    expectedVersion: string): Promise<MemberRow>
}

export type MemberDetailStatus = 'idle' | 'loading' | 'ready' | 'not-found' | 'load-error' | 'entry'

export type MemberDetailState = Readonly<{
  status: MemberDetailStatus
  memberId: string
  member: MemberRow | null
  busy: boolean
  notice: string
}>

export type MemberActionKey = 'disable' | 'enable' | 'revoke-store' | 'grant-actions'

const LIST_PAGE_SIZE = 50
// Defensive walk cap: the backend caps pageSize at 100 and the owner list is one store's staff;
// 40 pages cover 2 000 rows before the page gives up as not-found.
const LIST_PAGE_CAP = 40

/**
 * Which lifecycle buttons the detail page may offer. All four 54号 owner commands require a
 * usable store grant (REVOKED grant → 409 pending D3), and 停用/启用 are mutually exclusive
 * against the current member status.
 */
export function availableMemberActions(member: MemberRow | null): readonly MemberActionKey[] {
  if (!member || member.grantStatus !== 'ENABLED') return []
  const grantActions: MemberActionKey[] = ['revoke-store', 'grant-actions']
  if (member.memberStatus === 'ENABLED') return ['disable', ...grantActions]
  if (member.memberStatus === 'DISABLED') return ['enable', ...grantActions]
  // memberStatus REVOKED (command itself is D3-reserved; decoder keeps the value forward-safe):
  // only the grant-scoped operations remain legal.
  return grantActions
}

/** One controller per mounted member-detail page; stale loads are dropped via run counters. */
export class MemberDetailController {
  private state: MemberDetailState = { status: 'idle', memberId: '', member: null, busy: false, notice: '' }
  private listeners = new Set<() => void>()
  private active = true
  private run = 0
  constructor(private deps: MemberDetailDeps) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  private publish(patch: Partial<MemberDetailState>) {
    this.state = Object.freeze({ ...this.state, ...patch })
    this.listeners.forEach(listener => listener())
  }
  entry() { this.publish({ status: 'entry', notice: '请从商家工作台进入成员管理。' }) }

  async load(memberId: string) {
    if (!this.active || this.state.busy) return
    const run = ++this.run
    this.publish({ status: 'loading', memberId, member: null, notice: '' })
    try {
      let found: MemberRow | null = null
      for (let page = 1; page <= LIST_PAGE_CAP; page++) {
        const result = await this.deps.listMembers(page, LIST_PAGE_SIZE)
        if (!this.active || run !== this.run) return
        found = result.items.find(row => row.memberId === memberId) ?? null
        if (found) break
        if (result.items.length === 0 || page * LIST_PAGE_SIZE >= result.total) break
      }
      if (!this.active || run !== this.run) return
      if (found) this.publish({ status: 'ready', member: found, notice: '' })
      else this.publish({ status: 'not-found', notice: '该成员不存在或已不在本店成员列表中，请返回刷新。' })
    } catch (error) {
      if (!this.active || run !== this.run) return
      this.publish({ status: 'load-error', notice: membersMessage(error) })
    }
  }

  private replace(next: MemberRow, notice: string) {
    this.publish({ member: next, busy: false, notice })
  }

  private static conflict(error: unknown): boolean {
    return error instanceof ApiError && error.code === 'CONFLICT'
  }

  private fail(error: unknown): boolean {
    this.publish({ busy: false, notice: MemberDetailController.conflict(error)
      ? '成员状态已变化，请刷新后重试。' : membersMessage(error) })
    return false
  }

  /** 停用/启用 toggle; both pin memberVersion (54 §3 lifecycle commands). */
  async toggle(slot: string): Promise<boolean> {
    const row = this.state.member
    if (this.state.busy || !row) return false
    const actions = availableMemberActions(row)
    const disabling = row.memberStatus === 'ENABLED'
    if (disabling && !actions.includes('disable')) return false
    if (!disabling && !actions.includes('enable')) return false
    this.publish({ busy: true, notice: '' })
    try {
      const next = disabling
        ? await this.deps.disableMember(slot, row.memberId, row.memberVersion)
        : await this.deps.enableMember(slot, row.memberId, row.memberVersion)
      this.replace(next, disabling ? '成员已停用，其核销动作立即失效。' : '成员已恢复启用。')
      return true
    } catch (error) { return this.fail(error) }
  }

  /** 整店撤权 is terminal until ruling D3; pins grantVersion. */
  async revokeStore(slot: string): Promise<boolean> {
    const row = this.state.member
    if (this.state.busy || !row || !availableMemberActions(row).includes('revoke-store')) return false
    this.publish({ busy: true, notice: '' })
    try {
      const next = await this.deps.revokeStoreGrant(slot, row.memberId, row.grantVersion)
      this.replace(next, '门店授权已撤回（终局）。该成员在本店的核销权限立即失效。')
      return true
    } catch (error) { return this.fail(error) }
  }

  /** 授权操作: full action-set replacement with the D2 frozen single-action catalog. */
  async grantVerify(slot: string): Promise<boolean> {
    const row = this.state.member
    if (this.state.busy || !row || !availableMemberActions(row).includes('grant-actions')) return false
    this.publish({ busy: true, notice: '' })
    try {
      const next = await this.deps.grantActions(slot, row.memberId, APPROVED_ACTIONS, row.grantVersion)
      this.replace(next, '已授予核销操作权限（本店范围）。')
      return true
    } catch (error) { return this.fail(error) }
  }

  dispose() {
    this.active = false
    this.listeners.clear()
  }
}
