import { ApiError } from '../../shared/request'
import { ConsumerApi, id } from '../../shared/consumer-api'
import {
  decodeCoupon, decodeCouponPage, decodeLedgerPage, decodePointsBalance,
  type CouponPage, type CouponStatus, type CouponView,
  type PointsBalanceView, type PointsLedgerPage,
} from './model'

// 纯身份作用域的 c/ 只读路由（当前会话用户即属主，无 merchantId/storeId 坐标）：与 profile/
// pets/messages 一样走 ConsumerApi.request 的 consumer 工作区校验，四条路径均落在既有
// /^\/api\/v1\/c\// 白名单内，不改 consumer-api.ts。
export function isCouponPointsUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 401
}
export function isCouponNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 404
}

/**
 * Real wiring for the four CCR-C006 P1 read routes delivered by backend PR#112
 * (docs/04-api/10 §3.15.1/§3.15.2): my coupons three-bucket paging (server-side status
 * filter), single own-coupon projection, points balance and points ledger. Reads only —
 * no issuance, redeem, earn or exchange path exists in V1. The routes assemble with
 * pet.auth.c.enabled (IMPLEMENTED_DEFAULT_OFF): until the platform enables the switch the
 * requests fail closed through the normal error paths (404 route absent here, like any
 * other not-yet-mounted family).
 */
export class RealCouponPointsRepository {
  constructor(private api: ConsumerApi) {}

  listCoupons(status: CouponStatus, page = 1, pageSize = 20): Promise<CouponPage> {
    return this.api.request({ method: 'GET', path: '/api/v1/c/coupons', data: { status, page, pageSize } }, decodeCouponPage)
  }

  async coupon(couponId: string): Promise<CouponView> {
    // The server answers an unparsable id with the same uniform 404 as a foreign/absent
    // one (anti-enumeration); mirror that here instead of a decoder INVALID_RESPONSE.
    try { id(couponId) } catch { throw new ApiError('COMMON_NOT_FOUND', 404) }
    return this.api.request({ method: 'GET', path: `/api/v1/c/coupons/${couponId}` }, value => {
      const coupon = decodeCoupon(value)
      if (coupon.couponId !== couponId) throw new Error('INVALID_RESPONSE')
      return coupon
    })
  }

  balance(): Promise<PointsBalanceView> {
    return this.api.request({ method: 'GET', path: '/api/v1/c/points/balance' }, decodePointsBalance)
  }

  ledger(page = 1, pageSize = 20): Promise<PointsLedgerPage> {
    return this.api.request({ method: 'GET', path: '/api/v1/c/points/ledger', data: { page, pageSize } }, decodeLedgerPage)
  }
}
