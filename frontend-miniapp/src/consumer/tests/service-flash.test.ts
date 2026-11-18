import assert from 'node:assert/strict'
import test from 'node:test'
import {
  decodeServiceItem, flashCountdownText, flashDiscountAmount, flashOfferOf, fixtureServices,
} from '../service/model'

// Contract-77 §5 C 端闪价投影（P2）纯逻辑测试：解码（缺省键=LIST/null 与 P2 前行为一致、
// FLASH 必带全套窗口/价格、坏枚举/坏精度 fail closed）、展示投影（窗口内/售罄/窗口已过）、
// 倒计时文本与限时优惠金额（原价−生效价）计算。

const { description: _description, ...itemSource } = fixtureServices[0]
const flashFields = {
  priceSource: 'FLASH', listPrice: '128.00', flashPrice: '80.00',
  flashStartAt: '2026-11-20T02:00:00Z', flashEndAt: '2026-11-20T04:00:00Z', flashRemaining: 5,
} as const

test('flash projection decode: absent keys decode to LIST/nulls (pre-P2 wire stays valid)', () => {
  const item = decodeServiceItem(itemSource)
  assert.equal(item.priceSource, 'LIST')
  assert.equal(item.listPrice, null)
  assert.equal(item.flashPrice, null)
  assert.equal(item.flashStartAt, null)
  assert.equal(item.flashEndAt, null)
  assert.equal(item.flashRemaining, null)
  // 开关打开但无闪价：显式 LIST + null 字段同样通过。
  const explicitList = decodeServiceItem({
    ...itemSource, priceSource: 'LIST', listPrice: null, flashPrice: null,
    flashStartAt: null, flashEndAt: null, flashRemaining: null,
  })
  assert.equal(explicitList.priceSource, 'LIST')
})

test('flash projection decode: a FLASH source must carry the full window and price pair', () => {
  const item = decodeServiceItem({ ...itemSource, ...flashFields })
  assert.equal(item.priceSource, 'FLASH')
  assert.equal(item.flashPrice, '80.00')
  assert.equal(item.listPrice, '128.00')
  assert.equal(item.flashRemaining, 5)
  // 无总量：flashRemaining null 合法（不限量）。
  assert.equal(decodeServiceItem({ ...itemSource, ...flashFields, flashRemaining: null }).flashRemaining, null)
  for (const bad of [
    { priceSource: 'MEMBER' },
    { ...flashFields, flashPrice: null },
    { ...flashFields, listPrice: '128.0' },
    { ...flashFields, flashRemaining: 1.5 },
    { ...flashFields, flashRemaining: -1 },
    { ...flashFields, flashEndAt: '2026-11-20 04:00:00' },
    { ...flashFields, flashStartAt: undefined, priceSource: 'FLASH' },
  ]) {
    assert.throws(() => decodeServiceItem({ ...itemSource, ...bad }), /INVALID_RESPONSE/, JSON.stringify(bad))
  }
  // 未知字段仍整体拒绝（白名单纪律不变）。
  assert.throws(() => decodeServiceItem({ ...itemSource, ...flashFields, bookable: true }), /INVALID_RESPONSE/)
})

test('flashOfferOf: live window, sold-out quota and expired windows', () => {
  const during = Date.parse('2026-11-20T03:00:00Z')
  const service = { ...itemSource, ...flashFields }
  const offer = flashOfferOf(service, during)
  assert.notEqual(offer, null)
  assert.equal(offer?.flashPrice, '80.00')
  assert.equal(offer?.listPrice, '128.00')
  assert.equal(offer?.remaining, 5)
  assert.equal(offer?.soldOut, false)
  // 剩余 0：售罄（价格回原价语义由视图层承接）。
  const soldOut = flashOfferOf({ ...service, flashRemaining: 0 }, during)
  assert.equal(soldOut?.soldOut, true)
  // 窗口已过 / 无闪价 / 字段缺失：无投影，视图按现状渲染。
  assert.equal(flashOfferOf(service, Date.parse('2026-11-20T04:00:01Z')), null)
  assert.equal(flashOfferOf({ ...itemSource, priceSource: 'LIST' }, during), null)
  assert.equal(flashOfferOf(itemSource, during), null)
})

test('flashCountdownText: clock text with a day segment past 24h; null once over', () => {
  const end = '2026-11-20T04:00:00Z'
  assert.equal(flashCountdownText(Date.parse('2026-11-20T03:59:59Z'), end), '00:00:01')
  assert.equal(flashCountdownText(Date.parse('2026-11-20T02:00:00Z'), end), '02:00:00')
  assert.equal(flashCountdownText(Date.parse('2026-11-19T03:00:00Z'), end), '1天 01:00:00')
  assert.equal(flashCountdownText(Date.parse('2026-11-20T04:00:00Z'), end), null)
  assert.equal(flashCountdownText(Date.now(), 'not-a-date'), null)
})

test('flashDiscountAmount: list minus effective price, null unless positive', () => {
  assert.equal(flashDiscountAmount('128.00', '80.00'), '48.00')
  assert.equal(flashDiscountAmount('100.00', '99.99'), '0.01')
  assert.equal(flashDiscountAmount('80.00', '80.00'), null)
  assert.equal(flashDiscountAmount('80.00', '90.00'), null)
  assert.equal(flashDiscountAmount('80', '80.00'), null)
})
