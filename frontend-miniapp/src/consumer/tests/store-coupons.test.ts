import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { decodeStoreCoupons, MarketingRepository, type CouponCampaignView } from '../marketing/model'
import {
  STORE_COUPON_LIMIT, claimSuccessTitle, couponAmountText, couponButton, couponDeadlineText,
  couponThresholdText, storeCouponCardHeight,
} from '../pages/store-services/coupons'

// 商家详情页「店铺优惠券」区块（79 号 §5 C 端入口；2026-11-27 用户裁决）：解码、两种读取方式、
// 匿名路径门、展示语义纯函数与页面接线的源码规则。

const root = fileURLToPath(new URL('../../..', import.meta.url))
const pageSource = (file: string) => readFileSync(path.join(root, 'src', 'consumer', 'pages', 'store-services', file), 'utf8')

function ok(data: unknown) {
  return { statusCode: 200, data: { success: true, code: 'SUCCESS', message: 'ok', data } }
}
function memoryStore(): LocalStore {
  const values = new Map<string, unknown>()
  return { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
}

// 72 号 §5.1 的条目 + 79 号新增成员（出资方与归属商家）。
const wireCoupon = {
  campaignId: '8800000000000001', name: '新客立减券', templateId: '8800000000000002',
  amountOff: '10.00', thresholdAmount: '100.00', typeLabel: '满减券',
  remaining: 37, perUserLimit: 2, claimed: false, canClaim: true, reason: null,
  startAt: '2026-11-01T00:00:00Z', endAt: '2026-12-31T15:59:59Z', status: 'ACTIVE',
  fundingType: 'MERCHANT', merchantId: '710301', merchantName: '示例宠物医院',
}

// ── 解码：按已知键读取，79 号新增成员不影响 ──

test('store coupon list decodes the 72 §5.1 entries and tolerates the 79 members', () => {
  const [coupon] = decodeStoreCoupons({ items: [wireCoupon] })
  assert.equal(coupon!.campaignId, '8800000000000001')
  assert.equal(coupon!.amountOff, '10.00')
  assert.equal(coupon!.perUserLimit, 2)
  assert.deepEqual(decodeStoreCoupons({ items: [] }), [])
  // 空条目被滤掉，不让一张坏数据拖垮整个区块。
  assert.deepEqual(decodeStoreCoupons({ items: [null, wireCoupon] }).length, 1)
  for (const bad of [{}, { items: null }, { items: 'x' }, { items: [{ ...wireCoupon, amountOff: 'abc' }] },
    { items: [{ ...wireCoupon, perUserLimit: 11 }] }, { items: Array.from({ length: 51 }, () => wireCoupon) }]) {
    assert.throws(() => decodeStoreCoupons(bad), /INVALID_RESPONSE/)
  }
})

// ── 仓库：未登录走匿名读，登录态走带会话的读 ──

test('store coupons read anonymously through the gated list path, and require a session when asked', async () => {
  const seen: { method: string; path: string; data?: Record<string, unknown> }[] = []
  const api = new ConsumerApi(async request => {
    seen.push({ method: request.method, path: request.path, data: request.data })
    return ok({ items: [wireCoupon] })
  }, memoryStore(), async () => '00000000-0000-4000-8000-000000000000')
  const repo = new MarketingRepository(api)
  const coupons = await repo.storeCoupons('710302', false)
  assert.equal(coupons.length, 1)
  assert.deepEqual(seen, [{ method: 'GET', path: '/api/v1/c/coupon-campaigns', data: { storeId: '710302' } }])
  // 没有会话却要求带凭据读取：在发出任何请求之前就失败，不会退化成匿名读。
  seen.length = 0
  await assert.rejects(() => repo.storeCoupons('710302', true))
  assert.deepEqual(seen, [])
})

test('the anonymous path gate admits the coupon list and stays strict around it', async () => {
  const api = new ConsumerApi(async () => ok({ items: [] }), memoryStore(), async () => '00000000-0000-4000-8000-000000000000')
  await api.anonymousRequest({ method: 'GET', path: '/api/v1/c/coupon-campaigns', data: { storeId: '710302' } }, decodeStoreCoupons)
  await api.anonymousRequest({ method: 'GET', path: '/api/v1/c/coupon-campaigns/8800000000000001' }, value => value)
  for (const spec of [
    { method: 'POST', path: '/api/v1/c/coupon-campaigns' },
    { method: 'GET', path: '/api/v1/c/coupon-campaigns/' },
    { method: 'GET', path: '/api/v1/c/coupon-campaigns/1/claims' },
    { method: 'GET', path: '/api/v1/c/coupon-campaignsx' },
    { method: 'GET', path: '/api/v1/c/coupon-campaigns/0' },
  ]) {
    await assert.rejects(() => api.anonymousRequest(spec as never, value => value), /INVALID_PATH/)
  }
})

// ── 展示语义 ──

const coupon: CouponCampaignView = decodeStoreCoupons({ items: [wireCoupon] })[0]!

test('amount, threshold, deadline and the success toast follow the page price rule', () => {
  assert.equal(couponAmountText(coupon), '¥10')
  assert.equal(couponAmountText({ ...coupon, amountOff: '128.50' }), '¥128.5')
  assert.equal(couponAmountText({ ...coupon, amountOff: null, typeLabel: null }), '店铺券')
  assert.equal(couponThresholdText('100.00'), '满 ¥100 可用 · 仅限本商家门店使用')
  assert.equal(couponThresholdText(null), '无门槛 · 仅限本商家门店使用')
  assert.equal(couponThresholdText('0.00'), '无门槛 · 仅限本商家门店使用')
  // 领取截止按设备本地时区显示到日；无法解析时不展示。
  const [year, month, day] = couponDeadlineText(coupon.endAt)!.replace('可领至 ', '').split('-').map(Number)
  const local = new Date(Date.parse(coupon.endAt))
  assert.deepEqual([year, month, day], [local.getFullYear(), local.getMonth() + 1, local.getDate()])
  assert.equal(couponDeadlineText('not-an-instant'), null)
  assert.equal(claimSuccessTitle('10.00'), '领取成功 ¥10')
  assert.equal(claimSuccessTitle(null), '领取成功')
})

test('the claim button follows the server verdict, including claims below a multi-claim limit', () => {
  assert.deepEqual(couponButton(coupon, false), { label: '领取', disabled: false })
  assert.deepEqual(couponButton({ ...coupon, claimed: true }, false), { label: '再领一张', disabled: false })
  assert.deepEqual(couponButton(coupon, true), { label: '领取中…', disabled: true })
  for (const [reason, label] of [['CLAIMED', '已领取'], ['SOLD_OUT', '已抢完'], ['BUDGET_EXHAUSTED', '已抢完'], ['TEMPLATE_UNAVAILABLE', '暂不可领'], [null, '暂不可领']] as const) {
    assert.deepEqual(couponButton({ ...coupon, canClaim: false, reason }, false), { label, disabled: true }, reason ?? 'null')
  }
})

test('the card grows with the rows and caps what it shows', () => {
  assert.equal(STORE_COUPON_LIMIT, 3)
  assert.equal(storeCouponCardHeight(0, 0), 0)
  assert.equal(storeCouponCardHeight(1, 1), 50 + 81.5 + 16)
  assert.equal(storeCouponCardHeight(2, 2), 50 + 2 * 81.5 + 12 + 16)
  assert.equal(storeCouponCardHeight(3, 3), 50 + 3 * 81.5 + 2 * 12 + 16)
  // 超出上限时多一行「仅展示前 3 张」。
  assert.equal(storeCouponCardHeight(3, 5), storeCouponCardHeight(3, 3) + 26)
})

// ── 页面接线（源码规则）──

test('the store page wires the block between the promo strip and the services, and claims through the contract-72 route', () => {
  const page = pageSource('index.tsx')
  const view = pageSource('view.tsx')
  const css = pageSource('page.css')
  // 没有券时不渲染区块，服务项目起点与设计稿一致；有券时按卡高顺移。
  assert.ok(page.includes("const listTop = couponHeight > 0 ? couponTop + couponHeight + 15.5 : couponTop"))
  assert.ok(page.includes('coupon={couponBlock}'))
  assert.ok(page.includes('const couponBlock = couponHeight > 0'))
  assert.ok(view.includes("{coupon && <View className='svc-abs svc-coupon-card'"))
  assert.ok(view.indexOf("className='svc-abs svc-coupon-card'") > view.indexOf("className='svc-abs svc-promo'"))
  assert.ok(view.indexOf("className='svc-abs svc-coupon-card'") < view.indexOf("className='svc-abs svc-list-card'"))
  // 读取失败只隐藏区块；设计稿预览不加载券，保持与 690:6660 一致。
  assert.ok(page.includes('couponRepository.current.storeCoupons(storeId, Boolean(scope.current))'))
  assert.ok(page.includes('.catch(() => [])'))
  assert.ok(page.includes('preview ? Promise.resolve([])'))
  // 领券是与预约同类的动作：匿名走登录引导；领取走既有领券接口。
  assert.ok(page.includes("detailActionGate(context) === 'login-required'"))
  assert.ok(page.includes('couponRepository.current.claim(coupon.campaignId)'))
  // WXSS 铁律：不依赖动态 data-* 属性选择器。
  for (const banned of ['data-', '!important']) {
    const block = css.slice(css.indexOf('/* 店铺优惠券卡'), css.indexOf('/* 77号'))
    assert.equal(block.includes(banned), false, `coupon css must not contain ${banned}`)
  }
})
