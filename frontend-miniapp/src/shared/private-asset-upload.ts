import { ConsumerApi, decodePrivateAsset, id, object, type LocalStore, type PrivateAssetReceipt } from './consumer-api'
import { ApiError } from './request'

export type PendingUpload<Kind extends string> = { ownerUserId: string; kind: Kind; requestId: string; filePath: string; sha256: string; bytes: number; attempted: boolean; receipt?: PrivateAssetReceipt; rejected?: boolean }
export type UploadFiles = {
  choose(): Promise<string | null>
  save(path: string, requestId: string): Promise<string>
  inspect(path: string): Promise<{ sha256: string; bytes: number }>
  owns(path: string, requestId: string): boolean
  remove(path: string): Promise<void>
}
/** Separate owner journal deliberately survives session expiry/logout, never stores file bytes. */
export class PrivateAssetUpload<Kind extends string> {
  private flight: Promise<PrivateAssetReceipt | null> | null = null
  private flightKind: Kind | null = null
  private flightRevision = -1
  constructor(private api: ConsumerApi, private store: LocalStore, private files: UploadFiles,
    private options: { purpose: 'MERCHANT_APPLICATION_MATERIAL' | 'SERVICE_COVER'; workspace: 'consumer' | 'merchant'; kinds: readonly Kind[]; journal: string }) {}
  private key() {
    const context = this.api.scope.capture().context
    if (context.workspace !== this.options.workspace || this.api.currentSession?.userId !== context.userId) throw new Error('COMMON_UNAUTHORIZED')
    const target = this.options.workspace === 'merchant' ? `:${id(context.merchantId)}:${id(context.storeId)}` : ''
    return `${this.options.journal}:${id(context.userId)}${target}`
  }
  pending(): PendingUpload<Kind> | null {
    const value = this.store.get(this.key())
    if (!value) return null
    const v = object(value)
    if (v.ownerUserId !== this.api.currentSession?.userId || !this.options.kinds.includes(v.kind) || !/^[a-f0-9-]{36}$/.test(v.requestId) || typeof v.filePath !== 'string' || !this.files.owns(v.filePath, v.requestId) || !/^[a-f0-9]{64}$/.test(v.sha256) || !Number.isSafeInteger(v.bytes) || v.bytes < 1 || v.bytes > 10485760) throw new Error('UPLOAD_JOURNAL_INVALID')
    return { ownerUserId: id(v.ownerUserId), kind: v.kind, requestId: v.requestId, filePath: v.filePath, sha256: v.sha256, bytes: v.bytes, attempted: v.attempted !== false, rejected: v.rejected === true, ...(v.receipt ? { receipt: decodePrivateAsset(v.receipt) } : {}) }
  }
  upload(kind: Kind): Promise<PrivateAssetReceipt | null> {
    if (this.flight) return this.flightKind === kind && this.flightRevision === this.api.scope.revision ? this.flight : Promise.reject(new Error('UPLOAD_PENDING'))
    this.flightKind = kind
    this.flightRevision = this.api.scope.revision
    const ticket = this.api.scope.capture()
    const operation = (async () => {
      const key = this.key()
      if (!this.options.kinds.includes(kind)) throw new Error('UPLOAD_KIND_INVALID')
      let pending = this.pending()
      if (pending && pending.kind !== kind) throw new Error('UPLOAD_PENDING')
      if (!pending) {
        const selected = await this.files.choose(); ticket.assertCurrent()
        if (!selected) return null // picker cancellation precedes any network intent
        const requestId = await this.api.uuid(); ticket.assertCurrent()
        const fingerprint = await this.files.inspect(selected); ticket.assertCurrent()
        if (!/^[a-f0-9]{64}$/.test(fingerprint.sha256) || !Number.isSafeInteger(fingerprint.bytes) || fingerprint.bytes < 1 || fingerprint.bytes > 10485760) throw new Error('UPLOAD_FILE_INVALID')
        const filePath = await this.files.save(selected, requestId)
        try { ticket.assertCurrent() } catch (error) { await this.files.remove(filePath); throw error }
        pending = { ownerUserId: ticket.context.userId, kind, requestId, filePath, ...fingerprint, attempted: false }
        try { this.store.set(key, pending) } catch (error) { await this.files.remove(filePath); throw error }
        pending = this.pending()!
      }
      if (pending.receipt) return pending.receipt
      const current = await this.files.inspect(pending.filePath); ticket.assertCurrent()
      if (current.sha256 !== pending.sha256 || current.bytes !== pending.bytes) throw new Error('UPLOAD_FILE_CHANGED')
      const previouslyAttempted = pending.attempted
      pending = { ...pending, attempted: true }
      this.store.set(key, pending) // crash before ACK is conservatively an unknown outcome
      let receipt: PrivateAssetReceipt
      try { receipt = await this.api.uploadPrivateAsset({ ...pending, purpose: this.options.purpose }); ticket.assertCurrent() }
      catch (error) {
        ticket.assertCurrent()
        // A retry rejected by an ingress validator cannot disprove an earlier successful upload.
        if (error instanceof ApiError && ((error.statusCode === 422 && error.code === 'PRIVATE_ASSET_REJECTED') || (!previouslyAttempted && [400, 413, 415].includes(error.statusCode)))) this.store.set(key, { ...pending, rejected: true })
        throw error
      }
      this.store.set(key, { ...pending, receipt })
      return receipt
    })().finally(() => { if (this.flight === operation) { this.flight = null; this.flightKind = null } })
    this.flight = operation
    return operation
  }
  async acknowledge(assetId: string) {
    const pending = this.pending()
    if (!pending?.receipt || pending.receipt.assetId !== assetId) throw new Error('UPLOAD_PENDING')
    const key = this.key()
    const ticket = this.api.scope.capture()
    // Only the exact app-owned saved copy is removed, never the user's selected original.
    await this.files.remove(pending.filePath); ticket.assertCurrent()
    this.store.remove(key)
  }
  async discardRejected() {
    const pending = this.pending()
    if (!pending?.rejected || pending.receipt || this.flight) throw new Error('UPLOAD_PENDING')
    const ticket = this.api.scope.capture()
    const key = this.key()
    await this.files.remove(pending.filePath); ticket.assertCurrent()
    this.store.remove(key)
  }
}
