import type { MerchantAdmission } from '../../shared/merchant-repositories'
import type { AfterSaleStatus, CaseDetail, EvidenceInput, OpinionInput } from '../../shared/aftersale-api'

export const statusText: Record<AfterSaleStatus, string> = {
  PENDING: '待受理', PROCESSING: '处理中', WAITING_SUPPLEMENT: '待补证',
  RESOLVED: '已裁决', INVALIDATED: '已失效', WITHDRAWN: '已撤回', CLOSED: '已关闭',
}
/** WXSS attribute selectors never match: Taro 4.1.5 drops dynamic data-* from wxml, so state variants must be class names. */
export function statusTagClass(status: AfterSaleStatus): string {
  if (status === 'RESOLVED') return 'mas-tag mas-tag-resolved'
  if (status === 'INVALIDATED' || status === 'WITHDRAWN' || status === 'CLOSED') return 'mas-tag mas-tag-closed'
  return 'mas-tag'
}
export const opinionOptions = [
  { code: 'AGREE', label: '同意用户意见' }, { code: 'PARTLY_AGREE', label: '部分同意' },
  { code: 'DISAGREE', label: '不同意' }, { code: 'NEED_USER_SUPPLEMENT', label: '需用户补证' },
] as const
export type OpinionCode = typeof opinionOptions[number]['code']
export type ReplyDraft = Readonly<{ opinionCode: OpinionCode | ''; text: string; assetIds: readonly string[] }>
export const emptyReply = (): ReplyDraft => ({ opinionCode: '', text: '', assetIds: [] })

/** UI gate supplements current backend authorization; it never grants permission. */
export function ownerAccess(view: MerchantAdmission, merchantId: string, storeId: string) {
  const readable = view.merchantId === merchantId && view.storeId === storeId
    && view.membershipKind === 'OWNER' && view.admission !== 'DENIED'
    && view.allowedActions.includes('merchant.aftersale.read')
  const frozen = view.facts.merchantStatus === 'FROZEN' || view.facts.storeStatus === 'FROZEN'
  return { readable, writable: readable && !frozen, frozen }
}
export function canReply(detail: CaseDetail, writable: boolean, now: number): boolean {
  if (!writable || !['PENDING', 'PROCESSING', 'WAITING_SUPPLEMENT'].includes(detail.status)) return false
  // Wait for the real worker/read to publish PROCESSING after an expired round.
  return detail.status !== 'WAITING_SUPPLEMENT' || !!detail.supplementRequestId
    && !!detail.supplementDeadline && now < Date.parse(detail.supplementDeadline)
}
export function replyInput(detail: CaseDetail, draft: ReplyDraft, mode: 'opinion' | 'evidence'): OpinionInput | EvidenceInput {
  const text = draft.text.trim() ? draft.text : null
  if (draft.assetIds.length > 6 || new Set(draft.assetIds).size !== draft.assetIds.length) throw new Error('EVIDENCE_LIMIT')
  if (text !== null && (text.length < 10 || text.length > 500)) throw new Error('REPLY_TEXT_LENGTH')
  const base = { expectedVersion: detail.version, supplementRequestId: detail.status === 'WAITING_SUPPLEMENT' ? detail.supplementRequestId : null, evidenceAssetIds: [...draft.assetIds] }
  if (mode === 'opinion') {
    if (!draft.opinionCode || text === null) throw new Error('OPINION_REQUIRED')
    return { ...base, opinionCode: draft.opinionCode, explanation: text }
  }
  if (text === null && !draft.assetIds.length) throw new Error('EVIDENCE_REQUIRED')
  return { ...base, text }
}
export function dateText(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '—'
  // The product clock is UTC+8; device settings must not move a supplement deadline.
  const local = new Date(date.getTime() + 8 * 60 * 60 * 1000)
  return `${local.toISOString().slice(0, 10)} ${local.toISOString().slice(11, 16)}`
}
export const decisionText: Record<string, string> = {
  REJECT: '驳回申请', RESERVICE: '重新服务（人工安排）', OTHER: '其他处理',
  FULL_REFUND: '全额退款', PARTIAL_REFUND: '部分退款',
}
/** Mirrors pet.aftersale.type-labels / demand-labels in aftersale-catalog.yml (final C PRD §5.1.28 / M §5.10);
 *  unknown codes fall back to the raw code — labels are never invented client-side. */
const typeLabels: Record<string, string> = {
  FEE_DISPUTE: '费用争议', NON_PERFORMANCE: '未履约', OTHER: '其他', PET_SAFETY: '宠物安全', SERVICE_QUALITY: '质量问题',
}
const demandLabels: Record<string, string> = {
  APOLOGY: '道歉', OTHER: '其他', PARTIAL_COMPENSATION: '部分补偿', REFUND: '退款', RESERVICE: '重新服务',
}
export function typeLabel(code: string): string { return typeLabels[code] ?? code }
export function demandLabel(code: string): string { return demandLabels[code] ?? code }
