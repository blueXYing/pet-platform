import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../shared/request'
import type { RequestSpec } from '../../shared/request'
import { WorkspaceScope } from '../../shared/workspace'
import { isMerchantReviewPath, type ConsumerApi } from '../../shared/consumer-api'
import type { MerchantAdmission } from '../../shared/merchant-repositories'
import {
  ReviewAppealClient, decodeReviewAggregate, decodeReviewDetail, decodeReviewPage,
  type ReviewAggregate, type ReviewPage,
} from '../../shared/review-appeal-api'
import { MerchantReviewController } from '../review-appeal/controller'
import type { MerchantReviewDeps } from '../review-appeal/repository'
import {
  averageStars, averageText, goodRatePercent, groupedCount, reviewerInitial, reviewerName,
  scoredCountText, starBars,
} from '../review-appeal/model'

// CCR-M-REVIEW-VIEW-001（56号 §7）：买家昵称/头像增量键 + 门店评分聚合只读面。

const legacyItem = {
  reviewId: '9001', orderId: '8001', storeScore: '5.0', serviceScore: '4.0', staffScore: '3.0',
  compositeScore: '4.2', scoreIncluded: true, visibilityStatus: 'PUBLISHED', content: '服务尚可',
  createdAt: '2026-10-01T02:00:00.000Z', appealStatus: null, appealId: null,
}
const identityItem = { ...legacyItem, reviewerNickname: '林晓雨', reviewerAvatarUrl: 'https://thirdwx.qlogo.cn/mmopen/a/132' }
const page = (items: unknown[]) => ({ page: 1, pageSize: 20, total: items.length, items })
const detailOf = (item: Record<string, unknown>) => ({ ...item, appealReason: null, appealCreatedAt: null, decisionReason: null, decidedAt: null })
const figmaAggregate = { totalReviews: 1790, scoredCount: 1790, averageScore: '4.6', scoreCounts: { '1': 23, '2': 41, '3': 98, '4': 342, '5': 1286 } }
const emptyAggregate = { totalReviews: 0, scoredCount: 0, averageScore: null, scoreCounts: { '1': 0, '2': 0, '3': 0, '4': 0, '5': 0 } }
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))

// ---------------------------------------------------------------- 列表/详情解码器放宽

test('legacy twelve-key items (pre-§7 server) still decode with a null buyer identity', () => {
  const decoded = decodeReviewPage(page([legacyItem])).items[0]!
  assert.equal(decoded.compositeScore, '4.2')
  assert.equal(decoded.reviewerNickname, null)
  assert.equal(decoded.reviewerAvatarUrl, null)
  const detail = decodeReviewDetail(detailOf(legacyItem))
  assert.equal(detail.reviewerNickname, null)
  assert.equal(detail.reviewerAvatarUrl, null)
})

test('the §7 identity keys decode as-is (raw nickname, https avatar) on list and detail', () => {
  const decoded = decodeReviewPage(page([identityItem])).items[0]!
  assert.equal(decoded.reviewerNickname, '林晓雨', 'nickname is never masked client-side')
  assert.equal(decoded.reviewerAvatarUrl, 'https://thirdwx.qlogo.cn/mmopen/a/132')
  const detail = decodeReviewDetail(detailOf(identityItem))
  assert.equal(detail.reviewerNickname, '林晓雨')
  // Both keys nullable, and each key is independently optional.
  const nulls = decodeReviewPage(page([{ ...legacyItem, reviewerNickname: null, reviewerAvatarUrl: null }])).items[0]!
  assert.equal(nulls.reviewerNickname, null)
  assert.equal(nulls.reviewerAvatarUrl, null)
  const nicknameOnly = decodeReviewPage(page([{ ...legacyItem, reviewerNickname: '张伟' }])).items[0]!
  assert.equal(nicknameOnly.reviewerNickname, '张伟')
  assert.equal(nicknameOnly.reviewerAvatarUrl, null)
  // 64 code points (surrogate pairs count once) is the user_account.nickname bound.
  const emoji = '🐱'.repeat(64)
  assert.equal(decodeReviewPage(page([{ ...legacyItem, reviewerNickname: emoji }])).items[0]!.reviewerNickname, emoji)
})

