// CCR-M-STORE-AVATAR-001 店铺头像展示签名：解码器严格校验（三键同现/同空）+ 控制器
// 降级与续签语义（三 null 首字占位、失败不阻断资料页、临期重签、onError 回落、
// 签名 URL 仅内存态不落本地存储——deps 无任何存储通道，结构性保证）。

import test from 'node:test'
import assert from 'node:assert/strict'
import { WorkspaceScope } from '../../shared/workspace'
import { decodeStoreAvatarUrl } from '../../shared/merchant-storefront-api'
import type { StoreProfileView } from '../../shared/merchant-storefront-api'
import type { MerchantAdmission } from '../../shared/merchant-repositories'
import { ShopInfoController } from '../storefront/shop-info-controller'
import type { MerchantStorefrontDeps } from '../storefront/repository'

const owner: MerchantAdmission = {
  merchantId: '101', storeId: '102', membershipKind: 'OWNER', admission: 'ALLOWED',
  checkedAt: '2026-10-01T00:00:00.000Z', authzVersion: '0123456789abcdef',
  facts: { application: { status: 'APPROVED' }, signing: { status: 'SIGNED' }, merchantStatus: 'ACTIVE', storeStatus: 'ACTIVE', staffEnabled: null },
  allowedActions: ['merchant.order.read'], reasonCodes: [], nextSteps: [],
}

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
    avatarAssetId: '710402', businessStatus: 'OPEN', version: '3',
  },
}
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const signed = (assetId: string, expiresInMs: number) => ({
  assetId, url: `https://cdn.example.invalid/signed/${assetId}`,
  expiresAt: new Date(Date.now() + expiresInMs).toISOString(),
})

test('解码器：三键同现或同空，格式非法一律拒绝', () => {
  assert.deepEqual(decodeStoreAvatarUrl({ assetId: null, url: null, expiresAt: null }),
    { assetId: null, url: null, expiresAt: null })
  const full = { assetId: '710402', url: 'https://cdn.example.invalid/signed', expiresAt: '2026-11-06T02:00:00.000Z' }
  assert.deepEqual(decodeStoreAvatarUrl(full), full)
  // 混排（有 url 无 assetId / 缺过期时间）与键集不完整/多余都视为非法响应。
  assert.throws(() => decodeStoreAvatarUrl({ assetId: null, url: 'https://x.example.invalid/a', expiresAt: null }), /INVALID_RESPONSE/)
  assert.throws(() => decodeStoreAvatarUrl({ assetId: '710402', url: null, expiresAt: '2026-11-06T02:00:00.000Z' }), /INVALID_RESPONSE/)
  assert.throws(() => decodeStoreAvatarUrl({ assetId: '710402', url: 'https://x.example.invalid/a' }), /INVALID_RESPONSE/)
  assert.throws(() => decodeStoreAvatarUrl({ assetId: '710402', url: 'https://x.example.invalid/a', expiresAt: '2026-11-06T02:00:00.000Z', extra: 1 }), /INVALID_RESPONSE/)
  // 非 https、带空白、超长、非法 id、非毫秒 Z 时间都不接受。
  assert.throws(() => decodeStoreAvatarUrl({ assetId: '710402', url: 'http://cdn.example.invalid/a', expiresAt: '2026-11-06T02:00:00.000Z' }), /INVALID_RESPONSE/)
  assert.throws(() => decodeStoreAvatarUrl({ assetId: '710402', url: 'https://cdn.example.invalid/a b', expiresAt: '2026-11-06T02:00:00.000Z' }), /INVALID_RESPONSE/)
  assert.throws(() => decodeStoreAvatarUrl({ assetId: '710402', url: `https://cdn.example.invalid/${'a'.repeat(2100)}`, expiresAt: '2026-11-06T02:00:00.000Z' }), /INVALID_RESPONSE/)
  assert.throws(() => decodeStoreAvatarUrl({ assetId: '0', url: 'https://cdn.example.invalid/a', expiresAt: '2026-11-06T02:00:00.000Z' }), /INVALID_RESPONSE/)
  assert.throws(() => decodeStoreAvatarUrl({ assetId: '710402', url: 'https://cdn.example.invalid/a', expiresAt: '2026-11-06T02:00:00Z' }), /INVALID_RESPONSE/)
  assert.throws(() => decodeStoreAvatarUrl({ assetId: '710402', url: 'https://cdn.example.invalid/a', expiresAt: '2026-11-06T02:00:00.000+08:00' }), /INVALID_RESPONSE/)
})

function fixture(avatar: (() => Promise<{ assetId: string | null; url: string | null; expiresAt: string | null }>) | null) {
  const scope = new WorkspaceScope(); scope.replace({ userId: '1', workspace: 'merchant', merchantId: '101', storeId: '102' })
  let current = copy(view)
  let avatarReads = 0
  const deps: MerchantStorefrontDeps = {
    scope, admission: async () => copy(owner),
    storeProfile: async () => copy(current),
    saveStoreProfile: async () => { throw new Error('unused') },
    retireProfileConflict: () => { throw new Error('unused') },
    uploadStorePhoto: async () => { throw new Error('unused') },
    storeAvatarUrl: () => { avatarReads++; return avatar ? avatar() : Promise.resolve({ assetId: null, url: null, expiresAt: null }) },
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
    deps, get reads() { return avatarReads },
    set fresh(v: StoreProfileView) { current = v },
  }
}

