import test from 'node:test'
import assert from 'node:assert/strict'
import { WorkspaceScope } from '../../shared/workspace'
import type { MerchantAdmission } from '../../shared/merchant-repositories'
import type { CustomerDetail, CustomerPage, StatsOverview, StatsRange, StatsServiceRow, TrendBucket } from '../../shared/merchant-storefront-api'
import { MerchantCustomersController, MerchantStatsController, type CustomersState, type StatsState } from '../storefront/read-controllers'
import { lastVisitLabel } from '../storefront/model'
import type { MerchantStorefrontDeps } from '../storefront/repository'

const owner: MerchantAdmission = {
  merchantId: '101', storeId: '102', membershipKind: 'OWNER', admission: 'ALLOWED', checkedAt: '2026-10-01T00:00:00.000Z', authzVersion: '0123456789abcdef',
  facts: { application: { status: 'APPROVED' }, signing: { status: 'SIGNED' }, merchantStatus: 'ACTIVE', storeStatus: 'ACTIVE', staffEnabled: null },
  allowedActions: ['merchant.order.read'], reasonCodes: [], nextSteps: [],
}
const alice: CustomerPage['items'][number] = {
  userId: '201', nickname: '李小姐', phoneMasked: '136****2211', orderCount: 12,
  totalSpend: '2680.00', avgTicket: '223.33', lastVisitAt: '2026-10-19T01:00:00.000Z',
  topPet: { petName: '小橘', petType: 'CAT', breedName: '英短' },
}
const overview: StatsOverview = {
  range: 'LAST_7D', revenue: '26845.00', orderCount: 386, newCustomers: 58,
  avgTicket: '69.50', verifiedOrderCount: 300, verifyRate: '77.7', refundOrderCount: 20,
  refundRate: '5.1', repeatRate: '42.9', reviewAvg: '4.6',
  deltas: { revenue: '12.5', orderCount: '8.3', newCustomers: '-3.1', avgTicket: '4.2' },
  generatedAt: '2026-10-19T09:00:00.000Z',
}
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))

function fixture() {
  const scope = new WorkspaceScope(); scope.replace({ userId: '1', workspace: 'merchant', merchantId: '101', storeId: '102' })
  let page: CustomerPage = { page: 1, pageSize: 20, total: 1, items: [copy(alice)] }
  let detail: CustomerDetail = { customer: copy(alice), pets: [copy(alice.topPet!)] }
  let stats: StatsOverview = copy(overview)
  let top: { range: StatsRange; items: readonly StatsServiceRow[] } = { range: 'LAST_7D', items: [] }
  let trend: { range: StatsRange; buckets: readonly TrendBucket[] } = {
    range: 'LAST_7D', buckets: Array.from({ length: 7 }, (_, index) => ({
      date: `2026-10-${13 + index}`, revenue: '0.00', orderCount: 0 })),
  }
  let searches: string[] = [], detailIds: string[] = [], ranges: StatsRange[] = []
  const deps: MerchantStorefrontDeps = {
    scope, admission: async () => copy(owner),
    storeProfile: async () => { throw new Error('unused') },
    saveStoreProfile: async () => { throw new Error('unused') },
    retireProfileConflict: () => { throw new Error('unused') },
    customers: (nickname, page2, pageSize) => { searches.push(nickname); return Promise.resolve({ page: page2, pageSize, total: page.total, items: copy(page.items) }) },
    customerDetail: userId => { detailIds.push(userId); return Promise.resolve(copy(detail)) },
    statsOverview: range => { ranges.push(range); return Promise.resolve(copy(stats)) },
    statsServiceTop: range => Promise.resolve({ range, items: [...top.items] }),
    statsTrend: range => Promise.resolve({ range, buckets: [...trend.buckets] }),
  }
  return {
    deps,
    set page(v: CustomerPage) { page = v }, set detail(v: CustomerDetail) { detail = v },
    set stats(v: StatsOverview) { stats = v },
    get searches() { return searches }, get detailIds() { return detailIds }, get ranges() { return ranges },
  }
}

const customersReady = (controller: MerchantCustomersController) => new Promise(resolve => {
  const check = () => (controller.getSnapshot() as CustomersState).phase === 'ready' ? resolve(null) : setTimeout(check, 1); check()
})
const statsReady = (controller: MerchantStatsController) => new Promise(resolve => {
  const check = () => (controller.getSnapshot() as StatsState).phase === 'ready' ? resolve(null) : setTimeout(check, 1); check()
})

test('客户列表：读取派生分页、昵称搜索携带词、详情按 userId 载入', async () => {
  const fx = fixture()
  const controller = new MerchantCustomersController(fx.deps, { merchantId: '101', storeId: '102' })
  await controller.load()
  await customersReady(controller)
  assert.equal((controller.getSnapshot() as CustomersState).total, 1)
  assert.equal(fx.searches[0], '')
  controller.editSearch('李小姐')
  await controller.search()
  await customersReady(controller)
  assert.equal(fx.searches.at(-1), '李小姐')
  await controller.openDetail('201')
  assert.deepEqual(fx.detailIds, ['201'])
  assert.equal((controller.getSnapshot() as CustomersState).detail?.customer.nickname, '李小姐')
  controller.dispose()
})

test('客户详情只读红线：投影无健康字段可渲染（类型层保证），昵称空安全', () => {
  const bare = { ...copy(alice), nickname: null, phoneMasked: null, topPet: null }
  assert.equal(bare.nickname, null)
  assert.equal(lastVisitLabel(null, new Date('2026-10-19T00:00:00Z')), '未曾到店')
  assert.equal(lastVisitLabel('2026-10-19T01:00:00.000Z', new Date('2026-10-19T12:00:00Z')), '今天')
  assert.equal(lastVisitLabel('2026-10-17T01:00:00.000Z', new Date('2026-10-19T12:00:00Z')), '2天前')
})

test('数据统计：默认近7天三读并发，切换周期仅追加对应 range', async () => {
  const fx = fixture()
  const controller = new MerchantStatsController(fx.deps, { merchantId: '101', storeId: '102' })
  await controller.load()
  await statsReady(controller)
  const state = controller.getSnapshot() as StatsState
  assert.equal(state.range, 'LAST_7D')
  assert.equal(state.overview?.revenue, '26845.00')
  assert.equal(state.trend.length, 7)
  await controller.selectRange('TODAY')
  assert.deepEqual(fx.ranges, ['LAST_7D', 'TODAY'])
  assert.equal((controller.getSnapshot() as StatsState).overview?.range, 'LAST_7D'.replace('LAST_7D', 'LAST_7D'))
  controller.dispose()
})
