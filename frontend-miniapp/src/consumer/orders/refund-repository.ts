import { ConsumerApi, id, type Command } from '../../shared/consumer-api'
import { ApiError } from '../../shared/request'
import { RealOrderReadRepository } from './repository'
import { decodeRefundApplyReceipt, isDefiniteRefundConflict, type RefundApplyInput, type PendingApply } from './refund'

// C-005 退款申请写通道（10号 §3.9 + 49号，后端同批 PR，IMPLEMENTED_DEFAULT_OFF：pet.auth.c.
// enabled + pet.refund.application.http.enabled 默认关闭）。订单本人身份作用域的 c/ 路由：与
// order-verify 一样落在 ConsumerApi.request 既有 /^\/api\/v1\/c\// 白名单内，不改
// consumer-api.ts。POST 走 api.write 幂等槽（X-Request-Id 终端 UUID 由 ConsumerApi 分配；同槽
// 同参重试复用原命令，异参 PENDING_WRITE_CHANGED）；开关未开放期间失败关闭：路由未挂载 403 /
// 会话缺失 401 走常规错误路径，页面不虚构任何回执。
export class RealRefundApplyRepository {
  private rejectionProofs = new WeakMap<object, { slot: string; command: Command }>()
  constructor(private api: ConsumerApi) {}
  private slot(orderId: string) { return `refund-apply:${id(orderId)}` }

  detail(orderId: string) { return new RealOrderReadRepository(this.api).detail(orderId) }

  apply(orderId: string, input: RefundApplyInput) {
    const target = id(orderId)
    const slot = this.slot(target)
    return this.api.write(slot, { method: 'POST', path: `/api/v1/c/orders/${target}/refund-applications`, data: input }, value => {
      const receipt = decodeRefundApplyReceipt(value)
      // 本路由首回执只可能是两窗口终态配对（49号内核固定）；其余组合按服务端异常失败关闭。
      const preService = receipt.applicationStatus === 'AUTO_APPROVED' && receipt.route === 'AUTO_FULL_BEFORE_SERVICE'
      const postService = receipt.applicationStatus === 'PENDING_MERCHANT' && receipt.route === 'MERCHANT_CONFIRM_AFTER_SERVICE'
      if (!preService && !postService) throw new Error('INVALID_RESPONSE')
      return receipt
    }, undefined, (error, command) => {
      // 与 aftersale/order-verify 同范式：终局 409 记下证据，仅凭证据退槽，防止误退未知结果。
      if (isDefiniteRefundConflict(error) && error && typeof error === 'object') this.rejectionProofs.set(error, { slot, command })
    })
  }

  pendingApply(orderId: string): PendingApply | null {
    const data = this.api.pendingCommand(this.slot(orderId))?.data as Partial<PendingApply> | undefined
    if (!data || typeof data.reasonCode !== 'string' || data.reasonCode.trim().length === 0 || data.reasonCode.length > 64
      || data.reasonText !== null && typeof data.reasonText !== 'string' || typeof data.reasonText === 'string' && data.reasonText.length > 500) return null
    return { reasonCode: data.reasonCode, reasonText: data.reasonText ?? null }
  }

  retireConflict(orderId: string, error: unknown): void {
    if (!isDefiniteRefundConflict(error) || !error || typeof error !== 'object') throw new Error('UNCONFIRMED_WRITE')
    const proof = this.rejectionProofs.get(error), slot = this.slot(orderId)
    if (!proof || proof.slot !== slot) throw new Error('UNCONFIRMED_WRITE')
    this.api.retireRejectedCommand(slot, proof.command)
    this.rejectionProofs.delete(error)
  }
}

export function isRefundApplyUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 401
}