const until = (check: () => boolean, timeoutMs = 1000) => new Promise((resolve, reject) => {
  const started = Date.now()
  const tick = () => check() ? resolve(null)
    : Date.now() - started > timeoutMs ? reject(new Error('TIMEOUT')) : setTimeout(tick, 1)
  tick()
})

test('控制器：资料就绪后取签名展示已保存头像；三 null 保持首字占位', async () => {
  const fx = fixture(() => Promise.resolve(signed('710402', 10 * 60_000)))
  const controller = new ShopInfoController(fx.deps, { merchantId: '101', storeId: '102' })
  await controller.load()
  await until(() => controller.getSnapshot().phase === 'ready')
  await until(() => controller.getSnapshot().avatar !== null)
  assert.equal(controller.getSnapshot().avatar?.url, 'https://cdn.example.invalid/signed/710402')
  assert.equal(controller.getSnapshot().avatar?.assetId, '710402')
  assert.ok(fx.reads >= 1, 'load() 之后应触发一次 avatar-url 读')
  controller.dispose()
})

test('控制器：无头像/不可签/读失败均降级为 null，不阻断资料页', async () => {
  type AvatarImpl = (() => Promise<{ assetId: string | null; url: string | null; expiresAt: string | null }>) | null
  const cases: AvatarImpl[] = [
    null, // 服务端三 null（无头像/素材不可签）
    () => Promise.reject(new Error('COMMON_DEPENDENCY_UNAVAILABLE')), // 503/网络失败
    () => Promise.resolve(signed('889999', 10 * 60_000)), // 返回与已保存头像不一致的资产（异常响应）
  ]
  for (const impl of cases) {
    const fx = fixture(impl)
    const controller = new ShopInfoController(fx.deps, { merchantId: '101', storeId: '102' })
    await controller.load()
    await until(() => controller.getSnapshot().phase === 'ready')
    await until(() => fx.reads >= 1)
    assert.equal(controller.getSnapshot().phase, 'ready', '头像失败绝不让资料页失败')
    assert.equal(controller.getSnapshot().avatar, null, '降级为首字占位')
    controller.dispose()
  }
})

test('控制器：临期（<60s）重签，未临期跳过重复请求；onError 立即回落占位', async () => {
  const fx = fixture(() => Promise.resolve(signed('710402', 30 * 1000))) // 30s 后过期：临期
  const controller = new ShopInfoController(fx.deps, { merchantId: '101', storeId: '102' })
  await controller.load()
  await until(() => controller.getSnapshot().avatar !== null)
  const readsAfterLoad = fx.reads
  await controller.refreshAvatar() // 临期：应重新请求
  assert.ok(fx.reads > readsAfterLoad, '临期签名应重签')
  assert.notEqual(controller.getSnapshot().avatar, null)
  // 图片加载失败：立即清空占位，等下一次 onShow 重入再取新签名。
  controller.avatarImageError()
  assert.equal(controller.getSnapshot().avatar, null)
  await controller.refreshAvatar() // onShow 重入后的取新签名路径
  assert.notEqual(controller.getSnapshot().avatar, null)
  controller.dispose()
  // 未临期（长有效期）时跳过重复请求。
  const fresh = fixture(() => Promise.resolve(signed('710402', 30 * 60_000)))
  const stable = new ShopInfoController(fresh.deps, { merchantId: '101', storeId: '102' })
  await stable.load()
  await until(() => stable.getSnapshot().avatar !== null)
  const before = fresh.reads
  await stable.refreshAvatar()
  assert.equal(fresh.reads, before, '未临期不重复请求')
  stable.dispose()
})

test('控制器：无头像的店铺不发起签名读；草稿清空头像后展示回落占位', async () => {
  const fx = fixture(() => Promise.resolve(signed('710402', 10 * 60_000)))
  fx.fresh = copy({ ...view, profile: { ...view.profile, avatarAssetId: null } })
  const controller = new ShopInfoController(fx.deps, { merchantId: '101', storeId: '102' })
  await controller.load()
  await until(() => controller.getSnapshot().phase === 'ready')
  assert.equal(controller.getSnapshot().avatar, null)
  assert.equal(fx.reads, 0, '无头像不应发起 avatar-url 读')
  // 已有展示签名但草稿清空头像（未保存）：展示立即回落首字占位。
  fx.fresh = copy(view)
  await controller.load()
  await until(() => controller.getSnapshot().avatar !== null)
  controller.clearAvatar()
  assert.equal(controller.getSnapshot().draft.avatarAssetId, '')
  assert.equal(controller.getSnapshot().avatar?.assetId, '710402', '内存态保留签名，但页面以草稿比对决定展示')
  controller.dispose()
})
