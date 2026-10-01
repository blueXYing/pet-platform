import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

// Runs against production built UI and an isolated, persistent real BOOT fixture.
// Normal CI has no runtime and skips this suite. No business response is fulfilled.
type Runtime = {
  backendOrigin: string; baseUrl: string; clockInstant: string;
  admin: { account: string; password: string };
  scope: { merchantId: string; storeId: string };
  cases: { p4CaseId: string; priorFinalIds: string[]; workflowCaseId: string; recoveryCaseId: string };
  control: { baseUrl: string; secret: string };
};
type Summary = {
  caseId: string; status: string; version: string; orderVersion: string; currentAfterSaleId: string | null;
  counts: Record<string, number>; channelCalls: number;
};
const runtimePath = process.env.LIVE_AFS_RUNTIME;
const runtime = runtimePath ? JSON.parse(readFileSync(resolve(runtimePath), 'utf8')) as Runtime : undefined;
test.skip(!runtime, 'LIVE_AFS_RUNTIME not configured; true aftersale page integration is opt-in');
test.use({ baseURL: 'http://127.0.0.1:4174', trace: 'off', screenshot: 'off', video: 'off' });
const journalPrefix = 'pet.admin.aftersale.command.v1:';

function live(): Runtime {
  if (!runtime || ![runtime.backendOrigin, runtime.control.baseUrl].every(value => new URL(value).hostname === '127.0.0.1')) throw new Error('A loopback live fixture runtime is required');
  return runtime;
}
async function control(request: APIRequestContext, path: string, data?: unknown) {
  const f = live();
  const options = { headers: { 'X-Joint-Control': f.control.secret }, ...(data === undefined ? {} : { data }) };
  const response = data === undefined ? await request.get(f.control.baseUrl + path, options) : await request.post(f.control.baseUrl + path, options);
  expect(response.status(), 'Isolated fixture control must succeed').toBe(200);
  const envelope = await response.json();
  expect(envelope.fixtureOnly).toBe(true);
  return envelope.data;
}
async function summary(request: APIRequestContext, caseId: string): Promise<Summary> {
  return control(request, `/summary?caseId=${encodeURIComponent(caseId)}`);
}
async function signIn(page: Page) {
  const f = live();
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: '运营登录' })).toBeVisible();
  await page.getByLabel('账号').fill(f.admin.account);
  await page.getByLabel('密码').fill(f.admin.password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '售后工单', exact: true })).toBeVisible();
}
async function openScopedCase(page: Page, caseId: string) {
  const f = live();
  await page.getByRole('link', { name: '售后工单', exact: true }).click();
  await page.getByLabel('商家编号', { exact: true }).fill(f.scope.merchantId);
  await page.getByLabel('门店编号', { exact: true }).fill(f.scope.storeId);
  await page.getByRole('button', { name: '查询售后', exact: true }).click();
  const row = page.getByRole('row').filter({ has: page.getByRole('cell', { name: caseId, exact: true }) });
  await expect(row).toBeVisible();
  await row.getByRole('link', { name: '查看工单', exact: true }).click();
  await expect(page.getByRole('heading', { name: '售后工单详情', exact: true })).toBeVisible();
  await expect(snapshot(page, '工单编号')).toHaveText(caseId);
}
function snapshot(page: Page, label: string) {
  return page.locator('dl.snapshot > div').filter({ has: page.getByText(label, { exact: true }) }).locator('dd');
}
async function journals(page: Page) {
  return page.evaluate(prefix => Object.keys(localStorage).filter(key => key.startsWith(prefix)).map(key => JSON.parse(localStorage.getItem(key)!)), journalPrefix);
}
function assertNoMoney(value: Summary) {
  expect(value.channelCalls).toBe(0);
  for (const key of ['refundOrders', 'refundExecutions', 'fundings']) expect(value.counts[key], key).toBe(0);
}
async function viewActualEvidence(page: Page) {
  await page.getByRole('button', { name: '申请查看证据', exact: true }).first().click();
  const image = page.getByRole('img', { name: /^售后证据 / });
  await expect(image).toBeVisible();
  await expect.poll(() => image.evaluate(node => (node as HTMLImageElement).complete && (node as HTMLImageElement).naturalWidth > 0)).toBe(true);
}

