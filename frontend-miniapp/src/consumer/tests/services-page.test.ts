import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import {
  CATEGORY_CODE_PATTERN, dealBadgeText, dealLines, hasMoreFeatured,
  priceText, promotionLine, ratingText, soldText, storeTagLabel, storesCategoryUrl,
} from '../pages/services/view-model'

// F-C-02 服务 tab 整页对齐 110:480（74 §3.1/§3.4/§6 + 71 v0.3 §3.4）：展示语义纯函数、
// 登记偏差门禁（距离/资质标签不做、排序四键不做）、素材登记与 WXSS 铁律门禁。

const root = fileURLToPath(new URL('../../..', import.meta.url))
const pageDir = path.join(root, 'src', 'consumer', 'pages', 'services')
const source = (file: string) => readFileSync(path.join(pageDir, file), 'utf8')

// ── 分类落地路由（74 §3.2：categoryCode 精确筛选，fail-closed）──

test('category tap routes to the stores page with categoryCode; bad lexicon never navigates', () => {
  assert.equal(
    storesCategoryUrl('PET_GROOMING'),
    '/consumer/pages/store-services/stores?preview=0&categoryCode=PET_GROOMING')
  assert.equal(storesCategoryUrl('PLANT_BOARDING')!.endsWith('categoryCode=PLANT_BOARDING'), true)
  // 74 §2.1 词法（2..32，大写开头）：词法非法一律 null，不发起必失败的跳转。
  for (const bad of ['pet_grooming', 'a', 'PET GROOMING', '', 'PET-GROOMING', `${'A'.repeat(33)}`]) {
    assert.equal(storesCategoryUrl(bad), null, bad)
  }
  assert.equal(CATEGORY_CODE_PATTERN.test('A'.repeat(32)), true)
})

// ── 74 §6.1 评分展示：一位小数 / 暂无评分 / 聚合不可用不展示 ──

test('rating renders the one-decimal score, 暂无评分 under five reviews and hides unavailable facts', () => {
  assert.equal(ratingText({ score: '4.9', count: 328, available: true }), '4.9')
  assert.equal(ratingText({ score: '5.0', count: 12, available: true }), '5.0')
  // 评价数 <5：score=null 且 available=true → 「暂无评分」（契约 74 §11）。
  assert.equal(ratingText({ score: null, count: 3, available: true }), '暂无评分')
  // 聚合不可用（available=false）→ 不展示，不用 0 分/占位分冒充。
  assert.equal(ratingText({ score: null, count: 0, available: false }), null)
})

// ── 74 §6.2 月售/已售：聚合不可用 null → 不展示该段 ──

test('sold facts render only when the aggregate is available', () => {
  assert.equal(soldText('月售', 328), '月售328')
  assert.equal(soldText('已售', 0), '已售0')
  assert.equal(soldText('月售', null), null)
  assert.equal(soldText('已售', null), null)
})

// ── DECIMAL(18,2) 展示价 ──

test('sale price keeps two decimals only when cents are non-zero', () => {
  assert.equal(priceText('80.00'), '¥80')
  assert.equal(priceText('128.50'), '¥128.50')
  assert.equal(priceText('0.05'), '¥0.05')
  assert.equal(priceText('123456.00'), '¥123456')
})

// ── 74 §6.3/§6.4 标签与促销：真实分类名，无资质标签兜底 ──

test('store tag falls back to the real primary category; promotion prefers title then copy', () => {
  assert.equal(storeTagLabel({ tags: ['宠物美容'], categoryName: '宠物寄养' }), '宠物美容')
  assert.equal(storeTagLabel({ tags: [], categoryName: '绿植养护' }), '绿植养护')
  assert.equal(promotionLine({ offerTitle: '全场美容套餐5折起', offerCopy: '专业美容师持证上岗' }), '全场美容套餐5折起')
  assert.equal(promotionLine({ offerTitle: null, offerCopy: '专业美容师持证上岗' }), '专业美容师持证上岗')
  assert.equal(promotionLine({ offerTitle: null, offerCopy: null }), null)
  assert.equal(promotionLine(null), null)
})

// ── 71 §3.4 钜惠卡：角标与主/副文案降级 ──

test('deal badge derives from offerTag or a real promotion level, never a fabricated tag', () => {
  assert.equal(dealBadgeText({ offerTag: '5折', promotion: { level: null } }), '5折')
  assert.equal(dealBadgeText({ offerTag: null, promotion: { level: 8 } }), '8折')
  assert.equal(dealBadgeText({ offerTag: null, promotion: null }), null)
  assert.equal(dealBadgeText({ offerTag: null, promotion: { level: null } }), null)
})

