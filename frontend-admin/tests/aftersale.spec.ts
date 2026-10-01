import { expect, test, type Page, type Route } from '@playwright/test';
import type { Action, CaseDetail, CaseStatus, PendingIntent } from '../src/api/aftersales';

// Contract fixtures run the real production UI/transport with intercepted HTTP.
// These tests do not assert a live backend, migration, provider or production enablement.
test.use({ baseURL: 'http://127.0.0.1:4174' });
const CASE = '9007199254740993';
const ORDER = '9007199254740994';
const MERCHANT = '9007199254740995';
const STORE = '9007199254740996';
const BATCH = '9007199254740997';
const ASSET = '9007199254740998';
const PRIOR = ['9007199254740999', '9007199254741000'];
const ROOT = `/api/v1/admin/aftersales/${CASE}`;
const GRANT = `/api/v1/admin/aftersale-evidence-read-grants/${'a'.repeat(43)}`;
const HASH = 'a'.repeat(64);
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
const ACTIONS = ['aftersale.read', 'aftersale.handle', 'aftersale.decide'];
const summary = (status: CaseStatus = 'PENDING', version = '0') => ({ afterSaleId: CASE, orderId: ORDER, merchantId: MERCHANT, storeId: STORE, status, version, sourceStage: 'VERIFIED', typeCode: 'SERVICE_QUALITY', demandCode: 'OTHER', requestedAmount: '35.00', createdAt: '2026-10-01T02:00:00.000Z', deadline: '2026-10-08T02:00:00.000Z' });
function detail(status: CaseStatus = 'PENDING', version = '0', p4 = false): CaseDetail {
  const { merchantId: _m, storeId: _s, ...core } = summary(status, version);
  return { ...core, sourceStage: 'VERIFIED', description: '服务实际情况与说明不符，请核对本次服务证据。', supplementRequestId: null, supplementTarget: null, supplementDeadline: null, supplementReason: null, finalSetVersion: HASH, priorFinalCaseIds: p4 ? PRIOR : [], newProblemStatement: p4 ? '本次新问题与前次处理内容不同，提供新的服务图片说明。' : null, decisionType: null, refundAmount: null, decisionReason: null, evidence: [{ batchId: BATCH, submitterType: 'USER', text: '用户提交的本次服务现场情况和凭证。', opinionCode: null, submittedAt: '2026-10-01T02:00:00.000Z', assetIds: [ASSET] }] };
}
function receipt(status: CaseStatus, version: string) { return { commandId: '10001', orderId: ORDER, afterSaleId: CASE, status, version, occurredAt: '2026-10-01T03:00:00.000Z', evidenceBatchId: null, supplementRequestId: null, decisionId: status === 'RESOLVED' ? '10002' : null, refundOrderId: null }; }
function envelope(data: unknown, status = 200) { return { status, json: { code: 'SUCCESS', message: 'ok', data, traceId: 'fixture-aftersale' } }; }
function failed(code: string, status: number) { return { status, json: { code, message: code, data: null, traceId: 'fixture-aftersale-error' } }; }
async function signIn(page: Page, actions = ACTIONS, operatorId = '901', sessionId = 's1') {
  await page.route('**/api/v1/admin/auth/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/attempts')) return route.fulfill(envelope({ attemptId: '801', attemptToken: 'fixture-binding', expiresAt: '2026-10-02T02:00:00.000Z', nextStep: 'PROVE_IDENTITY' }, 201));
    if (path.endsWith('/requirements')) return route.fulfill(envelope({ requiredVerification: 'NONE' }));
    if (path.endsWith('/login')) return route.fulfill(envelope({ sessionId, operatorId, audience: 'ADMIN_WEB', tokenType: 'Bearer', accessToken: 'fixture-admin-token', expiresAt: '2026-10-02T02:00:00.000Z' }));
    if (path.endsWith('/session')) return route.fulfill(envelope({ sessionId, operatorId, audience: 'ADMIN_WEB', expiresAt: '2026-10-02T02:00:00.000Z', idleExpiresAt: '2026-10-02T01:00:00.000Z', authzVersion: 'v1' }));
    if (path.endsWith('/permissions')) return route.fulfill(envelope({ operatorId, authzVersion: 'v1', checkedAt: '2026-10-01T02:00:00.000Z', roles: ['OPERATOR'], dataScope: 'ALL', actionCodes: actions }));
    return route.fulfill(envelope(null));
  });
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill('fixture-ops');
  await page.getByLabel('密码', { exact: true }).fill('fixture-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
}
type Call = { path: string; method: string; requestId?: string; body?: unknown; authorization?: string };
function captures(page: Page) {
  const calls: Call[] = [];
  page.on('request', req => { const path = new URL(req.url()).pathname; if (path.includes('/aftersale')) calls.push({ path, method: req.method(), requestId: req.headers()['x-request-id'], body: req.postData() ? req.postDataJSON() : undefined, authorization: req.headers().authorization }); });
  return calls;
}
async function setupCases(page: Page, initial = detail(), handle?: (route: Route, path: string) => Promise<void>) {
  await page.route('**/api/v1/admin/aftersales**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/admin/aftersales') return route.fulfill(envelope({ page: 1, pageSize: 20, total: 1, items: [summary(initial.status, initial.version)] }));
    if (handle && route.request().method() === 'POST') return handle(route, path);
    if (path === ROOT) return route.fulfill(envelope(initial));
    const priorIndex = PRIOR.findIndex(id => path.endsWith(`/${id}`));
    if (priorIndex >= 0) return route.fulfill(envelope({ ...detail('RESOLVED', '3'), afterSaleId: PRIOR[priorIndex], decisionType: 'OTHER', decisionReason: `第 ${priorIndex + 1} 笔原处理结论，已安排人工解释。` }));
    return route.fulfill(failed('COMMON_NOT_FOUND', 404));
  });
}
async function openCase(page: Page) {
  await expect(page.getByRole('heading', { name: '售后工单', exact: true })).toBeVisible();
  await page.getByLabel('商家编号', { exact: true }).fill(MERCHANT);
  await page.getByLabel('门店编号', { exact: true }).fill(STORE);
  await page.getByRole('button', { name: '查询售后' }).click();
  await page.getByRole('link', { name: '查看工单', exact: true }).click();
  await expect(page.getByText('用户提交的本次服务现场情况和凭证。')).toBeVisible();
}

