// 工作台/M 端「我的」视觉验收用的内部夹具（?preview=1）。只用于开发者工具截图，
// 不暴露为公共 DTO、不参与真实鉴权、不写任何业务数据。数字对齐 Figma 95:1899 主稿。

import type { WorkbenchDashboardDeps } from './dashboard'
import type { StatsOverview, StoreProfileView } from '../../shared/merchant-storefront-api'
import type { MerchantOrderPage, MerchantOrderSummary } from '../order/model'
import type { RefundApplicationPage } from '../../shared/merchant-refund-api'

const iso = (offsetMinutes: number) => new Date(Date.now() + offsetMinutes * 60_000).toISOString()
const summary = (id: string, orderNo: string, amount: string, minutes: number): MerchantOrderSummary => ({
  orderId: id,
  orderNo,
  displayStatus: 'PENDING_CONFIRM',
  payAmount: amount,
  appointmentStart: iso(minutes),
  appointmentEnd: iso(minutes + 60),
  paidAt: iso(-5),
})

const profile: StoreProfileView = {
  merchantId: '9007199254740995',
  storeId: '9007199254740996',
  readonlyFacts: {
    storeName: '小李宠物店', address: '测试路 1 号', cityCode: '310100', phoneMasked: '138****0000',
    longitude: '121.0', latitude: '31.0', merchantStatus: 'ACTIVE', storeStatus: 'ACTIVE',
    operatingStatus: 'OPERATING', applicationStatus: 'APPROVED',
  },
  profile: {
    intro: null, announcement: null, photoAssetIds: [], businessHours: [], promoLevel: null,
    promoTitle: null, promoText: null, avatarAssetId: null, businessStatus: 'OPEN', version: '1',
  },
}
const stats: StatsOverview = {
  range: 'TODAY', revenue: '3846.00', orderCount: 42, newCustomers: 2, avgTicket: '91.57',
  verifiedOrderCount: 10, verifyRate: '80.0', refundOrderCount: 1, refundRate: '2.0',
  repeatRate: '10.0', reviewAvg: '4.8', rejectRate: '1.0',
  deltas: { revenue: '12.5', orderCount: '3.2', newCustomers: null, avgTicket: '1.1' },
  generatedAt: new Date().toISOString(),
}
const pending: MerchantOrderPage = {
  items: [summary('9101', '202611040001', '268.00', 15), summary('9102', '202611040002', '450.00', 30)],
  page: 1, pageSize: 20, total: 8,
}
const service: MerchantOrderPage = { items: [], page: 1, pageSize: 20, total: 5 }
const refunds = { page: 1, pageSize: 20, total: 1, items: [] } as unknown as RefundApplicationPage

export function previewStoreProfile(): StoreProfileView {
  return profile
}

export function previewDashboardDeps(): WorkbenchDashboardDeps {
  return {
    profile: async () => profile,
    stats: async () => stats,
    orders: async status => status === 'PENDING_CONFIRM' ? pending : service,
    refunds: async () => refunds,
  }
}