test('identity keys are strictly validated and unknown keys still fail closed', () => {
  const bad: Record<string, unknown>[] = [
    { reviewerNickname: '' },
    { reviewerNickname: 42 },
    { reviewerNickname: '长'.repeat(65) },
    { reviewerAvatarUrl: 'http://thirdwx.qlogo.cn/a.png' },
    { reviewerAvatarUrl: 'asset:900199900001' },
    { reviewerAvatarUrl: 'https://' },
    { reviewerAvatarUrl: `https://cdn.example.com/${'a'.repeat(512)}` },
    { reviewerAvatarUrl: 7 },
    { reviewerUserId: '8410' },
    { reviewerPhone: '138****0000' },
  ]
  for (const over of bad) {
    assert.throws(() => decodeReviewPage(page([{ ...legacyItem, ...over }])), /INVALID_RESPONSE/, JSON.stringify(over))
    assert.throws(() => decodeReviewDetail(detailOf({ ...legacyItem, ...over })), /INVALID_RESPONSE/, JSON.stringify(over))
  }
  // A missing base key is still a contract violation even with the identity keys present.
  const { appealId: _dropped, ...missing } = identityItem
  assert.throws(() => decodeReviewPage(page([missing])), /INVALID_RESPONSE/)
})

// ---------------------------------------------------------------- 聚合解码器

test('aggregate decoder accepts the exact four-key fact and the empty store 0/0/null/all-0', () => {
  const decoded = decodeReviewAggregate(copy(figmaAggregate))
  assert.deepEqual(decoded, figmaAggregate)
  const withHistory = decodeReviewAggregate({ ...copy(figmaAggregate), totalReviews: 1800 })
  assert.equal(withHistory.totalReviews, 1800, 'totalReviews may include unscored history')
  const empty = decodeReviewAggregate(copy(emptyAggregate))
  assert.equal(empty.averageScore, null)
  assert.deepEqual(empty.scoreCounts, { '1': 0, '2': 0, '3': 0, '4': 0, '5': 0 })
  // Unscored-only store: totals > 0 but no average and empty buckets.
  assert.equal(decodeReviewAggregate({ ...copy(emptyAggregate), totalReviews: 3 }).totalReviews, 3)
})

test('aggregate decoder fails closed on any shape or invariant violation', () => {
  const base = copy(figmaAggregate)
  const bad: unknown[] = [
    { ...base, extra: 1 },
    { totalReviews: 1, scoredCount: 1, averageScore: '4.0' },
    { ...base, scoreCounts: { ...base.scoreCounts, '0': 0 } },
    { ...base, scoreCounts: { '1': 23, '2': 41, '3': 98, '4': 342 } },
    { ...base, averageScore: 4.6 },
    { ...base, averageScore: '4.65' },
    { ...base, averageScore: '5.1' },
    { ...base, averageScore: null },
    { ...copy(emptyAggregate), averageScore: '4.0' },
    { ...base, scoredCount: 1791, totalReviews: 1791 },
    { ...base, totalReviews: 1789 },
    { ...base, totalReviews: -1 },
    { ...base, totalReviews: '1790' },
    { ...base, scoreCounts: { ...base.scoreCounts, '5': 1285.5 } },
    { ...base, scoreCounts: { ...base.scoreCounts, '5': '1286' } },
    { ...base, scoreCounts: [23, 41, 98, 342, 1286] },
    null,
  ]
  for (const value of bad) assert.throws(() => decodeReviewAggregate(value), /INVALID_RESPONSE/, JSON.stringify(value))
})

