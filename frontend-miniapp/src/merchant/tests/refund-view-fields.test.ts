import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'
import { MerchantRefundClient, decodeDetail, decodePage, decodeSummary, decodeTimeline } from '../../shared/merchant-refund-api'
import type { Transport, WireRequest } from '../../shared/request'
import { merchantRefundDeps } from '../refund/repository'
import { refundCustomerInitial, refundCustomerName, refundServiceLine, refundTimelineLabel, refundTimelineText } from '../refund/model'

// CCR-M-REFUND-VIEW-001（2026-11-06 用户裁决：批准 §3.1/§3.3；§3.2 申请说明解密不批；凭证图片不做）。
// 57号商家退款读侧：列表项与详情追加可空键 customerNickname / customerPhoneMasked（服务端脱敏
// 1xx****xxxx，永不回明文）/ serviceName；详情另含只读 timeline（stage ∈ APPLIED/MERCHANT_DEADLINE/
// DECIDED/REFUND_ORDER_CREATED，occurredAt 升序，出现即有事实）。本文件覆盖：严格解码（旧形状回归 +
// 新形状）、时间线独立严格解码与事实一致性、真实客户端端到端、页面降级占位、按钮 id/路由不变、
// 新增样式 .mrf-page 复合选择器。

const legacySummary = {
  applicationId: '881001', applicationNo: '881002', orderId: '777001', status: 'PENDING_MERCHANT', applicationVersion: '0',
  reasonCode: 'QA_REASON', refundAmount: '128.00', createdAt: '2026-11-05T08:15:32.649Z', merchantDeadline: '2026-11-06T08:15:32.649Z',
}
const customer = { customerNickname: 'E2E体验用户', customerPhoneMasked: '138****0100', serviceName: 'E2E到店精细洗护' }
const nullCustomer = { customerNickname: null, customerPhoneMasked: null, serviceName: null }
const legacyDetail = { ...legacySummary, decidedAt: null, decisionId: null, refundOrderId: null }
const pendingTimeline = [{ stage: 'APPLIED', occurredAt: legacySummary.createdAt }, { stage: 'MERCHANT_DEADLINE', occurredAt: legacySummary.merchantDeadline }]
const pendingDetail = { ...legacyDetail, ...customer, timeline: pendingTimeline }
const decidedAt = '2026-11-05T09:15:32.649Z'
const refundCreatedAt = '2026-11-05T09:15:37.649Z'
const approvedDetail = {
  ...legacyDetail, status: 'APPROVED', applicationVersion: '1', decidedAt, decisionId: '881003', refundOrderId: '881004', ...customer,
  timeline: [{ stage: 'APPLIED', occurredAt: legacySummary.createdAt }, { stage: 'DECIDED', occurredAt: decidedAt },
    { stage: 'REFUND_ORDER_CREATED', occurredAt: refundCreatedAt }, { stage: 'MERCHANT_DEADLINE', occurredAt: legacySummary.merchantDeadline }],
}
// The merchant family envelope carries success:true (consumer-api rejects a refund answer without it).
const ok = (data: unknown) => ({ statusCode: 200, data: { success: true, code: 'SUCCESS', message: 'ok', data, traceId: 'fixture-trace' } })
const root = fileURLToPath(new URL('../../..', import.meta.url))
const pageSource = (file: string) => readFileSync(path.join(root, 'src', 'merchant', 'pages', 'refund', file), 'utf8')