test('deal lines prefer the title and only keep the copy as a second line when both exist', () => {
  assert.deepEqual(dealLines({ offerTitle: 'A', offerCopy: 'B' }), { primary: 'A', secondary: 'B' })
  assert.deepEqual(dealLines({ offerTitle: null, offerCopy: 'B' }), { primary: 'B', secondary: null })
  assert.deepEqual(dealLines({ offerTitle: null, offerCopy: null }), { primary: null, secondary: null })
})

// ── 74 §3.4 分页：以服务端 total 为准 ──

test('featured pagination keeps loading until the server total is reached', () => {
  assert.equal(hasMoreFeatured(0, 23), true)
  assert.equal(hasMoreFeatured(10, 23), true)
  assert.equal(hasMoreFeatured(20, 23), true)
  assert.equal(hasMoreFeatured(23, 23), false)
  assert.equal(hasMoreFeatured(24, 23), false)
  assert.equal(hasMoreFeatured(0, 0), false)
})

// ── 登记偏差门禁：距离/资质标签不做（74 §10），排序四键不做 ──

test('the page family never fabricates distance or qualification labels', () => {
  for (const file of ['index.tsx', 'view-model.ts', 'deals-rail.tsx', 'featured-stores.tsx', 'services.css']) {
    const text = source(file)
    for (const banned of ['1.2km', '持证上岗', '5年经验', '综合排序', '距离最近', '评分最高', '销量优先']) {
      assert.equal(text.includes(banned), false, `${file} must not contain ${banned}`)
    }
  }
})

// ── 页面接线门禁：分类字典驱动、categoryCode 落地、优质商家分页读 ──

test('the services page is dictionary-driven and routes categories by categoryCode', () => {
  const index = source('index.tsx')
  // 旧硬编码 12 项分类数组必须移除（74 §9 迁移第 7 条）。
  assert.equal(index.includes("'全部服务', '宠物美容'"), false)
  assert.match(index, /ServiceCatalogRepository/)
  assert.match(index, /storesCategoryUrl/)
  assert.match(index, /syncCustomTabBar\(1\)/) // 服务 tab 桥接保持
  assert.match(index, /useFeaturedStores/)
  // 优质商家列表走 74 §3.4 聚合读，分页以服务端 total 为准。
  const featured = source('featured-stores.tsx')
  assert.match(featured, /featuredStores\(/)
  assert.match(featured, /FEATURED_PAGE_SIZE/)
  assert.match(featured, /hasMoreFeatured/)
  // 商家钜惠带图：71 v0.3 imageUrl 投影渲染，无图降级占位。
  const deals = source('deals-rail.tsx')
  assert.match(deals, /imageUrl/)
  assert.match(deals, /deal-img-placeholder/)
  assert.match(deals, /merchantDeals\(/)
})

// ── WXSS 铁律与素材登记门禁 ──

test('services css obeys the WXSS iron rules and icon assets match the manifest', () => {
  const css = source('services.css')
  assert.equal(/\[data-/.test(css), false) // 禁动态 data-* 属性选择器
  assert.equal(css.includes('@keyframes'), false) // 本页无 keyframes
  assert.match(css, /button::after/) // button 原生重置
  const manifest = JSON.parse(source(path.join('assets', 'manifest.json'))) as {
    assets: { nodeId: string; file: string; sha256: string; bytes: number }[]
  }
  assert.ok(manifest.assets.length >= 4)
  for (const asset of manifest.assets) {
    // manifest 的 file 带仓库级 frontend-miniapp/ 前缀（与 home manifest 同约定），测试根已是该目录。
    const relative = asset.file.replace(/^frontend-miniapp\//, '')
    const file = path.join(root, ...relative.split('/'))
    assert.ok(existsSync(file), `asset missing: ${asset.file}`)
    const bytes = readFileSync(file)
    assert.equal(bytes.length, asset.bytes, asset.file)
    assert.equal(createHash('sha256').update(bytes).digest('hex'), asset.sha256, asset.file)
  }
  // 11 类图标 D-01 未到位：页面不含伪造的本地分类图标路径（iconUrl=null → tile 占位）。
  const index = source('index.tsx')
  assert.doesNotMatch(index, /assets\/(pet-grooming|dog-walking|category-)/)
})