test('live production A page: scoped list, complete P4 review, accept, real buyer supplement, non-refund decision', async ({ page, request }) => {
  const f = live(), pageErrors: string[] = [], readFinals = new Set<string>();
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('response', response => {
    const path = new URL(response.url()).pathname;
    for (const id of f.cases.priorFinalIds) if (path === `/api/v1/admin/aftersales/${id}` && response.status() === 200) readFinals.add(id);
  });
  await signIn(page);
  await openScopedCase(page, f.cases.p4CaseId);
  await expect(snapshot(page, '状态')).toHaveText('待受理');
  await viewActualEvidence(page);
  await page.getByRole('button', { name: '隐藏图片', exact: true }).click();
  await expect(page.getByRole('img', { name: /^售后证据 / })).toHaveCount(0);
  expect(f.cases.priorFinalIds.length).toBeGreaterThanOrEqual(3);
  await page.getByLabel('新问题核对意见').fill('隔离联调逐笔阅读全部历史结论，核对本次独立新问题与原说明的差异。');
  await page.getByLabel('确认问题符合受理条件，已有终局时确属新问题').check();
  await expect(page.getByRole('button', { name: '受理工单', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '读取历史终局', exact: true }).click();
  const checks = page.getByLabel('已核对这笔终局及当前新问题说明', { exact: true });
  await expect(checks).toHaveCount(f.cases.priorFinalIds.length);
  expect([...readFinals].sort()).toEqual([...f.cases.priorFinalIds].sort());
  for (let i = 0; i < f.cases.priorFinalIds.length; i++) {
    await checks.nth(i).check();
    if (i < f.cases.priorFinalIds.length - 1) await expect(page.getByRole('button', { name: '受理工单', exact: true })).toBeDisabled();
  }
  await page.getByRole('button', { name: '受理工单', exact: true }).click();
  await expect(snapshot(page, '状态')).toHaveText('处理中');
  await page.getByLabel('补证方', { exact: true }).selectOption('USER');
  await page.getByLabel('补证原因', { exact: true }).fill('隔离联调要求用户补充本次服务问题的具体经过与新事实。');
  await page.getByLabel('补证截止（UTC 毫秒）', { exact: true }).fill(new Date(Date.parse(f.clockInstant) + 3_600_000).toISOString());
  await page.getByRole('button', { name: '提交补证要求', exact: true }).click();
  await expect(snapshot(page, '状态')).toHaveText('等待补证');
  await expect(page.getByRole('heading', { name: '当前补证要求' })).toBeVisible();
  await control(request, '/buyer/fulfill', { caseId: f.cases.p4CaseId });
  await page.getByRole('button', { name: '刷新工单', exact: true }).click();
  await expect(snapshot(page, '状态')).toHaveText('处理中');
  await expect(page.getByRole('heading', { name: '当前补证要求' })).toHaveCount(0);
  expect(await page.getByLabel('终局类型', { exact: true }).locator('option').evaluateAll(options => options.map(option => (option as HTMLOptionElement).value))).toEqual(['REJECT', 'RESERVICE', 'OTHER']);
  await page.getByLabel('终局类型', { exact: true }).selectOption('OTHER');
  await page.getByLabel('终局原因', { exact: true }).fill('隔离联调已结合双方卷宗及本轮补充事实，记录独立的非退款最终处理。');
  await page.getByLabel('确认基于双方证据作出最终处理').check();
  await page.getByRole('button', { name: '提交最终决定', exact: true }).click();
  await expect(snapshot(page, '状态')).toHaveText('已裁决');
  await expect(page.getByRole('heading', { name: '已记录的最终决定' })).toBeVisible();
  await expect(page.getByRole('button', { name: '提交最终决定', exact: true })).toHaveCount(0);
  expect(await journals(page)).toEqual([]);
  assertNoMoney(await summary(request, f.cases.p4CaseId));
  expect(pageErrors).toEqual([]);
});

test('live production A page: committed lost ACK, real revocation, durable original UUID, fresh login and explicit receipt replay', async ({ page, context, browser, request }) => {
  const f = live(), writes: { requestId?: string; body: string | null }[] = [], pageErrors: string[] = [];
  const acceptPath = `/api/v1/admin/aftersales/${f.cases.recoveryCaseId}/accept`;
  const match = new RegExp(acceptPath + '$');
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('request', req => { if (new URL(req.url()).pathname === acceptPath && req.method() === 'POST') writes.push({ requestId: req.headers()['x-request-id'], body: req.postData() }); });
  await signIn(page);
  await openScopedCase(page, f.cases.recoveryCaseId);
  await viewActualEvidence(page);
  const before = await summary(request, f.cases.recoveryCaseId);
  let delivered = false, actualReceipt: { afterSaleId: string; version: string; commandId: string } | undefined;
  // Only this one response is dropped AFTER actual backend commit. No fulfillment.
  await page.route(match, async route => {
    if (delivered) { await route.continue(); return; }
    delivered = true;
    const upstream = await route.fetch();
    expect(upstream.status()).toBe(200);
    const envelope = await upstream.json();
    expect(envelope.code).toBe('SUCCESS');
    actualReceipt = envelope.data;
    await route.abort('failed');
  });
  await page.getByLabel('确认问题符合受理条件，已有终局时确属新问题').check();
  await page.getByRole('button', { name: '受理工单', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('结果未知');
  await page.unroute(match);
  expect(delivered).toBe(true);
  expect(actualReceipt?.afterSaleId === f.cases.recoveryCaseId).toBe(true);
  const committed = await summary(request, f.cases.recoveryCaseId);
  expect(committed.status).toBe('PROCESSING');
  expect(BigInt(committed.version)).toBe(BigInt(before.version) + 1n);
  expect(actualReceipt?.version === committed.version).toBe(true);
  const pending = await journals(page);
  expect(pending).toHaveLength(1);
  expect(pending[0].intent.requestId === writes[0].requestId).toBe(true);
  expect(JSON.stringify(pending[0].intent.body) === JSON.stringify(JSON.parse(writes[0].body!))).toBe(true);
  expect(/accessToken|refreshToken|readUrl|Bearer/.test(JSON.stringify(pending))).toBe(false);
  await control(request, '/admin/permissions', { mode: 'READ_ONLY' });
  const denial = page.waitForResponse(response => new URL(response.url()).pathname === acceptPath && response.status() === 403);
  await page.getByRole('button', { name: '重试原操作', exact: true }).click();
  await denial;
  await expect(page.getByRole('heading', { name: '运营登录' })).toBeVisible();
  await expect(page.getByRole('img', { name: /^售后证据 / })).toHaveCount(0);
  expect(await journals(page)).toEqual(pending);
  expect(await summary(request, f.cases.recoveryCaseId)).toEqual(committed);
  const persistedOrigin = await context.storageState();
  await context.close();
  await control(request, '/admin/permissions', { mode: 'FULL_AFS' });
  // A new browser context with persisted origin state models profile restoration.
  // It is not evidence of an actual operating-system restart.
  const restarted = await browser.newContext({ baseURL: 'http://127.0.0.1:4174', storageState: persistedOrigin });
  try {
    const fresh = await restarted.newPage();
    fresh.on('pageerror', error => pageErrors.push(error.message));
    fresh.on('request', req => { if (new URL(req.url()).pathname === acceptPath && req.method() === 'POST') writes.push({ requestId: req.headers()['x-request-id'], body: req.postData() }); });
    await signIn(fresh);
    await openScopedCase(fresh, f.cases.recoveryCaseId);
    await expect(fresh.getByRole('button', { name: '重试原操作', exact: true })).toBeEnabled();
    expect(await journals(fresh)).toEqual(pending);
    const replay = fresh.waitForResponse(response => new URL(response.url()).pathname === acceptPath && response.status() === 200);
    await fresh.getByRole('button', { name: '重试原操作', exact: true }).click();
    const replayEnvelope = await (await replay).json();
    expect(replayEnvelope.data).toEqual(actualReceipt);
    await expect(snapshot(fresh, '状态')).toHaveText('处理中');
    expect(await journals(fresh)).toEqual([]);
    expect(writes).toHaveLength(3);
    expect(writes.every(write => write.requestId === writes[0].requestId && write.body === writes[0].body)).toBe(true);
    expect(await summary(request, f.cases.recoveryCaseId)).toEqual(committed);
    assertNoMoney(committed);
    expect(pageErrors).toEqual([]);
  } finally { await restarted.close(); }
});

type MiniHandoff = { stage: 'CREATED' | 'PARTIES_RESPONDED'; caseId: string; consumerVersion?: string; merchantVersion?: string };
const miniHandoffPath = resolve('..', '.cache', 'aftersale-joint-mini', 'mini-handoff.json');
const triadHandoffPath = resolve('..', '.cache', 'aftersale-joint-qa', 'triad-handoff.json');
async function waitForMini(stage: MiniHandoff['stage'], caseId?: string): Promise<MiniHandoff> {
  const until = Date.now() + 14 * 60_000;
  while (Date.now() < until) {
    if (existsSync(miniHandoffPath)) {
      const value = JSON.parse(readFileSync(miniHandoffPath, 'utf8')) as MiniHandoff;
      if (value.stage === stage && (!caseId || value.caseId === caseId)) return value;
    }
    await new Promise(done => setTimeout(done, 500));
  }
  throw new Error('Coordinated real miniapp page handoff did not arrive');
}
function triadHandoff(value: Record<string, unknown>) {
  mkdirSync(dirname(triadHandoffPath), { recursive: true });
  writeFileSync(triadHandoffPath + '.tmp', JSON.stringify(value));
  renameSync(triadHandoffPath + '.tmp', triadHandoffPath);
}

test('live same-case C/M/A pages: C creation, A accept and supplement, C evidence, M opinion, A non-refund final', async ({ page, request }) => {
  test.setTimeout(15 * 60_000);
  const f = live(), pageErrors: string[] = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  try {
    const created = await waitForMini('CREATED');
    await signIn(page);
    await openScopedCase(page, created.caseId);
    const initial = await summary(request, created.caseId);
    expect(initial.status).toBe('PENDING');
    await page.getByLabel('确认问题符合受理条件，已有终局时确属新问题').check();
    await page.getByRole('button', { name: '受理工单', exact: true }).click();
    await expect(snapshot(page, '状态')).toHaveText('处理中');
    await page.getByLabel('补证方', { exact: true }).selectOption('USER');
    await page.getByLabel('补证原因', { exact: true }).fill('同单三端联调：请用户从实际售后页面补充本次问题发生经过。');
    await page.getByLabel('补证截止（UTC 毫秒）', { exact: true }).fill(new Date(Date.parse(f.clockInstant) + 3_600_000).toISOString());
    await page.getByRole('button', { name: '提交补证要求', exact: true }).click();
    await expect(snapshot(page, '状态')).toHaveText('等待补证');
    const awaiting = await summary(request, created.caseId);
    const supplementId = (await page.getByRole('heading', { name: '当前补证要求' }).locator('..').textContent())?.match(/轮次 ([0-9]+)/)?.[1];
    expect(supplementId !== undefined).toBe(true);
    triadHandoff({ stage: 'USER_SUPPLEMENT_REQUESTED', caseId: created.caseId, version: awaiting.version, supplementRequestId: supplementId });
    // Root owns actual C/M WeChat page interactions. No buyer/control writes here.
    const responded = await waitForMini('PARTIES_RESPONDED', created.caseId);
    await page.getByRole('button', { name: '刷新工单', exact: true }).click();
    await expect(snapshot(page, '状态')).toHaveText('处理中');
    const afterParties = await summary(request, created.caseId);
    expect(afterParties.version).toBe(responded.merchantVersion);
    expect(BigInt(afterParties.version)).toBe(BigInt(awaiting.version) + 2n);
    expect(afterParties.counts.evidenceBatches).toBe(initial.counts.evidenceBatches + 2);
    await expect(page.locator('article.aftersale-card h3').filter({ hasText: '用户证据' })).toHaveCount(2);
    await expect(page.locator('article.aftersale-card h3').filter({ hasText: '商家证据' })).toHaveCount(1);
    await page.getByLabel('终局类型', { exact: true }).selectOption('OTHER');
    await page.getByLabel('终局原因', { exact: true }).fill('同单三端联调：已逐项核对用户实际补证及商家实际意见，记录非退款终局。');
    await page.getByLabel('确认基于双方证据作出最终处理').check();
    await page.getByRole('button', { name: '提交最终决定', exact: true }).click();
    await expect(snapshot(page, '状态')).toHaveText('已裁决');
    const final = await summary(request, created.caseId);
    expect(BigInt(final.version)).toBe(BigInt(afterParties.version) + 1n);
    expect(final.counts.decisions).toBe(initial.counts.decisions + 1);
    assertNoMoney(final);
    expect(await journals(page)).toEqual([]);
    expect(pageErrors).toEqual([]);
    triadHandoff({ stage: 'FINAL', caseId: created.caseId, status: final.status, version: final.version, evidenceBatches: final.counts.evidenceBatches, decisions: final.counts.decisions });
  } catch (error) { triadHandoff({ stage: 'FAILED' }); throw error; }
});
