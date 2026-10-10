import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../shared/request'
import { WorkspaceScope } from '../../shared/workspace'
import type { MerchantAdmission } from '../../shared/merchant-repositories'
import { isDefiniteAppealConflict, type AppealReceipt, type ReviewPage, type ReviewSummary } from '../../shared/review-appeal-api'
import { MerchantReviewController, type MerchantReviewState } from '../review-appeal/controller'
import type { MerchantReviewDeps } from '../review-appeal/repository'
import { canAppealEntry, ownerAccess } from '../review-appeal/model'

const owner: MerchantAdmission = {
  merchantId: '101', storeId: '102', membershipKind: 'OWNER', admission: 'ALLOWED', checkedAt: '2026-10-01T00:00:00.000Z', authzVersion: '0123456789abcdef',
  facts: { application: { status: 'APPROVED' }, signing: { status: 'SIGNED' }, merchantStatus: 'ACTIVE', storeStatus: 'ACTIVE', staffEnabled: null },
  allowedActions: ['merchant.review.read'], reasonCodes: [], nextSteps: [],
}
const review: ReviewSummary = { reviewId: '201', orderId: '301', storeScore: '5.0', serviceScore: '4.0', staffScore: '3.0', compositeScore: '4.2', scoreIncluded: true, visibilityStatus: 'PUBLISHED', content: '上门迟到了二十分钟', createdAt: '2026-10-01T00:00:00.000Z', appealStatus: null, appealId: null }
const receipt: AppealReceipt = { appealId: '401', reviewId: '201', status: 'SUBMITTED', createdAt: '2026-10-02T00:00:00.000Z' }
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const defer = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }

function fixture() {
  const scope = new WorkspaceScope(); scope.replace({ userId: '1', workspace: 'merchant', merchantId: '101', storeId: '102' })
  let view = copy(owner), current: ReviewPage = { page: 1, pageSize: 20, total: 1, items: [copy(review)] }
  let pending: { reason: string } | null = null, draft = '', writes = 0, listCalls = 0, retired = 0
  let appealImpl: () => Promise<AppealReceipt> = async () => copy(receipt)
  const deps: MerchantReviewDeps = {
    scope, admission: async () => copy(view),
    list: async (_m, _s, page, pageSize) => { listCalls++; return { page, pageSize, total: current.total, items: copy(current.items) } },
    detail: async () => { throw new Error('unused in this slice test') },
    appeal: async () => { writes++; return appealImpl() },
    pendingAppeal: () => pending,
    // 与真实 repository 同语义：仅终局拒绝可退役，否则抛 UNCONFIRMED_WRITE 保住命令。
    retireConflict: (_id, error) => { if (!isDefiniteAppealConflict(error)) throw new Error('UNCONFIRMED_WRITE'); retired++; pending = null },
    saveDraft: (_id, reason) => { draft = reason }, loadDraft: () => draft, clearDraft: () => { draft = '' },
  }
  return { deps, get view() { return view }, set view(v: MerchantAdmission) { view = v }, get page() { return current }, set page(v: ReviewPage) { current = v }, get pending() { return pending }, set pending(v: { reason: string } | null) { pending = v }, get draft() { return draft },
    set appeal(impl: () => Promise<AppealReceipt>) { appealImpl = impl }, get counts() { return { writes, listCalls, retired } } }
}
const settled = (state: MerchantReviewState) => state.phase

test('owner access gates on OWNER kind, coordinates and the merchant.review.read action', () => {
  assert.equal(ownerAccess(owner, '101', '102').readable, true)
  assert.equal(ownerAccess({ ...owner, membershipKind: 'STAFF' }, '101', '102').readable, false)
  assert.equal(ownerAccess({ ...owner, allowedActions: ['merchant.aftersale.read'] }, '101', '102').readable, false)
  assert.equal(ownerAccess(owner, '999', '102').readable, false)
  assert.equal(ownerAccess({ ...owner, facts: { ...owner.facts, storeStatus: 'FROZEN' } }, '101', '102').writable, false)
})

test('appeal entry only for a published, not-yet-appealed review with write access', () => {
  assert.equal(canAppealEntry(review, true), true)
  assert.equal(canAppealEntry(review, false), false)
  assert.equal(canAppealEntry({ ...review, appealStatus: 'SUBMITTED' }, true), false)
  assert.equal(canAppealEntry({ ...review, visibilityStatus: 'HIDDEN' }, true), false)
})

test('load reads admission first and never lists for a denied or STAFF session', async () => {
  const f = fixture(); f.view = { ...owner, membershipKind: 'STAFF' }
  const c = new MerchantReviewController(f.deps, f.deps.scope, { merchantId: '101', storeId: '102' }); await c.load()
  assert.equal(settled(c.getSnapshot()), 'entry'); assert.equal(f.counts.listCalls, 0)
  f.deps.admission = async () => { throw new ApiError('COMMON_UNAUTHORIZED', 401) }; await c.load()
  assert.equal(settled(c.getSnapshot()), 'unauthorized'); assert.equal(f.counts.listCalls, 0)
  c.dispose()
})