test('A-004 aftersale-only reader reaches its own guard without merchant.application.read', async ({ page }) => {
  const calls = captures(page);
  await setupCases(page); await signIn(page, ['aftersale.read']); await openCase(page);
  await expect(page.getByText('当前账号仅可查看售后工单与获权证据。')).toBeVisible();
  await expect(page.getByRole('button', { name: '受理工单' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: '商家申请审核' })).toHaveCount(0);
  expect(calls.some(call => call.path.includes('merchant-applications'))).toBe(false);
  await page.getByRole('link', { name: '返回售后列表' }).click();
  await expect(page.getByRole('cell', { name: CASE, exact: true })).toBeVisible();
});

test('A-004 handle grant does not authorize terminal decisions', async ({ page }) => {
  await setupCases(page, detail('PROCESSING', '2')); await signIn(page, ['aftersale.read', 'aftersale.handle']); await openCase(page);
  await expect(page.getByRole('button', { name: '提交补证要求' })).toBeVisible();
  await expect(page.getByRole('button', { name: '提交最终决定' })).toHaveCount(0);
  await page.screenshot({ path: 'test-results/aftersale-processing-handle.png', fullPage: true });
});

test('A-004 P4 reads EVERY final and submits exact finalSetVersion after manual review', async ({ page }) => {
  const calls = captures(page);
  await setupCases(page, detail('PENDING', '8', true), async route => { await route.fulfill(envelope(receipt('PROCESSING', '9'))); });
  await signIn(page); await openCase(page);
  await page.getByLabel('新问题核对意见').fill('逐笔核对两次历史处理，本次确为新的服务问题。');
  await page.getByLabel('确认问题符合受理条件，已有终局时确属新问题').check();
  await expect(page.getByRole('button', { name: '受理工单' })).toBeDisabled();
  await page.getByRole('button', { name: '读取历史终局' }).click();
  await expect(page.getByRole('heading', { name: `历史工单 ${PRIOR[1]} · 其他非退款处理` })).toBeVisible();
  const checkboxes = page.getByLabel('已核对这笔终局及当前新问题说明');
  await checkboxes.nth(0).check();
  await expect(page.getByRole('button', { name: '受理工单' })).toBeDisabled();
  await checkboxes.nth(1).check();
  await page.screenshot({ path: 'test-results/aftersale-p4-history.png', fullPage: true });
  await page.getByRole('button', { name: '受理工单' }).click();
  expect(calls.filter(c => c.path.endsWith('/accept'))[0]?.body).toEqual({ expectedVersion: '8', newProblemAssessment: '逐笔核对两次历史处理，本次确为新的服务问题。', expectedFinalSetVersion: HASH });
  expect(calls.filter(c => PRIOR.some(id => c.path.endsWith(id)))).toHaveLength(2);
});

test('A-004 store switch clears old list immediately and ignores a late response', async ({ page }) => {
  let late: Route | undefined;
  await page.route('**/api/v1/admin/aftersales**', route => { late = route; });
  await signIn(page);
  await page.getByLabel('商家编号', { exact: true }).fill(MERCHANT); await page.getByLabel('门店编号', { exact: true }).fill(STORE); await page.getByRole('button', { name: '查询售后' }).click();
  await expect.poll(() => !!late).toBe(true);
  await page.getByLabel('门店编号', { exact: true }).fill('12345');
  await late!.fulfill(envelope({ page: 1, pageSize: 20, total: 1, items: [summary()] }));
  await expect(page.getByRole('cell', { name: CASE, exact: true })).toHaveCount(0);
  await expect(page.getByText(`商家 ${MERCHANT} · 门店 ${STORE}`, { exact: true })).toHaveCount(0);
});

test('A-004 waiting and terminal states expose no accept, supplement or decision writes', async ({ page }) => {
  await setupCases(page, { ...detail('WAITING_SUPPLEMENT', '3'), supplementRequestId: '20001', supplementTarget: 'MERCHANT', supplementReason: '请提交实际服务照片', supplementDeadline: '2099-10-02T12:00:00.000Z' });
  await signIn(page); await openCase(page);
  await expect(page.getByRole('heading', { name: '当前补证要求' })).toBeVisible();
  for (const name of ['受理工单', '提交补证要求', '提交最终决定']) await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0);
});

test('A-004 revocation during evidence read clears the sensitive page and returns to login', async ({ page }) => {
  await setupCases(page, detail(), async route => { await route.fulfill(envelope({ readUrl: GRANT, expiresAt: '2099-10-01T02:05:00.000Z' })); });
  await page.route(`**${GRANT}`, route => route.fulfill(failed('FORBIDDEN', 403)));
  await signIn(page); await openCase(page); await page.getByRole('button', { name: '申请查看证据' }).click();
  await expect(page.getByRole('heading', { name: '运营登录' })).toBeVisible();
  await expect(page.getByText('用户提交的本次服务现场情况和凭证。')).toHaveCount(0);
  await expect(page.getByRole('img', { name: `售后证据 ${ASSET}` })).toHaveCount(0);
});

test('A-004 strict strings reject trailing newlines before URL/header normalization', async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/');
  const result = await page.evaluate(async ({ caseId, merchantId, storeId, payload, grantUrl }) => {
    const path = '/src/api/aftersales.ts'; const { createAfterSaleClient, validId, validUtc } = await import(path) as typeof import('../src/api/aftersales');
    const failure = async (p: Promise<unknown>) => { try { await p; return 'success'; } catch (e) { return (e as Error).message; } };
    let sent = 0;
    const client = createAfterSaleClient(async () => { sent++; return Response.json({ code: 'SUCCESS', message: 'ok', data: payload, traceId: 'test' }); }); client.resetContext('test-token');
    const intent: PendingIntent = { caseId, action: 'accept', requestId: crypto.randomUUID(), label: 'test', body: { expectedVersion: '0' } };
    const rejected = [await failure(client.get(caseId + '\n')), await failure(client.list({ merchantId: merchantId + '\n', storeId, page: 1, pageSize: 20 })), await failure(client.send({ ...intent, requestId: intent.requestId + '\n' })), await failure(client.send({ ...intent, body: { expectedVersion: '0\n' } })), await failure(client.send({ ...intent, body: { expectedVersion: '0', newProblemAssessment: '新问题', expectedFinalSetVersion: 'a'.repeat(64) + '\n' } })), await failure(client.consumeReadGrant(grantUrl + '\n'))];
    const corrupt = (patch: object) => { const c = createAfterSaleClient(async () => Response.json({ code: 'SUCCESS', message: 'ok', data: { ...payload, ...patch }, traceId: 'test' })); c.resetContext('test-token'); return failure(c.get(caseId)); };
    return { rejected, sent, id: validId('1\n'), version: validId('0\n', true), time: validUtc('2026-10-01T00:00:00.000Z\n'), money: await corrupt({ requestedAmount: '12.50\n' }), hash: await corrupt({ finalSetVersion: 'a'.repeat(64) + '\n' }) };
  }, { caseId: CASE, merchantId: MERCHANT, storeId: STORE, payload: detail(), grantUrl: GRANT });
  expect(result.rejected).toEqual(['INVALID_ARGUMENT', 'INVALID_ARGUMENT', 'INVALID_ARGUMENT', 'INVALID_ARGUMENT', 'INVALID_ARGUMENT', 'INVALID_READ_URL']); expect(result.sent).toBe(0); expect(result.id).toBe(false); expect(result.version).toBe(false); expect(result.time).toBe(false); expect(result.money).toBe('INVALID_RESPONSE'); expect(result.hash).toBe('INVALID_RESPONSE');
});

