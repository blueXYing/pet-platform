import { ApiError } from '../../shared/request'
import { ConsumerApi } from '../../shared/consumer-api'
import {
  decodeMerchantOrderPage, decodeOrderDecisionReceipt, definitiveNoWrite, isOrderId, reasonTextProblem, internalNoteProblem,
  type OrderDecisionDeps, type OrderDecisionReceipt, type RejectReasonCode,
} from './model'

/**
 * Real wiring for the contract-45 merchant decision routes on top of the shared ConsumerApi
 * (MerchantOrderController, switch pet.order.merchant.http.enabled default OFF — until the
 * platform enables it the request fails closed through the normal error paths and the page
 * renders the fail-closed panel). Confirm and reject are separate per-order X-Request-Id
 * slots (23号 §5): an explicit retry of the same payload replays the original requestId and
 * returns the first receipt; the slot survives unknown outcomes and identity rejections
 * because a replay re-proves the OWNER authority before any old receipt is returned.
 *
 * A definitive 409 (already decided / deadline passed / refund created / key used with other
 * content) proves this exact payload+key never committed — a journaled replay that HAD
 * succeeded would answer 200 with the original receipt — so the slot is retired and a
 * corrected payload mints a fresh key. Busy conflicts keep the journal.
 */
export class RealMerchantOrderRepository implements OrderDecisionDeps {
  constructor(private api: ConsumerApi) {}

  /** The slot is OWNER(merchant)-scoped: the server locates the order's store and re-proves
   *  the OWNER relation per call, so a selected sibling store never splits one order's
   *  journal in two. Entry discipline still requires a full merchant workspace ticket. */
  private coordinates(): { merchantId: string; storeId: string } {
    const context = this.api.scope.capture().context
    if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      throw new Error('WORKSPACE_PATH_MISMATCH')
    }
    return { merchantId: context.merchantId, storeId: context.storeId }
  }

  private slot(action: 'confirm' | 'reject', orderId: string): string {
    const { merchantId } = this.coordinates()
    return `merchant-${action}:${merchantId}:${orderId}`
  }

  private static pathFor(orderId: string, action: 'confirm' | 'reject'): string {
    return `/api/v1/merchant/orders/${orderId}/${action}`
  }

  private static noteProblemOrNull(note: string | null): string | null {
    return note === null ? null : internalNoteProblem(note)
  }

  async submit(action: 'confirm' | 'reject', orderId: string,
      payload: { internalNote?: string | null; reasonCode?: RejectReasonCode; reasonText?: string }): Promise<OrderDecisionReceipt> {
    const order = orderId.trim()
    if (!isOrderId(order)) throw new Error('ORDER_FORM_INVALID')
    const data: Record<string, unknown> = { expectedConfirmRound: 0 }
    if (action === 'confirm') {
      // Untouched field omits the note entirely; anything typed is sent verbatim (45号:
      // omission and the empty string are distinct idempotent payloads — no trimming).
      const note = payload.internalNote == null || payload.internalNote === '' ? null : payload.internalNote
      if (RealMerchantOrderRepository.noteProblemOrNull(note)) throw new Error('ORDER_FORM_INVALID')
      if (note !== null) data.internalNote = note
    } else {
      if (!payload.reasonCode || reasonTextProblem(payload.reasonText ?? '')) throw new Error('ORDER_FORM_INVALID')
      data.reasonCode = payload.reasonCode
      data.reasonText = payload.reasonText
    }
    const slot = this.slot(action, order)
    const spec: { method: 'POST'; path: string; data: Record<string, unknown> } =
      { method: 'POST', path: RealMerchantOrderRepository.pathFor(order, action), data }
    try {
      return await this.api.write(slot, spec, value => {
        const receipt = decodeOrderDecisionReceipt(value)
        if (receipt.orderId !== order) throw new Error('INVALID_RESPONSE')
        return receipt
      })
    } catch (error) {
      if (definitiveNoWrite(error)) {
        try { this.api.retireRejectedCommand(slot, spec) } catch { /* already retired or changed */ }
      }
      throw error
    }
  }

  async confirm(orderId: string, internalNote: string | null): Promise<OrderDecisionReceipt> {
    return this.submit('confirm', orderId, { internalNote })
  }

  async reject(orderId: string, reasonCode: RejectReasonCode, reasonText: string): Promise<OrderDecisionReceipt> {
    return this.submit('reject', orderId, { reasonCode, reasonText })
  }

  pending(): { action: 'confirm' | 'reject'; orderId: string } | null {
    let coordinates: { merchantId: string; storeId: string }
    try { coordinates = this.coordinates() } catch { return null }
    // Only the current workspace's slots: another merchant's unknown-outcome command is not
    // this page's business (its order would fail the owner check anyway).
    for (const action of ['confirm', 'reject'] as const) {
      for (const { command } of this.api.pendingCommands(`merchant-${action}:${coordinates.merchantId}:`)) {
        const orderId = new RegExp(`^/api/v1/merchant/orders/([1-9][0-9]{0,18})/${action}$`).exec(command.path)?.[1]
        if (orderId) return { action, orderId }
      }
    }
    return null
  }
}