test('one-time appeal: first submit reloads and overlays the appeal status', async () => {
  const f = fixture()
  const c = new MerchantReviewController(f.deps, f.deps.scope, { merchantId: '101', storeId: '102' }); await c.load()
  assert.equal(settled(c.getSnapshot()), 'ready')
  c.openSheet('201'); assert.equal(c.getSnapshot().sheetReviewId, '201')
  await c.submit('201', '  评价内容失实，涉及辱骂  ')
  assert.equal(f.counts.writes, 1); assert.equal(f.draft, '')
  const after = c.getSnapshot()
  assert.equal(after.sheetReviewId, null); assert.equal(after.receiptReviewId, '201'); assert.equal(settled(after), 'ready')
  c.dismissReceipt(); assert.equal(c.getSnapshot().receiptReviewId, null)
  c.dispose()
})

test('blank reason stays local and FROZEN keeps the list readable but the entry closed', async () => {
  const f = fixture(); f.view = { ...owner, admission: 'LIMITED', facts: { ...owner.facts, storeStatus: 'FROZEN' } }
  const c = new MerchantReviewController(f.deps, f.deps.scope, { merchantId: '101', storeId: '102' }); await c.load()
  const state = c.getSnapshot()
  assert.equal(settled(state), 'ready'); assert.equal(state.frozen, true); assert.equal(state.writable, false)
  assert.equal(canAppealEntry(state.items[0]!, state.writable), false)
  c.openSheet('201'); assert.equal(c.getSnapshot().sheetReviewId, null)
  assert.equal(f.counts.writes, 0)
  c.dispose()
})

test('unknown outcome keeps the original command; terminal one-time 409 retires it and reloads', async () => {
  const f = fixture()
  const c = new MerchantReviewController(f.deps, f.deps.scope, { merchantId: '101', storeId: '102' }); await c.load()
  // Unknown outcome (503): the journaled command survives as the pending intent for retry.
  f.appeal = async () => { f.pending = { reason: '评价内容失实' }; throw new ApiError('COMMON_DEPENDENCY_UNAVAILABLE', 503) }
  c.openSheet('201'); await c.submit('201', '评价内容失实')
  let state = c.getSnapshot()
  assert.equal(f.counts.writes, 1); assert.equal(f.counts.retired, 0)
  assert.deepEqual(state.sheetPending, { reason: '评价内容失实' })
  // Retrying the original command (same journal) resolves the unknown outcome.
  f.appeal = async () => copy(receipt)
  await c.retry('201'); state = c.getSnapshot()
  assert.equal(f.counts.writes, 2); assert.equal(state.sheetReviewId, null); assert.equal(state.receiptReviewId, '201')
  // Terminal one-time 409: retire + reload with the rejection notice preserved.
  f.appeal = async () => { f.pending = { reason: '再次申诉' }; throw new ApiError('REVIEW_APPEAL_ALREADY_USED', 409) }
  c.openSheet('201'); await c.retry('201')
  state = c.getSnapshot()
  assert.equal(f.counts.retired, 1); assert.equal(state.sheetPending, null)
  assert.equal(state.notice, '每条评价最多申诉一次')
  c.dispose()
})

test('late list response cannot repopulate the UI after an identity switch', async () => {
  const f = fixture(); const delayed = defer<ReviewPage>()
  f.deps.list = () => delayed.promise
  const c = new MerchantReviewController(f.deps, f.deps.scope, { merchantId: '101', storeId: '102' })
  const load = c.load(); await Promise.resolve(); await Promise.resolve()
  f.deps.scope.replace({ userId: '2', workspace: 'merchant', merchantId: '103', storeId: '104' })
  delayed.resolve({ page: 1, pageSize: 20, total: 1, items: [review] }); await load
  const state = c.getSnapshot()
  assert.deepEqual(state.items, []); assert.notEqual(settled(state), 'ready')
  c.dispose()
})

test('pagination appends deduplicated pages only while the identity holds', async () => {
  const f = fixture(); f.page = { page: 1, pageSize: 1, total: 2, items: [copy(review)] }
  let second: ReviewSummary = { ...copy(review), reviewId: '202' }
  const original = f.deps.list
  f.deps.list = async (m, s, page, pageSize) => { if (page === 1) return original(m, s, page, pageSize); return { page, pageSize, total: 2, items: [second] } }
  const c = new MerchantReviewController(f.deps, f.deps.scope, { merchantId: '101', storeId: '102' }); await c.load()
  await c.loadMore()
  assert.equal(c.getSnapshot().items.length, 2)
  // A repeated page with an already-known review id cannot duplicate a card.
  second = copy(review)
  await c.loadMore()
  assert.equal(c.getSnapshot().items.length, 2)
  c.dispose()
})
