import { ApiError } from '../../shared/request'
import { ConsumerApi, id } from '../../shared/consumer-api'
import {
  decodeFavoriteAddReceipt, decodeFavoriteCancelReceipt, decodeFavoritePage, decodeFavoriteStatus,
  decodeFootprintDeleteReceipt, decodeFootprintPage, decodeFootprintRecordReceipt,
  type FavoritePage, type FavoriteStatusView, type FavoriteToggleReceipt, type FootprintPage,
  type FootprintRecordReceipt, type TrackTargetType,
} from './model'

// 纯身份作用域的 c/ 路由（当前会话用户即属主，无 merchantId/storeId 坐标）：读走
// ConsumerApi.request；写命令（加/取消收藏、上报/删除足迹）无业务体、requestId 终态 UUID
// 幂等，走 ConsumerApi.write 日志槽位——显式重试沿用同一 X-Request-Id，服务端 23号 §5
// 重放首回执；网络结果未知时槽位保留，下次同一操作按缺口续传，不二次铸造请求号。
// 会话失效清账时槽位随之丢弃：收藏/足迹命令服务端自防重（业务重复塌缩/缺席成功），
// 换新请求号重试不产生第二行。
export function isFavoriteFootprintUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 401
}
export function isTrackTargetNotFound(error: unknown): boolean {
  return error instanceof ApiError && (error.code === 'SERVICE_NOT_FOUND' || error.code === 'STORE_NOT_FOUND')
}

/**
 * Real wiring for the Contract 58 routes (docs/04-api/58 §路由; 10号 §3.16, switch
 * pet.favtrack.http.enabled default OFF): favorites add/cancel/list/status plus footprint
 * record/list/delete. Reads fail closed until the platform enables the switch; writes
 * surface the server's catalog admission 404s verbatim (anti-enumeration).
 */
export class RealFavoriteFootprintRepository {
  constructor(private api: ConsumerApi) {}

  listFavorites(targetType: TrackTargetType | null, page = 1, pageSize = 20): Promise<FavoritePage> {
    const data: Record<string, unknown> = { page, pageSize }
    if (targetType !== null) data.targetType = targetType
    return this.api.request({ method: 'GET', path: '/api/v1/c/favorites', data }, decodeFavoritePage)
  }

  favoriteStatus(targetType: TrackTargetType, targetId: string): Promise<FavoriteStatusView> {
    id(targetId)
    return this.api.request(
      { method: 'GET', path: `/api/v1/c/favorites/${targetType}/${targetId}` },
      decodeFavoriteStatus,
    )
  }

  addFavorite(targetType: TrackTargetType, targetId: string): Promise<FavoriteToggleReceipt> {
    id(targetId)
    return this.api.write(`favorite-add:${targetType}:${targetId}`,
      { method: 'POST', path: `/api/v1/c/favorites/${targetType}/${targetId}` },
      decodeFavoriteAddReceipt)
  }

  cancelFavorite(targetType: TrackTargetType, targetId: string): Promise<{ removed: boolean }> {
    id(targetId)
    return this.api.write(`favorite-cancel:${targetType}:${targetId}`,
      { method: 'DELETE', path: `/api/v1/c/favorites/${targetType}/${targetId}` },
      decodeFavoriteCancelReceipt)
  }

  listFootprints(page = 1, pageSize = 20): Promise<FootprintPage> {
    return this.api.request({ method: 'GET', path: '/api/v1/c/footprints', data: { page, pageSize } }, decodeFootprintPage)
  }

  recordFootprint(targetType: TrackTargetType, targetId: string): Promise<FootprintRecordReceipt> {
    id(targetId)
    return this.api.write(`footprint-record:${targetType}:${targetId}`,
      { method: 'POST', path: `/api/v1/c/footprints/${targetType}/${targetId}` },
      decodeFootprintRecordReceipt)
  }

  deleteFootprint(footprintId: string): Promise<{ deleted: boolean }> {
    id(footprintId)
    return this.api.write(`footprint-delete:${footprintId}`,
      { method: 'DELETE', path: `/api/v1/c/footprints/${footprintId}` },
      decodeFootprintDeleteReceipt)
  }
}
