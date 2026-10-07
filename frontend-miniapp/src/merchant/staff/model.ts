import { ApiError } from '../../shared/request'

// Contract 54 §4/§7 employee-side invitation view state (D1-a invite-confirm binding). The
// employee reads ONE invitation by id with their own MINIAPP session and confirms it, and —
// since the 54号 §7 slice — also lists every invitation sent to their own verified phone
// (history including terminal states). Wire shapes follow
// docs/04-api/54-Merchant-Staff-Binding-Contract-v0.1.md §4/§7 (CStaffInvitationController):
// both projections carry merchant/store names but never any phone; a non-matching session
// reads exactly like a missing invitation (detail 404 / list empty page, 404 anti-enumeration),
// so the client never distinguishes the two. Decoders stay strict exact-key like the
// OWNER-side members model.
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

/** One §7 list row: same disclosure family as the detail plus row timestamps, never a phone. */
export type StaffInvitationSummary = Readonly<{
  invitationId: string
  merchantId: string
  merchantName: string
  storeId: string
  storeName: string
  memberName: string
  grantedActions: readonly string[]
  status: InvitationStatus
  invitedAt: string
  updatedAt: string
}>

export type StaffInvitationPage = Readonly<{
  items: readonly StaffInvitationSummary[]
  page: number
  pageSize: number
  total: number
}>

export interface StaffInvitationDeps {
  read(invitationId: string): Promise<StaffInvitationDetail>
  confirm(slot: string, invitationId: string): Promise<StaffConfirmReceipt>
}

export interface StaffInvitationListDeps {
  list(page: number, pageSize: number): Promise<StaffInvitationPage>
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
  // 54 §2: an invitation always carries a non-empty catalog subset (invite validates it;
  // action rows are never removed), so an empty wire array is damaged data.
  if (!Array.isArray(value) || value.length === 0) invalid()
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

/** Millisecond ISO instant, the C-side wire convention (54号 §7 invitedAt/updatedAt). */
function isInstant(value: unknown): value is string {
  return typeof value === 'string'
    && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z(?![\s\S])$/.test(value)
    && Number.isFinite(Date.parse(value))
}

export function decodeStaffInvitationSummary(value: unknown): StaffInvitationSummary {
  const v = exact(value, ['invitationId', 'merchantId', 'merchantName', 'storeId', 'storeName',
    'memberName', 'grantedActions', 'status', 'invitedAt', 'updatedAt'])
  if (!isId(v.invitationId) || !isId(v.merchantId) || !isId(v.storeId)) invalid()
  if (!isText(v.merchantName, 128) || !isText(v.storeName, 128)) invalid()
  if (!isText(v.memberName, 64)) invalid()
  if (!isInstant(v.invitedAt) || !isInstant(v.updatedAt)) invalid()
  return {
    invitationId: v.invitationId, merchantId: v.merchantId, merchantName: v.merchantName,
    storeId: v.storeId, storeName: v.storeName, memberName: v.memberName,
    grantedActions: actionsOf(v.grantedActions),
    status: statusOf(v.status, ['INVITED', 'CANCELED', 'CONFIRMED'] as const),
    invitedAt: v.invitedAt, updatedAt: v.updatedAt,
  }
}

export function decodeStaffInvitationPage(value: unknown): StaffInvitationPage {
  const v = exact(value, ['items', 'page', 'pageSize', 'total'])
  if (!Array.isArray(v.items) || v.items.length > 50) invalid()
  const page = Number(v.page), pageSize = Number(v.pageSize), total = Number(v.total)
  if (!Number.isInteger(page) || page < 1 || page > 10000) invalid()
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50) invalid()
  if (!Number.isInteger(total) || total < 0) invalid()
  return { items: v.items.map(decodeStaffInvitationSummary), page, pageSize, total }
}

/** Per-error-code Chinese mapping for the §7 list channel (paging surface only). */
export function staffInvitationListMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 401) return '登录已失效，请重新登录后查看邀请记录。'
    if (error.statusCode === 400) return '分页参数不正确，请重试。'
    if (error.statusCode === 503) return '邀请记录服务暂不可用（依赖故障），请稍后重试。'
    return '邀请记录加载失败，请稍后重试。'
  }
  return '邀请记录加载失败，请稍后重试。'
}

/** List fail-closed classification (§7): switch-off renders the route 404 exactly like the
 *  detail channel; dependency faults 503 close the block; 401/400 stay retryable states. */
export function staffInvitationListAvailability(error: unknown): 'ok' | 'closed' {
  if (error instanceof ApiError && (error.statusCode === 503 || error.statusCode === 404)) return 'closed'
  return 'ok'
}

/** Route/deep-link invitation id: Snowflake decimal string only (whitelist-id discipline). */
export function isInvitationId(value: string): boolean {
  return /^[1-9][0-9]{0,18}$/.test(value)
}
