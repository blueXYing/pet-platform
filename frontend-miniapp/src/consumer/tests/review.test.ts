import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import {
  canReviewEntry, compositePreview, decodeReviewEligibility, decodeReviewReceipt,
  eligibilityHeadline, emptyReviewDraft, isDefiniteReviewConflict, isReviewScenario,
  receiptHeadline, reviewCreateInput, reviewDimensions, validateReviewDraft,
  ReviewController, PreviewReviewRepository, PREVIEW_REVIEW_ORDER,
  type ReviewCreateInput, type ReviewDraft, type ReviewDraftErrors, type ReviewEligibility,
  type ReviewReceipt,
} from '../orders/review'
import { RealReviewRepository } from '../orders/review-repository'
import { decodeOrderDetail, PreviewOrderReadRepository, type OrderDetailView } from '../orders/model'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { ApiError } from '../../shared/request'
import { WorkspaceScope } from '../../shared/workspace'

// ---- 解码：本切片线上形状（10号 §3.14：资格恰四键 / 回执恰两键，毫秒 ISO 可空）。

const eligibleWire = {
  eligible: true, scoreIncluded: true,
  reviewDeadline: '2026-11-05T12:04:00.000Z', rejectCode: null,
}
const excludedWire = {
  eligible: true, scoreIncluded: false,
  reviewDeadline: '2026-11-05T12:04:00.000Z', rejectCode: null,
}

test('eligibility decoder accepts the verified and partial-refund projections', () => {
  const eligible = decodeReviewEligibility(JSON.parse(JSON.stringify(eligibleWire)))
  assert.equal(eligible.eligible, true)
  assert.equal(eligible.scoreIncluded, true)
  assert.equal(eligible.reviewDeadline, '2026-11-05T12:04:00.000Z')
  assert.equal(eligible.rejectCode, null)
  const excluded = decodeReviewEligibility(JSON.parse(JSON.stringify(excludedWire)))
  assert.equal(excluded.eligible, true)
  assert.equal(excluded.scoreIncluded, false)
  // 四个 rejectCode 都是契约词表（SSOT §11 + 2026-10-07 退款裁决 → REVIEW_NOT_ELIGIBLE）。
  for (const rejectCode of ['REVIEW_NOT_VERIFIED', 'REVIEW_WINDOW_EXPIRED', 'REVIEW_ALREADY_EXISTS', 'REVIEW_NOT_ELIGIBLE']) {
    const rejected = decodeReviewEligibility({ eligible: false, scoreIncluded: false, reviewDeadline: null, rejectCode })
    assert.equal(rejected.rejectCode, rejectCode)
  }
})

test('decoders fail closed on contract violations', () => {
  for (const mutate of [
    (v: Record<string, unknown>) => { v.extra = 1 },
    (v: Record<string, unknown>) => { delete v.rejectCode },
    (v: Record<string, unknown>) => { v.rejectCode = 'REVIEW_FROZEN' },
    (v: Record<string, unknown>) => { v.eligible = 'true' },
    (v: Record<string, unknown>) => { v.reviewDeadline = '2026-11-05T12:04:00Z' },     // 缺毫秒
    (v: Record<string, unknown>) => { v.reviewDeadline = '2026-11-05 12:04:00.000Z' }, // 非ISO
  ]) {
    const value = JSON.parse(JSON.stringify(eligibleWire)) as Record<string, unknown>
    mutate(value)
    assert.throws(() => decodeReviewEligibility(value), /INVALID_RESPONSE/, JSON.stringify(mutate))
  }
  for (const bad of [
    { reviewId: '970101001990001', scoreIncluded: true, extra: 1 },
    { reviewId: '970101001990001' },
    { reviewId: '0', scoreIncluded: true },
    { reviewId: '970101001990001', scoreIncluded: 'true' },
  ]) {
    assert.throws(() => decodeReviewReceipt(bad), /INVALID_RESPONSE/, JSON.stringify(bad))
  }
  assert.deepEqual(decodeReviewReceipt({ reviewId: '970101001990001', scoreIncluded: false }),
    { reviewId: '970101001990001', scoreIncluded: false } satisfies ReviewReceipt)
})