test('summary client reads exactly GET /api/v1/merchant/reviews/summary with the workspace coordinates', async () => {
  const seen: RequestSpec[] = []
  const api = {
    request: async <T>(spec: RequestSpec, decode: (value: unknown) => T): Promise<T> => { seen.push(spec); return decode(copy(figmaAggregate)) },
  } as unknown as ConsumerApi
  const result = await new ReviewAppealClient(api).summary('101', '102')
  assert.deepEqual(seen, [{ path: '/api/v1/merchant/reviews/summary', method: 'GET', data: { merchantId: '101', storeId: '102' } }])
  assert.equal(result.averageScore, '4.6')
  assert.throws(() => new ReviewAppealClient(api).summary('x', '102'), /INVALID_RESPONSE/)
  // The shared transport guard admits the read-only route (GET only).
  assert.equal(isMerchantReviewPath({ path: '/api/v1/merchant/reviews/summary', method: 'GET' }), true)
  assert.equal(isMerchantReviewPath({ path: '/api/v1/merchant/reviews/summary', method: 'POST' }), false)
})

// ---------------------------------------------------------------- 展示推导

test('reviewer display: raw nickname, 匿名用户 fallback and a first-code-point initial', () => {
  assert.equal(reviewerName({ reviewerNickname: '林晓雨' }), '林晓雨')
  assert.equal(reviewerName({ reviewerNickname: null }), '匿名用户')
  assert.equal(reviewerName({ reviewerNickname: '   ' }), '匿名用户')
  assert.equal(reviewerInitial({ reviewerNickname: '林晓雨' }), '林')
  assert.equal(reviewerInitial({ reviewerNickname: null }), '匿')
  assert.equal(reviewerInitial({ reviewerNickname: '🐶旺财' }), '🐶', 'never splits a surrogate pair')
  assert.equal(reviewerInitial({ reviewerNickname: ' amy' }), 'a')
})

test('score card, histogram and stats derive only display from the server facts', () => {
  const figma = decodeReviewAggregate(copy(figmaAggregate))
  assert.equal(averageText(figma), '4.6')
  assert.equal(averageStars(figma), 5)
  assert.equal(scoredCountText(figma), '共 1,790 条计分评价')
  assert.equal(groupedCount(1790), '1,790')
  assert.equal(groupedCount(1234567), '1,234,567')
  assert.equal(groupedCount(0), '0')
  // 5 星在上；比例条以最多一档为满格（原稿 5星 1286 满格、4星 342 ≈ 26.6%）。
  assert.deepEqual(starBars(figma), [
    { star: '5', count: 1286, widthPercent: 100 },
    { star: '4', count: 342, widthPercent: 26.6 },
    { star: '3', count: 98, widthPercent: 7.6 },
    { star: '2', count: 41, widthPercent: 3.2 },
    { star: '1', count: 23, widthPercent: 1.8 },
  ])
  const empty = decodeReviewAggregate(copy(emptyAggregate))
  assert.equal(averageText(empty), '暂无评分')
  assert.equal(averageStars(empty), 0)
  assert.equal(scoredCountText(empty), '共 0 条计分评价')
  assert.ok(starBars(empty).every(bar => bar.widthPercent === 0 && bar.count === 0))
  assert.equal(averageStars({ averageScore: '2.4' }), 2)
  assert.equal(averageStars({ averageScore: '3.5' }), 4)
})

test('good rate = (4★+5★)/scoredCount, hidden without scored reviews and never rounded to a fake extreme', () => {
  const rate = (scored: number, four: number, five: number) => goodRatePercent({ scoredCount: scored, scoreCounts: { '1': scored - four - five, '2': 0, '3': 0, '4': four, '5': five } })
  assert.equal(goodRatePercent(decodeReviewAggregate(copy(figmaAggregate))), 91, '(342+1286)/1790 = 90.95% → 91%')
  assert.equal(goodRatePercent(decodeReviewAggregate(copy(emptyAggregate))), null, 'no scored data: not displayed, no fake 0%')
  assert.equal(rate(4, 2, 2), 100)
  assert.equal(rate(4, 0, 0), 0)
  assert.equal(rate(3, 1, 1), 67)
  assert.equal(rate(8, 1, 0), 13, '12.5% rounds half up')
  assert.equal(rate(300, 0, 299), 99, '99.67% is not every review: never 100%')
  assert.equal(rate(300, 1, 0), 1, '0.33% still has a good review: never 0%')
})

