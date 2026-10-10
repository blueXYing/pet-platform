import { expect, test, type Page } from '@playwright/test';
import type { AppealDetail, AppealStatus } from '../src/api/reviewAppeals';

// Contract56 fixtures run the real production UI/transport with intercepted HTTP (same
// discipline as aftersale.spec.ts). These tests do not assert a live backend or production
// enablement.
test.use({ baseURL: 'http://127.0.0.1:4174' });
const APPEAL = '9007199254740993';
const REVIEW = '9007199254740994';
const ORDER = '9007199254740995';
const MERCHANT = '9007199254740996';
const STORE = '9007199254740997';
const OPERATOR = '901';
const ROOT = `/api/v1/admin/review-appeals/${APPEAL}`;
const ACTIONS = ['review.appeal.read', 'review.appeal.decide'];
const summary = (status: AppealStatus = 'SUBMITTED') => ({ appealId: APPEAL, reviewId: REVIEW, merchantId: MERCHANT, storeId: STORE, orderId: ORDER, status, reason: '评价包含辱骂内容，与订单事实不符。', createdAt: '2026-10-09T02:00:00.000Z', decidedAt: null });
const review = () => ({ reviewId: REVIEW, orderId: ORDER, storeScore: '5.0', serviceScore: '4.0', staffScore: '3.0', compositeScore: '4.2', scoreIncluded: true, visibilityStatus: 'PUBLISHED', content: '服务很差，上门迟到一小时。', createdAt: '2026-10-08T02:00:00.000Z', appealStatus: 'SUBMITTED', appealId: APPEAL });
function detail(status: AppealStatus = 'SUBMITTED'): AppealDetail {
  const decided = status !== 'SUBMITTED';
  return { ...summary(status), decisionReason: decided ? '核查属实，评价内容违规，隐藏处理。' : null, decidedBy: decided ? OPERATOR : null, decidedAt: decided ? '2026-10-09T06:00:00.000Z' : null, review: { ...review(), appealStatus: status, visibilityStatus: status === 'APPROVED' ? 'HIDDEN' : 'PUBLISHED' } };
}
const decisionReceipt = () => ({ appealId: APPEAL, reviewId: REVIEW, status: 'APPROVED', decisionType: 'APPROVED', decisionReason: '核查属实，评价内容违规，隐藏处理。', decidedAt: '2026-10-09T06:00:00.000Z', reviewVisibility: 'HIDDEN' });
function envelope(data: unknown, status = 200) { return { status, json: { code: 'SUCCESS', message: 'ok', data, traceId: 'fixture-appeal' } }; }
function failed(code: string, status: number) { return { status, json: { code, message: code, data: null, traceId: 'fixture-appeal-error' } }; }
async function signIn(page: Page, actions = ACTIONS) {
  await page.route('**/api/v1/admin/auth/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/attempts')) return route.fulfill(envelope({ attemptId: '801', attemptToken: 'fixture-binding', expiresAt: '2026-10-10T02:00:00.000Z', nextStep: 'PROVE_IDENTITY' }, 201));
    if (path.endsWith('/requirements')) return route.fulfill(envelope({ requiredVerification: 'NONE' }));
    if (path.endsWith('/login')) return route.fulfill(envelope({ sessionId: 's1', operatorId: OPERATOR, audience: 'ADMIN_WEB', tokenType: 'Bearer', accessToken: 'fixture-admin-token', expiresAt: '2026-10-10T02:00:00.000Z' }));
    if (path.endsWith('/session')) return route.fulfill(envelope({ sessionId: 's1', operatorId: OPERATOR, audience: 'ADMIN_WEB', expiresAt: '2026-10-10T02:00:00.000Z', idleExpiresAt: '2026-10-10T01:00:00.000Z', authzVersion: 'v1' }));
    if (path.endsWith('/permissions')) return route.fulfill(envelope({ operatorId: OPERATOR, authzVersion: 'v1', checkedAt: '2026-10-09T02:00:00.000Z', roles: ['OPERATOR'], dataScope: 'ALL', actionCodes: actions }));
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
  page.on('request', req => { const url = new URL(req.url()); const path = url.pathname + url.search; if (url.pathname.includes('review-appeals')) calls.push({ path, method: req.method(), requestId: req.headers()['x-request-id'], body: req.postData() ? req.postDataJSON() : undefined, authorization: req.headers().authorization }); });
  return calls;
}
/** Stateful fixture: the terminal decision flips the served detail, like the real kernel. */
async function setupAppeals(page: Page, initial = detail(), onDecision?: (route: import('@playwright/test').Route, decide: (next: AppealStatus) => void) => Promise<void>) {
  let current = initial;
  const flip = (next: AppealStatus) => { current = detail(next); };
  await page.route('**/api/v1/admin/review-appeals**', async route => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const method = route.request().method();
    if (path === '/api/v1/admin/review-appeals' && method === 'GET') {
      const status = url.searchParams.get('status') ?? '';
      const items = status === '' || current.status === status ? [{ ...summary(current.status) }] : [];
      return route.fulfill(envelope({ page: 1, pageSize: 20, total: items.length, items }));
    }
    if (path === `${ROOT}/decision` && method === 'POST') {
      if (onDecision) return onDecision(route, flip);
      current = detail('APPROVED');
      return route.fulfill(envelope(decisionReceipt()));
    }
    if (path === ROOT && method === 'GET') return route.fulfill(envelope(current));
    return route.fulfill(failed('COMMON_NOT_FOUND', 404));
  });
}
/** Reader-only accounts land on the legacy 403 home; the header nav link is the entry. */
async function openAppealListOnly(page: Page) {
  await expect(page.getByRole('link', { name: '评价申诉', exact: true })).toBeVisible();
  await page.getByRole('link', { name: '评价申诉', exact: true }).click();
  await expect(page.getByRole('heading', { name: '评价申诉', exact: true })).toBeVisible();
}

