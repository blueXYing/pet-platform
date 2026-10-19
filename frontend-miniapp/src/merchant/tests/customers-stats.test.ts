import test from 'node:test'
import assert from 'node:assert/strict'
import { WorkspaceScope } from '../../shared/workspace'
import type { MerchantAdmission } from '../../shared/merchant-repositories'
import type {
  CustomerDetail, CustomerNote, CustomerPage, StatsOverview, StatsRange,
  StatsServiceOrdersPage, StatsServiceRow, StatsUtilization, TrendBucket,
} from '../../shared/merchant-storefront-api'
import {
  CustomerDetailController, MerchantCustomersController, MerchantStatsController,
  ServiceOrdersController, type CustomersState, type StatsState,
} from '../storefront/read-controllers'
import { defaultCustomWindow, lastVisitLabel, validCustomWindow, windowLabel } from '../storefront/model'
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
const note: CustomerNote = { content: '对猫毛过敏，注意戴手套', version: '1', updatedAt: '2026-10-19T08:00:00.000Z' }
const overview: StatsOverview = {
  range: 'LAST_7D', revenue: '26845.00', orderCount: 386, newCustomers: 58,
  avgTicket: '69.50', verifiedOrderCount: 300, verifyRate: '77.7', refundOrderCount: 20,
  refundRate: '5.1', repeatRate: '42.9', reviewAvg: '4.6', rejectRate: '3.2',
  deltas: { revenue: '12.5', orderCount: '8.3', newCustomers: '-3.1', avgTicket: '4.2' },
  generatedAt: '2026-10-19T09:00:00.000Z',
}
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))

function fixture() {
  const scope = new WorkspaceScope(); scope.replace({ userId: '1', workspace: 'merchant', merchantId: '101', storeId: '102' })
  let page: CustomerPage = { page: 1, pageSize: 20, total: 1, items: [copy(alice)] }
  let detail: CustomerDetail = { customer: copy(alice), pets: [copy(alice.topPet!)], note: copy(note) }
  let stats: StatsOverview = copy(overview)
  let top: { range: StatsRange; items: readonly StatsServiceRow[] } = { range: 'LAST_7D', items: [] }
  let trend: { range: StatsRange; buckets: readonly TrendBucket[] } = {
    range: 'LAST_7D', buckets: Array.from({ length: 7 }, (_, index) => ({
      date: `2026-10-${13 + index}`, revenue: '0.00', orderCount: 0 })),
  }
  let utilization: StatsUtilization = {
    range: 'LAST_7D', from: '2026-10-13', to: '2026-10-20',
    availableMinutes: 600, bookedMinutes: 90, utilizationRate: '15.0',
  }
  let searches: Array<{ nickname?: string; orderNoSuffix?: string }> = []
  let detailIds: string[] = [], ranges: StatsRange[] = [], notes: Array<{ userId: string; note: string; version: string }> = []
  let savedNote: { userId: string; note: CustomerNote } = { userId: '201', note: copy(note) }
  const deps: MerchantStorefrontDeps = {
    scope, admission: async () => copy(owner),
    storeProfile: async () => { throw new Error('unused') },
    saveStoreProfile: async () => { throw new Error('unused') },
    retireProfileConflict: () => { throw new Error('unused') },
    uploadStorePhoto: async () => ({ assetId: '88001' }),
    customers: (filter, page2, pageSize) => { searches.push({ ...filter }); return Promise.resolve({ page: page2, pageSize, total: page.total, items: copy(page.items) }) },
    customerDetail: userId => { detailIds.push(userId); return Promise.resolve(copy(detail)) },
    saveCustomerNote: (userId, value, version) => {
      notes.push({ userId, note: value, version })
      savedNote = { userId, note: { content: value === '' ? null : value, version: String(Number(version) + 1), updatedAt: '2026-10-19T09:30:00.000Z' } }
      return Promise.resolve(copy(savedNote))
    },
    retireNoteConflict: () => { throw new Error('unused') },
    statsOverview: (range, custom) => { void custom; ranges.push(range); return Promise.resolve({ ...copy(stats), range }) },
    statsServiceTop: (range, custom) => { void custom; return Promise.resolve({ range, items: [...top.items] }) },
    statsTrend: (range, custom) => { void custom; return Promise.resolve({ range, buckets: [...trend.buckets] }) },
    statsServiceOrders: async (serviceId, range, page2, custom) => {
      void custom
      const result: StatsServiceOrdersPage = {
        serviceId, serviceName: '猫咪洗澡', range, page: page2, pageSize: 20, total: 2,
        items: [
          { orderId: '9152', orderNo: '9152', orderStage: 'COMPLETED', payAmount: '90.00', refundedAmount: '0.00', createdAt: '2026-10-16T10:00:00.000Z', appointmentStartAt: '2026-10-17T09:00:00.000Z', verifiedAt: '2026-10-17T10:00:00.000Z' },
          { orderId: '9151', orderNo: '9151', orderStage: 'COMPLETED', payAmount: '120.00', refundedAmount: '20.00', createdAt: '2026-10-14T09:00:00.000Z', appointmentStartAt: '2026-10-15T09:00:00.000Z', verifiedAt: null },
        ],
      }
      return result
    },
    statsUtilization: (range, custom) => { void custom; return Promise.resolve({ ...copy(utilization), range }) },
  }
  return {
    deps,
    set page(v: CustomerPage) { page = v }, set detail(v: CustomerDetail) { detail = v },
    set stats(v: StatsOverview) { stats = v },
    get searches() { return searches }, get detailIds() { return detailIds }, get ranges() { return ranges },
    get notes() { return notes },
  }
}

