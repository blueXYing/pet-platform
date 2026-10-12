import { ReviewAppealClient, appealReasonInput, isDefiniteAppealConflict, type AppealReceipt, type ReviewDetail, type ReviewPage } from '../../shared/review-appeal-api'
import type { ConsumerApi, LocalStore } from '../../shared/consumer-api'
import { MerchantAdmissionRepository, type MerchantAdmission } from '../../shared/merchant-repositories'

/** Only client-local drafts; persisted values never supply business authority. */
export type MerchantReviewDeps = {
  scope: ConsumerApi['scope']
  admission(merchantId: string, storeId: string): Promise<MerchantAdmission>
  list(merchantId: string, storeId: string, page: number, pageSize: number): Promise<ReviewPage>
  detail(reviewId: string): Promise<ReviewDetail>
  appeal(reviewId: string, reason: string): Promise<AppealReceipt>
  pendingAppeal(reviewId: string): { reason: string } | null
  retireConflict(reviewId: string, error: unknown): void
  saveDraft(reviewId: string, reason: string): void
  loadDraft(reviewId: string): string
  clearDraft(reviewId: string): void
}

export function merchantReviewDeps(api: ConsumerApi, _store: LocalStore): MerchantReviewDeps {
  if (api.scope.current?.workspace !== 'merchant') throw new Error('MERCHANT_ENTRY_REQUIRED')
  const admission = new MerchantAdmissionRepository(api)
  const client = new ReviewAppealClient(api)
  const draftSlot = (reviewId: string) => {
    const c = api.scope.capture().context
    if (c.workspace !== 'merchant' || !c.merchantId || !c.storeId) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return `merchant-review-appeal-draft:${c.userId}:${c.merchantId}:${c.storeId}:${reviewId}`
  }
  return {
    scope: api.scope,
    admission: (merchantId, storeId) => admission.admission(merchantId, storeId),
    list: (merchantId, storeId, page, pageSize) => client.list(merchantId, storeId, page, pageSize),
    detail: reviewId => client.detail(reviewId),
    appeal: (reviewId, reason) => client.appeal(reviewId, appealReasonInput(reason)),
    pendingAppeal: reviewId => client.pendingAppeal(reviewId),
    retireConflict: (reviewId, error) => { if (!isDefiniteAppealConflict(error)) throw new Error('UNCONFIRMED_WRITE'); client.retireConflict(reviewId, error) },
    saveDraft: (reviewId, reason) => api.saveIntent(draftSlot(reviewId), { reason }),
    loadDraft: reviewId => {
      const value = api.intent(draftSlot(reviewId)) as { reason?: unknown } | undefined
      if (!value || typeof value.reason !== 'string' || value.reason.length > 1000) return ''
      return value.reason
    },
    clearDraft: reviewId => api.saveIntent(draftSlot(reviewId), undefined),
  }
}
