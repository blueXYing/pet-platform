import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { ConsumerApi, type LocalStore, type MarketingAssetReceipt, type MarketingAssetTransport } from '../../shared/consumer-api'
import type { WireRequest } from '../../shared/request'
import type { UploadFiles } from '../../shared/private-asset-upload'
import { MerchantMarketingApi, type CampaignInput } from '../marketing/api'
import { MarketingCoverUpload } from '../marketing/cover-upload'
import {
  CAMPAIGN_THEMES, PLAY_TYPE_OPTIONS, beijingDateText, beijingDayToUtcIso,
  campaignEditable, campaignStatusPill, campaignSubtitleLine, campaignThemeLabel,
} from '../marketing/model'

// Contract-76 P1 前端纯逻辑测试：模型解码（新字段+白名单+类型不符 fail）、overview 计数、
// 创建载荷（coverUrl 不得由前端传）、状态胶囊映射、主题 chips 数据、北京时间↔UTC 转换、
// 上传通道请求/响应校验与封面 journal（fake transport，不碰网络）。

const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00Z' }
const ok = (data: unknown, statusCode = 200) => ({ statusCode, data: { success: true, code: 'SUCCESS', data } })
const assetReceipt = (overrides: Record<string, unknown> = {}): MarketingAssetReceipt => ({
  assetId: '700', url: 'https://cdn.example.invalid/api/v1/c/marketing/assets/700',
  contentType: 'image/png', bytes: 9, width: 1200, height: 800, sha256: 'b'.repeat(64),
  ...overrides as Partial<MarketingAssetReceipt>,
})
const campaignCard = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  campaignId: '9001', name: '中秋宠物月饼礼盒', title: '中秋宠物月饼礼盒', subtitle: '节日限定礼盒',
  description: '活动规则与参与方式', themeCode: 'MID_AUTUMN', themeLabel: '中秋',
  highlightText: '宠物月饼礼盒', playType: 'THEME', coverAssetId: '700',
  coverUrl: 'https://cdn.example.invalid/api/v1/c/marketing/assets/700',
  status: 'DRAFT', startAt: '2026-09-01T16:00:00.000Z', endAt: '2026-10-15T15:59:59.999Z',
  version: '3', rejectReason: null, ...overrides,
})
const overviewData = { totalCampaignCount: 2, activeCampaignCount: 1, pendingReviewCount: 1, enrolledCampaignCount: 3 }

type Setup = ReturnType<typeof setup>
function setup(json: (spec: WireRequest) => unknown = spec => {
  if (spec.path === '/api/v1/c/auth/session') return session
  if (spec.path === '/api/v1/merchant/marketing/my-campaigns' && spec.method === 'GET') return { items: [campaignCard()], page: 1, pageSize: 50, total: 1 }
  if (spec.path === '/api/v1/merchant/marketing/overview') return overviewData
  if (spec.path.startsWith('/api/v1/merchant/marketing/my-campaigns') && spec.method !== 'GET') return { id: '9001', status: 'DRAFT', version: '4', occurredAt: '2026-11-17T08:00:00.000Z' }
  return {}
}) {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => { values.set(key, structuredClone(value)) }, remove: key => { values.delete(key) } }
  values.set('pet.c.session.v1', { ...session, accessToken: 'test-only-token', tokenType: 'Bearer' })
  const requests: WireRequest[] = []
  const marketingCalls: { filePath: string; requestId: string; authorization: string; merchantId: string; storeId: string }[] = []
  let respond: MarketingAssetTransport = async () => ok(assetReceipt(), 201)
  const marketing: MarketingAssetTransport = async input => { marketingCalls.push(input); return respond(input) }
  const api = new ConsumerApi(async spec => { requests.push(spec); return ok(spec.path === '/api/v1/c/auth/session' ? session : json(spec)) }, store, async () => randomUUID(),
    undefined, undefined, undefined, undefined, marketing)
  let chosen = 0
  const removed: string[] = []
  const files: UploadFiles = {
    choose: async () => { chosen++; return 'original' },
    save: async (_path, id) => `/owned/${id}`,
    owns: (path, id) => path === `/owned/${id}`,
    inspect: async () => ({ sha256: 'a'.repeat(64), bytes: 9 }),
    remove: async path => { removed.push(path) },
  }
  return {
    api, store, requests, marketingCalls, files, removed,
    chosen: () => chosen,
    respondWith: (next: MarketingAssetTransport) => { respond = next },
    async merchantScope() {
      await api.restore()
      api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '301', storeId: '401' })
    },
  }
}

