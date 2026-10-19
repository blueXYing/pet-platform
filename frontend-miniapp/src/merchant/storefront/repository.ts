// M 端收尾三页的依赖装配：唯一替换点（真实 ConsumerApi 实现；测试用假 deps 注入）。
// 铁律与既有切片一致：客户端只承载投影与草稿，不授予任何业务权威。
// 2026-10-19 扩展：店铺照片/头像上传（STORE_PHOTO 管线）、客户内部备注写、
// 统计自定义区间/服务下钻/排期利用率。

import { MerchantAdmissionRepository, type MerchantAdmission } from '../../shared/merchant-repositories'
import type { ConsumerApi, LocalStore } from '../../shared/consumer-api'
import {
  MerchantCustomersClient, MerchantCustomerNoteClient, MerchantStatsClient, StoreProfileClient,
  type CustomerDetail, type CustomerNote, type CustomerPage, type StatsOverview,
  type StatsRange, type StatsServiceOrdersPage, type StatsUtilization, type StatsWindow,
  type StoreProfileInput, type StoreProfileView,
  type StatsServiceRow, type TrendBucket,
} from '../../shared/merchant-storefront-api'

export type MerchantStorefrontDeps = {
  scope: ConsumerApi['scope']
  admission(merchantId: string, storeId: string): Promise<MerchantAdmission>
  storeProfile(): Promise<StoreProfileView>
  saveStoreProfile(input: StoreProfileInput): Promise<StoreProfileView>
  retireProfileConflict(error: unknown): void
  uploadStorePhoto(filePath: string): Promise<{ assetId: string }>
  customers(filter: { nickname?: string; orderNoSuffix?: string }, page: number,
    pageSize: number): Promise<CustomerPage>
  customerDetail(userId: string): Promise<CustomerDetail>
  saveCustomerNote(userId: string, note: string, expectedVersion: string):
    Promise<{ userId: string; note: CustomerNote }>
  retireNoteConflict(error: unknown): void
  statsOverview(range: StatsRange, custom?: StatsWindow): Promise<StatsOverview>
  statsServiceTop(range: StatsRange, custom?: StatsWindow): Promise<{ range: StatsRange; items: readonly StatsServiceRow[] }>
  statsTrend(range: StatsRange, custom?: StatsWindow): Promise<{ range: StatsRange; buckets: readonly TrendBucket[] }>
  statsServiceOrders(serviceId: string, range: StatsRange, page: number, custom?: StatsWindow):
    Promise<StatsServiceOrdersPage>
  statsUtilization(range: StatsRange, custom?: StatsWindow): Promise<StatsUtilization>
}

export function merchantStorefrontDeps(api: ConsumerApi, _store: LocalStore): MerchantStorefrontDeps {
  if (api.scope.current?.workspace !== 'merchant') throw new Error('MERCHANT_ENTRY_REQUIRED')
  const admission = new MerchantAdmissionRepository(api)
  const profile = new StoreProfileClient(api)
  const customers = new MerchantCustomersClient(api)
  const notes = new MerchantCustomerNoteClient(api)
  const stats = new MerchantStatsClient(api)
  return {
    scope: api.scope,
    admission: (merchantId, storeId) => admission.admission(merchantId, storeId),
    storeProfile: () => profile.get(),
    saveStoreProfile: input => profile.save(input),
    retireProfileConflict: error => profile.retireConflict(error),
    uploadStorePhoto: filePath => api.uploadStorePhotoAsset(filePath),
    customers: (filter, page, pageSize) => customers.list(filter, page, pageSize),
    customerDetail: userId => customers.detail(userId),
    saveCustomerNote: (userId, note, expectedVersion) => notes.save(userId, note, expectedVersion),
    retireNoteConflict: error => notes.retireConflict(error),
    statsOverview: (range, custom) => stats.overview(range, custom),
    statsServiceTop: (range, custom) => stats.serviceTop(range, custom),
    statsTrend: (range, custom) => stats.revenueTrend(range, custom),
    statsServiceOrders: (serviceId, range, page, custom) => stats.serviceOrders(serviceId, range, page, custom),
    statsUtilization: (range, custom) => stats.scheduleUtilization(range, custom),
  }
}