test('A-004 duplicate problem quotes a genuine prior final without making a decision', async ({ page }) => {
  const calls = captures(page);
  await setupCases(page, detail('PENDING', '8', true), async route => { await route.fulfill(envelope(receipt('CLOSED', '9'))); });
  await signIn(page); await openCase(page); await page.getByRole('button', { name: '读取历史终局' }).click();
  await expect(page.getByLabel('已核对这笔终局及当前新问题说明')).toHaveCount(2);
  for (const box of await page.getByLabel('已核对这笔终局及当前新问题说明').all()) await box.check();
  await page.getByLabel('引用历史终局').selectOption(PRIOR[1]);
  await page.getByLabel('重复问题关闭原因').fill('重复提出已处理的问题，引用第二笔既有终局。');
  await page.getByLabel('确认本次为重复问题并引用原结论').check();
  await page.getByRole('button', { name: '关闭重复问题' }).click();
  expect(calls.find(c => c.path.endsWith('/close-duplicate'))?.body).toEqual({ expectedVersion: '8', priorFinalCaseId: PRIOR[1], reason: '重复提出已处理的问题，引用第二笔既有终局。' });
  expect(calls.filter(c => c.path.endsWith('/decisions'))).toHaveLength(0);
});

test('A-004 UTC supplement and nonrefund decision use stable UUID/string CAS and no money coercion', async ({ page }) => {
  const calls = captures(page);
  await setupCases(page, detail('PROCESSING', '9007199254741001'), async route => { await route.fulfill(envelope(receipt('PROCESSING', '9007199254741002'))); });
  await signIn(page); await openCase(page);
  await page.getByLabel('补证方', { exact: true }).selectOption('MERCHANT');
  await page.getByLabel('补证原因', { exact: true }).fill('请提供本次服务完整现场照片');
  await page.getByLabel('补证截止（UTC 毫秒）').fill('2099-10-02T12:00:00.000+08:00');
  await expect(page.getByRole('button', { name: '提交补证要求' })).toBeDisabled();
  await page.getByLabel('补证截止（UTC 毫秒）').fill('2099-10-02T12:00:00.000Z');
  await page.getByRole('button', { name: '提交补证要求' }).click();
  await expect(page.getByText('补证要求已提交，以工单刷新结果为准。')).toBeVisible();
  const supplement = calls.find(c => c.path.endsWith('/supplement-requests'));
  expect(supplement?.body).toEqual({ expectedVersion: '9007199254741001', targetParty: 'MERCHANT', reason: '请提供本次服务完整现场照片', deadline: '2099-10-02T12:00:00.000Z' });
  await page.getByLabel('终局类型').selectOption('RESERVICE');
  await page.getByLabel('终局原因', { exact: true }).fill('商家人工联系用户安排重新服务');
  await page.getByLabel('确认基于双方证据作出最终处理').check();
  await page.getByRole('button', { name: '提交最终决定' }).click();
  const decision = calls.find(c => c.path.endsWith('/decisions'));
  expect(decision?.body).toEqual({ expectedVersion: '9007199254741001', decisionType: 'RESERVICE', reason: '商家人工联系用户安排重新服务', refundAmount: null });
  for (const call of [supplement, decision]) expect(call?.requestId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  await expect(page.getByRole('option', { name: '全额退款', exact: true })).toHaveCount(0);
  await expect(page.getByRole('option', { name: '部分退款', exact: true })).toHaveCount(0);
});

test('A-004 lost ACK survives refresh AND route unload; original payload and UUID alone resolve it', async ({ page }) => {
  const calls = captures(page);
  let accepts = 0;
  await setupCases(page, detail(), async route => { accepts++; if (accepts === 1) await route.abort('connectionreset'); else await route.fulfill(envelope(receipt('PROCESSING', '1'))); });
  await signIn(page); await openCase(page);
  await page.getByLabel('确认问题符合受理条件，已有终局时确属新问题').check();
  await page.getByRole('button', { name: '受理工单' }).click();
  await expect(page.getByRole('alert')).toContainText('结果未知');
  await page.getByRole('button', { name: '刷新工单' }).click();
  await expect(page.getByRole('button', { name: '受理工单' })).toBeDisabled();
  await page.getByRole('link', { name: '返回售后列表' }).click();
  await page.getByRole('link', { name: '查看工单', exact: true }).click();
  await expect(page.getByRole('button', { name: '重试原操作' })).toBeVisible();
  await page.getByRole('button', { name: '重试原操作' }).click();
  await expect(page.getByRole('button', { name: '重试原操作' })).toHaveCount(0);
  const writes = calls.filter(c => c.path.endsWith('/accept'));
  expect(writes).toHaveLength(2); expect(writes[1].requestId).toBe(writes[0].requestId); expect(writes[1].body).toEqual(writes[0].body);
});

async function fillBusiness(page: Page, action: Action) {
  if (action === 'accept') { await page.getByLabel('确认问题符合受理条件，已有终局时确属新问题').check(); await page.getByRole('button', { name: '受理工单' }).click(); }
  if (action === 'supplement-requests') { await page.getByLabel('补证原因', { exact: true }).fill('需要本次真实服务照片'); await page.getByLabel('补证截止（UTC 毫秒）').fill('2099-10-02T12:00:00.000Z'); await page.getByRole('button', { name: '提交补证要求' }).click(); }
  if (action === 'decisions') { await page.getByLabel('终局类型').selectOption('OTHER'); await page.getByLabel('终局原因', { exact: true }).fill('原运营未确认的处理说明，仅本人可以恢复'); await page.getByLabel('确认基于双方证据作出最终处理').check(); await page.getByRole('button', { name: '提交最终决定' }).click(); }
  if (action === 'close-duplicate') {
    await page.getByRole('button', { name: '读取历史终局' }).click(); await expect(page.getByLabel('已核对这笔终局及当前新问题说明')).toHaveCount(2);
    for (const box of await page.getByLabel('已核对这笔终局及当前新问题说明').all()) await box.check();
    await page.getByLabel('引用历史终局').selectOption(PRIOR[0]); await page.getByLabel('重复问题关闭原因').fill('重复原问题，引用第一笔原结论'); await page.getByLabel('确认本次为重复问题并引用原结论').check(); await page.getByRole('button', { name: '关闭重复问题' }).click();
  }
}
for (const action of ['accept', 'supplement-requests', 'close-duplicate', 'decisions'] as const) {
  test(`A-004 ${action} lost ACK survives full browser reload and same operator new session`, async ({ page }) => {
    const calls = captures(page); let writes = 0;
    const processing = action === 'supplement-requests' || action === 'decisions';
    const initial = detail(processing ? 'PROCESSING' : 'PENDING', '4', action === 'close-duplicate');
    await setupCases(page, initial, async route => { writes++; if (writes === 1) await route.abort('connectionreset'); else await route.fulfill(envelope(receipt(action === 'accept' ? 'PROCESSING' : action === 'supplement-requests' ? 'WAITING_SUPPLEMENT' : action === 'close-duplicate' ? 'CLOSED' : 'RESOLVED', '5'))); });
    await signIn(page); await openCase(page); await fillBusiness(page, action); await expect(page.getByRole('alert')).toContainText('结果未知');
    const stored = await page.evaluate(() => Object.keys(localStorage).filter(k => k.startsWith('pet.admin.aftersale.command.v1:')).map(k => ({ key: k, raw: localStorage.getItem(k)! })));
    expect(stored).toHaveLength(1); expect(stored[0].key).toBe(`pet.admin.aftersale.command.v1:901:${MERCHANT}:${STORE}:${CASE}`); expect(stored[0].raw).not.toContain('fixture-admin-token'); expect(stored[0].raw).not.toContain('readUrl'); expect(stored[0].raw).not.toContain('blob:');
    await page.reload(); await expect(page.getByRole('heading', { name: '运营登录' })).toBeVisible();
    await signIn(page, ACTIONS, '901', 's2'); await expect(page.getByRole('heading', { name: '售后工单', exact: true })).toBeVisible(); expect(writes).toBe(1);
    await openCase(page); await expect(page.getByRole('button', { name: '重试原操作' })).toBeVisible(); expect(writes).toBe(1);
    await page.getByRole('button', { name: '重试原操作' }).click(); await expect(page.getByRole('button', { name: '重试原操作' })).toHaveCount(0);
    const sent = calls.filter(c => c.path.endsWith(`/${action}`)); expect(sent).toHaveLength(2); expect(sent[1].requestId).toBe(sent[0].requestId); expect(sent[1].body).toEqual(sent[0].body);
    expect(await page.evaluate(() => Object.keys(localStorage).filter(k => k.startsWith('pet.admin.aftersale.command.v1:')))).toEqual([]);
  });
}

test('A-004 401 on an unknown command preserves the business journal through reauthentication', async ({ page }) => {
  const calls = captures(page); let writes = 0;
  await setupCases(page, detail('PROCESSING', '4'), async route => { writes++; if (writes === 1) await route.abort('connectionreset'); else if (writes === 2) await route.fulfill(failed('COMMON_UNAUTHORIZED', 401)); else await route.fulfill(envelope(receipt('RESOLVED', '5'))); });
  await signIn(page); await openCase(page); await fillBusiness(page, 'decisions'); await expect(page.getByRole('button', { name: '重试原操作' })).toBeVisible();
  await page.getByRole('button', { name: '重试原操作' }).click(); await expect(page.getByRole('heading', { name: '运营登录' })).toBeVisible();
  await signIn(page, ACTIONS, '901', 's2'); await openCase(page); await expect(page.getByRole('button', { name: '重试原操作' })).toBeVisible(); expect(writes).toBe(2);
  await page.getByRole('button', { name: '重试原操作' }).click(); await expect(page.getByRole('button', { name: '重试原操作' })).toHaveCount(0);
  const sent = calls.filter(c => c.path.endsWith('/decisions')); expect(sent).toHaveLength(3); for (const call of sent) { expect(call.requestId).toBe(sent[0].requestId); expect(call.body).toEqual(sent[0].body); }
});

test('A-004 closing the browser context retains the original command in persisted origin storage', async ({ page, context, browser }) => {
  const originalCalls = captures(page);
  await setupCases(page, detail('PROCESSING', '4'), async route => { await route.abort('connectionreset'); });
  await signIn(page); await openCase(page); await fillBusiness(page, 'decisions'); await expect(page.getByRole('button', { name: '重试原操作' })).toBeVisible();
  // The saved origin state models the browser's persistent profile; no auth token
  // or session state is persisted by the application. The next context must log in.
  const persistedOrigin = await context.storageState();
  expect(persistedOrigin.origins[0]?.localStorage).toHaveLength(1);
  expect(JSON.stringify(persistedOrigin)).not.toContain('fixture-admin-token');
  await page.close(); await context.close();
  const restarted = await browser.newContext({ baseURL: 'http://127.0.0.1:4174', storageState: persistedOrigin });
  try {
    const fresh = await restarted.newPage(); const replayCalls = captures(fresh); let writes = 0;
    await setupCases(fresh, detail('PROCESSING', '4'), async route => { writes++; await route.fulfill(envelope(receipt('RESOLVED', '5'))); });
    await fresh.goto('/aftersales'); await expect(fresh.getByRole('heading', { name: '运营登录' })).toBeVisible();
    await signIn(fresh, ACTIONS, '901', 'new-session'); await openCase(fresh);
    await expect(fresh.getByRole('button', { name: '重试原操作' })).toBeVisible(); expect(writes).toBe(0);
    await fresh.getByRole('button', { name: '重试原操作' }).click(); await expect(fresh.getByRole('button', { name: '重试原操作' })).toHaveCount(0);
    const original = originalCalls.find(c => c.path.endsWith('/decisions'))!; const replay = replayCalls.find(c => c.path.endsWith('/decisions'))!;
    expect(replay.requestId).toBe(original.requestId); expect(replay.body).toEqual(original.body); expect(writes).toBe(1);
    expect(replayCalls.filter(c => c.method === 'GET' && c.path === '/api/v1/admin/aftersales')).toHaveLength(1);
    expect(replayCalls.some(c => c.method === 'GET' && c.path === ROOT)).toBe(true);
  } finally { await restarted.close(); }
});

test('A-004 another operator cannot see or replay the original operator journal', async ({ page }) => {
  let writes = 0;
  await setupCases(page, detail('PROCESSING', '4'), async route => { writes++; await route.abort('connectionreset'); });
  await signIn(page); await openCase(page); await fillBusiness(page, 'decisions'); await expect(page.getByRole('button', { name: '重试原操作' })).toBeVisible();
  await page.getByRole('button', { name: '退出', exact: true }).click(); await signIn(page, ACTIONS, '902', 's2'); await openCase(page);
  await expect(page.getByRole('button', { name: '重试原操作' })).toHaveCount(0); await expect(page.getByText(/原运营未确认的处理说明，仅本人可以恢复/)).toHaveCount(0); expect(writes).toBe(1);
  await page.getByRole('button', { name: '退出', exact: true }).click(); await signIn(page, ACTIONS, '901', 's3'); await openCase(page);
  await expect(page.getByRole('button', { name: '重试原操作' })).toBeVisible(); await page.getByText('查看原处理内容', { exact: true }).click(); await expect(page.getByText('原处理原因：原运营未确认的处理说明，仅本人可以恢复')).toBeVisible(); expect(writes).toBe(1);
});

test('A-004 leaving the store hides its unknown write but returning restores the original command', async ({ page }) => {
  const calls = captures(page); let writes = 0;
  await page.route('**/api/v1/admin/aftersales**', async route => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/v1/admin/aftersales') return route.fulfill(envelope({ page: Number(url.searchParams.get('page')), pageSize: Number(url.searchParams.get('pageSize')), total: url.searchParams.get('storeId') === STORE ? 1 : 0, items: url.searchParams.get('storeId') === STORE ? [summary()] : [] }));
    if (url.pathname.endsWith('/accept')) { writes++; if (writes === 1) return route.abort('connectionreset'); return route.fulfill(envelope(receipt('PROCESSING', '1'))); }
    return route.fulfill(envelope(detail()));
  });
  await signIn(page); await openCase(page); await fillBusiness(page, 'accept'); await expect(page.getByRole('button', { name: '重试原操作' })).toBeVisible();
  // Missing or malformed query scope must not inherit the old page's store proof.
  for (const suffix of ['', `?merchantId=${MERCHANT}&storeId=%0A`]) {
    await page.evaluate(url => { history.pushState({}, '', url); window.dispatchEvent(new PopStateEvent('popstate')); }, `/aftersales/${CASE}${suffix}`);
    await expect(page.getByText('用户提交的本次服务现场情况和凭证。')).toBeVisible(); await expect(page.getByRole('button', { name: '重试原操作' })).toHaveCount(0); await expect(page.getByRole('button', { name: '受理工单' })).toBeDisabled();
  }
  await page.getByRole('link', { name: '返回售后列表' }).click(); await page.getByLabel('商家编号', { exact: true }).fill(MERCHANT); await page.getByLabel('门店编号', { exact: true }).fill(STORE); await page.getByRole('button', { name: '查询售后' }).click(); await page.getByRole('link', { name: '查看工单', exact: true }).click(); await expect(page.getByRole('button', { name: '重试原操作' })).toBeVisible();
  await page.getByRole('link', { name: '返回售后列表' }).click(); await page.getByLabel('门店编号', { exact: true }).fill('12345'); await page.getByRole('button', { name: '查询售后' }).click();
  await expect(page.getByText('该门店没有符合条件的售后工单。')).toBeVisible(); await expect(page.getByRole('button', { name: '重试原操作' })).toHaveCount(0);
  // A forged deep link cannot recover the original store's payload just because case GET is authorized.
  await page.evaluate(({ caseId, merchantId }) => { history.pushState({}, '', `/aftersales/${caseId}?merchantId=${merchantId}&storeId=12345`); window.dispatchEvent(new PopStateEvent('popstate')); }, { caseId: CASE, merchantId: MERCHANT });
  await expect(page.getByRole('alert')).toContainText('RESOURCE_SCOPE_UNVERIFIED'); await expect(page.getByRole('button', { name: '重试原操作' })).toHaveCount(0); expect(writes).toBe(1);
  await page.getByRole('link', { name: '返回售后列表' }).click();
  await expect(page.getByText('该门店没有符合条件的售后工单。')).toBeVisible();
  await page.getByLabel('门店编号', { exact: true }).fill(STORE); await page.getByRole('button', { name: '查询售后' }).click(); await page.getByRole('link', { name: '查看工单', exact: true }).click();
  await expect(page.getByRole('button', { name: '重试原操作' })).toBeVisible(); expect(writes).toBe(1); await page.getByRole('button', { name: '重试原操作' }).click();
  const sent = calls.filter(c => c.path.endsWith('/accept')); expect(sent).toHaveLength(2); expect(sent[1].requestId).toBe(sent[0].requestId); expect(sent[1].body).toEqual(sent[0].body);
});