// ---- 表单草稿：结构校验归本模块，资格准入归服务端内核。

test('draft validation enforces the three 1..5 dimensions and the content bounds', () => {
  assert.deepEqual(validateReviewDraft(emptyReviewDraft()), {
    storeScore: '请选择 1~5 星评分', serviceScore: '请选择 1~5 星评分', staffScore: '请选择 1~5 星评分',
  })
  assert.deepEqual(validateReviewDraft({ storeScore: 5, serviceScore: 4, staffScore: 3, content: '' }), {})
  assert.equal((validateReviewDraft({ storeScore: 0, serviceScore: 4, staffScore: 3, content: '' }) as ReviewDraftErrors).storeScore !== undefined, true)
  assert.equal((validateReviewDraft({ storeScore: 6, serviceScore: 4, staffScore: 3, content: '' }) as ReviewDraftErrors).storeScore !== undefined, true)
  assert.equal((validateReviewDraft({ storeScore: 2.5, serviceScore: 4, staffScore: 3, content: '' }) as ReviewDraftErrors).storeScore !== undefined, true)
  assert.equal((validateReviewDraft({ storeScore: 5, serviceScore: 4, staffScore: 3, content: 'x'.repeat(2001) }) as ReviewDraftErrors).content !== undefined, true)
  assert.equal((validateReviewDraft({ storeScore: 5, serviceScore: 4, staffScore: 3, content: 'y'.repeat(2000) }) as ReviewDraftErrors).content, undefined)
})

test('submit payload keeps integer scores, omits blank content and closes the media capability', () => {
  assert.deepEqual(reviewCreateInput({ storeScore: 5, serviceScore: 4, staffScore: 3, content: '  整体服务很好  ' }),
    { storeScore: 5, serviceScore: 4, staffScore: 3, content: '整体服务很好', mediaFileIds: [] } satisfies ReviewCreateInput)
  assert.deepEqual(reviewCreateInput({ storeScore: 1, serviceScore: 1, staffScore: 1, content: '   ' }),
    { storeScore: 1, serviceScore: 1, staffScore: 1, content: null, mediaFileIds: [] } satisfies ReviewCreateInput)
})

// ---- 展示映射（ARCH-005：40/40/20 口径/综合分预览/资格文案只在此处）。

test('composite preview follows the 40/40/20 weighting and stays null until all three dimensions are rated', () => {
  assert.equal(compositePreview({ storeScore: 5, serviceScore: 4, staffScore: 3, content: '' }), '4.2')
  assert.equal(compositePreview({ storeScore: 5, serviceScore: 5, staffScore: 5, content: '' }), '5.0')
  assert.equal(compositePreview({ storeScore: 1, serviceScore: 1, staffScore: 1, content: '' }), '1.0')
  assert.equal(compositePreview({ storeScore: 0, serviceScore: 4, staffScore: 3, content: '' }), null)
  assert.deepEqual(reviewDimensions.map(dimension => dimension.weight), ['40%', '40%', '20%'])
  assert.deepEqual(reviewDimensions.map(dimension => dimension.label), ['门店评分', '服务评分', '人员评分'])
})

test('eligibility copy states every §3.14 rejection honestly', () => {
  const of = (rejectCode: ReviewEligibility['rejectCode'], eligible = false): ReviewEligibility =>
    ({ eligible, scoreIncluded: false, reviewDeadline: null, rejectCode })
  assert.equal(eligibilityHeadline({ eligible: true, scoreIncluded: true, reviewDeadline: null, rejectCode: null }), '本单可评价')
  assert.equal(eligibilityHeadline({ eligible: true, scoreIncluded: false, reviewDeadline: null, rejectCode: null }), '本单可评价（不计入商家评分）')
  assert.equal(eligibilityHeadline(of('REVIEW_NOT_VERIFIED')), '服务核销完成后才能评价')
  assert.equal(eligibilityHeadline(of('REVIEW_WINDOW_EXPIRED')), '核销后 30 天评价期已过')
  assert.equal(eligibilityHeadline(of('REVIEW_ALREADY_EXISTS')), '本单已评价过')
  assert.equal(eligibilityHeadline(of('REVIEW_NOT_ELIGIBLE')), '订单退款后不可评价')
  assert.equal(receiptHeadline({ reviewId: '970101001990001', scoreIncluded: true }), '评价提交成功')
  assert.equal(receiptHeadline({ reviewId: '970101001990001', scoreIncluded: false }), '评价提交成功（本单部分退款，不计入商家评分）')
})

