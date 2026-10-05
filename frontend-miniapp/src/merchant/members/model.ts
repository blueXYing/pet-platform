import { ApiError } from '../../shared/request'

// Contract 54 member-management view state (D1-a invite-confirm binding; verify-only catalog).
// Wire shapes follow docs/04-api/54-Merchant-Staff-Binding-Contract-v0.1.md; decoders stay
// strict exact-key against the shapes below. Phone and name never appear unmasked here —
// the server projects phoneMasked only, and the canonical request bytes never carry plaintext.
//
// State machine anchors (contract 54 §2):
// - invitation INVITED -> CANCELED (owner cancel, terminal) | CONFIRMED (employee confirm, terminal);
// - member ENABLED <-> DISABLED (owner toggle); grant ENABLED -> REVOKED terminal (D3 pending);
// - member status feeds the contract-52 read kernel: DISABLED/REVOKED fail the action gate closed.
export type MemberStatus = 'ENABLED' | 'DISABLED' | 'REVOKED'
export type GrantStatus = 'ENABLED' | 'REVOKED'
export type InvitationStatus = 'INVITED' | 'CANCELED' | 'CONFIRMED'

export type MemberRow = Readonly<{
  merchantId: string
  storeId: string
  memberId: string
  memberName: string
  phoneMasked: string
  memberStatus: MemberStatus
  grantStatus: GrantStatus
  grantedActions: readonly string[]
  memberVersion: string
  grantVersion: string
}>

export type InvitationRow = Readonly<{
  merchantId: string
  storeId: string
  invitationId: string
  memberName: string
  phoneMasked: string
  status: InvitationStatus
  version: string
}>

export type MembersPage = Readonly<{ items: readonly MemberRow[]; page: number; pageSize: number; total: number }>
export type InvitationsPage = Readonly<{ items: readonly InvitationRow[]; page: number; pageSize: number; total: number }>

export interface MembersDeps {
  listMembers(page: number, pageSize: number): Promise<MembersPage>
  listInvitations(page: number, pageSize: number): Promise<InvitationsPage>
  /** Registers an invitation (phone + name); the V1 catalog action set is server-pinned. */
  invite(slot: string, phone: string, memberName: string): Promise<InvitationRow>
  cancelInvitation(slot: string, invitationId: string, expectedVersion: string): Promise<InvitationRow>
  disableMember(slot: string, memberId: string, expectedVersion: string): Promise<MemberRow>
  enableMember(slot: string, memberId: string, expectedVersion: string): Promise<MemberRow>
}

export const memberStatusText: Record<MemberStatus, string> = {
  ENABLED: '启用中', DISABLED: '已停用', REVOKED: '已撤销',
}
export const invitationStatusText: Record<InvitationStatus, string> = {
  INVITED: '待确认', CANCELED: '已撤销', CONFIRMED: '已确认',
}

// WXSS attribute selectors never match (Taro 4.1.5 drops dynamic data-* from native wxml):
// state variants are explicit class names; unknown values fall back to the base rule.
export function memberStatusClass(status: MemberStatus): string {
  if (status === 'ENABLED') return 'mmb-member-state mmb-member-state-enabled'
  if (status === 'DISABLED') return 'mmb-member-state mmb-member-state-disabled'
  return 'mmb-member-state'
}
export function invitationTagClass(status: InvitationStatus): string {
  if (status === 'INVITED') return 'mmb-inv-tag mmb-inv-tag-invited'
  if (status === 'CANCELED') return 'mmb-inv-tag mmb-inv-tag-canceled'
  if (status === 'CONFIRMED') return 'mmb-inv-tag mmb-inv-tag-confirmed'
  return 'mmb-inv-tag'
}

export function membersMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录已失效，请重新进入。'
    if (error.code === 'CONFLICT') return '状态已变化，请刷新后重试。'
    if (error.code === 'NOT_FOUND') return '该成员或邀请不存在，请刷新列表。'
    if (error.code === 'COMMON_REQUEST_ID_CONFLICT') return '请求已被占用，请重试。'
    return '操作失败，请稍后重试。'
  }
  if (error instanceof Error && error.message === 'WORKSPACE_PATH_MISMATCH') {
    return '工作区已切换，请重新从商家工作台进入。'
  }
  return '操作失败，请稍后重试。'
}

