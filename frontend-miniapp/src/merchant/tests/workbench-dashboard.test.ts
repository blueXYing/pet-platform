import assert from 'node:assert/strict'
import test from 'node:test'
import { MerchantWorkbenchDashboard, type WorkbenchDashboardDeps } from '../workbench/dashboard'
import type { StoreProfileView, StatsOverview } from '../../shared/merchant-storefront-api'
import type { MerchantOrderPage, MerchantOrderSummary } from '../order/model'
import type { RefundApplicationPage } from '../../shared/merchant-refund-api'

const order: MerchantOrderSummary = {
  orderId: '9001', orderNo: '202611040001', displayStatus: 'PENDING_CONFIRM',
  payAmount: '128.00', appointmentStart: '2026-11-04T02:30:00.000Z',
  appointmentEnd: '2026-11-04T03:30:00.000Z', paidAt: '2026-11-04T01:00:00.000Z',
}
const profile = {
  merchantId: '1', storeId: '2',
  readonlyFacts: {
    storeName: '小李宠物店', address: '测试路 1 号', cityCode: '310100', phoneMasked: '138****0000',
    longitude: '121.0', latitude: '31.0', merchantStatus: 'ACTIVE', storeStatus: 'ACTIVE',
    operatingStatus: 'OPERATING', applicationStatus: 'APPROVED',
  },
  profile: {
    intro: null, announcement: null, photoAssetIds: [], businessHours: [], promoLevel: null,
    promoTitle: null, promoText: null, avatarAssetId: null, businessStatus: 'OPEN', version: '1',
  },
} as StoreProfileView
const stats = {
  range: 'TODAY', revenue: '3846.00', orderCount: 42, newCustomers: 2, avgTicket: '91.57',
  verifiedOrderCount: 10, verifyRate: '80.0', refundOrderCount: 1, refundRate: '2.0',
  repeatRate: '10.0', reviewAvg: '4.8', rejectRate: '1.0',
  deltas: { revenue: '12.5', orderCount: '3.2', newCustomers: null, avgTicket: '1.1' },
  generatedAt: '2026-11-04T02:00:00.000Z',
} as StatsOverview
const confirmPage: MerchantOrderPage = { items: [order], page: 1, pageSize: 20, total: 8 }
const servicePage: MerchantOrderPage = { items: [], page: 1, pageSize: 20, total: 5 }
const refundPage = { page: 1, pageSize: 20, total: 1, items: [] } as unknown as RefundApplicationPage

function deps(overrides: Partial<WorkbenchDashboardDeps> = {}): WorkbenchDashboardDeps {
  return {
    profile: async () => profile,
    stats: async () => stats,
    orders: async status => status === 'PENDING_CONFIRM' ? confirmPage : servicePage,
    refunds: async () => refundPage,
    ...overrides,
  }
}

test('dashboard aggregates the five read routes into the workbench state', async () => {
  const dashboard = new MerchantWorkbenchDashboard(deps())
  await dashboard.load()
  const state = dashboard.getSnapshot()
  assert.equal(state.phase, 'ready')
  assert.equal(state.notice, '')
  assert.equal(state.storeName, '小李宠物店')
  assert.equal(state.storeInitial, '小')
  assert.equal(state.businessStatus, 'OPEN')
  assert.equal(state.revenue, '3846.00')
  assert.equal(state.revenueDelta, '12.5')
  assert.equal(state.todayOrders, 42)
  assert.equal(state.pendingConfirm, 8)
  assert.equal(state.pendingService, 5)
  assert.equal(state.refunds, 1)
  assert.equal(state.pending.length, 1)
  assert.equal(state.pending[0]?.orderId, '9001')
  assert.equal(state.profileReady, true)
  assert.equal(state.statsReady, true)
  assert.equal(state.ordersReady, true)
  assert.equal(state.refundsReady, true)
})

test('dashboard degrades a failed route without failing the page', async () => {
  const dashboard = new MerchantWorkbenchDashboard(deps({ stats: async () => { throw new Error('503') } }))
  await dashboard.load()
  const state = dashboard.getSnapshot()
  assert.equal(state.phase, 'ready')
  assert.equal(state.statsReady, false)
  assert.equal(state.revenue, null)
  assert.equal(state.todayOrders, null)
  assert.equal(state.pendingConfirm, 8)
  assert.match(state.notice, /部分经营数据暂不可用/)
})

test('dashboard reports an error only when every attempted route fails', async () => {
  const fail = async () => { throw new Error('503') }
  const dashboard = new MerchantWorkbenchDashboard({
    profile: fail, stats: fail, orders: fail, refunds: fail,
  })
  await dashboard.load()
  const state = dashboard.getSnapshot()
  assert.equal(state.phase, 'error')
  assert.match(state.notice, /经营数据读取失败/)
})

test('dashboard skip sentinel is not counted as a failure', async () => {
  const skip = async () => { throw new Error('DASHBOARD_SKIPPED') }
  const dashboard = new MerchantWorkbenchDashboard({ profile: skip, stats: skip, orders: skip, refunds: skip })
  await dashboard.load()
  const state = dashboard.getSnapshot()
  assert.equal(state.phase, 'ready')
  assert.equal(state.notice, '')
  assert.equal(state.profileReady, false)
  assert.equal(state.pending.length, 0)
})

test('dashboard ignores a late result after hide, and reloads after show', async () => {
  let release: (value: StatsOverview) => void = () => {}
  const slowStats = new Promise<StatsOverview>(resolve => { release = resolve })
  const dashboard = new MerchantWorkbenchDashboard(deps({ stats: async () => slowStats }))
  const loading = dashboard.load()
  dashboard.hide()
  release(stats)
  await loading
  assert.equal(dashboard.getSnapshot().phase, 'idle')
  dashboard.show()
  await dashboard.load()
  assert.equal(dashboard.getSnapshot().phase, 'ready')
  assert.equal(dashboard.getSnapshot().revenue, '3846.00')
})