test('my-campaigns decode: new P1 fields survive strict decoding and unknown fields are ignored', async () => {
  const h = await setup(); await h.merchantScope()
  const [campaign] = await new MerchantMarketingApi(h.api).myCampaigns('301', '401')
  assert.equal(campaign.campaignId, '9001')
  assert.equal(campaign.description, '活动规则与参与方式')
  assert.equal(campaign.themeCode, 'MID_AUTUMN')
  assert.equal(campaign.themeLabel, '中秋')
  assert.equal(campaign.highlightText, '宠物月饼礼盒')
  assert.equal(campaign.playType, 'THEME')
  assert.equal(campaign.coverAssetId, '700')
  assert.equal(campaign.coverUrl, 'https://cdn.example.invalid/api/v1/c/marketing/assets/700')
  assert.equal(campaign.rejectReason, null)
})

test('my-campaigns decode: enum whitelist / id format / type mismatch must fail', async () => {
  const cases: Record<string, unknown>[] = [
    { themeCode: 'HALLOWEEN' },
    { themeCode: 7 },
    { playType: 'FLASH_PRICE' },
    { coverAssetId: 'not-an-id' },
    { description: 42 },
    { highlightText: true },
    { rejectReason: { reason: 'x' } },
  ]
  for (const overrides of cases) {
    const h = await setup(() => ({ items: [campaignCard(overrides)], page: 1, pageSize: 50, total: 1 }))
    await h.merchantScope()
    await assert.rejects(new MerchantMarketingApi(h.api).myCampaigns('301', '401'), /INVALID_RESPONSE/, JSON.stringify(overrides))
  }
  // 长度上限：description ≤300 码点、highlightText ≤100 码点。
  const h = await setup(() => ({ items: [campaignCard({ description: '长'.repeat(301) })], page: 1, pageSize: 50, total: 1 }))
  await h.merchantScope()
  await assert.rejects(new MerchantMarketingApi(h.api).myCampaigns('301', '401'), /INVALID_RESPONSE/)
})

test('overview decode: exactly the four counts; non-integer or negative counts fail', async () => {
  const h = await setup(); await h.merchantScope()
  assert.deepEqual(await new MerchantMarketingApi(h.api).overview('301', '401'), overviewData)
  const request = h.requests.find(spec => spec.path === '/api/v1/merchant/marketing/overview')
  assert.equal(request?.method, 'GET')
  assert.deepEqual(request?.data, { merchantId: '301', storeId: '401' })
  for (const bad of [
    { ...overviewData, totalCampaignCount: '2' },
    { ...overviewData, activeCampaignCount: -1 },
    { ...overviewData, pendingReviewCount: 1.5 },
    { totalCampaignCount: 1, activeCampaignCount: 1, pendingReviewCount: 1 }, // enrolledCampaignCount 缺失
  ]) {
    const h2 = await setup(() => bad)
    await h2.merchantScope()
    await assert.rejects(new MerchantMarketingApi(h2.api).overview('301', '401'), /INVALID_RESPONSE/, JSON.stringify(bad))
  }
})