const customersReady = (controller: MerchantCustomersController) => new Promise(resolve => {
  const check = () => (controller.getSnapshot() as CustomersState).phase === 'ready' ? resolve(null) : setTimeout(check, 1); check()
})
const statsReady = (controller: MerchantStatsController) => new Promise(resolve => {
  const check = () => (controller.getSnapshot() as StatsState).phase === 'ready' ? resolve(null) : setTimeout(check, 1); check()
})
const detailReady = (controller: CustomerDetailController) => new Promise(resolve => {
  const check = () => (controller.getSnapshot() as CustomersState).phase === 'ready' ? resolve(null) : setTimeout(check, 1); check()
})

test('客户列表：读取派生分页、昵称搜索携带词、详情按 userId 载入', async () => {
  const fx = fixture()
  const controller = new MerchantCustomersController(fx.deps, { merchantId: '101', storeId: '102' })
  await controller.load()
  await customersReady(controller)
  assert.equal((controller.getSnapshot() as CustomersState).total, 1)
  assert.deepEqual(fx.searches[0], {})
  controller.editSearch('李小姐')
  await controller.search()
  await customersReady(controller)
  assert.deepEqual(fx.searches.at(-1), { nickname: '李小姐' })
  await controller.openDetail('201')
  assert.deepEqual(fx.detailIds, ['201'])
  assert.equal((controller.getSnapshot() as CustomersState).detail?.customer.nickname, '李小姐')
  controller.dispose()
})

test('订单尾号搜索（2026-10-19 扩展）：模式切换后仅携带尾号过滤，非法输入被拦截', async () => {
  const fx = fixture()
  const controller = new MerchantCustomersController(fx.deps, { merchantId: '101', storeId: '102' })
  await controller.load()
  await customersReady(controller)
  controller.switchSearchMode('orderNoSuffix')
  assert.equal(controller.getSnapshot().search, '')
  controller.editSearch('9160')
  await controller.search()
  await customersReady(controller)
  assert.deepEqual(fx.searches.at(-1), { orderNoSuffix: '9160' })
  // 非法尾号（1 位/含非数字）不发起请求。
  controller.editSearch('16x')
  await controller.search()
  assert.match(controller.getSnapshot().notice, /2-10 位数字/)
  controller.editSearch('9160')
  // 双过滤被客户端拒绝（尾号模式无昵称），昵称模式回退正常。
  controller.switchSearchMode('nickname')
  controller.editSearch('李')
  await controller.search()
  await customersReady(controller)
  assert.deepEqual(fx.searches.at(-1), { nickname: '李' })
  controller.dispose()
})