async function openAppeal(page: Page) {
  // In-app navigation only: a full page load resets the session state (no token persistence).
  await expect(page.getByRole('link', { name: '评价申诉', exact: true })).toBeVisible();
  await page.getByRole('link', { name: '评价申诉', exact: true }).click();
  await expect(page.getByRole('heading', { name: '评价申诉', exact: true })).toBeVisible();
  await page.getByRole('link', { name: '打开', exact: true }).click();
  await expect(page.getByRole('heading', { name: '评价申诉详情', exact: true })).toBeVisible();
  await expect(page.getByText('评价包含辱骂内容，与订单事实不符。')).toBeVisible();
}

test('REV-002 reader lists and filters appeals without a decide button', async ({ page }) => {
  const calls = captures(page);
  await setupAppeals(page);
  await signIn(page, ['review.appeal.read']);
  await openAppealListOnly(page);
  await expect(page.getByRole('cell', { name: APPEAL, exact: true })).toBeVisible();
  await expect(page.getByText('当前账号仅可查看，无 review.appeal.decide 权限。')).toHaveCount(0);
  // The status filter re-queries with the exact filter value.
  await page.getByLabel('状态').selectOption('APPROVED');
  await expect(page.getByText('没有符合条件的申诉。')).toBeVisible();
  const listCalls = calls.filter(call => call.method === 'GET' && call.path.split('?')[0] === '/api/v1/admin/review-appeals');
  expect(listCalls.length).toBeGreaterThanOrEqual(2);
  expect(listCalls.some(call => call.path.includes('status=APPROVED'))).toBe(true);
  expect(calls.some(call => call.method === 'POST')).toBe(false);
});