test('create/update payload carries the P1 fields and never a client coverUrl', async () => {
  const h = await setup(); await h.merchantScope()
  const face = new MerchantMarketingApi(h.api)
  const input: CampaignInput = {
    name: '中秋宠物月饼礼盒', title: '中秋宠物月饼礼盒', subtitle: '节日限定礼盒',
    description: '活动规则与参与方式', themeCode: 'MID_AUTUMN', highlightText: '宠物月饼礼盒',
    playType: 'THEME', coverAssetId: '700',
    startAt: '2026-08-31T16:00:00.000Z', endAt: '2026-10-15T15:59:59.999Z',
  }
  await face.createCampaign('301', '401', input)
  await face.updateCampaign('301', '401', '9001', input, '3')
  const [create, update] = h.requests.filter(spec => spec.path.startsWith('/api/v1/merchant/marketing/my-campaigns'))
  assert.equal(create?.method, 'POST')
  assert.equal(update?.method, 'PUT')
  assert.equal(update?.path, '/api/v1/merchant/marketing/my-campaigns/9001')
  assert.deepEqual(create?.data, { merchantId: '301', storeId: '401', ...input })
  assert.deepEqual(update?.data, { merchantId: '301', storeId: '401', ...input, expectedVersion: '3' })
  assert.ok(!('coverUrl' in (create?.data ?? {})), 'coverUrl must be derived server-side, never sent by the client')
})

test('status pill maps status plus the time window', () => {
  const start = '2026-09-01T00:00:00.000Z', end = '2026-09-30T23:59:59.000Z'
  const during = Date.parse('2026-09-15T00:00:00.000Z')
  assert.deepEqual(campaignStatusPill('DRAFT', during, start, end), { text: '草稿', tone: 'draft' })
  assert.deepEqual(campaignStatusPill('PENDING_REVIEW', during, start, end), { text: '待审核', tone: 'review' })
  assert.deepEqual(campaignStatusPill('REJECTED', during, start, end), { text: '已驳回', tone: 'rejected' })
  assert.deepEqual(campaignStatusPill('SCHEDULED', during, start, end), { text: '待开始', tone: 'scheduled' })
  assert.deepEqual(campaignStatusPill('ONLINE', Date.parse('2026-08-15T00:00:00.000Z'), start, end), { text: '待开始', tone: 'scheduled' })
  assert.deepEqual(campaignStatusPill('ONLINE', during, start, end), { text: '进行中', tone: 'online' })
  assert.deepEqual(campaignStatusPill('ONLINE', Date.parse('2026-10-15T00:00:00.000Z'), start, end), { text: '已结束', tone: 'ended' })
  assert.deepEqual(campaignStatusPill('ENDED', during, start, end), { text: '已结束', tone: 'ended' })
  assert.deepEqual(campaignStatusPill('CANCELED', during, start, end), { text: '已取消', tone: 'canceled' })
  assert.deepEqual(campaignStatusPill('PAUSED', during, start, end), { text: '已暂停', tone: 'paused' })
  assert.equal(campaignEditable('DRAFT'), true)
  assert.equal(campaignEditable('REJECTED'), true)
  assert.equal(campaignEditable('PENDING_REVIEW'), false)
  assert.equal(campaignEditable('ONLINE'), false)
})

test('theme chips: the seven whitelisted codes carry Chinese labels', () => {
  assert.deepEqual(CAMPAIGN_THEMES.map(theme => theme.code),
    ['SPRING_FESTIVAL', 'MID_AUTUMN', 'NATIONAL_DAY', 'CHRISTMAS', 'NEW_YEAR', 'DOUBLE_11', 'OTHER'])
  assert.deepEqual(CAMPAIGN_THEMES.map(theme => theme.label), ['春节', '中秋', '国庆', '圣诞', '元旦', '双11', '其他'])
  assert.equal(campaignThemeLabel('MID_AUTUMN'), '中秋')
  assert.equal(campaignThemeLabel(null), '未选择')
})

test('play type options: only THEME is selectable in P1, the rest are greyed 待开放', () => {
  assert.deepEqual(PLAY_TYPE_OPTIONS.filter(option => option.available).map(option => option.type), ['THEME'])
  for (const option of PLAY_TYPE_OPTIONS.filter(option => !option.available)) {
    assert.equal(option.hint, '待开放')
  }
})

