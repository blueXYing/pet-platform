// M 端收尾三页的依赖装配：唯一替换点（真实 ConsumerApi 实现；测试用假 deps 注入）。
// 铁律与既有切片一致：客户端只承载投影与草稿，不授予任何业务权威。

import { MerchantAdmissionRepository, type MerchantAdmission } from '../../shared/merchant-repositories'
import type { ConsumerApi, LocalStore } from '../../shared/consumer-api'
import {
  MerchantCustomersClient, MerchantStatsClient, StoreProfileClient,
  type CustomerDetail, type CustomerPage, type StatsOverview,
  type StatsRange, type StoreProfileInput, type StoreProfileView,
  type StatsServiceRow, type TrendBucket,
} from '../../shared/merchant-storefront-api'

export type MerchantStorefrontDeps = {
  scope: ConsumerApi['scope']
  admission(merchantId: string, storeId: string): Promise<MerchantAdmission>
  storeProfile(): Promise<StoreProfileView>
  saveStoreProfile(input: StoreProfileInput): Promise<StoreProfileView>
  retireProfileConflict(error: unknown): void
  customers(nickname: string, page: number, pageSize: number): Promise<CustomerPage>
  customerDetail(userId: string): Promise<CustomerDetail>
  statsOverview(range: StatsRange): Promise<StatsOverview>
  statsServiceTop(range: StatsRange): Promise<{ range: StatsRange; items: readonly StatsServiceRow[] }>
  statsTrend(range: StatsRange): Promise<{ range: StatsRange; buckets: readonly TrendBucket[] }>
}

export function merchantStorefrontDeps(api: ConsumerApi, _store: LocalStore): MerchantStorefrontDeps {
  if (api.scope.current?.workspace !== 'merchant') throw new Error('MERCHANT_ENTRY_REQUIRED')
  const admission = new MerchantAdmissionRepository(api)
  const profile = new StoreProfileClient(api)
  const customers = new MerchantCustomersClient(api)
  const stats = new MerchantStatsClient(api)
  return {
    scope: api.scope,
    admission: (merchantId, storeId) => admission.admission(merchantId, storeId),
    storeProfile: () => profile.get(),
    saveStoreProfile: input => profile.save(input),
    retireProfileConflict: error => profile.retireConflict(error),
    customers: (nickname, page, pageSize) => customers.list(nickname, page, pageSize),
    customerDetail: userId => customers.detail(userId),
    statsOverview: range => stats.overview(range),
    statsServiceTop: range => stats.serviceTop(range),
    statsTrend: range => stats.revenueTrend(range),
  }
}
