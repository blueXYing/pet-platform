import { ApiError } from '../../shared/request'
import { ConsumerApi, id } from '../../shared/consumer-api'
import {
  availabilityQuery, buildOrderRequest, decodeAvailability, decodeCreateOrderReceipt, decodePaymentReceipt,
  PAYMENT_CHANNEL, type AvailabilityView, type BookingDeps, type BookingDraft, type CreateOrderReceipt, type PaymentReceipt,
} from './model'

// 预约下单/支付发起真实仓库（10号 §3.4/§3.5/§3.6）。后端 createOrder/createOrderPayment 由
// 并行后端切片同批交付：路由未开放期间失败关闭（404/503 走常规错误路径，orders 切片同语义）。
// 三条路由都落在 ConsumerApi 既有 /c/ 白名单内，不改 consumer-api.ts。
// - availability：登录态只读（SCH-D1），GET query 仅 storeId/startDate/endDate（§3.4 拒绝未知参数）；
// - create：幂等槽 order:create（23号 X-Request-Id 跨页面重开/重启重放；载荷变化即
//   PENDING_WRITE_CHANGED，不盲目换号重试）；
// - pay：幂等槽 order-pay:{orderId}（同语义；channel 固定 WECHAT_MINI_PROGRAM，§3.6）。
export const ORDER_CREATE_SLOT = 'order:create'
export const paymentSlot = (orderId: string): string => `order-pay:${orderId}`

/** §3.5 409 族确定性业务拒绝：该精确载荷已被终局拒绝（排期/资格/接送间隔），按 23号 retire
 *  语义释放槽位，用户重选后以新编号提交；IDEMPOTENCY_KEY_CONFLICT 不在其列（换载荷前不得复用编号）。 */
const CREATE_DEFINITELY_REJECTED: ReadonlySet<string> = new Set([
  'MERCHANT_DISABLED', 'STORE_DISABLED', 'SERVICE_NOT_BOOKABLE', 'SCHEDULE_NOT_AVAILABLE',
  'SCHEDULE_CAPACITY_EXCEEDED', 'SCHEDULE_PICKUP_RETURN_INTERVAL_INVALID', 'COUPON_NOT_AVAILABLE',
])

export class RealBookingRepository implements BookingDeps {
  constructor(private api: ConsumerApi) {}
  async availability(serviceId: string, storeId: string, date: string): Promise<AvailabilityView> {
    const query = availabilityQuery(serviceId, storeId, date)
    return this.api.request({ method: 'GET', path: query.path, data: query.data }, decodeAvailability)
  }
  async create(draft: BookingDraft): Promise<CreateOrderReceipt> {
    const slot = ORDER_CREATE_SLOT
    const spec = { method: 'POST' as const, path: '/api/v1/c/orders', data: buildOrderRequest(draft) }
    return this.api.write(slot, spec, decodeCreateOrderReceipt, undefined, (error, command) => {
      if (error instanceof ApiError && error.statusCode === 409 && CREATE_DEFINITELY_REJECTED.has(error.code)) {
        try { this.api.retireRejectedCommand(slot, command) } catch { /* 载荷已变化：保留原日志 */ }
      }
    })
  }
  async pay(orderId: string): Promise<PaymentReceipt> {
    const target = id(orderId)
    return this.api.write(paymentSlot(target), { method: 'POST', path: `/api/v1/c/orders/${target}/payments`, data: { channel: PAYMENT_CHANNEL } }, decodePaymentReceipt)
  }
}