test('entry gating reads only the server actions boolean; definite conflicts follow 12号 §11', async () => {
  const completed = await new PreviewOrderReadRepository('normal').detail(PREVIEW_REVIEW_ORDER)
  assert.equal(canReviewEntry(completed), true)
  const detail: OrderDetailView = decodeOrderDetail({ ...JSON.parse(JSON.stringify(completed)), actions: null })
  assert.equal(canReviewEntry(detail), false)
  assert.equal(isDefiniteReviewConflict(new ApiError('REVIEW_ALREADY_EXISTS', 409)), true)
  assert.equal(isDefiniteReviewConflict(new ApiError('REVIEW_WINDOW_EXPIRED', 409)), true)
  assert.equal(isDefiniteReviewConflict(new ApiError('REVIEW_NOT_ELIGIBLE', 409)), true)
  assert.equal(isDefiniteReviewConflict(new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409)), false)
  assert.equal(isDefiniteReviewConflict(new ApiError('COMMON_INVALID_ARGUMENT', 400)), false)
})

test('scenario guard only accepts the registered preview scenarios', () => {
  assert.equal(isReviewScenario('fresh'), true)
  assert.equal(isReviewScenario('done'), true)
  assert.equal(isReviewScenario('ineligible'), true)
  assert.equal(isReviewScenario('other'), false)
  assert.equal(isReviewScenario(undefined), false)
})

// ---- preview 仓库：资格两态 + 一单一评 + 评分边界。

test('preview repository mirrors the kernel rules per scenario', async () => {
  const fresh = new PreviewReviewRepository('fresh')
  assert.equal((await fresh.eligibility(PREVIEW_REVIEW_ORDER)).eligible, true)
  const receipt = await fresh.create(PREVIEW_REVIEW_ORDER, reviewCreateInput({ storeScore: 5, serviceScore: 4, staffScore: 3, content: '' }))
  assert.equal(typeof receipt.reviewId, 'string')
  assert.equal((await fresh.eligibility(PREVIEW_REVIEW_ORDER)).rejectCode, 'REVIEW_ALREADY_EXISTS')
  await assert.rejects(fresh.create(PREVIEW_REVIEW_ORDER, reviewCreateInput({ storeScore: 5, serviceScore: 4, staffScore: 3, content: '' })),
    (error: unknown) => error instanceof ApiError && error.code === 'REVIEW_ALREADY_EXISTS')
  const done = new PreviewReviewRepository('done')
  assert.equal((await done.eligibility(PREVIEW_REVIEW_ORDER)).rejectCode, 'REVIEW_ALREADY_EXISTS')
  await assert.rejects(done.create(PREVIEW_REVIEW_ORDER, reviewCreateInput({ storeScore: 5, serviceScore: 4, staffScore: 3, content: '' })),
    (error: unknown) => error instanceof ApiError && error.code === 'REVIEW_ALREADY_EXISTS')
  const ineligible = new PreviewReviewRepository('ineligible')
  assert.equal((await ineligible.eligibility(PREVIEW_REVIEW_ORDER)).rejectCode, 'REVIEW_NOT_VERIFIED')
  assert.equal(canReviewEntry(await ineligible.detail(PREVIEW_REVIEW_ORDER)), false)
})

// ---- 控制器：门控 + 资格回读 + 提交 + 终局冲突退槽。

const scope = () => new WorkspaceScope()

