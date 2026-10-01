import { decodePrivateAsset, id, type ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import type { UploadFiles } from '../../shared/private-asset-upload'
import { AfterSaleClient, isDefiniteAfterSaleConflict, type OpinionInput, type EvidenceInput } from '../../shared/aftersale-api'
import { MerchantAdmissionRepository } from '../../shared/merchant-repositories'
import type { MerchantAfterSaleDeps, UploadAttempt } from './controller'
import { emptyReply, opinionOptions, type ReplyDraft } from './model'

/** Only client-local drafts/journals; persisted values never supply business authority. */
export function merchantAfterSaleDeps(client: AfterSaleClient, store: LocalStore, files: Pick<UploadFiles, 'save' | 'inspect' | 'owns' | 'remove'>): MerchantAfterSaleDeps {
  if (client.party !== 'merchant') throw new Error('WORKSPACE_PATH_MISMATCH')
  const api: ConsumerApi = client.api
  const admission = new MerchantAdmissionRepository(api)
  const slot = (caseId: string, kind: string) => {
    const c = api.scope.capture().context
    if (c.workspace !== 'merchant' || !c.merchantId || !c.storeId) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return `merchant-aftersale:${c.merchantId}:${c.storeId}:${id(caseId)}:${kind}`
  }
  const uploadCoordinates = (caseId: string) => {
    const c = api.scope.capture().context
    if (c.workspace !== 'merchant' || !c.merchantId || !c.storeId || api.currentSession?.userId !== c.userId) throw new Error('MERCHANT_ENTRY_REQUIRED')
    return { ownerUserId: id(c.userId), merchantId: id(c.merchantId), storeId: id(c.storeId), caseId: id(caseId) }
  }
  const uploadKey = (c: ReturnType<typeof uploadCoordinates>) => `pet.merchant-aftersale-upload.v1:${c.ownerUserId}:${c.merchantId}:${c.storeId}:${c.caseId}`
  return {
    scope: api.scope,
    admission: (merchantId, storeId) => admission.admission(merchantId, storeId),
    list: query => client.list(query), detail: caseId => client.detail(caseId),
    opinion: (caseId, input) => client.opinion(caseId, input), evidence: (caseId, input) => client.evidence(caseId, input),
    pending: caseId => {
      const opinion = client.pending(caseId, 'opinion')
      if (opinion) return { action: 'opinion', input: opinion as OpinionInput }
      const evidence = client.pending(caseId, 'evidence')
      return evidence ? { action: 'evidence', input: evidence as EvidenceInput } : null
    },
    retireConflict: (caseId, action, error) => { if (!isDefiniteAfterSaleConflict(error)) return false; try { client.retireConflict(caseId, action, error); return true } catch { return false } },
    readEvidence: (caseId, batchId, assetId, reason) => client.readEvidence(caseId, batchId, assetId, reason),
    clearImages: () => client.clearImages(), uuid: () => api.uuid(), upload: (filePath, requestId) => client.upload(filePath, requestId), files, now: () => Date.now(),
    uploadAttempt: caseId => {
      const c = uploadCoordinates(caseId)
      const value = store.get(uploadKey(c)) as (Partial<UploadAttempt> & Partial<typeof c>) | undefined
      if (!value) return null
      if (Object.entries(c).some(([key, expected]) => value[key as keyof typeof c] !== expected)
        || typeof value.filePath !== 'string' || !value.filePath || typeof value.requestId !== 'string'
        || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![\s\S])/i.test(value.requestId)
        || !files.owns(value.filePath, value.requestId) || typeof value.sha256 !== 'string' || !/^[a-f0-9]{64}(?![\s\S])/.test(value.sha256)
        || !Number.isSafeInteger(value.bytes) || value.bytes! < 1 || value.bytes! > 10485760 || typeof value.attempted !== 'boolean') throw new Error('INVALID_UPLOAD_JOURNAL')
      return { filePath: value.filePath, requestId: value.requestId, sha256: value.sha256, bytes: value.bytes!, attempted: value.attempted,
        ...(value.receipt ? { receipt: decodePrivateAsset(value.receipt) } : {}) }
    },
    saveUploadAttempt: (caseId, value) => { const c = uploadCoordinates(caseId), key = uploadKey(c); if (value) store.set(key, { ...c, ...value }); else store.remove(key) },
    loadDraft: caseId => {
      const value = api.intent(slot(caseId, 'draft')) as Partial<ReplyDraft> | undefined
      if (!value) return null
      if (typeof value.text !== 'string' || value.text.length > 500 || !Array.isArray(value.assetIds)
        || value.assetIds.length > 6 || new Set(value.assetIds).size !== value.assetIds.length
        || value.opinionCode !== '' && !opinionOptions.some(item => item.code === value.opinionCode)) return emptyReply()
      try { value.assetIds.forEach(id) } catch { return emptyReply() }
      return { opinionCode: value.opinionCode!, text: value.text, assetIds: [...value.assetIds] }
    },
    saveDraft: (caseId, value) => api.saveIntent(slot(caseId, 'draft'), value),
  }
}
