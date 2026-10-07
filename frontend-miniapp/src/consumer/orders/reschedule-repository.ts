import { ConsumerApi, id, type Command } from '../../shared/consumer-api'
import { ApiError } from '../../shared/request'
import { RealOrderReadRepository } from './repository'
import { availabilityQuery, decodeRescheduleReceipt, decodeSelectionAvailability, isDefiniteRescheduleConflict, type RescheduleInput, type SelectionView } from './reschedule'

// C 端改期写通道（10号 §3.8 + 46号，后端同批 PR，IMPLEMENTED_DEFAULT_OFF：pet.auth.c.enabled +
// pet.order.reschedule.http.enabled + pet.order.reschedule.enabled 多层默认关闭）。订单本人身份
// 作用域的 c/ 路由：与 refund-apply 一样落在 ConsumerApi.request 既有 /^\/api\/v1\/c\// 白名单内，
// 不改 consumer-api.ts。POST 走 api.write 幂等槽（X-Request-Id 终端 UUID 由 ConsumerApi 分配；同槽
// 同参重试复用原命令，异参 PENDING_WRITE_CHANGED；首交与受保护重放同一 200 首次成功回执）；开关
// 未开放期间失败关闭：路由未挂载 403 / 会话缺失 401 走常规错误路径，页面不虚构任何回执。
export class RealRescheduleRepository {
  private rejectionProofs = new WeakMap<object, { slot: string; command: Command }>()
  constructor(private api: ConsumerApi) {}

  private slot(orderId: string) { return `reschedule:${id(orderId)}` }

  detail(orderId: string) { return new RealOrderReadRepository(this.api).detail(orderId) }

  /** §3.4 可约时段（39号选窗增补开启时 item 携带 windowId/kind；未开启时无法构造 46号请求，
   *  调用方按 selectionBranch 失败关闭，不猜测窗口身份）。 */
  availability(serviceId: string, storeId: string, date: string): Promise<SelectionView> {
    const query = availabilityQuery(serviceId, storeId, date)
    return this.api.request({ method: 'GET', path: query.path, data: query.data }, decodeSelectionAvailability)
  }

  reschedule(orderId: string, input: RescheduleInput) {
    const target = id(orderId)
    const slot = this.slot(target)
    return this.api.write(slot, { method: 'POST', path: `/api/v1/c/orders/${target}/reschedule`, data: input }, value => {
      const receipt = decodeRescheduleReceipt(value)
      if (receipt.orderId !== target) throw new Error('INVALID_RESPONSE')
      return receipt
    }, undefined, (error, command) => {
      // 与 refund-apply 同范式：终局 409 记下证据，仅凭证据退槽，防止误退未知结果。
      if (isDefiniteRescheduleConflict(error) && error && typeof error === 'object') this.rejectionProofs.set(error, { slot, command })
    })
  }

  /** 幂等恢复：从 pendingCommand 载荷还原原改期请求（两分支形状，未知/非法形态返回 null）。 */
  pendingReschedule(orderId: string): { input: RescheduleInput } | null {
    const data = this.api.pendingCommand(this.slot(orderId))?.data as Partial<RescheduleInput> | undefined
    if (!data || typeof data.expectedOrderVersion !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(data.expectedOrderVersion)) return null
    const store = data as { appointmentStart?: unknown; appointmentEnd?: unknown; selectedGeneralWindowId?: unknown }
    const pickup = data as { pickupStart?: unknown; returnStart?: unknown; selectedPickupWindowId?: unknown; selectedReturnWindowId?: unknown }
    const instantLike = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
    const idLike = (value: unknown): value is string => typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value)
    if (instantLike(store.appointmentStart) && instantLike(store.appointmentEnd) && idLike(store.selectedGeneralWindowId)
      && pickup.pickupStart === undefined && pickup.returnStart === undefined
      && pickup.selectedPickupWindowId === undefined && pickup.selectedReturnWindowId === undefined)
      return { input: { expectedOrderVersion: data.expectedOrderVersion, appointmentStart: store.appointmentStart, appointmentEnd: store.appointmentEnd, selectedGeneralWindowId: store.selectedGeneralWindowId } }
    if (instantLike(pickup.pickupStart) && instantLike(pickup.returnStart) && idLike(pickup.selectedPickupWindowId) && idLike(pickup.selectedReturnWindowId)
      && store.appointmentStart === undefined && store.appointmentEnd === undefined && store.selectedGeneralWindowId === undefined)
      return { input: { expectedOrderVersion: data.expectedOrderVersion, pickupStart: pickup.pickupStart, returnStart: pickup.returnStart, selectedPickupWindowId: pickup.selectedPickupWindowId, selectedReturnWindowId: pickup.selectedReturnWindowId } }
    return null
  }

  retireConflict(orderId: string, error: unknown): void {
    if (!isDefiniteRescheduleConflict(error) || !error || typeof error !== 'object') throw new Error('UNCONFIRMED_WRITE')
    const proof = this.rejectionProofs.get(error), slot = this.slot(orderId)
    if (!proof || proof.slot !== slot) throw new Error('UNCONFIRMED_WRITE')
    this.api.retireRejectedCommand(slot, proof.command)
    this.rejectionProofs.delete(error)
  }
}

export function isRescheduleUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 401
}
