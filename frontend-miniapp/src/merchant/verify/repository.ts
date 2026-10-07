import { ApiError } from '../../shared/request'
import { ConsumerApi } from '../../shared/consumer-api'
import {
  codeProblem, decodeVerificationReceipt, isOrderId, normalizeCode,
  type VerificationDeps, type VerificationReceipt,
} from './model'

/**
 * Real wiring for the contract-48 K2 v0.3 verification completion route on top of the
 * shared ConsumerApi (MerchantOrderVerificationController, switch
 * pet.verification.completion.http.enabled default OFF — until the platform enables it the
 * request fails closed through the normal error paths and the page renders the fail-closed
 * panel). The command journals a per-order X-Request-Id slot through ConsumerApi.write so an
 * explicit retry replays the same requestId after a lost response (23号 §5: a replay returns
 * the first receipt with its original time and version). A definitive 409 proves the exact
 * payload+key never committed (a journaled replay that HAD succeeded would answer 200 with
 * the original receipt), so the slot is retired and a corrected payload reuses it; a 409
 * with a different key (VERIFICATION_ALREADY_DONE) also proves this command wrote nothing.
 * 401/403/404 keep the journal: replays re-prove the K1 identity chain before any old
 * receipt is returned, so those rejections cannot disprove an earlier unknown send.
 */
export class RealVerificationRepository implements VerificationDeps {
  constructor(private api: ConsumerApi) {}

  private coordinates(): { merchantId: string; storeId: string } {
    const context = this.api.scope.capture().context
    if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      throw new Error('WORKSPACE_PATH_MISMATCH')
    }
    return { merchantId: context.merchantId, storeId: context.storeId }
  }

  private slot(orderId: string): string {
    const { merchantId, storeId } = this.coordinates()
    return `merchant-verify:${merchantId}:${storeId}:${orderId}`
  }

  private static pathFor(orderId: string): string {
    return `/api/v1/merchant/orders/${orderId}/verification`
  }

  async verify(orderId: string, rawCode: string): Promise<VerificationReceipt> {
    if (!isOrderId(orderId.trim())) throw new Error('VERIFY_FORM_INVALID')
    const verificationCode = normalizeCode(rawCode)
    if (codeProblem(verificationCode)) throw new Error('VERIFY_FORM_INVALID')
    const data = { verificationCode }
    const slot = this.slot(orderId.trim())
    const path = RealVerificationRepository.pathFor(orderId.trim())
    try {
      return await this.api.write(slot, { method: 'POST', path, data }, value => {
        const receipt = decodeVerificationReceipt(value)
        if (receipt.orderId !== orderId.trim()) throw new Error('INVALID_RESPONSE')
        return receipt
      })
    } catch (error) {
      if (error instanceof ApiError && error.statusCode === 409) {
        try { this.api.retireRejectedCommand(slot, { method: 'POST', path, data }) } catch { /* already retired */ }
      }
      throw error
    }
  }

  pending(): { orderId: string; verificationCode: string } | null {
    let coordinates: { merchantId: string; storeId: string }
    try { coordinates = this.coordinates() } catch { return null }
    // Only the current workspace's slots: another store's unknown-outcome command is not
    // this page's business (its order would 404 the store-consistency check anyway).
    for (const { command } of this.api.pendingCommands(`merchant-verify:${coordinates.merchantId}:${coordinates.storeId}:`)) {
      const orderId = /^\/api\/v1\/merchant\/orders\/([1-9][0-9]{0,18})\/verification$/.exec(command.path)?.[1]
      const code = typeof command.data?.verificationCode === 'string' ? command.data.verificationCode : ''
      if (orderId && !codeProblem(code)) return { orderId, verificationCode: code }
    }
    return null
  }
}
