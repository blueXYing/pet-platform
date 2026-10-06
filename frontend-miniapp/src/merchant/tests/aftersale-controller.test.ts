import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../shared/request'
import { WorkspaceScope } from '../../shared/workspace'
import type { MerchantAdmission } from '../../shared/merchant-repositories'
import type { CaseDetail, CaseSummary, CommandReceipt, OpinionInput } from '../../shared/aftersale-api'
import { isDefiniteAfterSaleConflict } from '../../shared/aftersale-api'
import type { PrivateAssetReceipt } from '../../shared/consumer-api'
import { MerchantAfterSaleController, type MerchantAfterSaleDeps, type PendingReply, type UploadAttempt } from '../aftersale/controller'
import { canReply, dateText, emptyReply, ownerAccess, replyInput } from '../aftersale/model'

const owner: MerchantAdmission = {
  merchantId: '101', storeId: '102', membershipKind: 'OWNER', admission: 'ALLOWED', checkedAt: '2026-10-01T00:00:00.000Z', authzVersion: '0123456789abcdef',
  facts: { application: { status: 'APPROVED' }, signing: { status: 'SIGNED' }, merchantStatus: 'ACTIVE', storeStatus: 'ACTIVE', staffEnabled: null },
  allowedActions: ['merchant.aftersale.read'], reasonCodes: [], nextSteps: [],
}
const summary: CaseSummary = { afterSaleId: '201', orderId: '301', merchantId: '101', storeId: '102', version: '0', status: 'PENDING', sourceStage: 'VERIFIED', typeCode: 'SERVICE', demandCode: 'RESERVICE', requestedAmount: null, createdAt: '2026-10-01T00:00:00.000Z', deadline: '2026-10-08T00:00:00.000Z' }
const { merchantId: _m, storeId: _s, ...base } = summary
const detail: CaseDetail = { ...base, description: '测试工单的真实问题说明文本', supplementRequestId: null, supplementTarget: null, supplementDeadline: null, supplementReason: null, finalSetVersion: 'a'.repeat(64), priorFinalCaseIds: [], newProblemStatement: null, decisionType: null, refundAmount: null, decisionReason: null,
  evidence: [{ batchId: '401', submitterType: 'USER', text: '用户已提交的问题说明证据', opinionCode: null, submittedAt: summary.createdAt, assetIds: ['501'] }] }
const receipt: CommandReceipt = { commandId: '601', orderId: '301', afterSaleId: '201', status: 'PENDING', version: '1', occurredAt: summary.createdAt, evidenceBatchId: '402', supplementRequestId: null, decisionId: null, refundOrderId: null }
const assetReceipt: PrivateAssetReceipt = { assetId: '502', status: 'READY', objectSha256: 'c'.repeat(64), mediaType: 'image/png', bytes: 9 }
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const defer = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }
function fixture() {
  const scope = new WorkspaceScope(); scope.replace({ userId: '1', workspace: 'merchant', merchantId: '101', storeId: '102' })
  let view = copy(owner), current = copy(detail), pending: PendingReply | null = null, uploadAttempt: UploadAttempt | null = null, draft = emptyReply()
  let imagesCleared = 0, listCalls = 0, writes = 0, uuids = 0
  const deps: MerchantAfterSaleDeps = {
    scope, admission: async () => copy(view), list: async query => { listCalls++; return { page: query.page, pageSize: query.pageSize, total: 1, items: [copy(summary)] } },
    detail: async () => copy(current), opinion: async () => { writes++; return copy(receipt) }, evidence: async () => { writes++; return copy(receipt) }, pending: () => pending,
    retireConflict: (_id, _action, error) => { if (isDefiniteAfterSaleConflict(error)) { pending = null; return true }; return false },
    loadDraft: () => draft, saveDraft: (_id, value) => { draft = value || emptyReply() },
    readEvidence: async () => '/private/test.img', clearImages: () => { imagesCleared++ },
    uuid: async () => { uuids++; return '00000000-0000-4000-8000-000000000001' },
    upload: async () => copy(assetReceipt), uploadAttempt: () => uploadAttempt, saveUploadAttempt: (_id, value) => { uploadAttempt = value }, now: () => Date.parse(summary.createdAt),
    files: { save: async (_path, requestId) => `/owned/${requestId}`, inspect: async () => ({ sha256: 'a'.repeat(64), bytes: 9 }), owns: (path, requestId) => path === `/owned/${requestId}`, remove: async () => {} },
  }
  return { deps, get view() { return view }, set view(v: MerchantAdmission) { view = v }, get detail() { return current }, set detail(v: CaseDetail) { current = v }, get pending() { return pending }, set pending(v: PendingReply | null) { pending = v }, get uploadAttempt() { return uploadAttempt }, get counts() { return { imagesCleared, listCalls, writes, uuids } } }
}

