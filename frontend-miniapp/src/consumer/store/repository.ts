import type { ConsumerApi } from '../../shared/consumer-api'
import type { WorkspaceScope } from '../../shared/workspace'
import { decodeCityList, decodeStore, decodeStorePage, type StoreCity, type StoreDetailView, type StoreDirectoryDeps, type StoreListQuery, type StorePage } from './model'

function isStatus(error: unknown, statusCode: number): boolean {
  return typeof error === 'object' && error !== null && 'statusCode' in error && (error as { statusCode?: number }).statusCode === statusCode
}
export function isStoreNotFound(error: unknown): boolean {
  return isStatus(error, 404) || (error instanceof Error && error.message === 'STORE_NOT_FOUND')
}

/**
 * STR-D8 catalog reads are anonymous-browsable, so the directory must not require a
 * workspace context: with a session the scope still guards stale context switches via
 * run(); logged out (no context) the read runs bare and anonymousRequest keeps its own
 * optional-context guard. Without this the page-level scope.run would throw NO_CONTEXT
 * before any wire traffic and the designed anonymous browsing could never happen.
 */
export function runCatalogRead<T>(scope: WorkspaceScope, operation: () => Promise<T>): Promise<T> {
  return scope.current ? scope.run(undefined, operation) : operation()
}

/**
 * Real wiring for GET /api/v1/c/stores and /api/v1/c/stores/{storeId} (role B implementing).
 * Reads are anonymous per the 2026-09-22 adjudication, so they go through
 * ConsumerApi.anonymousRequest and fail closed until the backend ships. The open-city catalog
 * defaults to the anonymous §3.3.4 /c/cities directory (2026-10-19 city-selection ruling; the
 * same boot-side trusted catalog the city filter bounds); callers may still inject the
 * authenticated /c/merchant-application-cities directory where a session exists.
 */
export class RealStoreRepository implements StoreDirectoryDeps {
  constructor(private api: ConsumerApi, private cityCatalog?: () => Promise<StoreCity[]>) {}
  cities() {
    // §3.3.4 匿名开放城市目录优先（匿名用户也可选城市）；注入的认证目录（入驻路由）作回退。
    // 任一目录读取失败时页面保留默认（省略 city = 全部开放城市），匿名列表仍可渲染。
    return this.cityCatalog
      ? this.cityCatalog()
      : this.api.anonymousRequest({ method: 'GET', path: '/api/v1/c/cities' }, decodeCityList)
  }
  list({ city, keyword, categoryCode, page = 1, pageSize = 20 }: StoreListQuery): Promise<StorePage> {
    const data: Record<string, unknown> = { page, pageSize }
    if (city !== undefined) data.city = city
    // HTTP10 §3.3.2 搜索增补（2026-10-19）：keyword 收窄门店目录（服务名匹配走 /c/services §3.3.3）。
    if (keyword !== undefined) data.keyword = keyword
    // 74 §3.2（2026-10-30）：categoryCode 精确筛选，与 city/keyword 按 AND 组合；响应字段不变。
    if (categoryCode !== undefined) data.categoryCode = categoryCode
    return this.api.anonymousRequest({ method: 'GET', path: '/api/v1/c/stores', data }, decodeStorePage)
  }
  detail(storeId: string): Promise<StoreDetailView> {
    return this.api.anonymousRequest({ method: 'GET', path: `/api/v1/c/stores/${storeId}` }, value => {
      const store = decodeStore(value)
      if (store.storeId !== storeId) throw new Error('INVALID_RESPONSE')
      return store
    })
  }
}
