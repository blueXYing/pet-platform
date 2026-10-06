import { ConsumerApi, id, type Command } from '../../shared/consumer-api'
import { ApiError } from '../../shared/request'
import {
  decodeCredentialView, decodeIssueReceipt, isDefiniteVerifyConflict,
  type CredentialView, type IssueReceipt, type OrderVerifyDeps, type PendingIssue, type RefreshKind,
} from './model'

// 47号 §4 v0.2（后端 PR#114，IMPLEMENTED_DEFAULT_OFF：pet.auth.c.enabled +
// pet.verification.credential.http.enabled 默认关闭）。订单本人身份作用域的 c/ 路由：与
// coupon-points 一样落在 ConsumerApi.request 既有 /^\/api\/v1\/c\// 白名单内，不改
// consumer-api.ts。GET 不带任何 query（带参 400）；POST 走 api.write 幂等槽（X-Request-Id
// 终端 UUID 由 ConsumerApi 分配；同槽同参重试复用原命令，异参 PENDING_WRITE_CHANGED）。
// 开关未开放期间失败关闭：路由未挂载 404 / 会话缺失 401 走常规错误路径，页面不渲染任何虚构码。
export class RealOrderVerifyRepository implements OrderVerifyDeps {
  private rejectionProofs = new WeakMap<object, { slot: string; command: Command }>()
  constructor(private api: ConsumerApi) {}
  private slot(orderId: string) { return `verify-code:${id(orderId)}` }
  view(orderId: string): Promise<CredentialView> {
    const target = id(orderId)
    return this.api.request({ method: 'GET', path: `/api/v1/c/orders/${target}/verification-code` }, value => {
      const view = decodeCredentialView(value)
      if (view.orderId !== target) throw new Error('INVALID_RESPONSE')
      return view
    })
  }
  issue(orderId: string, expectedCredentialVersion: string, refreshKind: RefreshKind): Promise<IssueReceipt> {
    const target = id(orderId)
    if (!/^(0|[1-9][0-9]{0,18})(?![\s\S])/.test(expectedCredentialVersion)) throw new Error('INVALID_RESPONSE')
    const data = { expectedCredentialVersion, refreshKind }
    const slot = this.slot(target)
    return this.api.write(slot, { method: 'POST', path: `/api/v1/c/orders/${target}/verification-code`, data }, value => {
      const receipt = decodeIssueReceipt(value)
      if (receipt.orderId !== target) throw new Error('INVALID_RESPONSE')
      return receipt
    }, undefined, (error, command) => {
      // 与 aftersale 同范式：终局 409 记下证据，仅凭证据退槽，防止误退未知结果。
      if (isDefiniteVerifyConflict(error) && error && typeof error === 'object') this.rejectionProofs.set(error, { slot, command })
    })
  }
  pendingIssue(orderId: string): PendingIssue | null {
    const data = this.api.pendingCommand(this.slot(orderId))?.data as Partial<PendingIssue> | undefined
    if (!data || typeof data.expectedCredentialVersion !== 'string' || !/^(0|[1-9][0-9]{0,18})(?![\s\S])/.test(data.expectedCredentialVersion)
      || data.refreshKind !== 'INITIAL' && data.refreshKind !== 'AUTO' && data.refreshKind !== 'MANUAL') return null
    return { expectedCredentialVersion: data.expectedCredentialVersion, refreshKind: data.refreshKind }
  }
  retireConflict(orderId: string, error: unknown): void {
    if (!isDefiniteVerifyConflict(error) || !error || typeof error !== 'object') throw new Error('UNCONFIRMED_WRITE')
    const proof = this.rejectionProofs.get(error), slot = this.slot(orderId)
    if (!proof || proof.slot !== slot) throw new Error('UNCONFIRMED_WRITE')
    this.api.retireRejectedCommand(slot, proof.command)
    this.rejectionProofs.delete(error)
  }
}

export function isVerifyUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 401
}