test('客户详情备注（2026-10-19 扩展）：读取带入草稿，保存走幂等写并回填版本', async () => {
  const fx = fixture()
  const controller = new CustomerDetailController(fx.deps, { merchantId: '101', storeId: '102' }, '201')
  await controller.load()
  await detailReady(controller)
  assert.equal(controller.getSnapshot().detail?.note?.content, '对猫毛过敏，注意戴手套')
  assert.equal(controller.getSnapshot().noteVersion, '1')
  controller.editNote('改口：需戴手套')
  await controller.saveNote()
  assert.equal(fx.notes.length, 1)
  assert.deepEqual(fx.notes[0], { userId: '201', note: '改口：需戴手套', version: '1' })
  assert.equal(controller.getSnapshot().noteVersion, '2')
  assert.equal(controller.getSnapshot().detail?.note?.version, '2')
  // 空串=清空，照常走写路径。
  controller.editNote('')
  await controller.saveNote()
  assert.equal(fx.notes.at(-1)?.note, '')
  controller.dispose()
})

test('客户详情只读红线：投影无健康字段可渲染（类型层保证），昵称空安全', () => {
  const bare = { ...copy(alice), nickname: null, phoneMasked: null, topPet: null }
  assert.equal(bare.nickname, null)
  assert.equal(lastVisitLabel(null, new Date('2026-10-19T00:00:00Z')), '未曾到店')
  assert.equal(lastVisitLabel('2026-10-19T01:00:00.000Z', new Date('2026-10-19T12:00:00Z')), '今天')
  assert.equal(lastVisitLabel('2026-10-17T01:00:00.000Z', new Date('2026-10-19T12:00:00Z')), '2天前')
})

test('数据统计：默认近7天四读并发（含利用率），切换周期仅追加对应 range', async () => {
  const fx = fixture()
  const controller = new MerchantStatsController(fx.deps, { merchantId: '101', storeId: '102' })
  await controller.load()
  await statsReady(controller)
  const state = controller.getSnapshot() as StatsState
  assert.equal(state.range, 'LAST_7D')
  assert.equal(state.overview?.revenue, '26845.00')
  assert.equal(state.overview?.rejectRate, '3.2')
  assert.equal(state.trend.length, 7)
  assert.equal(state.utilization?.utilizationRate, '15.0')
  await controller.selectRange('TODAY')
  assert.deepEqual(fx.ranges, ['LAST_7D', 'TODAY'])
  assert.equal((controller.getSnapshot() as StatsState).utilization?.range, 'TODAY')
  controller.dispose()
})

test('自定义区间（2026-10-19 扩展）：92 天护栏与区间标签', async () => {
  assert.equal(validCustomWindow('2026-09-01', '2026-09-30'), true)
  assert.equal(validCustomWindow('2026-01-01', '2026-04-03'), false) // 93 天
  assert.equal(validCustomWindow('2026-10-02', '2026-10-01'), false)
  assert.equal(validCustomWindow('2026-9-1', '2026-09-30'), false)
  assert.equal(windowLabel('CUSTOM', '2026-09-01', '2026-09-30'), '2026-09-01 ~ 2026-09-30')
  assert.equal(windowLabel('LAST_7D'), '近 7 天')
  const win = defaultCustomWindow(new Date('2026-10-19T00:00:00Z'))
  assert.equal(win.to, '2026-10-19')
  assert.equal(win.from, '2026-09-20')
  // CUSTOM 选择在护栏内生效并下发 from/to。
  const fx = fixture()
  const controller = new MerchantStatsController(fx.deps, { merchantId: '101', storeId: '102' })
  await controller.load()
  await statsReady(controller)
  controller.editCustomWindow('2026-09-01', '2026-09-30')
  await controller.selectRange('CUSTOM')
  assert.equal((controller.getSnapshot() as StatsState).range, 'CUSTOM')
  // 超护栏的直接拒绝，不发请求。
  controller.editCustomWindow('2026-01-01', '2026-04-03')
  await controller.selectRange('LAST_30D')
  await controller.applyCustomWindow()
  assert.match((controller.getSnapshot() as StatsState).notice, /92 天/)
  controller.dispose()
})

test('服务下钻（2026-10-19 扩展）：分页只读行，首次载入即就绪', async () => {
  const fx = fixture()
  const controller = new ServiceOrdersController(fx.deps, { merchantId: '101', storeId: '102' }, '501', 'LAST_7D')
  assert.equal(controller.getSnapshot().phase, 'idle')
  await controller.load()
  assert.equal(controller.getSnapshot().phase, 'ready')
  assert.equal(controller.getSnapshot().page?.serviceName, '猫咪洗澡')
  assert.equal(controller.getSnapshot().page?.items.length, 2)
  assert.equal(controller.getSnapshot().page?.items[0].refundedAmount, '0.00')
  void fx
  controller.dispose()
})