test('controller gates on actions, loads eligibility, submits and flips to the receipt', async () => {
  const repository = new PreviewReviewRepository('fresh')
  const controller = new ReviewController({
    detail: target => repository.detail(target),
    eligibility: target => repository.eligibility(target),
    create: (target, input) => repository.create(target, input),
    pendingReview: () => repository.pendingReview(),
    retireConflict: () => repository.retireConflict(),
  }, scope())
  await controller.load(PREVIEW_REVIEW_ORDER)
  assert.equal(controller.getSnapshot().phase, 'ready')
  assert.equal(controller.getSnapshot().eligibility?.eligible, true)
  const draft: ReviewDraft = { storeScore: 5, serviceScore: 4, staffScore: 3, content: '不错' }
  await controller.submit(PREVIEW_REVIEW_ORDER, draft)
  assert.notEqual(controller.getSnapshot().receipt, null)
  assert.equal(controller.getSnapshot().receipt?.scoreIncluded, true)
  // 一单一评：资格面随即翻为不可评（REVIEW_ALREADY_EXISTS），回执保留展示。
  assert.equal(controller.getSnapshot().eligibility?.rejectCode, 'REVIEW_ALREADY_EXISTS')
  controller.dispose()
})

test('controller refuses incomplete drafts and ineligible orders without sending anything', async () => {
  const sent: ReviewCreateInput[] = []
  const repository = new PreviewReviewRepository('fresh')
  const controller = new ReviewController({
    detail: target => repository.detail(target),
    eligibility: target => repository.eligibility(target),
    create: (target, input) => { sent.push(input); return repository.create(target, input) },
    pendingReview: () => null,
    retireConflict: () => { throw new Error('UNCONFIRMED_WRITE') },
  }, scope())
  await controller.load(PREVIEW_REVIEW_ORDER)
  await controller.submit(PREVIEW_REVIEW_ORDER, emptyReviewDraft())
  assert.ok(/请完成三维评分/.test(controller.getSnapshot().notice))
  assert.equal(sent.length, 0)
  const ineligibleRepository = new PreviewReviewRepository('ineligible')
  const ineligible = new ReviewController({
    detail: target => ineligibleRepository.detail(target),
    eligibility: target => ineligibleRepository.eligibility(target),
    create: (target, input) => ineligibleRepository.create(target, input),
    pendingReview: () => null,
    retireConflict: () => { throw new Error('UNCONFIRMED_WRITE') },
  }, scope())
  await ineligible.load(PREVIEW_REVIEW_ORDER)
  assert.equal(ineligible.getSnapshot().phase, 'ineligible')
  await ineligible.submit(PREVIEW_REVIEW_ORDER, { storeScore: 5, serviceScore: 4, staffScore: 3, content: '' })
  assert.equal(sent.length, 0)
  controller.dispose(); ineligible.dispose()
})

test('controller fails closed on read errors and 401 routes to the login state', async () => {
  const forbidden = new ReviewController({
    detail: () => Promise.reject(new ApiError('COMMON_NOT_FOUND', 404)),
    eligibility: () => Promise.reject(new ApiError('COMMON_NOT_FOUND', 404)),
    create: () => { throw new Error('must not send') },
    pendingReview: () => null,
    retireConflict: () => { throw new Error('UNCONFIRMED_WRITE') },
  }, scope())
  await forbidden.load(PREVIEW_REVIEW_ORDER)
  assert.equal(forbidden.getSnapshot().phase, 'load-error')
  assert.ok(/不存在或仅订单本人/.test(forbidden.getSnapshot().notice))
  const unauthorized = new ReviewController({
    detail: () => Promise.reject(new ApiError('COMMON_UNAUTHORIZED', 401)),
    eligibility: () => Promise.reject(new ApiError('COMMON_UNAUTHORIZED', 401)),
    create: () => { throw new Error('must not send') },
    pendingReview: () => null,
    retireConflict: () => { throw new Error('UNCONFIRMED_WRITE') },
  }, scope())
  await unauthorized.load(PREVIEW_REVIEW_ORDER)
  assert.equal(unauthorized.getSnapshot().phase, 'unauthorized')
  forbidden.dispose(); unauthorized.dispose()
})