test('REV-002 decide is a terminal one-shot command with the original UUID replayed', async ({ page }) => {
  const calls = captures(page);
  // The default stateful handler both validates the body and flips the served detail.
  await setupAppeals(page, detail(), async (route, flip) => {
    const body = route.request().postDataJSON() as { decisionType: string; reason: string };
    if (body.decisionType !== 'APPROVED' || body.reason.trim().length < 1) return route.fulfill(failed('COMMON_INVALID_ARGUMENT', 400));
    flip('APPROVED');
    return route.fulfill(envelope(decisionReceipt()));
  });
  await signIn(page);
  await openAppeal(page);
  await expect(page.getByText('服务很差，上门迟到一小时。')).toBeVisible();
  // Blank reason never leaves the browser.
  await page.getByRole('button', { name: '申诉成立（隐藏评价）' }).click();
  await expect(page.getByText('请填写 1~1000 个字符的裁决理由。')).toBeVisible();
  expect(calls.filter(call => call.method === 'POST')).toHaveLength(0);
  // Terminal decision: reason + the fixed receipt shape.
  await page.getByLabel('裁决理由').fill('核查属实，评价内容违规，隐藏处理。');
  await page.getByRole('button', { name: '申诉成立（隐藏评价）' }).click();
  await expect(page.getByText('申诉成立（评价已隐藏）')).toBeVisible({ timeout: 10_000 });
  const decisions = calls.filter(call => call.method === 'POST' && call.path === `${ROOT}/decision`);
  expect(decisions).toHaveLength(1);
  expect(decisions[0].requestId).toMatch(/^[0-9a-f-]{36}$/);
  expect(decisions[0].body).toEqual({ decisionType: 'APPROVED', reason: '核查属实，评价内容违规，隐藏处理。' });
});

test('REV-002 decided appeal shows the terminal facts and never offers a re-decision', async ({ page }) => {
  await setupAppeals(page, detail('APPROVED'));
  await signIn(page);
  await openAppeal(page);
  await expect(page.getByText('申诉成立（评价已隐藏）')).toBeVisible();
  await expect(page.getByText('已隐藏', { exact: true })).toBeVisible();
  await expect(page.getByText('核查属实，评价内容违规，隐藏处理。')).toBeVisible();
  await expect(page.getByRole('button', { name: '申诉成立（隐藏评价）' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '申诉未成立（维持展示）' })).toHaveCount(0);
});

test('REV-002 conflict on a decided appeal surfaces the finality rule without journal loss', async ({ page }) => {
  await setupAppeals(page, detail(), async route => route.fulfill(failed('COMMON_CONFLICT', 409)));
  await signIn(page);
  await openAppealListOnly(page);
  await page.getByRole('link', { name: '打开', exact: true }).click();
  await expect(page.getByRole('heading', { name: '评价申诉详情', exact: true })).toBeVisible();
  await expect(page.getByText('评价包含辱骂内容，与订单事实不符。')).toBeVisible();
  await page.getByLabel('裁决理由').fill('尝试对已裁决的申诉再次裁决');
  await page.getByRole('button', { name: '申诉未成立（维持展示）' }).click();
  await expect(page.getByText('该申诉已裁决，不可改判；请重新打开确认最新状态。')).toBeVisible();
  // The journaled command survives an unknown outcome: re-entering the detail view restores
  // the pending intent instead of minting a new UUID silently (no full reload — the session
  // is in-memory state; the journal itself lives in localStorage across sessions).
  await page.getByRole('link', { name: '返回申诉列表', exact: true }).click();
  await page.getByRole('link', { name: '打开', exact: true }).click();
  await expect(page.getByText('检测到未确认的裁决命令，请重试原操作（不会重复裁决）。')).toBeVisible();
});

test('REV-002 missing action code hides the entry and deep links fail closed to login', async ({ page }) => {
  // Without review.appeal.read the workbench never offers the entry (the route guard itself
  // is the shared action-guard branch already exercised by the merchant-application pages).
  await signIn(page, ['aftersale.read']);
  await expect(page.getByRole('link', { name: '评价申诉', exact: true })).toHaveCount(0);
  // A deep link is a full page load: the in-memory session is gone, and the anonymous
  // visitor lands on the login page — never on the appeal surface.
  await page.goto('/review-appeals');
  await expect(page.getByRole('heading', { name: '运营登录', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '评价申诉', exact: true })).toHaveCount(0);
});
