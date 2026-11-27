import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { campaignValidityLabel, decodeCampaignDetail, decodeCampaigns, decodePlacements } from '../marketing/model'

// 42 号 §十二：首页默认营销数据是平台活动（不关联商家、全部城市、长期有效），
// 列表页与落地页不显示"0 家门店"。

const root = fileURLToPath(new URL('../../..', import.meta.url))

const campaign = {
  campaignId: '720102', title: '宠物美容狂欢', subtitle: '洗澡美容低至 5 折起',
  tagCode: 'full_card', tagName: '限时5折',
  coverUrl: 'https://mtxoss2.oss-cn-hangzhou.aliyuncs.com/marketing/fullcard/grooming-clean.png',
  startAt: '2026-10-28T06:13:27.000Z', endAt: '2099-12-31T00:00:00.000Z', storeCount: 0,
}

// ── 有效期文案：默认数据的结束时间在 2099 年，显示"长期有效"而不是具体日期 ──

test('validity label hides the far-future end date of default campaigns', () => {
  assert.equal(campaignValidityLabel(campaign.startAt, campaign.endAt), '长期有效')
  assert.equal(
    campaignValidityLabel('2026-10-28T06:13:27.000Z', '2026-12-31T00:00:00.000Z'),
    '2026-10-28 至 2026-12-31')
})

// ── 接口形状：0 家门店、长期有效、插画是完整 https 地址的平台活动照常解码 ──

test('store-less long-running platform campaigns decode like any other campaign', () => {
  const placements = decodePlacements({ items: [{
    placementId: '720202', positionCode: 'HOME_ACTIVITY', campaignId: campaign.campaignId,
    title: campaign.title, subtitle: campaign.subtitle, tagCode: campaign.tagCode,
    tagName: campaign.tagName, coverUrl: campaign.coverUrl, templateCode: null,
    jumpType: 'CAMPAIGN_PAGE', jumpParams: '{"campaignId":"720102"}',
    startAt: campaign.startAt, endAt: campaign.endAt,
  }] })
  assert.equal(placements.length, 1)
  assert.equal(placements[0]!.coverUrl, campaign.coverUrl)

  const listed = decodeCampaigns({ items: [campaign], page: 1, pageSize: 10, total: 1 })
  assert.equal(listed.items[0]!.storeCount, 0)

  const detail = decodeCampaignDetail({
    campaign, schema: null, storeCount: 0, stores: [], couponCampaign: null,
  })
  assert.deepEqual(detail.stores, [])
  assert.equal(detail.storeCount, 0)
})

// ── 静态门禁：落地页不再渲染"暂无参与活动商家"（没有门店时整块隐藏） ──

test('landing page hides the store section instead of listing an empty merchant list', () => {
  const landing = readFileSync(
    path.join(root, 'src/consumer/pages/marketing/campaign.tsx'), 'utf8')
  assert.equal(landing.includes('暂无参与活动商家'), false)
  assert.match(landing, /detail\.stores\.length > 0/)
})