test('controller retires the journal on a definite conflict; unknown outcomes keep the original command', async () => {
  let retired = 0
  const repository = new PreviewReviewRepository('fresh')
  const conflict = new ReviewController({
    detail: target => repository.detail(target),
    eligibility: target => repository.eligibility(target),
    // 服务端在并发/重复下回一单一评终局 409：退槽后重新读取，拒绝文案保持可见。
    create: () => Promise.reject(new ApiError('REVIEW_ALREADY_EXISTS', 409)),
    pendingReview: () => null,
    retireConflict: () => { retired += 1 },
  }, scope())
  await conflict.load(PREVIEW_REVIEW_ORDER)
  assert.equal(conflict.getSnapshot().phase, 'ready')
  await conflict.submit(PREVIEW_REVIEW_ORDER, { storeScore: 5, serviceScore: 4, staffScore: 3, content: '' })
  assert.ok(/已评价过/.test(conflict.getSnapshot().notice))
  assert.equal(retired, 1)
  const freshRepository = new PreviewReviewRepository('fresh')
  const unknown = new ReviewController({
    detail: target => freshRepository.detail(target),
    eligibility: target => freshRepository.eligibility(target),
    create: () => Promise.reject(new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503)),
    pendingReview: () => ({ storeScore: 5, serviceScore: 4, staffScore: 3, content: null }),
    retireConflict: () => { throw new Error('UNCONFIRMED_WRITE') },
  }, scope())
  await unknown.load(PREVIEW_REVIEW_ORDER)
  await unknown.submit(PREVIEW_REVIEW_ORDER, { storeScore: 5, serviceScore: 4, staffScore: 3, content: '' })
  assert.ok(/结果尚未确认/.test(unknown.getSnapshot().notice))
  assert.notEqual(unknown.getSnapshot().pending, null)
  conflict.dispose(); unknown.dispose()
})

// ---- 真实仓库：路径/幂等槽/回执解码/终局退槽（本地假 transport，只验通道语义）。

// ---- 真实 repository：经 ConsumerApi 白名单/会话门禁/幂等槽的 §3.14 路由。

const sessionView = { userId: '957001', sessionId: '957101', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const ok = (data: unknown, statusCode = 200) => ({ statusCode, data: { code: 'SUCCESS', message: 'ok', data, traceId: 'test' } })
const failure = (statusCode: number, code: string) => ({ statusCode, data: { code, message: 'x', data: null, traceId: 'test' } })

function authenticatedApi(transport: (request: { method: string; path: string; data?: Record<string, unknown>; requestId?: string; headers: Record<string, string> }) => Promise<{ statusCode: number; data: unknown }>) {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, JSON.parse(JSON.stringify(value))), remove: key => { values.delete(key) } }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'test-only', tokenType: 'Bearer' })
  const api = new ConsumerApi(transport, store, async () => randomUUID())
  return { api, restore: () => api.restore() }
}

test('real repository reads eligibility without a request id and posts the strict body with a terminal UUID', async () => {
  const seen: { method: string; path: string; data?: Record<string, unknown>; requestId?: string; headers: Record<string, string> }[] = []
  const { api, restore } = authenticatedApi(async request => {
    seen.push({ method: request.method, path: request.path, data: request.data, requestId: request.requestId, headers: request.headers })
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.method === 'GET' && request.path === '/api/v1/c/orders/900101001990004/review-eligibility') return ok(JSON.parse(JSON.stringify(eligibleWire)))
    if (request.method === 'POST') return ok({ reviewId: '970101001990009', scoreIncluded: true }, 201)
    throw new Error('UNEXPECTED_PATH ' + request.method + ' ' + request.path)
  })
  await restore()
  const repository = new RealReviewRepository(api)
  const eligibility = await repository.eligibility('900101001990004')
  assert.equal(eligibility.eligible, true)
  const read = seen.find(entry => entry.method === 'GET')!
  assert.equal(read.requestId, undefined) // GET 无 X-Request-Id
  const receipt = await repository.create('900101001990004', { storeScore: 5, serviceScore: 4, staffScore: 3, content: null, mediaFileIds: [] })
  assert.deepEqual(receipt, { reviewId: '970101001990009', scoreIncluded: true } satisfies ReviewReceipt)
  const write = seen.find(entry => entry.method === 'POST')!
  assert.equal(write.path, '/api/v1/c/orders/900101001990004/reviews')
  assert.deepEqual(write.data, { storeScore: 5, serviceScore: 4, staffScore: 3, content: null, mediaFileIds: [] })
  assert.match(write.requestId!, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)
  assert.equal(write.headers['X-Request-Id'], write.requestId)
  assert.equal(repository.pendingReview('900101001990004'), null)
})