test('beijing date pickers convert to ISO-8601 UTC and back', () => {
  assert.equal(beijingDayToUtcIso('2026-09-01'), '2026-08-31T16:00:00.000Z')
  assert.equal(beijingDayToUtcIso('2026-09-30', true), '2026-09-30T15:59:59.999Z')
  assert.throws(() => beijingDayToUtcIso('2026/09/01'), /INVALID_DATE/)
  assert.equal(beijingDateText('2026-08-31T16:00:00.000Z'), '2026-09-01')
  assert.equal(beijingDateText('2026-09-30T15:59:59.999Z'), '2026-09-30')
  assert.equal(beijingDateText(null), '--')
  assert.equal(campaignSubtitleLine({ subtitle: '节日限定礼盒', description: '说明' }), '节日限定礼盒')
  assert.equal(campaignSubtitleLine({ subtitle: null, description: '说明' }), '说明')
  assert.equal(campaignSubtitleLine({ subtitle: null, description: null }), null)
})

test('upload channel: merchant scope, bearer credential and terminal request id reach the transport', async () => {
  const h = await setup(); await h.merchantScope()
  const receipt = await h.api.uploadMarketingAsset({ filePath: '/owned/1', requestId: randomUUID() })
  assert.deepEqual(receipt, assetReceipt())
  assert.equal(h.marketingCalls[0].authorization, 'Bearer test-only-token')
  assert.match(h.marketingCalls[0].requestId, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)
  assert.equal(h.marketingCalls[0].filePath, '/owned/1')
  // scope 走 query string（后端 onlyParameters 只认 merchantId/storeId + 单 file part）
  assert.equal(h.marketingCalls[0].merchantId, '301')
  assert.equal(h.marketingCalls[0].storeId, '401')
})

test('upload channel: non-merchant workspace or malformed request id fails closed without a call', async () => {
  const h = await setup(); await h.merchantScope()
  h.api.scope.replace({ userId: '101', workspace: 'consumer', merchantId: null, storeId: null })
  await assert.rejects(h.api.uploadMarketingAsset({ filePath: '/owned/1', requestId: randomUUID() }), /WORKSPACE_PATH_MISMATCH/)
  h.api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '301', storeId: '401' })
  await assert.rejects(h.api.uploadMarketingAsset({ filePath: '/owned/1', requestId: 'not-a-uuid' }), /REQUEST_ID_REQUIRED/)
  const disconnectedStore = new Map<string, unknown>([['pet.c.session.v1', { ...session, accessToken: 'test-only-token', tokenType: 'Bearer' }]])
  const disconnected = new ConsumerApi(async spec => ok(spec.path === '/api/v1/c/auth/session' ? session : {}), { get: key => disconnectedStore.get(key), set: (key, value) => { disconnectedStore.set(key, value) }, remove: key => { disconnectedStore.delete(key) } }, async () => randomUUID())
  await disconnected.restore()
  disconnected.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '301', storeId: '401' })
  await assert.rejects(disconnected.uploadMarketingAsset({ filePath: '/owned/1', requestId: randomUUID() }), /UPLOAD_NOT_CONNECTED/)
  assert.equal(h.marketingCalls.length, 0)
})