test('A-004 a lost read-grant ACK is ephemeral and never stored with business journals', async ({ page }) => {
  const calls = captures(page); let grants = 0;
  await setupCases(page, detail(), async route => { grants++; if (grants === 1) await route.abort('connectionreset'); else await route.fulfill(envelope({ readUrl: GRANT, expiresAt: '2099-10-01T02:05:00.000Z' })); });
  await page.route(`**${GRANT}`, route => route.fulfill({ status: 200, contentType: 'image/png', body: PNG }));
  await signIn(page); await openCase(page); await page.getByRole('button', { name: '申请查看证据' }).click(); await expect(page.getByRole('button', { name: '重试原操作' })).toBeVisible();
  expect(await page.evaluate(() => Object.keys(localStorage).filter(k => k.startsWith('pet.admin.aftersale.command.v1:')))).toEqual([]);
  await page.reload(); await signIn(page, ACTIONS, '901', 's2'); await openCase(page); await expect(page.getByRole('button', { name: '重试原操作' })).toHaveCount(0); expect(grants).toBe(1);
  await page.getByRole('button', { name: '申请查看证据' }).click(); await expect(page.getByRole('img', { name: `售后证据 ${ASSET}` })).toBeVisible();
  const sent = calls.filter(c => c.path.endsWith('/read-grants')); expect(sent).toHaveLength(2); expect(sent[1].requestId).not.toBe(sent[0].requestId);
});