// ---------------------------------------------------------------- 控制器装配

const owner: MerchantAdmission = {
  merchantId: '101', storeId: '102', membershipKind: 'OWNER', admission: 'ALLOWED', checkedAt: '2026-10-01T00:00:00.000Z', authzVersion: '0123456789abcdef',
  facts: { application: { status: 'APPROVED' }, signing: { status: 'SIGNED' }, merchantStatus: 'ACTIVE', storeStatus: 'ACTIVE', staffEnabled: null },
  allowedActions: ['merchant.review.read'], reasonCodes: [], nextSteps: [],
}

function fixture(summary: MerchantReviewDeps['summary']) {
  const scope = new WorkspaceScope(); scope.replace({ userId: '1', workspace: 'merchant', merchantId: '101', storeId: '102' })
  let summaryCalls = 0, listCalls = 0, view: MerchantAdmission = copy(owner)
  const items = decodeReviewPage(page([identityItem, { ...legacyItem, reviewId: '9002', orderId: '8002' }])).items
  const deps: MerchantReviewDeps = {
    scope, admission: async () => copy(view),
    list: async (_m, _s, p, pageSize): Promise<ReviewPage> => { listCalls++; return { page: p, pageSize, total: items.length, items } },
    summary: (m, s) => { summaryCalls++; return summary(m, s) },
    detail: async () => { throw new Error('unused') },
    appeal: async () => { throw new Error('unused') },
    pendingAppeal: () => null, retireConflict: () => { throw new Error('UNCONFIRMED_WRITE') },
    saveDraft: () => {}, loadDraft: () => '', clearDraft: () => {},
  }
  return { deps, set view(v: MerchantAdmission) { view = v }, get counts() { return { summaryCalls, listCalls } } }
}

test('load publishes the aggregate next to the identity-bearing review list', async () => {
  const f = fixture(async (merchantId, storeId) => {
    assert.equal(merchantId, '101'); assert.equal(storeId, '102')
    return decodeReviewAggregate(copy(figmaAggregate))
  })
  const c = new MerchantReviewController(f.deps, f.deps.scope, { merchantId: '101', storeId: '102' }); await c.load()
  const state = c.getSnapshot()
  assert.equal(state.phase, 'ready')
  assert.equal(state.aggregate?.averageScore, '4.6')
  assert.equal(state.items[0]!.reviewerNickname, '林晓雨')
  assert.equal(reviewerName(state.items[1]!), '匿名用户')
  assert.equal(f.counts.summaryCalls, 1)
  c.dispose()
})

test('an unavailable aggregate only hides the summary section; the list stays ready', async () => {
  for (const failing of [
    async (): Promise<ReviewAggregate> => { throw new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503) },
    async (): Promise<ReviewAggregate> => { throw new Error('INVALID_RESPONSE') },
    (): Promise<ReviewAggregate> => { throw new Error('WORKSPACE_PATH_MISMATCH') },
  ]) {
    const f = fixture(failing)
    const c = new MerchantReviewController(f.deps, f.deps.scope, { merchantId: '101', storeId: '102' }); await c.load()
    const state = c.getSnapshot()
    assert.equal(state.phase, 'ready')
    assert.equal(state.aggregate, null, 'no fabricated 0/null aggregate on failure')
    assert.equal(state.items.length, 2)
    assert.equal(state.notice, '')
    c.dispose()
  }
})

test('a denied or STAFF session never reads the aggregate', async () => {
  const f = fixture(async () => decodeReviewAggregate(copy(figmaAggregate)))
  f.view = { ...owner, membershipKind: 'STAFF' }
  const c = new MerchantReviewController(f.deps, f.deps.scope, { merchantId: '101', storeId: '102' }); await c.load()
  assert.equal(c.getSnapshot().phase, 'entry')
  assert.equal(c.getSnapshot().aggregate, null)
  assert.deepEqual(f.counts, { summaryCalls: 0, listCalls: 0 })
  c.dispose()
})
