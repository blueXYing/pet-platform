import { ApiError } from '../../shared/request'
import { ConsumerApi, id } from '../../shared/consumer-api'
import { decodeOrderDetail, decodeOrderPage, type DisplayOrderStatus, type OrderDetailView, type OrderPage } from './model'

// C-004 前端切片（10号 §3.7 C 端订单读取，只读）。后端同批交付（并行 PR）：路由随
// pet.auth.c.enabled 装配，未开放期间失败关闭（路由未挂载 404 走常规错误路径，coupon-points
// 同语义）。纯身份作用域的 c/ 只读路由：与 coupon-points/order-verify 一样落在 ConsumerApi
// 白名单 /^\/api\/v1\/c\// 内，不改 consumer-api.ts。GET query 仅 displayStatus/page/pageSize
// （§3.7；“全部”桶不发 displayStatus 参数）；详情按路径 orderId 寻址，回执 orderId 必须回显一致。
export function isOrderReadUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 401
}

export class RealOrderReadRepository {
  constructor(private api: ConsumerApi) {}

  list(displayStatus: DisplayOrderStatus | null, page = 1, pageSize = 20): Promise<OrderPage> {
    return this.api.request({ method: 'GET', path: '/api/v1/c/orders',
      data: { page, pageSize, ...(displayStatus === null ? {} : { displayStatus }) } }, decodeOrderPage)
  }

  async detail(orderId: string): Promise<OrderDetailView> {
    const target = id(orderId)
    return this.api.request({ method: 'GET', path: `/api/v1/c/orders/${target}` }, value => {
      const detail = decodeOrderDetail(value)
      if (detail.orderId !== target) throw new Error('INVALID_RESPONSE')
      return detail
    })
  }
}