test('A-004 business POST fails closed if the durable journal cannot be written', async ({ page }) => {
  const calls = captures(page);
  await setupCases(page, detail('PROCESSING', '4'), async route => { await route.fulfill(envelope(receipt('RESOLVED', '5'))); });
  await signIn(page); await openCase(page);
  await page.evaluate(() => { const original = Storage.prototype.setItem; Storage.prototype.setItem = function (key, value) { if (key.startsWith('pet.admin.aftersale.command.v1:')) throw new Error('storage unavailable'); original.call(this, key, value); }; });
  await fillBusiness(page, 'decisions'); await expect(page.getByRole('alert')).toContainText('JOURNAL_UNAVAILABLE');
  expect(calls.filter(c => c.method === 'POST')).toEqual([]);
});

for (const point of ['command', 'grant', 'post-failure'] as const) {
  test(`A-004 journal read failure at ${point} closes the page without an unhandled error`, async ({ page }) => {
    const calls = captures(page); const errors: string[] = []; page.on('pageerror', e => errors.push(e.message));
    const disableReads = () => page.evaluate(() => {
      const original = Storage.prototype.getItem;
      Object.assign(window, { restoreJournalRead: () => { Storage.prototype.getItem = original; } });
      Storage.prototype.getItem = function (key) { if (key.startsWith('pet.admin.aftersale.command.v1:')) throw new Error('storage unavailable'); return original.call(this, key); };
    });
    await setupCases(page, detail('PROCESSING', '4'), async route => { await disableReads(); await route.abort('connectionreset'); });
    await signIn(page); await openCase(page);
    if (point !== 'post-failure') await disableReads();
    if (point === 'grant') await page.getByRole('button', { name: '申请查看证据' }).click(); else await fillBusiness(page, 'decisions');
    await expect(page.getByRole('alert')).toContainText('JOURNAL_UNAVAILABLE'); await expect(page.getByRole('button', { name: '提交最终决定' })).toHaveCount(0); await expect(page.getByRole('button', { name: '重试原操作' })).toHaveCount(0);
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(point === 'post-failure' ? 1 : 0); expect(errors).toEqual([]);
    await page.evaluate(() => (window as unknown as { restoreJournalRead: () => void }).restoreJournalRead());
    await page.getByRole('button', { name: '刷新工单' }).click(); await expect(page.getByText('用户提交的本次服务现场情况和凭证。')).toBeVisible();
    await expect(page.getByRole('button', { name: '重试原操作' })).toHaveCount(point === 'post-failure' ? 1 : 0);
  });
}