test('upload channel: receipt validation and 401 clearing follow the fail-closed envelope discipline', async () => {
  const h = await setup(); await h.merchantScope()
  const bad = [
    { sha256: 'zz' }, { bytes: 2097153 }, { width: 2001 }, { height: 0 },
    { contentType: 'image/webp' }, { url: 'javascript:alert(1)' }, { assetId: 'x' },
  ]
  for (const overrides of bad) {
    h.respondWith(async () => ok(assetReceipt(overrides), 201))
    await assert.rejects(h.api.uploadMarketingAsset({ filePath: '/owned/1', requestId: randomUUID() }), /INVALID_RESPONSE/, JSON.stringify(overrides))
  }
  // 非 2xx 或信封缺 success 一律按 ApiError 收口，不伪造成功。
  h.respondWith(async () => ({ statusCode: 413, data: { code: 'MARKETING_ASSET_TOO_LARGE', success: false } }))
  await assert.rejects(h.api.uploadMarketingAsset({ filePath: '/owned/1', requestId: randomUUID() }), /MARKETING_ASSET_TOO_LARGE/)
  // 401 后清理本地凭据（独立 setup：clear 后凭据不再可用）。
  const h2 = await setup(); await h2.merchantScope()
  h2.respondWith(async () => ({ statusCode: 401, data: { code: 'COMMON_UNAUTHORIZED', success: false } }))
  await assert.rejects(h2.api.uploadMarketingAsset({ filePath: '/owned/1', requestId: randomUUID() }), /COMMON_UNAUTHORIZED/)
  assert.equal(h2.api.currentSession, null, '401 must clear the credential')
})

test('cover journal: success → acknowledge removes the local copy; the journal key isolates user/merchant/store', async () => {
  const h = await setup(); await h.merchantScope()
  const upload = new MarketingCoverUpload(h.api, h.store, h.files)
  assert.deepEqual(await upload.upload(), assetReceipt())
  const pending = upload.pending()
  assert.equal(pending?.receipt?.assetId, '700')
  assert.equal(pending?.ownerUserId, '101')
  assert.notEqual(h.store.get('pet.marketing-cover-upload.v1:101:301:401'), undefined, 'journal key must embed user/merchant/store')
  assert.equal(h.removed.length, 0)
  await upload.acknowledge('700')
  assert.equal(upload.pending(), null)
  assert.deepEqual(h.removed, [`/owned/${pending?.requestId}`])
  h.api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '301', storeId: '402' })
  const other = new MarketingCoverUpload(h.api, h.store, h.files)
  assert.equal(other.pending(), null, 'another store must not see this store journal')
  await other.upload()
  await other.discard()
})

test('cover journal: unknown outcomes keep the exact file and request id for retry without re-choosing', async () => {
  const h = await setup(); await h.merchantScope()
  h.respondWith(async () => { throw new Error('lost connection') })
  const first = new MarketingCoverUpload(h.api, h.store, h.files)
  await assert.rejects(first.upload(), /lost connection/)
  const pending = first.pending()
  assert.equal(pending?.attempted, true)
  assert.equal(pending?.receipt, undefined)
  h.respondWith(async () => ok(assetReceipt(), 201))
  const second = new MarketingCoverUpload(h.api, h.store, h.files)
  assert.deepEqual(await second.upload(), assetReceipt())
  assert.deepEqual(h.marketingCalls[0], h.marketingCalls[1])
  assert.equal(h.chosen(), 1)
})

test('cover journal: definite pick rejections allow replacement; oversized files never reach the transport', async () => {
  const h = await setup(); await h.merchantScope()
  h.respondWith(async () => ({ statusCode: 413, data: { code: 'MARKETING_ASSET_TOO_LARGE', success: false } }))
  const upload = new MarketingCoverUpload(h.api, h.store, h.files)
  await assert.rejects(upload.upload(), /MARKETING_ASSET_TOO_LARGE/)
  assert.equal(upload.pending()?.rejected, true)
  await upload.discard()
  assert.equal(upload.pending(), null)
  assert.equal(h.removed.length, 1)
  const probe = new MarketingCoverUpload(h.api, h.store, { ...h.files, inspect: async () => ({ sha256: 'a'.repeat(64), bytes: 3000000 }) })
  await assert.rejects(probe.upload(), /UPLOAD_FILE_INVALID/)
  const dimensions = new MarketingCoverUpload(h.api, h.store, h.files, async () => ({ width: 2500, height: 100 }))
  await assert.rejects(dimensions.upload(), /UPLOAD_FILE_INVALID/)
  assert.equal(h.marketingCalls.length, 1, 'only the first 413 attempt reached the transport')
})
