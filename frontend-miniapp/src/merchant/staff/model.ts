import { ApiError } from '../../shared/request'

// Contract 54 §4 employee-side invitation view state (D1-a invite-confirm binding). The
// employee reads ONE invitation by id with their own MINIAPP session and confirms it; there
// is deliberately no list endpoint, so "my invitations" on the staff workbench is an
// id-keyed lookup, never a fabricated list. Wire shapes follow
// docs/04-api/54-Merchant-Staff-Binding-Contract-v0.1.md §4 (CStaffInvitationController):
// the detail projection carries merchant/store names but no phone; a non-matching session
// reads exactly like a missing invitation (404 anti-enumeration), so the client never
// distinguishes the two. Decoders stay strict exact-key like the OWNER-side members model.
export type InvitationStatus = 'INVITED' | 'CANCELED' | 'CONFIRMED'
export type MemberStatus = 'ENABLED' | 'DISABLED' | 'REVOKED'

export type StaffInvitationDetail = Readonly<{
  invitationId: string
  merchantId: string
  merchantName: string
  storeId: string
  storeName: string
  memberName: string
  grantedActions: readonly string[]
  status: InvitationStatus
}>

export type StaffConfirmReceipt = Readonly<{
  memberId: string
  merchantId: string
  storeId: string
  memberStatus: MemberStatus
  grantedActions: readonly string[]
  replayed: boolean
}>

export interface StaffInvitationDeps {
  read(invitationId: string): Promise<StaffInvitationDetail>
  confirm(slot: string, invitationId: string): Promise<StaffConfirmReceipt>
}

export const invitationStatusText: Record<InvitationStatus, string> = {
  INVITED: '待确认', CANCELED: '已撤销', CONFIRMED: '已确认',
}
export const memberStatusText: Record<MemberStatus, string> = {
  ENABLED: '启用中', DISABLED: '已停用', REVOKED: '已撤销',
}

// D2 frozen catalog (contract 54 §3): V1 grants exactly one staff action. Display labels
// stay inside the approved catalog; unknown codes never reach the UI (decoder rejects).
export const staffActionText: Record<string, string> = {
  'merchant.order.verify': '订单核销',
}

// WXSS attribute selectors never match (Taro 4.1.5 drops dynamic data-* from native wxml):
// state variants are explicit class names; unknown values fall back to the base rule.
export function invitationTagClass(status: InvitationStatus): string {
  if (status === 'INVITED') return 'msi-tag msi-tag-invited'
  if (status === 'CANCELED') return 'msi-tag msi-tag-canceled'
  if (status === 'CONFIRMED') return 'msi-tag msi-tag-confirmed'
  return 'msi-tag'
}
export function memberStateClass(status: MemberStatus): string {
  if (status === 'ENABLED') return 'msi-member-state msi-member-state-enabled'
  return 'msi-member-state'
}

/** Per-error-code Chinese mapping for the employee read+confirm channel (54 §2/§4). */
export function staffInvitationMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录已失效，请重新登录后查看邀请。'
    if (error.statusCode === 400) return '邀请编号格式不正确，请核对后重试。'
    if (error.statusCode === 404) {
      // Anti-enumeration: missing invitation, phone mismatch and (switch off) missing route
      // are indistinguishable by design — one honest copy covers all three.
      return '未找到可确认的邀请：编号可能有误、邀请不是发给当前登录手机号的，或平台暂未开放该功能。'
    }
    if (error.statusCode === 409) {
      return '邀请状态已变化（可能已确认、已撤销、当前账号已绑定该商家或商家入驻/协议状态变化），请刷新查看。'
    }
    if (error.statusCode === 503) return '邀请服务暂不可用（依赖故障），请稍后重试；已提交的确认不会重复执行。'
    return '操作失败，请稍后重试。'
  }
  if (error instanceof Error && error.message === 'PENDING_WRITE_CHANGED') {
    return '上次确认结果尚未确认，请用同一按钮重试；不会重复绑定。'
  }
  if (error instanceof Error && error.message === 'WORKSPACE_PATH_MISMATCH') {
    return '工作区已切换，请重新进入后再试。'
  }
  return '操作失败，请稍后重试。'
}

/** Switch-off/dependency fail-closed classification for the initial read (members/schedule
 *  pattern): 503 dependency faults and 404 (route absent when pet.merchant.staff-member
 *  enabled=false renders exactly like an invisible invitation) both render the whole page
 *  as a non-interactive panel instead of an editable form. */
export function staffInvitationAvailability(error: unknown): 'ok' | 'closed' {
  if (error instanceof ApiError && (error.statusCode === 503 || error.statusCode === 404)) return 'closed'
  return 'ok'
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
function isText(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.length >= 1 && value.length <= max
}
function statusOf<T extends string>(value: unknown, allowed: readonly T[]): T {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value) ? value as T : invalid()
}
// Same frozen catalog as the OWNER members decoder (54 §3): only merchant.order.verify.
const APPROVED_ACTIONS: readonly string[] = ['merchant.order.verify']

function actionsOf(value: unknown): readonly string[] {
  if (!Array.isArray(value)) invalid()
  return value.map(code => {
    if (typeof code !== 'string' || !/^[a-z0-9][a-z0-9.-]{0,99}$/.test(code)) invalid()
    if (!APPROVED_ACTIONS.includes(code)) invalid()
    return code
  })
}

export function decodeStaffInvitationDetail(value: unknown): StaffInvitationDetail {
  const v = exact(value, ['invitationId', 'merchantId', 'merchantName', 'storeId', 'storeName',
    'memberName', 'grantedActions', 'status'])
  if (!isId(v.invitationId) || !isId(v.merchantId) || !isId(v.storeId)) invalid()
  if (!isText(v.merchantName, 128) || !isText(v.storeName, 128)) invalid()
  if (!isText(v.memberName, 64)) invalid()
  return {
    invitationId: v.invitationId, merchantId: v.merchantId, merchantName: v.merchantName,
    storeId: v.storeId, storeName: v.storeName, memberName: v.memberName,
    grantedActions: actionsOf(v.grantedActions),
    status: statusOf(v.status, ['INVITED', 'CANCELED', 'CONFIRMED'] as const),
  }
}

export function decodeStaffConfirmReceipt(value: unknown): StaffConfirmReceipt {
  const v = exact(value, ['memberId', 'merchantId', 'storeId', 'memberStatus', 'grantedActions', 'replayed'])
  if (!isId(v.memberId) || !isId(v.merchantId) || !isId(v.storeId)) invalid()
  if (typeof v.replayed !== 'boolean') invalid()
  return {
    memberId: v.memberId, merchantId: v.merchantId, storeId: v.storeId,
    memberStatus: statusOf(v.memberStatus, ['ENABLED', 'DISABLED', 'REVOKED'] as const),
    grantedActions: actionsOf(v.grantedActions), replayed: v.replayed,
  }
}

/** Route/deep-link invitation id: Snowflake decimal string only (whitelist-id discipline). */
export function isInvitationId(value: string): boolean {
  return /^[1-9][0-9]{0,18}$/.test(value)
}