for (const failure of [{ code: 'COMMON_RATE_LIMITED', status: 429 }, { code: 'IDEMPOTENCY_KEY_CONFLICT', status: 409 }, { code: 'IDEMPOTENCY_IN_PROGRESS', status: 409 }, { code: 'COMMON_CONFLICT', status: 409 }]) {
  test(`A-004 ${failure.code} retains the original UUID and blocks a replacement command`, async ({ page }) => {
    const calls = captures(page); let writes = 0;
    await setupCases(page, detail('PROCESSING', '4'), async route => { writes++; if (writes === 1) await route.fulfill(failed(failure.code, failure.status)); else await route.fulfill(envelope(receipt('RESOLVED', '5'))); });
    await signIn(page); await openCase(page); await fillBusiness(page, 'decisions'); await expect(page.getByRole('button', { name: '重试原操作' })).toBeVisible(); await expect(page.getByRole('button', { name: '提交最终决定' })).toBeDisabled();
    await page.getByRole('button', { name: '刷新工单' }).click(); await expect(page.getByRole('button', { name: '重试原操作' })).toBeVisible(); expect(writes).toBe(1);
    await page.getByRole('button', { name: '重试原操作' }).click(); await expect(page.getByRole('button', { name: '重试原操作' })).toHaveCount(0);
    const sent = calls.filter(c => c.path.endsWith('/decisions')); expect(sent).toHaveLength(2); expect(sent[1].requestId).toBe(sent[0].requestId); expect(sent[1].body).toEqual(sent[0].body);
  });
}

test('A-004 manual refresh invalidates old human confirmation and reasons', async ({ page }) => {
  await setupCases(page, detail('PENDING', '4', true)); await signIn(page); await openCase(page); await page.getByRole('button', { name: '读取历史终局' }).click(); await expect(page.getByLabel('已核对这笔终局及当前新问题说明')).toHaveCount(2);
  for (const box of await page.getByLabel('已核对这笔终局及当前新问题说明').all()) await box.check(); await page.getByLabel('新问题核对意见').fill('已核对原历史，此处是新问题说明'); await page.getByLabel('确认问题符合受理条件，已有终局时确属新问题').check(); await expect(page.getByRole('button', { name: '受理工单' })).toBeEnabled();
  await page.getByRole('button', { name: '刷新工单' }).click(); await expect(page.getByLabel('确认问题符合受理条件，已有终局时确属新问题')).not.toBeChecked(); await expect(page.getByLabel('新问题核对意见')).toHaveValue(''); await expect(page.getByLabel('已核对这笔终局及当前新问题说明')).toHaveCount(0); await expect(page.getByRole('button', { name: '受理工单' })).toBeDisabled();
});