// ---------------------------------------------------------------------------
// 商家订单列表读侧（10号 §4.1 增补）：只读 GET，商家坐标由工作台票根携带并经
// ConsumerApi 的 WORKSPACE_PATH_MISMATCH 防线核对（与 merchantId/storeId 严格一致），
// 服务端仍在门店 guard 事务内重验 OWNER 归属；开关未开时按常规错误路径失败关闭。
// ---------------------------------------------------------------------------
export interface MerchantOrderListDeps {
  list(query: { merchantId: string; storeId: string; displayStatus?: string; page: number; pageSize: number }): Promise<import('./model').MerchantOrderPage>
}

const MERCHANT_LIST_PAGE_SIZE = 20

export class RealMerchantOrderListRepository implements MerchantOrderListDeps {
  constructor(private api: ConsumerApi) {}

  /** tab=null 即「全部」桶（不发送 displayStatus，§3.7 先例）。 */
  async list(query: { merchantId: string; storeId: string; displayStatus?: string; page: number; pageSize: number }): Promise<import('./model').MerchantOrderPage> {
    const coordinates = this.coordinates()
    if (query.merchantId !== coordinates.merchantId || query.storeId !== coordinates.storeId) {
      throw new Error('WORKSPACE_PATH_MISMATCH')
    }
    if (!Number.isInteger(query.page) || query.page < 1 || query.page > 10000
      || !Number.isInteger(query.pageSize) || query.pageSize < 1 || query.pageSize > 100
      || query.pageSize !== MERCHANT_LIST_PAGE_SIZE) throw new Error('INVALID_QUERY')
    const data: Record<string, unknown> = {
      merchantId: query.merchantId, storeId: query.storeId, page: query.page, pageSize: query.pageSize,
    }
    if (query.displayStatus !== undefined) data.displayStatus = query.displayStatus
    const page = await this.api.request(
      { method: 'GET', path: '/api/v1/merchant/orders', data },
      value => {
        const decoded = decodeMerchantOrderPage(value)
        if (decoded.page !== query.page || decoded.pageSize !== query.pageSize) throw new Error('INVALID_RESPONSE')
        if (query.displayStatus !== undefined
          && decoded.items.some(item => item.displayStatus !== query.displayStatus)) throw new Error('INVALID_RESPONSE')
        return decoded
      })
    return page
  }

  private coordinates(): { merchantId: string; storeId: string } {
    const context = this.api.scope.capture().context
    if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      throw new Error('WORKSPACE_PATH_MISMATCH')
    }
    return { merchantId: context.merchantId, storeId: context.storeId }
  }
}