test('real repository journals the write, replays the same key, and rejects changed payloads', async () => {
  const writes: { requestId: string }[] = []
  let attempts = 0
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.method === 'GET') return ok(JSON.parse(JSON.stringify(eligibleWire)))
    writes.push({ requestId: request.requestId! }); attempts++
    if (attempts === 1) throw new Error('test-only lost ACK after real commit')
    return ok({ reviewId: '970101001990009', scoreIncluded: true }, 200)
  })
  await restore()
  const repository = new RealReviewRepository(api)
  const input = { storeScore: 5, serviceScore: 4, staffScore: 3, content: null, mediaFileIds: [] }
  await assert.rejects(repository.create('900101001990004', input), /lost ACK/)
  // 未确认结果：槽内保留原命令；异参不允许换 payload 重试。
  assert.deepEqual(repository.pendingReview('900101001990004'), { storeScore: 5, serviceScore: 4, staffScore: 3, content: null })
  await assert.rejects(repository.create('900101001990004', { ...input, staffScore: 4 }), /PENDING_WRITE_CHANGED/)
  // 同参重试复用同一 X-Request-Id（五元组幂等重放 200）；成功后槽清空。
  const replay = await repository.create('900101001990004', input)
  assert.equal(replay.reviewId, '970101001990009')
  assert.equal(writes.length, 2)
  assert.equal(writes[1]!.requestId, writes[0]!.requestId)
  assert.equal(repository.pendingReview('900101001990004'), null)
})

test('real repository retires the journaled command on a definite 409 with proof only', async () => {
  let attempts = 0
  const { api, restore } = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.method === 'GET') return ok(JSON.parse(JSON.stringify(eligibleWire)))
    attempts++
    return failure(409, attempts === 1 ? 'REVIEW_ALREADY_EXISTS' : 'REVIEW_WINDOW_EXPIRED')
  })
  await restore()
  const repository = new RealReviewRepository(api)
  const input = { storeScore: 5, serviceScore: 4, staffScore: 3, content: null, mediaFileIds: [] }
  const rejected = await repository.create('900101001990004', input).catch(error => error)
  assert.ok(rejected instanceof ApiError && rejected.code === 'REVIEW_ALREADY_EXISTS')
  // 未经该错误实例（无证据）不得退槽。
  assert.throws(() => repository.retireConflict('900101001990004', new ApiError('REVIEW_ALREADY_EXISTS', 409)), /UNCONFIRMED_WRITE/)
  repository.retireConflict('900101001990004', rejected)
  assert.equal(repository.pendingReview('900101001990004'), null)
  // 退槽后新 UUID 新命令；窗口过期 409 同为终局，可凭证据退槽。
  const expired = await repository.create('900101001990004', input).catch(error => error)
  assert.ok(expired instanceof ApiError && expired.code === 'REVIEW_WINDOW_EXPIRED')
  repository.retireConflict('900101001990004', expired)
  assert.equal(repository.pendingReview('900101001990004'), null)
})

test('real repository surfaces the switched-off route and unknown-result 503s keep the journal', async () => {
  const switchedOff = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    return failure(403, 'COMMON_FORBIDDEN') // 路由未挂载（开关默认关，catch-all deny）
  })
  await switchedOff.restore()
  const denied = await new RealReviewRepository(switchedOff.api).eligibility('900101001990004').catch(error => error)
  assert.ok(denied instanceof ApiError && denied.statusCode === 403)
  const unknown = authenticatedApi(async request => {
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.method === 'GET') return ok(JSON.parse(JSON.stringify(eligibleWire)))
    return failure(503, 'COMMON_DEPENDENCY_UNAVAILABLE')
  })
  await unknown.restore()
  const repository = new RealReviewRepository(unknown.api)
  const input = { storeScore: 5, serviceScore: 4, staffScore: 3, content: null, mediaFileIds: [] }
  await assert.rejects(repository.create('900101001990004', input), (error: unknown) => error instanceof ApiError && error.statusCode === 503)
  assert.deepEqual(repository.pendingReview('900101001990004'), { storeScore: 5, serviceScore: 4, staffScore: 3, content: null })
})