for (const event of ['visibilitychange', 'pagehide'] as const) {
  test(`A-004 ${event} clears object URLs and refuses a late binary image`, async ({ page }) => {
    const revoked: string[] = []; let binary: Route | undefined; let reads = 0;
    await page.addInitScript(() => { const revoked: string[] = []; const original = URL.revokeObjectURL; URL.revokeObjectURL = value => { revoked.push(value); original(value); }; Object.assign(window, { evidenceRevoked: revoked }); });
    await setupCases(page, detail(), async route => { await route.fulfill(envelope({ readUrl: GRANT, expiresAt: '2099-10-01T02:05:00.000Z' })); });
    await page.route(`**${GRANT}`, route => { reads++; if (reads === 1) return route.fulfill({ status: 200, contentType: 'image/png', body: PNG }); binary = route; return Promise.resolve(); });
    await signIn(page); await openCase(page); await page.getByRole('button', { name: '申请查看证据' }).click(); const img = page.getByRole('img', { name: `售后证据 ${ASSET}` }); await expect(img).toBeVisible(); const oldUrl = await img.getAttribute('src'); revoked.push(oldUrl!);
    await page.getByRole('button', { name: '申请查看证据' }).click(); await expect.poll(() => !!binary).toBe(true);
    await page.evaluate(event => { if (event === 'visibilitychange') { Object.defineProperty(document, 'hidden', { configurable: true, value: true }); document.dispatchEvent(new Event('visibilitychange')); } else window.dispatchEvent(new PageTransitionEvent('pagehide')); }, event);
    await expect(img).toHaveCount(0); await expect(page.getByText('用户提交的本次服务现场情况和凭证。')).toHaveCount(0);
    await binary!.fulfill({ status: 200, contentType: 'image/png', body: PNG });
    await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, value: false }); document.dispatchEvent(new Event('visibilitychange')); });
    await expect(img).toHaveCount(0); expect(await page.evaluate(() => (window as unknown as { evidenceRevoked: string[] }).evidenceRevoked)).toContain(revoked[0]);
    await page.getByRole('button', { name: '刷新工单' }).click(); await expect(page.getByText('用户提交的本次服务现场情况和凭证。')).toBeVisible(); await expect(img).toHaveCount(0); expect(reads).toBe(2);
  });
}

test('A-004 409 reloads authoritative version and clears the old decision confirmation', async ({ page }) => {
  const calls = captures(page);
  let version = '4';
  await page.route('**/api/v1/admin/aftersales**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/admin/aftersales') return route.fulfill(envelope({ page: 1, pageSize: 20, total: 1, items: [summary('PROCESSING', version)] }));
    if (path.endsWith('/decisions')) { version = '5'; return route.fulfill(failed('AFTERSALE_STATE_NOT_ALLOWED', 409)); }
    return route.fulfill(envelope(detail('PROCESSING', version)));
  });
  await signIn(page); await openCase(page); await page.getByLabel('终局原因', { exact: true }).fill('根据用户和商家的证据予以驳回'); await page.getByLabel('确认基于双方证据作出最终处理').check(); await page.getByRole('button', { name: '提交最终决定' }).click();
  await expect(page.getByRole('alert')).toContainText('重新核对');
  await expect(page.getByLabel('终局原因', { exact: true })).toHaveValue('');
  await expect(page.getByLabel('确认基于双方证据作出最终处理')).not.toBeChecked();
  expect(calls.filter(c => c.path.endsWith('/decisions'))).toHaveLength(1);
  expect(calls.filter(c => c.path === ROOT && c.method === 'GET')).toHaveLength(2);
});

test('A-004 private evidence uses only current-route grant and bearer GET, then revokes blob URL', async ({ page }) => {
  const calls = captures(page);
  await page.addInitScript(() => { const revoked: string[] = []; const original = URL.revokeObjectURL; URL.revokeObjectURL = value => { revoked.push(value); original(value); }; Object.assign(window, { evidenceRevoked: revoked }); });
  await setupCases(page, detail(), async (route, path) => { if (path.endsWith('/read-grants')) await route.fulfill(envelope({ readUrl: GRANT, expiresAt: '2099-10-01T02:05:00.000Z' })); else await route.fulfill(failed('COMMON_NOT_FOUND', 404)); });
  await page.route(`**${GRANT}`, route => route.fulfill({ status: 200, contentType: 'image/png', body: PNG }));
  await signIn(page); await openCase(page); await page.getByRole('button', { name: '申请查看证据' }).click();
  const image = page.getByRole('img', { name: `售后证据 ${ASSET}` }); await expect(image).toBeVisible();
  const objectUrl = await image.getAttribute('src'); expect(objectUrl).toMatch(/^blob:/);
  const grant = calls.find(c => c.path.endsWith('/read-grants')); expect(grant?.path).toBe(`${ROOT}/evidence-batches/${BATCH}/assets/${ASSET}/read-grants`); expect(grant?.body).toEqual({ reason: '售后工单处理，核对双方提交的图片证据' }); expect(grant?.requestId).toBeTruthy();
  const read = calls.find(c => c.path === GRANT); expect(read?.method).toBe('GET'); expect(read?.authorization).toBe('Bearer fixture-admin-token'); expect(read?.body).toBeUndefined();
  await page.getByRole('button', { name: '隐藏图片' }).click(); await expect(image).toHaveCount(0);
  expect(await page.evaluate(() => (window as unknown as { evidenceRevoked: string[] }).evidenceRevoked)).toContain(objectUrl);
});

test('A-004 consumed/expired grant does not reuse a consumed binary URL or retain unknown command', async ({ page }) => {
  const calls = captures(page);
  await setupCases(page, detail(), async route => { await route.fulfill(envelope({ readUrl: GRANT, expiresAt: '2099-10-01T02:05:00.000Z' })); });
  await page.route(`**${GRANT}`, route => route.fulfill(failed('PRIVATE_ASSET_READ_GRANT_EXPIRED', 410)));
  await signIn(page); await openCase(page); await page.getByRole('button', { name: '申请查看证据' }).click();
  await expect(page.getByRole('alert')).toContainText('重新申请');
  await expect(page.getByRole('button', { name: '重试原操作' })).toHaveCount(0);
  await page.getByRole('button', { name: '申请查看证据' }).click();
  const grants = calls.filter(c => c.path.endsWith('/read-grants')); expect(grants).toHaveLength(2); expect(grants[1].requestId).not.toBe(grants[0].requestId);
});

