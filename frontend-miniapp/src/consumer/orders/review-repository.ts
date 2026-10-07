import { ConsumerApi, id, type Command } from '../../shared/consumer-api'
import { ApiError } from '../../shared/request'
import { RealOrderReadRepository } from './repository'
import {
  decodeReviewEligibility, decodeReviewReceipt, isDefiniteReviewConflict,
  type PendingReview, type ReviewCreateInput,
} from './review'

// REV-001 评价读写通道（10号 §3.14，后端同批 PR，IMPLEMENTED_DEFAULT_OFF：pet.auth.c.enabled +
// pet.review.http.enabled 默认关闭）。订单本人身份作用域的 c/ 路由：与 refund-apply 一样落在
// ConsumerApi.request 既有 /^\/api\/v1\/c\// 白名单内，不改 consumer-api.ts。GET 资格查询走
// request()（no-store 读）；POST 走 api.write 幂等槽（X-Request-Id 终端 UUID 由 ConsumerApi
// 分配；同槽同参重试复用原命令，异参 PENDING_WRITE_CHANGED）；开关未开放期间失败关闭：
// 路由未挂载 403 / 会话缺失 401 走常规错误路径，页面不虚构任何回执。
export class RealReviewRepository {
  private rejectionProofs = new WeakMap<object, { slot: string; command: Command }>()
  constructor(private api: ConsumerApi) {}
  private slot(orderId: string) { return `review-create:${id(orderId)}` }

  detail(orderId: string) { return new RealOrderReadRepository(this.api).detail(orderId) }

  eligibility(orderId: string) {
    const target = id(orderId)
    return this.api.request({ method: 'GET', path: `/api/v1/c/orders/${target}/review-eligibility` },
      value => decodeReviewEligibility(value))
  }

  create(orderId: string, input: ReviewCreateInput) {
    const target = id(orderId)
    const slot = this.slot(target)
    return this.api.write(slot, { method: 'POST', path: `/api/v1/c/orders/${target}/reviews`, data: input },
      value => decodeReviewReceipt(value), undefined, (error, command) => {
        // 与 refund-apply 同范式：终局 409 记下证据，仅凭证据退槽，防止误退未知结果。
        if (isDefiniteReviewConflict(error) && error && typeof error === 'object') this.rejectionProofs.set(error, { slot, command })
      })
  }

  pendingReview(orderId: string): PendingReview | null {
    const data = this.api.pendingCommand(this.slot(orderId))?.data as Partial<PendingReview> | undefined
    if (!data || !Number.isInteger(data.storeScore) || !Number.isInteger(data.serviceScore)
      || !Number.isInteger(data.staffScore) || data.storeScore! < 1 || data.storeScore! > 5
      || data.serviceScore! < 1 || data.serviceScore! > 5 || data.staffScore! < 1 || data.staffScore! > 5
      || data.content !== null && typeof data.content !== 'string' || typeof data.content === 'string' && Array.from(data.content).length > 2000) return null
    return { storeScore: data.storeScore!, serviceScore: data.serviceScore!, staffScore: data.staffScore!, content: data.content ?? null }
  }

  retireConflict(orderId: string, error: unknown): void {
    if (!isDefiniteReviewConflict(error) || !error || typeof error !== 'object') throw new Error('UNCONFIRMED_WRITE')
    const proof = this.rejectionProofs.get(error), slot = this.slot(orderId)
    if (!proof || proof.slot !== slot) throw new Error('UNCONFIRMED_WRITE')
    this.api.retireRejectedCommand(slot, proof.command)
    this.rejectionProofs.delete(error)
  }
}

export function isReviewUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 401
}
