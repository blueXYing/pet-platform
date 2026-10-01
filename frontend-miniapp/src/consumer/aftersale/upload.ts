import { decodePrivateAsset, id, object, type LocalStore, type PrivateAssetReceipt } from '../../shared/consumer-api'
import type { AfterSaleClient } from '../../shared/aftersale-api'
import type { UploadFiles } from '../../shared/private-asset-upload'
import { ApiError } from '../../shared/request'

type UploadJournal = { ownerUserId: string; requestId: string; filePath: string; sha256: string; bytes: number; attempted: boolean; receipt?: PrivateAssetReceipt; rejected?: boolean }
/** Private image upload recovery is isolated by user and target form. A timed-out upload
 * keeps the saved copy, bytes fingerprint and UUID, including across session expiry. */
export class AfterSaleEvidenceUpload {
  private flight: Promise<PrivateAssetReceipt | null> | null = null
  constructor(private client: Pick<AfterSaleClient, 'api' | 'upload'>, private store: LocalStore, private files: UploadFiles, private target: string) {}
  private key() {
    const c = this.client.api.scope.capture().context
    if (c.workspace !== 'consumer' || c.userId !== this.client.api.currentSession?.userId) throw new Error('COMMON_UNAUTHORIZED')
    return `pet.aftersale.c.upload.v1:${id(c.userId)}:${this.target}`
  }
  pending(): UploadJournal | null {
    const saved = this.store.get(this.key())
    if (!saved) return null
    const v = object(saved)
    if (v.ownerUserId !== this.client.api.currentSession?.userId || !/^[a-f0-9-]{36}$/.test(v.requestId) || typeof v.filePath !== 'string' || !this.files.owns(v.filePath, v.requestId) || !/^[a-f0-9]{64}$/.test(v.sha256) || !Number.isSafeInteger(v.bytes) || v.bytes < 1 || v.bytes > 10485760) throw new Error('UPLOAD_JOURNAL_INVALID')
    return { ownerUserId: id(v.ownerUserId), requestId: v.requestId, filePath: v.filePath, sha256: v.sha256, bytes: v.bytes, attempted: v.attempted !== false, rejected: v.rejected === true, ...(v.receipt ? { receipt: decodePrivateAsset(v.receipt) } : {}) }
  }
  upload(): Promise<PrivateAssetReceipt | null> {
    if (this.flight) return this.flight
    const ticket = this.client.api.scope.capture()
    const operation = (async () => {
      const key = this.key()
      let pending = this.pending()
      if (!pending) {
        const selected = await this.files.choose(); ticket.assertCurrent()
        if (!selected) return null
        const requestId = await this.client.api.uuid(); ticket.assertCurrent()
        const fingerprint = await this.files.inspect(selected); ticket.assertCurrent()
        if (!/^[a-f0-9]{64}$/.test(fingerprint.sha256) || !Number.isSafeInteger(fingerprint.bytes) || fingerprint.bytes < 1 || fingerprint.bytes > 10485760) throw new Error('UPLOAD_FILE_INVALID')
        const filePath = await this.files.save(selected, requestId)
        try { ticket.assertCurrent(); this.store.set(key, { ownerUserId: ticket.context.userId, requestId, filePath, ...fingerprint, attempted: false }) }
        catch (error) { await this.files.remove(filePath); throw error }
        pending = this.pending()!
      }
      if (pending.receipt) return pending.receipt
      if (pending.rejected) throw new Error('UPLOAD_REJECTED')
      const fingerprint = await this.files.inspect(pending.filePath); ticket.assertCurrent()
      if (fingerprint.sha256 !== pending.sha256 || fingerprint.bytes !== pending.bytes) throw new Error('UPLOAD_FILE_CHANGED')
      const attempted = pending.attempted
      pending = { ...pending, attempted: true }; this.store.set(key, pending)
      try {
        const receipt = await this.client.upload(pending.filePath, pending.requestId); ticket.assertCurrent()
        this.store.set(key, { ...pending, receipt }); return receipt
      } catch (error) {
        ticket.assertCurrent()
        if (error instanceof ApiError && ((error.statusCode === 422 && error.code === 'PRIVATE_ASSET_REJECTED') || (!attempted && [400, 413, 415].includes(error.statusCode)))) this.store.set(key, { ...pending, rejected: true })
        throw error
      }
    })().finally(() => { if (this.flight === operation) this.flight = null })
    this.flight = operation
    return operation
  }
  async acknowledge(assetId: string) {
    const pending = this.pending()
    if (!pending?.receipt || pending.receipt.assetId !== assetId) throw new Error('UPLOAD_PENDING')
    const key = this.key(), ticket = this.client.api.scope.capture()
    await this.files.remove(pending.filePath); ticket.assertCurrent(); this.store.remove(key)
  }
  async discardRejected() {
    const pending = this.pending()
    if (!pending?.rejected || pending.receipt || this.flight) throw new Error('UPLOAD_PENDING')
    const key = this.key(), ticket = this.client.api.scope.capture()
    await this.files.remove(pending.filePath); ticket.assertCurrent(); this.store.remove(key)
  }
}