test('A-004 API fails closed on number IDs, bad envelope, foreign grants and stale scope', async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/');
  const result = await page.evaluate(async ({ caseId, merchantId, storeId, payload }) => {
    const path = '/src/api/aftersales.ts';
    const { createAfterSaleClient } = await import(path) as typeof import('../src/api/aftersales');
    const failure = async (p: Promise<unknown>) => { try { await p; return 'success'; } catch (e) { return (e as Error).message; } };
    let calls = 0;
    const client = createAfterSaleClient(async () => { calls++; return Response.json({ code: 'SUCCESS', message: 'ok', data: { ...payload, afterSaleId: Number(caseId) }, traceId: 'test' }); }); client.resetContext('test-token');
    const numericId = await failure(client.get(caseId));
    const malformedEnvelope = createAfterSaleClient(async () => Response.json({ success: true, code: 'SUCCESS', message: 'ok', data: payload, traceId: 'test' })); malformedEnvelope.resetContext('test-token');
    const foreignGrant = await failure(client.consumeReadGrant(`/api/v1/c/aftersale-evidence-read-grants/${'a'.repeat(43)}`));
    let finish!: (r: Response) => void;
    const delayed = createAfterSaleClient(() => new Promise<Response>(resolve => { finish = resolve; })); delayed.resetContext('test-token');
    const read = delayed.list({ merchantId, storeId, page: 1, pageSize: 20 }); delayed.changeScope(); finish(Response.json({ code: 'SUCCESS', message: 'ok', data: {}, traceId: 'test' }));
    const funding = await failure(client.send({ caseId, action: 'decisions', requestId: crypto.randomUUID(), label: 'bad', body: { expectedVersion: '0', decisionType: 'FULL_REFUND', refundAmount: '35.00', reason: '不能由前端开放资金裁决' } } as unknown as PendingIntent));
    return { numericId, badEnvelope: await failure(malformedEnvelope.get(caseId)), foreignGrant, stale: await failure(read), funding, calls };
  }, { caseId: CASE, merchantId: MERCHANT, storeId: STORE, payload: detail() });
  expect(result).toEqual({ numericId: 'INVALID_RESPONSE', badEnvelope: 'INVALID_RESPONSE', foreignGrant: 'INVALID_READ_URL', stale: 'STALE_CONTEXT', funding: 'INVALID_ARGUMENT', calls: 1 });
});

test('A-004 API preserves unknown supplement UUID past its deadline and invalidates late image bytes', async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/');
  const result = await page.evaluate(async ({ caseId, grantUrl, ack, caseSummary, caseDetail }) => {
    const path = '/src/api/aftersales.ts'; const { createAfterSaleClient } = await import(path) as typeof import('../src/api/aftersales');
    const actualNow = Date.now; const started = actualNow(); let writes = 0; const captured: { id: string | null; body: unknown }[] = [];
    const client = createAfterSaleClient(async request => {
      if (request.method === 'GET') return Response.json({ code: 'SUCCESS', message: 'ok', data: new URL(request.url).pathname === '/api/v1/admin/aftersales' ? { page: 1, pageSize: 20, total: 1, items: [caseSummary] } : caseDetail, traceId: 'test' });
      captured.push({ id: request.headers.get('X-Request-Id'), body: await request.json() }); writes++; if (writes === 1) throw new TypeError('lost ack'); return Response.json({ code: 'SUCCESS', message: 'ok', data: ack, traceId: 'test' });
    }); client.resetContext('test-token'); client.bindIdentity('901');
    await client.list({ merchantId: caseSummary.merchantId, storeId: caseSummary.storeId, page: 1, pageSize: 20 }); await client.get(caseId);
    const intent: PendingIntent = { caseId, action: 'supplement-requests', label: 'test', requestId: crypto.randomUUID(), body: { expectedVersion: '1', targetParty: 'USER', reason: '请补充现场图片', deadline: new Date(started + 1000).toISOString() } };
    try { await client.send(intent); } catch { /* leave pending */ }
    Date.now = () => started + 5000;
    try { await client.send(client.pending(caseId)!); } finally { Date.now = actualNow; }
    let complete!: (r: Response) => void;
    const imageClient = createAfterSaleClient(() => new Promise<Response>(resolve => { complete = resolve; })); imageClient.resetContext('old-token');
    const read = imageClient.consumeReadGrant(grantUrl); imageClient.resetContext('new-token'); complete(new Response(new Uint8Array([1]), { headers: { 'Content-Type': 'image/png' } }));
    let stale = ''; try { await read; } catch (e) { stale = (e as Error).message; }
    return { captured, unresolved: !!client.pending(caseId), stale };
  }, { caseId: CASE, grantUrl: GRANT, ack: receipt('WAITING_SUPPLEMENT', '2'), caseSummary: summary(), caseDetail: detail() });
  expect(result.captured).toHaveLength(2); expect(result.captured[1]).toEqual(result.captured[0]); expect(result.unresolved).toBe(false); expect(result.stale).toBe('STALE_CONTEXT');
});

test('A-004 two clients cannot let a late original ACK retire a newer unknown command', async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/');
  const result = await page.evaluate(async ({ caseId, merchantId, storeId, caseSummary, caseDetail, ack }) => {
    const path = '/src/api/aftersales.ts'; const { createAfterSaleClient } = await import(path) as typeof import('../src/api/aftersales');
    const read = (request: Request) => Response.json({ code: 'SUCCESS', message: 'ok', data: new URL(request.url).pathname === '/api/v1/admin/aftersales' ? { page: 1, pageSize: 20, total: 1, items: [caseSummary] } : caseDetail, traceId: 'two-clients' });
    let finishOld!: (r: Response) => void;
    const slow = createAfterSaleClient(request => request.method === 'GET' ? Promise.resolve(read(request)) : new Promise<Response>(resolve => { finishOld = resolve; }));
    let writes = 0;
    const fast = createAfterSaleClient(async request => { if (request.method === 'GET') return read(request); writes++; if (writes === 1) return Response.json({ code: 'SUCCESS', message: 'ok', data: ack, traceId: 'two-clients' }); throw new TypeError('new command ACK lost'); });
    for (const client of [slow, fast]) { client.resetContext('test-token'); client.bindIdentity('901'); await client.list({ merchantId, storeId, page: 1, pageSize: 20 }); await client.get(caseId); }
    const original: PendingIntent = { caseId, action: 'accept', requestId: crypto.randomUUID(), label: 'original', body: { expectedVersion: '0' } };
    const late = slow.send(original); await fast.send(fast.pending(caseId)!);
    const newer: PendingIntent = { caseId, action: 'decisions', requestId: crypto.randomUUID(), label: 'newer', body: { expectedVersion: '1', decisionType: 'OTHER', reason: '先前回执已确认，此处为新的终局处理', refundAmount: null } };
    try { await fast.send(newer); } catch { /* unknown newer request remains */ }
    const before = fast.pending(caseId); finishOld(Response.json({ code: 'SUCCESS', message: 'ok', data: ack, traceId: 'two-clients' })); await late;
    return { before, afterSlow: slow.pending(caseId), afterFast: fast.pending(caseId), newer };
  }, { caseId: CASE, merchantId: MERCHANT, storeId: STORE, caseSummary: summary(), caseDetail: detail(), ack: receipt('PROCESSING', '1') });
  expect(result.before).toEqual(result.newer); expect(result.afterSlow).toEqual(result.newer); expect(result.afterFast).toEqual(result.newer);
});