function invalid(): never {
  throw new Error('INVALID_RESPONSE')
}
function exact(value: unknown, keys: readonly string[]): Record<string, any> {
  if (typeof value !== 'object' || value === null) invalid()
  const record = value as Record<string, unknown>
  if (Object.keys(record).length !== keys.length || keys.some(key => !(key in record))) invalid()
  return record
}
function isId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value)
}
function isVersion(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value)
}
function isMaskedPhone(value: unknown): value is string {
  return typeof value === 'string' && /^1[0-9]{2}\*{4}[0-9]{4}$/.test(value)
}
function isText(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.length >= 1 && value.length <= max
}
function statusOf<T extends string>(value: unknown, allowed: readonly T[]): T {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value) ? value as T : invalid()
}
// The D2 approved minimal catalog is frozen by contract 54 §3; the decoder rejects anything
// outside it so a future server catalog change cannot silently reach this UI.
const APPROVED_ACTIONS: readonly string[] = ['merchant.order.verify']

function actionsOf(value: unknown): readonly string[] {
  if (!Array.isArray(value)) invalid()
  return value.map(code => {
    if (typeof code !== 'string' || !/^[a-z0-9][a-z0-9.-]{0,99}$/.test(code)) invalid()
    if (!APPROVED_ACTIONS.includes(code)) invalid()
    return code
  })
}

export function decodeMemberRow(value: unknown): MemberRow {
  const v = exact(value, ['merchantId', 'storeId', 'memberId', 'memberName', 'phoneMasked',
    'memberStatus', 'grantStatus', 'grantedActions', 'memberVersion', 'grantVersion'])
  if (!isId(v.merchantId) || !isId(v.storeId) || !isId(v.memberId)) invalid()
  if (!isText(v.memberName, 64) || !isMaskedPhone(v.phoneMasked)) invalid()
  if (!isVersion(v.memberVersion) || !isVersion(v.grantVersion)) invalid()
  const grantStatus = statusOf(v.grantStatus, ['ENABLED', 'REVOKED'] as const)
  if (grantStatus === 'REVOKED' && v.grantedActions.length !== 0) invalid()
  return {
    merchantId: v.merchantId, storeId: v.storeId, memberId: v.memberId,
    memberName: v.memberName, phoneMasked: v.phoneMasked,
    memberStatus: statusOf(v.memberStatus, ['ENABLED', 'DISABLED', 'REVOKED'] as const),
    grantStatus, grantedActions: actionsOf(v.grantedActions),
    memberVersion: v.memberVersion, grantVersion: v.grantVersion,
  }
}

export function decodeMembersPage(value: unknown): MembersPage {
  const v = exact(value, ['items', 'page', 'pageSize', 'total'])
  if (!Array.isArray(v.items) || v.items.length > 100) invalid()
  const page = Number(v.page), pageSize = Number(v.pageSize), total = Number(v.total)
  if (!Number.isInteger(page) || page < 1 || page > 10000) invalid()
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100) invalid()
  if (!Number.isInteger(total) || total < 0) invalid()
  return { items: v.items.map(decodeMemberRow), page, pageSize, total }
}

export function decodeInvitationRow(value: unknown): InvitationRow {
  const v = exact(value, ['merchantId', 'storeId', 'invitationId', 'memberName', 'phoneMasked',
    'status', 'version'])
  if (!isId(v.merchantId) || !isId(v.storeId) || !isId(v.invitationId)) invalid()
  if (!isText(v.memberName, 64) || !isMaskedPhone(v.phoneMasked)) invalid()
  if (!isVersion(v.version)) invalid()
  return {
    merchantId: v.merchantId, storeId: v.storeId, invitationId: v.invitationId,
    memberName: v.memberName, phoneMasked: v.phoneMasked,
    status: statusOf(v.status, ['INVITED', 'CANCELED', 'CONFIRMED'] as const),
    version: v.version,
  }
}

export function decodeInvitationsPage(value: unknown): InvitationsPage {
  const v = exact(value, ['items', 'page', 'pageSize', 'total'])
  if (!Array.isArray(v.items) || v.items.length > 100) invalid()
  const page = Number(v.page), pageSize = Number(v.pageSize), total = Number(v.total)
  if (!Number.isInteger(page) || page < 1 || page > 10000) invalid()
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100) invalid()
  if (!Number.isInteger(total) || total < 0) invalid()
  return { items: v.items.map(decodeInvitationRow), page, pageSize, total }
}

/** The invited phone: mainland mobile, validated client-side for UX; the server re-validates. */
export function isValidInvitePhone(phone: string): boolean {
  return /^1[0-9]{10}$/.test(phone)
}
export function isValidInviteName(name: string): boolean {
  const trimmed = name.trim()
  return trimmed.length >= 1 && [...trimmed].length <= 64
}