async function setup(handler: Transport) {
  const session = { userId: '101', sessionId: '201', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
  const saved = new Map<string, unknown>([['pet.c.session.v1', { ...session, tokenType: 'Bearer', accessToken: 'test-only-unusable' }]])
  const store: LocalStore = { get: key => saved.get(key), set: (key, value) => { saved.set(key, structuredClone(value)) }, remove: key => { saved.delete(key) } }
  const calls: WireRequest[] = []
  const api = new ConsumerApi(async request => { calls.push(structuredClone(request)); return request.path.endsWith('/auth/session') ? ok(session) : handler(request) }, store, async () => randomUUID())
  await api.restore()
  return { api, store, calls }
}

test('decoders accept the new shapes and the legacy 9/12-key shapes, always yielding the three keys', () => {
  // New shapes decode verbatim.
  assert.deepEqual(decodeSummary({ ...legacySummary, ...customer }), { ...legacySummary, ...customer })
  assert.deepEqual(decodeDetail({ ...pendingDetail }), { ...pendingDetail })
  assert.deepEqual(decodeDetail({ ...approvedDetail }), { ...approvedDetail })
  // Explicit nulls stay null: degradation belongs to the page, never to the decoder.
  assert.deepEqual(decodeSummary({ ...legacySummary, ...nullCustomer }), { ...legacySummary, ...nullCustomer })
  assert.deepEqual(decodeDetail({ ...pendingDetail, ...nullCustomer }), { ...pendingDetail, ...nullCustomer })
  // Regression: the pre-CCR shapes (old backend) still decode — the three keys read null and the
  // old 12-key detail has no timeline facts (empty, the page renders no timeline block).
  assert.deepEqual(decodeSummary({ ...legacySummary }), { ...legacySummary, ...nullCustomer })
  assert.deepEqual(decodeDetail({ ...legacyDetail }), { ...legacyDetail, ...nullCustomer, timeline: [] })
  const page = decodePage({ page: 1, pageSize: 20, total: 2, items: [{ ...legacySummary, ...customer }, { ...legacySummary, applicationId: '881009', ...nullCustomer }] })
  assert.deepEqual(page.items.map(item => item.customerNickname), ['E2E体验用户', null])
  assert.deepEqual(page.items.map(item => item.customerPhoneMasked), ['138****0100', null])
  assert.equal('timeline' in page.items[0]!, false, 'list items never carry a timeline')
  // Unicode names are carried verbatim.
  assert.equal(decodeSummary({ ...legacySummary, ...customer, customerNickname: '小橘😀' }).customerNickname, '小橘😀')
})

test('decoders stay strict: all-or-none key set, string|null values, server mask only, no private field', () => {
  for (const bad of [
    { ...legacySummary, customerNickname: 'E2E体验用户' },
    { ...legacySummary, customerNickname: null, customerPhoneMasked: null },
    { ...legacySummary, ...customer, timeline: pendingTimeline },
    { ...legacySummary, ...customer, reasonText: '服务完成后发现毛发修剪不齐' },
    { ...legacySummary, ...customer, userId: '101' },
    { ...legacySummary, ...customer, phone: '13800000100' },
    { ...legacySummary, ...customer, customerAvatarUrl: null },
    { ...legacySummary, ...customer, customerPhoneMasked: '13800000100' },
    { ...legacySummary, ...customer, customerPhoneMasked: '+86****0100' },
    { ...legacySummary, ...customer, customerPhoneMasked: '138****010' },
    { ...legacySummary, ...customer, customerPhoneMasked: '138****0100 ' },
    { ...legacySummary, ...customer, customerNickname: 1 },
    { ...legacySummary, ...customer, customerNickname: '' },
    { ...legacySummary, ...customer, customerNickname: '   ' },
    { ...legacySummary, ...customer, serviceName: [] },
    { ...legacySummary, ...customer, applicationId: 881001 },
  ]) assert.throws(() => decodeSummary(bad), /INVALID_RESPONSE/, JSON.stringify(bad))
  for (const bad of [
    { ...legacyDetail, ...customer },
    { ...legacyDetail, timeline: pendingTimeline },
    { ...pendingDetail, reasonText: '服务完成后发现毛发修剪不齐' },
    { ...pendingDetail, customerPhoneMasked: '13800000100' },
    { ...pendingDetail, timeline: null },
    { ...pendingDetail, decidedAt: '2026-11-05T09:15:32' },
  ]) assert.throws(() => decodeDetail(bad), /INVALID_RESPONSE/, JSON.stringify(bad))
})

test('timeline decoder: stage enum, millisecond UTC instants, one entry per stage, ascending (ties allowed)', () => {
  assert.deepEqual(decodeTimeline(pendingTimeline), pendingTimeline)
  assert.deepEqual(decodeTimeline(approvedDetail.timeline), approvedDetail.timeline)
  // A pre-service automatic approval is decided at its creation instant: equal instants are ascending.
  const tie = [{ stage: 'APPLIED', occurredAt: legacySummary.createdAt }, { stage: 'DECIDED', occurredAt: legacySummary.createdAt }]
  assert.deepEqual(decodeTimeline(tie), tie)
  // The contract has no minItems: an empty array is not a fabricated fact.
  assert.deepEqual(decodeTimeline([]), [])
  for (const bad of [
    null, {}, 'APPLIED',
    [{ stage: 'REFUNDED', occurredAt: legacySummary.createdAt }],
    [{ stage: 'REFUND_PENDING_CONFIRM', occurredAt: legacySummary.createdAt }],
    [{ stage: 'applied', occurredAt: legacySummary.createdAt }],
    [{ stage: 'APPLIED', occurredAt: '2026-11-05T08:15:32Z' }],
    [{ stage: 'APPLIED', occurredAt: '2026-11-05T16:15:32.649+08:00' }],
    [{ stage: 'APPLIED', occurredAt: '2026-02-30T00:00:00.000Z' }],
    [{ stage: 'APPLIED', occurredAt: 1762330532649 }],
    [{ stage: 'APPLIED', occurredAt: legacySummary.createdAt, note: '内部备注' }],
    [{ stage: 'APPLIED' }],
    [{ stage: 'APPLIED', occurredAt: legacySummary.createdAt }, { stage: 'APPLIED', occurredAt: legacySummary.createdAt }],
    [{ stage: 'MERCHANT_DEADLINE', occurredAt: legacySummary.merchantDeadline }, { stage: 'APPLIED', occurredAt: legacySummary.createdAt }],
    [...approvedDetail.timeline, { stage: 'APPLIED', occurredAt: legacySummary.merchantDeadline }],
  ]) assert.throws(() => decodeTimeline(bad), /INVALID_RESPONSE/, JSON.stringify(bad))
})

test('detail timeline entries must match the detail’s own facts (出现即有事实, nothing inferred)', () => {
  // PENDING detail: exactly APPLIED + MERCHANT_DEADLINE, each equal to its own field.
  const pending = decodeDetail({ ...pendingDetail })
  assert.deepEqual(pending.timeline.map(entry => entry.stage), ['APPLIED', 'MERCHANT_DEADLINE'])
  assert.equal(pending.timeline[0]!.occurredAt, pending.createdAt)
  assert.equal(pending.timeline[1]!.occurredAt, pending.merchantDeadline)
  // Decided detail carries DECIDED at decidedAt and the bound refund order.
  const approved = decodeDetail({ ...approvedDetail })
  assert.deepEqual(approved.timeline.map(entry => entry.stage), ['APPLIED', 'DECIDED', 'REFUND_ORDER_CREATED', 'MERCHANT_DEADLINE'])
  assert.equal(approved.timeline[1]!.occurredAt, approved.decidedAt)
  for (const bad of [
    // A pending application can never show a decision.
    { ...pendingDetail, timeline: [...pendingTimeline.slice(0, 1), { stage: 'DECIDED', occurredAt: decidedAt }, pendingTimeline[1]] },
    // Every instant is the detail's own fact, never a shifted one.
    { ...pendingDetail, timeline: [{ stage: 'APPLIED', occurredAt: '2026-11-05T08:15:33.000Z' }, pendingTimeline[1]] },
    { ...pendingDetail, timeline: [pendingTimeline[0], { stage: 'MERCHANT_DEADLINE', occurredAt: '2026-11-06T08:15:33.000Z' }] },
    { ...approvedDetail, timeline: approvedDetail.timeline.map(entry => entry.stage === 'DECIDED' ? { ...entry, occurredAt: '2026-11-05T09:15:33.000Z' } : entry) },
    // No refund order, no REFUND_ORDER_CREATED.
    { ...approvedDetail, refundOrderId: null },
  ]) assert.throws(() => decodeDetail(bad), /INVALID_RESPONSE/, JSON.stringify(bad))
})

test('real merchant client and repository carry the fields end to end without changing the requests', async () => {
  const h = await setup(async request => request.path === '/api/v1/merchant/refund-applications/881001' ? ok({ ...approvedDetail })
    : ok({ page: 1, pageSize: 20, total: 2, items: [{ ...legacySummary, ...customer }, { ...legacySummary, applicationId: '881009', ...nullCustomer }] }))
  h.api.scope.replace({ userId: '101', workspace: 'merchant', merchantId: '501', storeId: '601' })
  const deps = merchantRefundDeps(new MerchantRefundClient(h.api))
  const page = await deps.list({ merchantId: '501', storeId: '601', page: 1, pageSize: 20 })
  assert.deepEqual(page.items, [{ ...legacySummary, ...customer }, { ...legacySummary, applicationId: '881009', ...nullCustomer }])
  assert.deepEqual(await deps.detail('881001'), { ...approvedDetail })
  // Same paths and the same query as before the CCR: no new parameter, no new route.
  const list = h.calls.find(call => call.path === '/api/v1/merchant/refund-applications')
  assert.deepEqual(list?.data, { merchantId: '501', storeId: '601', page: 1, pageSize: 20 })
  const detail = h.calls.find(call => call.path === '/api/v1/merchant/refund-applications/881001')
  assert.deepEqual(detail?.data, { merchantId: '501', storeId: '601' })
  assert.equal(detail?.method, 'GET')
  assert.equal(h.calls.some(call => /phone|nickname|timeline|reasonText/i.test(JSON.stringify(call.data ?? {}))), false)
})

test('page helpers degrade null facts to placeholders and never present a system decision as the merchant’s', () => {
  assert.equal(refundCustomerName(customer), 'E2E体验用户')
  assert.equal(refundCustomerName(nullCustomer), '顾客')
  assert.equal(refundCustomerName({}), '顾客')
  assert.equal(refundCustomerInitial(customer), 'E')
  assert.equal(refundCustomerInitial({ customerNickname: '😀小橘' }), '😀')
  assert.equal(refundCustomerInitial(nullCustomer), '客')
  assert.equal(refundServiceLine({ ...customer, orderId: '9007199254740993' }), 'E2E到店精细洗护 · 订单 54740993')
  assert.equal(refundServiceLine({ ...nullCustomer, orderId: '777001' }), '订单 777001')
  assert.deepEqual(refundTimelineText, { APPLIED: '申请提交', MERCHANT_DEADLINE: '商家处理期限', DECIDED: '商家已决定', REFUND_ORDER_CREATED: '退款单已创建' })
  assert.equal(refundTimelineLabel('DECIDED', 'APPROVED'), '商家已决定')
  assert.equal(refundTimelineLabel('DECIDED', 'REJECTED'), '商家已决定')
  assert.equal(refundTimelineLabel('DECIDED', 'AUTO_APPROVED'), '系统已自动同意')
  assert.equal(refundTimelineLabel('REFUND_ORDER_CREATED', 'AUTO_APPROVED'), '退款单已创建')
  assert.equal(refundTimelineLabel('APPLIED', 'PENDING_MERCHANT'), '申请提交')
})

test('pages render customer/service/timeline blocks, keep ids/aria/routes, and scope new styles under .mrf-page', () => {
  const list = pageSource('index.tsx'), detailPage = pageSource('detail.tsx'), shell = pageSource('page.tsx'), css = pageSource('page.css')
  // List card: customer name (placeholder via the model) + masked phone line + service line.
  assert.match(list, /refundCustomerName\(item\)/)
  assert.match(list, /item\.customerPhoneMasked && <Text className='mrf-customer-phone'>\{item\.customerPhoneMasked\}<\/Text>/)
  assert.match(list, /refundServiceLine\(item\)/)
  assert.equal(/timeline/.test(list), false, 'the list renders no timeline')
  // Detail: customer block, service field with a non-fabricated dash, factual timeline only.
  assert.match(detailPage, /refundCustomerName\(detail\)/)
  assert.match(detailPage, /detail\.customerPhoneMasked && <Text className='mrf-customer-phone'>\{detail\.customerPhoneMasked\}<\/Text>/)
  assert.match(detailPage, /detail\.serviceName \?\? '—'/)
  assert.match(detailPage, /detail\.timeline\.length > 0 &&/)
  assert.match(detailPage, /refundTimelineLabel\(entry\.stage, detail\.status\)/)
  // §3.2 not approved: no buyer reason text is ever rendered (the only reasonText is the merchant's own reject draft).
  assert.equal(/detail\.reasonText|customerReason|reasonTextCipher/.test(detailPage), false)
  // No buyer id, raw mobile or avatar is rendered (the shell's own preview scope ticket is not a rendered fact).
  for (const source of [list, detailPage]) assert.equal(/userId|mobile|avatarUrl/i.test(source), false)
  for (const source of [list, detailPage, shell]) assert.equal(/<Image[^>]*customer|customerAvatar/i.test(source), false)
  // Existing interaction surface is unchanged.
  assert.match(list, /id=\{`mrf-case-\$\{item\.applicationId\}`\}/)
  assert.match(list, /\/merchant\/pages\/refund\/detail\?applicationId=\$\{encodeURIComponent\(item\.applicationId\)\}/)
  assert.ok(list.includes("id='mrf-more'"))
  for (const id of ['mrf-open-reject', 'mrf-approve', 'mrf-reject-text', 'mrf-submit-reject']) assert.ok(detailPage.includes(`id='${id}'`), id)
  for (const aria of ["ariaLabel='关闭填写面板'", "ariaLabel='拒绝退款'"]) assert.ok(detailPage.includes(aria), aria)
  assert.ok(shell.includes("id='mrf-back'") && shell.includes("ariaLabel='返回'"))
  // Every new CCR rule is a .mrf-page compound selector.
  const block = css.slice(css.indexOf('CCR-M-REFUND-VIEW-001'))
  const rules = block.split('\n').filter(line => /^\s*\.[\w-]/.test(line))
  assert.ok(rules.length >= 10, String(rules.length))
  for (const rule of rules) assert.match(rule.trim(), /^\.mrf-page \.mrf-[\w-]+/, rule)
})
