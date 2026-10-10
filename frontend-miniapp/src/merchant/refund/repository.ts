import { MerchantRefundClient, type RefundApplicationDetail, type RefundApplicationPage, type RefundDecisionReceipt } from '../../shared/merchant-refund-api'
import { MerchantAdmissionRepository } from '../../shared/merchant-repositories'
import type { MerchantAdmission } from '../../shared/merchant-repositories'
import type { RejectDraft } from './model'

/** Contract 57 wiring dependencies; only client-local drafts, the server owns every fact. */
export type MerchantRefundDeps = {
  admission(merchantId: string, storeId: string): Promise<MerchantAdmission>
  list(query: { merchantId: string; storeId: string; page: number; pageSize: number }): Promise<RefundApplicationPage>
  detail(applicationId: string): Promise<RefundApplicationDetail>
  approve(applicationId: string): Promise<RefundDecisionReceipt>
  reject(applicationId: string, reasonText: string): Promise<RefundDecisionReceipt>
  pending(): { action: 'approve' | 'reject'; applicationId: string; reasonText: string | null } | null
  loadDraft(applicationId: string): RejectDraft | null
  saveDraft(applicationId: string, draft: RejectDraft | null): void
}

export function merchantRefundDeps(client: MerchantRefundClient): MerchantRefundDeps {
  const admission = new MerchantAdmissionRepository(client.api)
  const slot = (applicationId: string) => {
    const context = client.api.scope.capture().context
    if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return `merchant-refund-draft:${context.merchantId}:${context.storeId}:${applicationId}:reject`
  }
  return {
    admission: (merchantId, storeId) => admission.admission(merchantId, storeId),
    list: query => client.list(query),
    detail: applicationId => client.detail(applicationId),
    approve: applicationId => client.approve(applicationId),
    reject: (applicationId, reasonText) => client.reject(applicationId, reasonText),
    pending: () => client.pending(),
    loadDraft: applicationId => {
      const value = client.api.intent(slot(applicationId)) as Partial<RejectDraft> | undefined
      if (!value) return null
      if (typeof value.reasonText !== 'string' || value.reasonText.length > 500) return { reasonText: '' }
      return { reasonText: value.reasonText }
    },
    saveDraft: (applicationId, draft) => client.api.saveIntent(slot(applicationId), draft),
  }
}
