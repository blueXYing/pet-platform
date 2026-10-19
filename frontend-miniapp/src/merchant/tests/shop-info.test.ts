import test from 'node:test'
import assert from 'node:assert/strict'
import { WorkspaceScope } from '../../shared/workspace'
import type { MerchantAdmission } from '../../shared/merchant-repositories'
import type { StoreProfileView } from '../../shared/merchant-storefront-api'
import { ShopInfoController, type ShopInfoState } from '../storefront/shop-info-controller'
import type { MerchantStorefrontDeps } from '../storefront/repository'
import { ownerAccess } from '../storefront/model'

const owner: MerchantAdmission = {
  merchantId: '101', storeId: '102', membershipKind: 'OWNER', admission: 'ALLOWED', checkedAt: '2026-10-01T00:00:00.000Z', authzVersion: '0123456789abcdef',
  facts: { application: { status: 'APPROVED' }, signing: { status: 'SIGNED' }, merchantStatus: 'ACTIVE', storeStatus: 'ACTIVE', staffEnabled: null },
  allowedActions: ['merchant.order.read'], reasonCodes: [], nextSteps: [],
}
const limited: MerchantAdmission = { ...owner, admission: 'LIMITED', facts: { ...owner.facts, storeStatus: 'OFFLINE' } }

const view: StoreProfileView = {
  merchantId: '101', storeId: '102',
  readonlyFacts: {
    storeName: '小李宠物店', address: '张江路 88 号', cityCode: '310115', phoneMasked: '138****8000',
    longitude: '121.4737', latitude: '31.2304', merchantStatus: 'ACTIVE', storeStatus: 'ACTIVE',
    operatingStatus: 'OPERATING', applicationStatus: 'APPROVED',
  },
  profile: {
    intro: null, announcement: null, photoAssetIds: [], businessHours: [],
    promoLevel: null, promoTitle: null, promoText: null,
    avatarAssetId: null, businessStatus: 'OPEN', version: '0',
  },
}
const saved: StoreProfileView = { ...view, profile: { ...view.profile, intro: '专注宠物洗护', version: '1' } }
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))

function fixture() {
  const scope = new WorkspaceScope(); scope.replace({ userId: '1', workspace: 'merchant', merchantId: '101', storeId: '102' })
  let admission = copy(owner), current = copy(view)
  let savedImpl: ((input: unknown) => Promise<StoreProfileView>) | null = null
  let saves = 0, retires = 0, uploads = 0, lastInput: unknown = null
  const deps: MerchantStorefrontDeps = {
    scope, admission: async () => copy(admission),
    storeProfile: async () => copy(current),
    saveStoreProfile: input => { saves++; lastInput = input; return savedImpl ? savedImpl(input) : Promise.resolve(copy(saved)) },
    retireProfileConflict: () => { retires++ },
    uploadStorePhoto: filePath => { void filePath; uploads++; return Promise.resolve({ assetId: '88001' }) },
    customers: async () => { throw new Error('unused') },
    customerDetail: async () => { throw new Error('unused') },
    saveCustomerNote: async () => { throw new Error('unused') },
    retireNoteConflict: () => { throw new Error('unused') },
    statsOverview: async () => { throw new Error('unused') },
    statsServiceTop: async () => { throw new Error('unused') },
    statsTrend: async () => { throw new Error('unused') },
    statsServiceOrders: async () => { throw new Error('unused') },
    statsUtilization: async () => { throw new Error('unused') },
  }
  return {
    deps, get admission() { return admission }, set admission(v: MerchantAdmission) { admission = v },
    get current() { return current }, set current(v: StoreProfileView) { current = v },
    set saved(impl: ((input: unknown) => Promise<StoreProfileView>) | null) { savedImpl = impl },
    get counts() { return { saves, retires, uploads } }, get input() { return lastInput },
  }
}

const phase = (state: ShopInfoState) => state.phase
const ready = (controller: ShopInfoController) => new Promise(resolve => {
  const check = () => phase(controller.getSnapshot()) === 'ready' ? resolve(null) : setTimeout(check, 1); check()
})