test('owner access rejects STAFF/wrong coordinates and preserves FROZEN read only/OFFLINE backend writes', async () => {
  assert.equal(ownerAccess({ ...owner, membershipKind: 'STAFF' }, '101', '102').readable, false)
  assert.equal(ownerAccess(owner, '101', '999').readable, false)
  const f = fixture(); f.view = { ...owner, admission: 'LIMITED', facts: { ...owner.facts, storeStatus: 'FROZEN' } }
  const c = new MerchantAfterSaleController(f.deps, '201'); await c.load()
  assert.equal(c.getSnapshot().status, 'ready'); assert.equal(c.getSnapshot().frozen, true); assert.equal(c.canReply(), false)
  c.setDraft({ text: '商家提供的十个字以上说明', opinionCode: 'AGREE' }); await c.submit('opinion')
  assert.equal(f.counts.writes, 0)
  f.view = { ...owner, facts: { ...owner.facts, merchantStatus: 'OFFLINE', storeStatus: 'OFFLINE' } }; await c.load()
  assert.equal(c.getSnapshot().writable, true)
  c.dispose()
})
test('STAFF and unavailable admission fail closed before reading any case data', async () => {
  const f = fixture(); f.view = { ...owner, membershipKind: 'STAFF' }
  const c = new MerchantAfterSaleController(f.deps); await c.load(); assert.equal(c.getSnapshot().status, 'denied'); assert.equal(f.counts.listCalls, 0)
  f.view = owner; f.deps.admission = async () => { throw new Error('NETWORK') }; await c.load(); assert.equal(c.getSnapshot().status, 'error'); assert.equal(f.counts.listCalls, 0); c.dispose()
})
test('read always uses selected merchant/store and all filter clears previous status', async () => {
  const f = fixture(); const queries: any[] = []
  f.deps.list = async query => { queries.push(query); return { page: query.page, pageSize: query.pageSize, total: 0, items: [] } }
  const c = new MerchantAfterSaleController(f.deps); await c.load('PENDING'); await c.load(null)
  assert.equal(queries[0].merchantId, '101'); assert.equal(queries[0].storeId, '102'); assert.equal(queries[0].status, 'PENDING'); assert.equal('status' in queries[1], false); c.dispose()
})
test('late list response cannot repopulate UI after same-coordinate revalidation or identity switch', async () => {
  const f = fixture(); const delayed = defer<{ page: number; pageSize: number; total: number; items: CaseSummary[] }>()
  f.deps.list = () => delayed.promise
  const c = new MerchantAfterSaleController(f.deps); const load = c.load(); await Promise.resolve(); await Promise.resolve()
  f.deps.scope.replace({ userId: '2', workspace: 'merchant', merchantId: '103', storeId: '104' }); delayed.resolve({ page: 1, pageSize: 20, total: 1, items: [summary] }); await load
  assert.equal(c.getSnapshot().status, 'entry'); assert.deepEqual(c.getSnapshot().items, []); assert.equal(c.getSnapshot().detail, null); c.dispose()
})
test('round must be strictly before deadline, and both parties carry current round id', () => {
  const round: CaseDetail = { ...detail, status: 'WAITING_SUPPLEMENT', supplementRequestId: '701', supplementTarget: 'USER', supplementDeadline: '2026-10-01T00:01:00.000Z' }
  assert.equal(canReply(round, true, Date.parse(round.supplementDeadline!) - 1), true)
  assert.equal(canReply(round, true, Date.parse(round.supplementDeadline!)), false)
  const input = replyInput(round, { opinionCode: 'NEED_USER_SUPPLEMENT', text: '请用户进一步提供相关证据图片', assetIds: [] }, 'opinion')
  assert.equal(input.supplementRequestId, '701'); assert.equal(input.expectedVersion, '0')
  assert.equal(canReply({ ...round, status: 'RESOLVED' }, true, 0), false)
})
test('four opinions are advisory, empty evidence and Java-character-limit overflow are rejected', () => {
  for (const opinionCode of ['AGREE', 'PARTLY_AGREE', 'DISAGREE', 'NEED_USER_SUPPLEMENT'] as const) {
    const input = replyInput(detail, { opinionCode, text: '商家真实说明并提供处理建议', assetIds: [] }, 'opinion')
    assert.equal('decisionType' in input, false); assert.equal('refundAmount' in input, false)
  }
  assert.throws(() => replyInput(detail, emptyReply(), 'evidence'), /EVIDENCE_REQUIRED/)
  assert.throws(() => replyInput(detail, { opinionCode: 'AGREE', text: '😀'.repeat(251), assetIds: [] }, 'opinion'), /REPLY_TEXT_LENGTH/)
  assert.throws(() => replyInput(detail, { opinionCode: 'AGREE', text: '商家真实说明并提供处理建议', assetIds: ['5', '5'] }, 'opinion'), /EVIDENCE_LIMIT/)
})
test('unknown write stays locked and retries exact previous input even after terminal detail', async () => {
  const f = fixture(); let first: OpinionInput | null = null, calls = 0
  f.deps.opinion = async (_id, input) => { calls++; if (calls === 1) { first = copy(input); f.pending = { action: 'opinion', input: copy(input) }; throw new Error('LOST_RESPONSE') }; assert.deepEqual(input, first); f.pending = null; return receipt }
  const c = new MerchantAfterSaleController(f.deps, '201'); await c.load(); c.setDraft({ opinionCode: 'PARTLY_AGREE', text: '商家部分同意并说明具体事实' }); await c.submit('opinion')
  assert.ok(c.getSnapshot().pending); c.setDraft({ text: '修改后的其他说明不能覆盖待确认提交' }); assert.equal(c.getSnapshot().draft.text, first!.explanation)
  f.detail = { ...detail, version: '9', status: 'RESOLVED', decisionType: 'OTHER', decisionReason: '平台已经完成了真实处理' }; await c.load(); await c.submit('opinion')
  assert.equal(calls, 2); assert.equal(c.getSnapshot().pending, null); c.dispose()
})
test('definite supplement conflict reloads actual version but idempotent conflict retains pending replay', async () => {
  const f = fixture(); const c = new MerchantAfterSaleController(f.deps, '201'); await c.load(); c.setDraft({ opinionCode: 'AGREE', text: '商家同意用户意见并提供补充说明' })
  f.deps.opinion = async (_id, input) => { f.pending = { action: 'opinion', input }; f.detail = { ...detail, version: '2' }; throw new ApiError('AFTERSALE_SUPPLEMENT_STALE', 409) }
  await c.submit('opinion'); assert.equal(c.getSnapshot().detail!.version, '2'); assert.equal(c.getSnapshot().pending, null)
  f.deps.opinion = async (_id, input) => { f.pending = { action: 'opinion', input }; throw new ApiError('IDEMPOTENT_IN_PROGRESS', 409) }
  await c.submit('opinion'); assert.ok(c.getSnapshot().pending); assert.match(c.getSnapshot().notice, /按原内容重试/); c.dispose()
})
test('new definite conflict codes gate M writes throughout fresh admission/detail and require manual resubmit', async () => {
  for (const code of ['AFTERSALE_VERSION_CONFLICT', 'AFTERSALE_FINAL_SET_CONFLICT']) {
    const f = fixture(), refreshed = defer<CaseDetail>()
    let reads = 0, writes = 0, retireCalls = 0
    f.deps.detail = () => ++reads === 1 ? Promise.resolve(detail) : refreshed.promise
    f.deps.retireConflict = () => { retireCalls++; f.pending = null; return true }
    f.deps.opinion = async (_id, input) => { writes++; f.pending = { action: 'opinion', input }; throw new ApiError(code, 409) }
    const c = new MerchantAfterSaleController(f.deps, '201'); await c.load(); c.setDraft({ opinionCode: 'AGREE', text: '商家根据真实情况提交处理意见' })
    const submitting = c.submit('opinion')
    for (let i = 0; i < 8; i++) await Promise.resolve()
    assert.equal(c.getSnapshot().status, 'loading'); assert.equal(c.getSnapshot().detail, null); assert.equal(c.getSnapshot().busy, true)
    await c.submit('opinion'); assert.equal(writes, 1)
    refreshed.resolve({ ...detail, version: '8' }); await submitting
    assert.equal(retireCalls, 1); assert.equal(c.getSnapshot().detail?.version, '8'); assert.equal(c.getSnapshot().pending, null); assert.equal(writes, 1)
    assert.equal(c.getSnapshot().busy, false); assert.match(c.getSnapshot().notice, /核对/); c.dispose()
  }
})
test('definite conflict followed by failed M refresh remains unable to submit with stale detail', async () => {
  const f = fixture(); let reads = 0, writes = 0
  f.deps.detail = async () => { if (++reads > 1) throw new Error('READ_FAILED'); return detail }
  f.deps.opinion = async (_id, input) => { writes++; f.pending = { action: 'opinion', input }; throw new ApiError('AFTERSALE_VERSION_CONFLICT', 409) }
  const c = new MerchantAfterSaleController(f.deps, '201'); await c.load(); c.setDraft({ opinionCode: 'AGREE', text: '商家依据真实情况说明相关意见' })
  await c.submit('opinion'); assert.equal(c.getSnapshot().status, 'error'); assert.equal(c.getSnapshot().detail, null); assert.equal(c.canReply(), false)
  await c.submit('opinion'); assert.equal(writes, 1); c.dispose()
})
test('failed M retirement preserves original pending input even after the latest detail version changes', async () => {
  const f = fixture(); let first: OpinionInput | null = null, writes = 0
  f.deps.retireConflict = () => false
  f.deps.opinion = async (_id, input) => {
    if (++writes === 1) { first = copy(input); f.pending = { action: 'opinion', input: copy(input) }; f.detail = { ...detail, version: '9' }; throw new ApiError('AFTERSALE_VERSION_CONFLICT', 409) }
    assert.deepEqual(input, first); f.pending = null; return receipt
  }
  const c = new MerchantAfterSaleController(f.deps, '201'); await c.load(); c.setDraft({ opinionCode: 'AGREE', text: '商家依据当前证据确认处理意见' })
  await c.submit('opinion'); assert.equal(c.getSnapshot().detail?.version, '9'); assert.deepEqual(c.getSnapshot().pending?.input, first)
  c.setDraft({ text: '不能替换尚未确认的原始请求内容' }); assert.equal(c.getSnapshot().draft.text, first!.explanation)
  await c.submit('opinion'); assert.equal(writes, 2); assert.equal(c.getSnapshot().pending, null); c.dispose()
})
test('COMMON_CONFLICT, unknown 409 and 429 never retire or change an unresolved opinion input', async () => {
  for (const [code, status] of [['COMMON_CONFLICT', 409], ['IDEMPOTENCY_CONFLICT', 409], ['UNKNOWN_CONFLICT', 409], ['COMMON_RATE_LIMITED', 429]] as const) {
    const f = fixture(); let original: OpinionInput | null = null, calls = 0, retireCalls = 0
    f.deps.retireConflict = () => { retireCalls++; f.pending = null; return true }
    f.deps.opinion = async (_id, input) => {
      if (++calls === 1) { original = copy(input); f.pending = { action: 'opinion', input: copy(input) }; throw new ApiError(code, status) }
      assert.deepEqual(input, original); f.pending = null; return receipt
    }
    const c = new MerchantAfterSaleController(f.deps, '201'); await c.load(); c.setDraft({ opinionCode: 'AGREE', text: '商家根据真实证据提交相同原意见' }); await c.submit('opinion')
    assert.equal(retireCalls, 0); assert.deepEqual(c.getSnapshot().pending?.input, original); assert.match(c.getSnapshot().notice, /原内容重试/)
    c.setDraft({ text: '争锁忙或限流之后不允许更改原内容' }); assert.equal(c.getSnapshot().draft.text, original!.explanation)
    await c.submit('opinion'); assert.equal(calls, 2); assert.equal(c.getSnapshot().pending, null); c.dispose()
  }
})
test('acknowledged write with failed read stays confirmed and requires fresh detail before another action', async () => {
  const f = fixture(); let detailReads = 0
  f.deps.detail = async () => { if (++detailReads > 1) throw new Error('READ_NETWORK'); return detail }
  const c = new MerchantAfterSaleController(f.deps, '201'); await c.load(); c.setDraft({ opinionCode: 'AGREE', text: '商家同意并说明相关事实和证据' }); await c.submit('opinion')
  assert.equal(f.counts.writes, 1); assert.equal(c.getSnapshot().status, 'error'); assert.equal(c.canReply(), false); assert.match(c.getSnapshot().notice, /^已提交/); c.dispose()
})
test('image upload persists original file/UUID across remount and publishes only READY asset', async () => {
  const f = fixture(); const attempts: Pick<UploadAttempt, 'filePath' | 'requestId'>[] = []; let uploadCalls = 0
  f.deps.upload = async (filePath, requestId) => { attempts.push({ filePath, requestId }); if (++uploadCalls === 1) throw new Error('LOST_UPLOAD'); return assetReceipt }
  const c = new MerchantAfterSaleController(f.deps, '201'); await c.load(); await c.upload('/private/original.png')
  assert.equal(c.getSnapshot().uploadPending, true); assert.deepEqual(c.getSnapshot().draft.assetIds, []); c.dispose()
  const re = new MerchantAfterSaleController(f.deps, '201'); await re.load(); assert.equal(re.getSnapshot().uploadPending, true); await re.upload('/private/different.png')
  assert.deepEqual(attempts[0], attempts[1]); assert.equal(f.counts.uuids, 1); assert.deepEqual(re.getSnapshot().draft.assetIds, ['502']); assert.equal(f.uploadAttempt, null); re.dispose()
})
test('an upload completion after context switch never attaches an asset to new identity', async () => {
  const f = fixture(), delayed = defer<PrivateAssetReceipt>(); f.deps.upload = () => delayed.promise
  const c = new MerchantAfterSaleController(f.deps, '201'); await c.load(); const upload = c.upload('/private/test.png'); await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
  f.deps.scope.replace(null); delayed.resolve(assetReceipt); await upload
  assert.deepEqual(c.getSnapshot().draft.assetIds, []); assert.equal(c.getSnapshot().status, 'entry'); c.dispose()
})
test('saved image fingerprint changes stop unknown-result retries before any new network request', async () => {
  const f = fixture(); let calls = 0
  f.deps.upload = async () => { calls++; throw new Error('LOST_UPLOAD') }
  const c = new MerchantAfterSaleController(f.deps, '201'); await c.load(); await c.upload('/temp/source.png'); assert.equal(calls, 1)
  f.deps.files.inspect = async () => ({ sha256: 'b'.repeat(64), bytes: 9 }); await c.upload()
  assert.equal(calls, 1); assert.equal(c.getSnapshot().uploadPending, true); assert.match(c.getSnapshot().notice, /文件已发生变化/); c.dispose()
})
test('receipt saved before failed draft insertion recovers without re-upload and cleans only owned copy after draft durability', async () => {
  const f = fixture(); let calls = 0, failSave = true; const events: string[] = []
  const saveDraft = f.deps.saveDraft, saveAttempt = f.deps.saveUploadAttempt
  f.deps.upload = async () => { calls++; return assetReceipt }
  f.deps.saveUploadAttempt = (id, attempt) => { saveAttempt(id, attempt); events.push(attempt?.receipt ? 'receipt-saved' : attempt ? 'attempt-saved' : 'attempt-cleared') }
  f.deps.saveDraft = (id, draft) => { if (failSave) { failSave = false; throw new Error('LOCAL_STORAGE_FAILURE') }; saveDraft(id, draft); events.push('draft-saved') }
  f.deps.files.remove = async path => { assert.match(path, /^\/owned\//); events.push('owned-copy-removed') }
  const c = new MerchantAfterSaleController(f.deps, '201'); await c.load(); await c.upload('/temp/user-original.png'); assert.equal(f.uploadAttempt?.receipt?.assetId, '502'); assert.equal(calls, 1); c.dispose()
  const restored = new MerchantAfterSaleController(f.deps, '201'); await restored.load(); await restored.upload(); assert.equal(calls, 1); assert.deepEqual(restored.getSnapshot().draft.assetIds, ['502'])
  assert.ok(events.indexOf('receipt-saved') < events.indexOf('draft-saved')); assert.ok(events.indexOf('draft-saved') < events.indexOf('owned-copy-removed')); assert.ok(events.indexOf('owned-copy-removed') < events.indexOf('attempt-cleared')); restored.dispose()
})
test('a later ingress rejection cannot discard a previously unknown successful upload', async () => {
  const f = fixture(); let calls = 0
  f.deps.upload = async () => { if (++calls === 1) throw new Error('LOST_UPLOAD'); throw new ApiError('COMMON_INVALID_ARGUMENT', 415) }
  const c = new MerchantAfterSaleController(f.deps, '201'); await c.load(); await c.upload('/temp/source.png'); const key = f.uploadAttempt?.requestId; await c.upload()
  assert.equal(f.uploadAttempt?.requestId, key); assert.equal(c.getSnapshot().uploadPending, true); c.dispose()
})
test('private image read uses actual case/batch/asset, hides local image on scope switch and 410 needs new grant', async () => {
  const f = fixture(); let imageCalls = 0
  f.deps.readEvidence = async (caseId, batchId, assetId, reason) => { imageCalls++; assert.deepEqual([caseId, batchId, assetId], ['201', '401', '501']); assert.ok(reason.length > 0); if (imageCalls === 1) throw new ApiError('PRIVATE_GRANT_EXPIRED', 410); return '/private/new-grant.img' }
  const c = new MerchantAfterSaleController(f.deps, '201'); await c.load(); await c.readEvidence('401', '999'); assert.equal(imageCalls, 0)
  await c.readEvidence('401', '501'); assert.equal(c.getSnapshot().image, null); assert.match(c.getSnapshot().notice, /重新查看/)
  await c.readEvidence('401', '501'); assert.equal(c.getSnapshot().image, '/private/new-grant.img')
  f.deps.scope.replace({ userId: '1', workspace: 'consumer', merchantId: null, storeId: null }); assert.equal(c.getSnapshot().image, null); assert.ok(f.counts.imagesCleared >= 3); c.dispose()
})
test('Beijing deadline is stable across device timezones without modifying the UTC contract', () => {
  assert.equal(dateText('2026-10-01T00:00:00.000Z'), '2026-10-01 08:00'); assert.equal(dateText(null), '—')
})
