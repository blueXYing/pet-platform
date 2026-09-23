import type { ConsumerApi } from '../../shared/consumer-api'
import { decodeStore, decodeStorePage, type StoreCity, type StoreDetailView, type StoreDirectoryDeps, type StoreListQuery, type StorePage } from './model'

function isStatus(error: unknown, statusCode: number): boolean {
  return typeof error === 'object' && error !== null && 'statusCode' in error && (error as { statusCode?: number }).statusCode === statusCode
}
export function isStoreNotFound(error: unknown): boolean {
  return isStatus(error, 404) || (error instanceof Error && error.message === 'STORE_NOT_FOUND')
}

/**
 * Real wiring for GET /api/v1/c/stores and /api/v1/c/stores/{storeId} (role B implementing).
 * Reads are anonymous per the 2026-09-22 adjudication, so they go through
 * ConsumerApi.anonymousRequest and fail closed until the backend ships. The open-city catalog
 * is injected (pages pass the approved /c/merchant-application-cities directory — the store
 * proposal scopes the city parameter to that same boot-side catalog, STR-D3).
 */
export class RealStoreRepository implements StoreDirectoryDeps {
  constructor(private api: ConsumerApi, private cityCatalog: () => Promise<StoreCity[]>) {}
  cities() {
    // The catalog route stays an authenticated consumer read; if it 401s the page keeps the
    // default (omit city = all open cities), so the anonymous listing still renders.
    return this.cityCatalog()
  }
  list({ city, page = 1, pageSize = 20 }: StoreListQuery): Promise<StorePage> {
    const data: Record<string, unknown> = { page, pageSize }
    if (city !== undefined) data.city = city
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