test('店铺信息：读取默认投影并采纳草稿，保存走全字段替换且版本回填', async () => {
  const fx = fixture()
  const controller = new ShopInfoController(fx.deps, { merchantId: '101', storeId: '102' })
  await controller.load()
  await ready(controller)
  assert.equal(controller.getSnapshot().phase, 'ready')
  assert.equal(controller.getSnapshot().view?.readonlyFacts.phoneMasked, '138****8000')
  assert.equal(controller.getSnapshot().draft.businessStatus, 'OPEN')
  controller.editDraft({ intro: '专注宠物洗护', promoLevel: 8 })
  await controller.save()
  assert.equal(controller.getSnapshot().savedVersion, '1')
  assert.equal(fx.counts.saves, 1)
  const input = fx.input as {
    storeName: string; address: string; phone: string; intro: string; promoLevel: number
    expectedVersion: string; photoAssetIds: string[]; businessHours: unknown[]
    avatarAssetId: string; businessStatus: string
  }
  assert.equal(input.expectedVersion, '0')
  assert.equal(input.promoLevel, 8)
  assert.deepEqual(input.photoAssetIds, [])
  // 店铺事实全字段替换：名称/地址必填、电话保持空串语义。
  assert.equal(input.storeName, '小李宠物店')
  assert.equal(input.address, '张江路 88 号')
  assert.equal(input.phone, '')
  assert.equal(input.businessStatus, 'OPEN')
  controller.dispose()
})

test('店铺信息 2026-10-19 扩展：营业状态开关、头像与照片上传进草稿、保存随全字段替换', async () => {
  const fx = fixture()
  const controller = new ShopInfoController(fx.deps, { merchantId: '101', storeId: '102' })
  await controller.load()
  await ready(controller)
  // 营业状态切换：RESTING 进草稿，保存后投影回 OPEN（服务端返回）。
  controller.toggleBusinessStatus(true)
  assert.equal(controller.getSnapshot().draft.businessStatus, 'RESTING')
  // 页内上传：照片与头像均走 STORE_PHOTO 管线（上传即刻进草稿，保存时随全字段替换提交）。
  await controller.addPhoto('/tmp/a.png')
  await controller.setAvatar('/tmp/face.png')
  assert.equal(fx.counts.uploads, 2)
  assert.deepEqual(controller.getSnapshot().draft.photoAssetIds, ['88001'])
  assert.equal(controller.getSnapshot().draft.avatarAssetId, '88001')
  controller.removePhoto('88001')
  controller.clearAvatar()
  assert.deepEqual(controller.getSnapshot().draft.photoAssetIds, [])
  controller.toggleBusinessStatus(true)
  await controller.save()
  const input = fx.input as { avatarAssetId: string; businessStatus: string; photoAssetIds: string[] }
  assert.equal(input.businessStatus, 'RESTING')
  assert.equal(input.avatarAssetId, '')
  assert.deepEqual(input.photoAssetIds, [])
  controller.dispose()
})

test('店铺信息：版本冲突退役槽位并提示刷新（409 终局）', async () => {
  const fx = fixture()
  const conflict = Object.assign(new Error('conflict'), { code: 'COMMON_CONFLICT' }) as Error & { code: string; statusCode?: number }
  ;(conflict as unknown as { statusCode: number }).statusCode = 409
  fx.saved = () => Promise.reject(conflict)
  const controller = new ShopInfoController(fx.deps, { merchantId: '101', storeId: '102' })
  await controller.load()
  controller.editDraft({ intro: '并发修改' })
  await controller.save()
  assert.equal(controller.getSnapshot().savedVersion, null)
  assert.match(controller.getSnapshot().notice, /刷新/)
  assert.equal(fx.counts.retires, 1)
  controller.dispose()
})

test('准入：LIMITED 可读不可写，非 OWNER 不可读（70/69 读取家族、27 写族复用）', () => {
  assert.deepEqual(ownerAccess(owner, '101', '102'), { readable: true, writable: true, frozen: false })
  assert.deepEqual(ownerAccess(limited, '101', '102'), { readable: true, writable: false, frozen: true })
  assert.equal(ownerAccess(owner, '999', '102').readable, false)
})
