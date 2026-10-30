import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import {
  pickHotServiceBanner, placementJumpUrl, previewHotServiceBanner,
} from '../service-catalog/hot-service'
import { PLACEMENT_POSITION_HOT_SERVICE_TOP, decodePlacements } from '../marketing/model'

// F-C-03 热门服务二级页（74 §3.7 / 71 v0.3）：banner 选取、投放位跳转白名单与路由登记门禁。

const root = fileURLToPath(new URL('../../..', import.meta.url))

const bannerRow = {
  placementId: '880001', positionCode: 'HOT_SERVICE_TOP', campaignId: '880000',
  title: '优惠活动商家', subtitle: '精选优质商家优惠，点击查看详情',
  tagCode: 'HOT', tagName: '限时优惠 · 先到先得',
  coverUrl: 'https://cdn.example/banner.png', templateCode: null,
  jumpType: 'CAMPAIGN', jumpParams: '{"campaignId":"880000"}',
  startAt: '2026-10-30T00:00:00Z', endAt: '2026-11-30T00:00:00Z',
}

// ── 74 §3.7 banner 选取：sortWeight ASC, placementId ASC 第一条（服务端已排序）──

test('banner picker takes the first placement and hides the block without one', () => {
  const items = decodePlacements({ items: [bannerRow, { ...bannerRow, placementId: '880002' }] })
  assert.equal(pickHotServiceBanner(items)!.placementId, '880001')
  assert.equal(pickHotServiceBanner([]), null)
})

// ── 74 §3.7 banner 点击：投放位既有跳转规则的 C 端承接子集（fail-closed）──

test('placement jump maps the four routed landing types to existing pages', () => {
  assert.equal(
    placementJumpUrl({ jumpType: 'CAMPAIGN', jumpParams: '{"campaignId":"880000"}' }),
    '/consumer/pages/marketing/campaign?campaignId=880000')
  assert.equal(
    placementJumpUrl({ jumpType: 'STORE', jumpParams: '{"storeId":"710302"}' }),
    '/consumer/pages/store-services/index?preview=0&storeId=710302')
  assert.equal(
    placementJumpUrl({ jumpType: 'SERVICE', jumpParams: '{"serviceId":"710401"}' }),
    '/consumer/pages/store-services/service-detail?preview=0&serviceId=710401')
  assert.equal(
    placementJumpUrl({ jumpType: 'SERVICE_CATEGORY', jumpParams: '{"categoryCode":"PET_GROOMING"}' }),
    '/consumer/pages/store-services/stores?preview=0&categoryCode=PET_GROOMING')
  // 解码风格与全仓一致：未知附加字段忽略，不视为破坏。
  assert.equal(
    placementJumpUrl({ jumpType: 'SERVICE', jumpParams: '{"serviceId":"710401","extra":true}' }),
    '/consumer/pages/store-services/service-detail?preview=0&serviceId=710401')
})

test('placement jump fails closed on broken params, bad lexicon and unrouted types', () => {
  // JSON 破损 / 非对象 / 键缺失 / 词法非法（ID 非十进制 Snowflake、分类码非字典词法）一律不跳。
  for (const jump of [
    { jumpType: 'CAMPAIGN', jumpParams: 'not-json' },
    { jumpType: 'CAMPAIGN', jumpParams: '[]' },
    { jumpType: 'CAMPAIGN', jumpParams: '{}' },
    { jumpType: 'CAMPAIGN', jumpParams: '{"campaignId":"0"}' },
    { jumpType: 'CAMPAIGN', jumpParams: '{"campaignId":"abc"}' },
    { jumpType: 'STORE', jumpParams: '{"storeId":710302}' },
    { jumpType: 'SERVICE_CATEGORY', jumpParams: '{"categoryCode":"pet_grooming"}' },
    { jumpType: 'SERVICE_CATEGORY', jumpParams: '{"categoryCode":""}' },
    { jumpType: 'SERVICE', jumpParams: '{"serviceId":null}' },
    { jumpType: 'SERVICE', jumpParams: '{"otherKey":"710401"}' },
  ]) assert.equal(placementJumpUrl(jump), null)
  // C 端无既有承接路由的类型（含未知类型/空串）V1 不跳转，不自行扩展白名单。
  for (const jumpType of ['NONE', 'COMMUNITY', 'MINIAPP_PATH', 'HOT_SERVICE', '', 'SHOP']) {
    assert.equal(placementJumpUrl({ jumpType, jumpParams: '{"storeId":"710302"}' }), null)
  }
})

// ── preview 契约 Mock banner：版式样板（不伪造图片素材，跳转 NONE）──

test('preview banner is a HOT_SERVICE_TOP layout sample without a fabricated image or jump', () => {
  assert.equal(previewHotServiceBanner.positionCode, PLACEMENT_POSITION_HOT_SERVICE_TOP)
  assert.equal(previewHotServiceBanner.coverUrl, null)
  assert.equal(placementJumpUrl(previewHotServiceBanner), null)
})

// ── 路由登记门禁：app.config.ts / package-check.cjs 与页面文件一一对应 ──

test('hot-services route is registered in app.config and the package gate with page files', () => {
  const appConfig = readFileSync(path.join(root, 'src', 'app.config.ts'), 'utf8')
  assert.match(appConfig,
    /root: 'consumer\/pages\/store-services', pages: \['index', 'service-detail', 'stores', 'hot-services'\]/)
  const packageCheck = readFileSync(path.join(root, 'package-check.cjs'), 'utf8')
  assert.match(packageCheck,
    /root: 'consumer\/pages\/store-services', pages: \['index', 'service-detail', 'stores', 'hot-services'\]/)
  for (const file of ['hot-services.tsx', 'hot-services.config.ts', 'hot-services.css']) {
    assert.ok(existsSync(path.join(root, 'src', 'consumer', 'pages', 'store-services', file)),
      `page file missing: ${file}`)
  }
  // 页面源码遵守二级页门禁：底栏只在 preview 通道传入；无底部 tab 栏的真实二级页。
  const page = readFileSync(path.join(root, 'src', 'consumer', 'pages', 'store-services', 'hot-services.tsx'), 'utf8')
  assert.match(page, /navigation=\{preview \?/)
  assert.match(page, /switchConsumerTab\('services'\)/)
})
