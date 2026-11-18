import { ConsumerApi, decodeMarketingAsset, id, object, type LocalStore, type MarketingAssetReceipt } from '../../shared/consumer-api'
import type { UploadFiles } from '../../shared/private-asset-upload'
import { ApiError } from '../../shared/request'

// Contract-76 §4.2 merchant campaign-cover upload journal: a pending pick survives page
// remount/app restart with its exact file + terminal request id, is isolated by
// userId/merchantId/storeId (never mixed into the private-material journal), and never
// stores file bytes. The saved local copy is removed only after the receipt is
// acknowledged by a saved campaign (or explicitly discarded).
export const MARKETING_COVER_LIMITS = { maxBytes: 2097152, minPixels: 1, maxPixels: 2000 } as const
export type MarketingCoverPending = {
  ownerUserId: string; requestId: string; filePath: string; sha256: string; bytes: number
  attempted: boolean; receipt?: MarketingAssetReceipt; rejected?: boolean
}
export type CoverImageProbe = (path: string) => Promise<{ width: number; height: number }>

export class MarketingCoverUpload {
  private flight: Promise<MarketingAssetReceipt | null> | null = null
  private flightRevision = -1
  constructor(private api: ConsumerApi, private store: LocalStore, private files: UploadFiles, private probe?: CoverImageProbe) {}

  /** Journal key per HANDOFF §4.5.2: isolated by user + merchant + store. */
  private key() {
    const context = this.api.scope.capture().context
    if (context.workspace !== 'merchant' || !context.merchantId || !context.storeId) throw new Error('WORKSPACE_PATH_MISMATCH')
    if (this.api.currentSession?.userId !== context.userId) throw new Error('COMMON_UNAUTHORIZED')
    return `pet.marketing-cover-upload.v1:${id(context.userId)}:${id(context.merchantId)}:${id(context.storeId)}`
  }

  pending(): MarketingCoverPending | null {
    const value = this.store.get(this.key())
    if (!value) return null
    const v = object(value)
    if (v.ownerUserId !== this.api.currentSession?.userId
      || !/^[a-f0-9-]{36}$/.test(v.requestId)
      || typeof v.filePath !== 'string' || !this.files.owns(v.filePath, v.requestId)
      || !/^[a-f0-9]{64}$/.test(v.sha256)
      || !Number.isSafeInteger(v.bytes) || v.bytes < 1 || v.bytes > MARKETING_COVER_LIMITS.maxBytes) throw new Error('UPLOAD_JOURNAL_INVALID')
    return {
      ownerUserId: id(v.ownerUserId), requestId: v.requestId, filePath: v.filePath,
      sha256: v.sha256, bytes: v.bytes, attempted: v.attempted !== false, rejected: v.rejected === true,
      ...(v.receipt ? { receipt: decodeMarketingAsset(v.receipt) } : {}),
    }
  }

  upload(): Promise<MarketingAssetReceipt | null> {
    if (this.flight) return this.flightRevision === this.api.scope.revision ? this.flight : Promise.reject(new Error('UPLOAD_PENDING'))
    this.flightRevision = this.api.scope.revision
    const ticket = this.api.scope.capture()
    const operation = (async () => {
      const key = this.key()
      let pending = this.pending()
      if (!pending) {
        const selected = await this.files.choose(); ticket.assertCurrent()
        if (!selected) return null // picker cancellation precedes any network intent
        const requestId = await this.api.uuid(); ticket.assertCurrent()
        const fingerprint = await this.files.inspect(selected); ticket.assertCurrent()
        if (!/^[a-f0-9]{64}$/.test(fingerprint.sha256) || !Number.isSafeInteger(fingerprint.bytes)
          || fingerprint.bytes < 1 || fingerprint.bytes > MARKETING_COVER_LIMITS.maxBytes) throw new Error('UPLOAD_FILE_INVALID')
        if (this.probe) {
          const size = await this.probe(selected); ticket.assertCurrent()
          if (!Number.isSafeInteger(size.width) || !Number.isSafeInteger(size.height)
            || size.width < MARKETING_COVER_LIMITS.minPixels || size.width > MARKETING_COVER_LIMITS.maxPixels
            || size.height < MARKETING_COVER_LIMITS.minPixels || size.height > MARKETING_COVER_LIMITS.maxPixels) throw new Error('UPLOAD_FILE_INVALID')
        }
        const filePath = await this.files.save(selected, requestId)
        try { ticket.assertCurrent() } catch (error) { await this.files.remove(filePath); throw error }
        pending = { ownerUserId: ticket.context.userId, requestId, filePath, ...fingerprint, attempted: false }
        try { this.store.set(key, pending) } catch (error) { await this.files.remove(filePath); throw error }
        pending = this.pending()!
      }
      if (pending.receipt) return pending.receipt
      const current = await this.files.inspect(pending.filePath); ticket.assertCurrent()
      if (current.sha256 !== pending.sha256 || current.bytes !== pending.bytes) throw new Error('UPLOAD_FILE_CHANGED')
      const previouslyAttempted = pending.attempted
      pending = { ...pending, attempted: true }
      this.store.set(key, pending) // crash before the receipt is conservatively an unknown outcome
      let receipt: MarketingAssetReceipt
      try {
        receipt = await this.api.uploadMarketingAsset({ filePath: pending.filePath, requestId: pending.requestId }); ticket.assertCurrent()
      } catch (error) {
        ticket.assertCurrent()
        // 76号 §4.2 终局拒绝（400 类型/像素/归属、413 超限）才允许换图；其余结果未知，
        // 保留原 requestId 重试，不得伪造成功。
        if (!previouslyAttempted && error instanceof ApiError && [400, 413].includes(error.statusCode)) this.store.set(key, { ...pending, rejected: true })
        throw error
      }
      this.store.set(key, { ...pending, receipt })
      return receipt
    })().finally(() => { if (this.flight === operation) this.flight = null })
    this.flight = operation
    return operation
  }

  /** The saved campaign acknowledged this exact asset: drop the local copy and the journal. */
  async acknowledge(assetId: string) {
    const pending = this.pending()
    if (!pending?.receipt || pending.receipt.assetId !== assetId) throw new Error('UPLOAD_PENDING')
    const key = this.key()
    const ticket = this.api.scope.capture()
    await this.files.remove(pending.filePath); ticket.assertCurrent()
    this.store.remove(key)
  }

  /** 放弃/换图：仅无在途上传时允许，删除本地副本与 journal（服务端素材留存无害）。 */
  async discard() {
    if (this.flight) throw new Error('UPLOAD_PENDING')
    const pending = this.pending()
    if (!pending) return
    const key = this.key()
    const ticket = this.api.scope.capture()
    await this.files.remove(pending.filePath); ticket.assertCurrent()
    this.store.remove(key)
  }
}
