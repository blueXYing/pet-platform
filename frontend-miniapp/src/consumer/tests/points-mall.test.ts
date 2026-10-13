import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import { mallActionLabel, mallGoods, mallPageCopy, type MallGoodView } from '../pages/points-mall/model'
import { MINE_POINTS_MALL_URL } from '../pages/mine/model'

// 积分商城展示态切片测试（32号裁决补充第二节第 1 条：V1 积分只赚取/扣回、不消费——
// 商品可陈列、兑换动作不可用、页面文案明示暂未开放）。商品为设计源 128:2614 的前端
// 设计样例（designSamples 先例），无商品后端、无任何写路径。

test('mall goods follow the 128:2614 design samples verbatim', () => {
  assert.equal(mallGoods.length, 6)
  assert.deepEqual(
    mallGoods.map(good => [good.name, good.points, good.stock] as const),
    [
      ['宠物零食大礼包', '500', '库存 12'],
      ['宠物梳毛器套装', '300', '库存 8'],
      ['宠物牵引绳', '200', '库存 20'],
      ['猫咪玩具逗猫棒', '150', '库存 0'],
      ['宠物洗澡毛巾', '100', '库存 30'],
      ['宠物饮水碗', '250', '库存 15'],
    ],
  )
  // 原稿仅首卡带「热销」角标；仅第四卡为售罄文案。
  assert.deepEqual(mallGoods.map(good => good.hot), [true, false, false, false, false, false])
  assert.deepEqual(mallGoods.map(good => good.action), ['exchange', 'exchange', 'exchange', 'sold-out', 'exchange', 'exchange'])
  // 展示数字为正整数积分（无小数/金额语义）。
  for (const good of mallGoods) assert.match(good.points, /^[1-9]\d*$/)
  // 设计样例 id 稳定且唯一（渲染 key）。
  assert.equal(new Set(mallGoods.map(good => good.goodId)).size, mallGoods.length)
})

test('action labels keep the design wording (兑换 x5 / 已兑完 x1)', () => {
  const soldOut: MallGoodView = { ...mallGoods[3]! }
  assert.equal(mallActionLabel(mallGoods[0]!), '兑换')
  assert.equal(mallActionLabel(soldOut), '已兑完')
})

test('display-state copy explicitly states exchange is not open (32号第二节第1条)', () => {
  for (const copy of [mallPageCopy.notice, mallPageCopy.previewNote, mallPageCopy.realNote]) {
    assert.ok(copy.includes('兑换暂未开放'), copy)
  }
  // 既有积分硬规则的复述：只赚取、不消费。
  assert.ok(mallPageCopy.notice.includes('不支持积分消费、兑换或抵现'))
  assert.ok(mallPageCopy.realNote.includes('只赚取') && mallPageCopy.realNote.includes('不消费'))
  assert.equal(mallPageCopy.title, '积分商城')
})

test('mall page source renders every exchange action disabled with no write path (static guard)', () => {
  const page = readFileSync(
    join(fileURLToPath(import.meta.url), '../../pages/points-mall/index.tsx'), 'utf8')
  // 兑换动作一律 disabled（WXSS 铁律：禁用态不落 data-*，用 className 变体 is-disabled）。
  assert.ok(page.includes('disabled ariaLabel'))
  assert.ok(page.includes('is-disabled'))
  // 32号口径：读侧只有 balance（既有 points 读侧仓库），跳转只有返回/我的/积分明细页。
  assert.ok(page.includes('repository.current!.balance()'))
  // 消费语义与写路径词汇零出现（不虚构抵扣/兑换请求/支付）。
  for (const banned of ['积分抵扣', '立即兑换', 'Taro.request', 'requestPayment', 'cashier', 'exchange(']) {
    assert.ok(!page.includes(banned), banned)
  }
})

test('mine hub mall entry routes to the registered points-mall page', async () => {
  ;(globalThis as Record<string, unknown>).defineAppConfig = (value: unknown) => value
  ;(globalThis as Record<string, unknown>).definePageConfig = (value: unknown) => value
  const config = (await import('../../app.config')).default as { subPackages: Array<{ root: string; pages: string[] }> }
  assert.equal(MINE_POINTS_MALL_URL, '/consumer/pages/points-mall/index')
  const pack = config.subPackages.find(item => item.root === 'consumer/pages/points-mall')
  assert.deepEqual(pack?.pages, ['index'])
})
