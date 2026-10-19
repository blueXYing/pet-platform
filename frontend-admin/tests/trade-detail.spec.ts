import { expect, test, type Page } from '@playwright/test';

// Contract-63 refund/verification DETAIL faces (FL-1/FL-2 regression, E2E final recheck
// 2026-10-19): the detail validators used to double-exact the same payload (refund 13 vs 11
// keys, verification 9 vs 6 keys), so every 200 detail response failed INVALID_RESPONSE and
// both detail pages were permanently dead (渠道查单/重试 entry unreachable). Fix keeps the
// summary field checks but asserts the flat detail shape exactly once, per OpenAPI-Core-v0.4
// AdminTradeRefundDetail / AdminTradeVerificationDetail (allOf summary + detail extension).
// Contract fixtures run the real production UI/transport with intercepted HTTP — no live
// backend, migration or production enablement asserted. Detail payloads mirror the real
// round-4 backend envelope captured in probes/refund-detail-payload.json.
test.use({ baseURL: 'http://127.0.0.1:4174' });
const OPERATOR = '901';
const REFUND_ID = '105505504552431616';
const VERIFICATION_ID = '105505504552431700';
const ORDER_ID = '105485164972326912';
const ACTIONS = ['refund.read', 'order.verification-log.read'];
const CREATED = '2026-10-19T03:21:12.523Z';
function envelope(data: unknown, status = 200) { return { status, json: { code: 'SUCCESS', message: 'ok', data, traceId: 'fixture-trade-detail' } }; }

// Exact 11-key summary row (list face) and the allOf 13-key detail (detail face).
const refundRow = {
  refundOrderId: REFUND_ID, refundNo: '105505504720203776', orderId: ORDER_ID,
  refundType: 'FULL', sourceType: 'ADMIN_ABNORMAL_CLOSE', status: 'PROCESSING',
  refundAmount: '60.00', initiatorType: 'OPS', channelRefundNo: null, succeededAt: null,
  createdAt: CREATED,
};
const refundDetail = {
  ...refundRow,
  application: {
    applicationId: '9007199254740999', status: 'APPROVED', requestedAmount: '60.00',
    merchantDeadline: '2026-10-19T02:00:00.000Z', decidedAt: '2026-10-19T02:30:00.000Z',
  },
  execution: {
    channelTradeNo: 'e2e-trade-105485165114933249', refundAmount: '60.00',
    channelPaidAt: '2026-10-19T02:00:21.000Z', currency: 'CNY',
  },
};
// Exact 6-key summary row (list face) and the allOf 9-key detail (detail face).
const verificationRow = {
  verificationId: VERIFICATION_ID, verificationNo: '105505504720203900', orderId: ORDER_ID,
  storeId: '710302', verifyMethod: 'SCAN', verifiedAt: CREATED,
};
const verificationDetail = {
  ...verificationRow,
  operatorStaffId: '902',
  attempts: [
    { attemptId: '9007199254741000', result: 'FAILED', failReason: '凭证不匹配', attemptedAt: '2026-10-19T03:20:00.000Z' },
    { attemptId: '9007199254741001', result: 'SUCCESS', failReason: null, attemptedAt: CREATED },
  ],
  riskAttempts: [
    { riskAttemptId: '9007199254741002', resultCode: 'ATTEMPT_LIMIT', countsFailure: true, attemptedAt: '2026-10-19T03:20:00.000Z' },
  ],
};

async function signIn(page: Page) {
  await page.route('**/api/v1/admin/auth/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/attempts')) return route.fulfill(envelope({ attemptId: '801', attemptToken: 'fixture-binding', expiresAt: '2026-10-20T02:00:00.000Z', nextStep: 'PROVE_IDENTITY' }, 201));
    if (path.endsWith('/requirements')) return route.fulfill(envelope({ requiredVerification: 'NONE' }));
    if (path.endsWith('/login')) return route.fulfill(envelope({ sessionId: 's1', operatorId: OPERATOR, audience: 'ADMIN_WEB', tokenType: 'Bearer', accessToken: 'fixture-admin-token', expiresAt: '2026-10-20T02:00:00.000Z' }));
    if (path.endsWith('/session')) return route.fulfill(envelope({ sessionId: 's1', operatorId: OPERATOR, audience: 'ADMIN_WEB', expiresAt: '2026-10-20T02:00:00.000Z', idleExpiresAt: '2026-10-20T01:00:00.000Z', authzVersion: 'v1' }));
    if (path.endsWith('/permissions')) return route.fulfill(envelope({ operatorId: OPERATOR, authzVersion: 'v1', checkedAt: CREATED, roles: ['OPERATOR'], dataScope: 'ALL', actionCodes: ACTIONS }));
    return route.fulfill(envelope(null));
  });
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill('fixture-ops');
  await page.getByLabel('密码', { exact: true }).fill('fixture-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
}

function routeRefunds(page: Page, detail: unknown = refundDetail) {
  return page.route('**/api/v1/admin/trade/refunds**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/admin/trade/refunds') return route.fulfill(envelope({ page: 1, pageSize: 20, total: 1, items: [refundRow] }));
    if (path === `/api/v1/admin/trade/refunds/${REFUND_ID}`) return route.fulfill(envelope(detail));
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: 'NOT_FOUND', data: null, traceId: 'fixture-trade-detail' } });
  });
}
function routeVerifications(page: Page, detail: unknown = verificationDetail) {
  return page.route('**/api/v1/admin/trade/verifications**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/admin/trade/verifications') return route.fulfill(envelope({ page: 1, pageSize: 20, total: 1, items: [verificationRow] }));
    if (path === `/api/v1/admin/trade/verifications/${VERIFICATION_ID}`) return route.fulfill(envelope(detail));
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: 'NOT_FOUND', data: null, traceId: 'fixture-trade-detail' } });
  });
}

test('refund detail renders the 13-key contract payload and reaches the disposal face (FL-1)', async ({ page }) => {
  await signIn(page);
  await routeRefunds(page);
  const detailCalls: string[] = [];
  page.on('request', req => {
    const path = new URL(req.url()).pathname;
    if (path === `/api/v1/admin/trade/refunds/${REFUND_ID}`) detailCalls.push(path);
  });
  await page.getByRole('link', { name: '退款单', exact: true }).click();
  await expect(page.getByRole('heading', { name: '退款单管理' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('link', { name: '详情' })).toBeVisible({ timeout: 20_000 });
  await page.getByRole('link', { name: '详情' }).click();

  // FL-1 regression: the detail page used to die with 退款单详情加载失败（INVALID_RESPONSE）.
  await expect(page.getByRole('heading', { name: '退款单详情' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(page.getByText('105505504720203776')).toBeVisible();
  // Summary fields, related application projection and execution facts all render.
  await expect(page.getByRole('heading', { name: '关联申请' })).toBeVisible();
  await expect(page.getByText('9007199254740999')).toBeVisible();
  await expect(page.getByRole('heading', { name: '渠道执行事实' })).toBeVisible();
  await expect(page.getByText('e2e-trade-105485165114933249')).toBeVisible();
  // The previously unreachable 渠道处置 entry (渠道查单/重试) is now on screen.
  await expect(page.getByRole('heading', { name: '渠道处置（refund.channel.query / refund.retry）' })).toBeVisible();
  await expect(page.getByRole('button', { name: '渠道查单' })).toBeVisible();
  await expect(page.getByRole('button', { name: '渠道重试' })).toBeVisible();
  expect(detailCalls).toContain(`/api/v1/admin/trade/refunds/${REFUND_ID}`);
});

test('verification detail renders the 9-key contract payload with attempt chains (FL-2)', async ({ page }) => {
  await signIn(page);
  await routeVerifications(page);
  await page.getByRole('link', { name: '核销记录', exact: true }).click();
  await expect(page.getByRole('link', { name: '详情' })).toBeVisible({ timeout: 20_000 });
  await page.getByRole('link', { name: '详情' }).click();

  // FL-2 regression: the detail page used to die with 核销详情加载失败（INVALID_RESPONSE）.
  await expect(page.getByRole('heading', { name: '核销记录详情' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(page.getByText('105505504720203900')).toBeVisible();
  await expect(page.getByText('902')).toBeVisible();
  await expect(page.getByRole('heading', { name: '尝试链（争议溯源）' })).toBeVisible();
  await expect(page.getByText('凭证不匹配')).toBeVisible();
  await expect(page.getByRole('heading', { name: '风控尝试与锁定' })).toBeVisible();
  await expect(page.getByText('ATTEMPT_LIMIT')).toBeVisible();
});

test('refund detail still fails closed on a malformed 13-key payload', async ({ page }) => {
  await signIn(page);
  // Drop a summary key (sourceType): the 13-key shape no longer matches — the validator must
  // keep rejecting contract violations, not just accept anything with 13 keys.
  const broken: Record<string, unknown> = { ...refundDetail };
  delete broken.sourceType;
  await routeRefunds(page, broken);
  await page.getByRole('link', { name: '退款单', exact: true }).click();
  await expect(page.getByRole('link', { name: '详情' })).toBeVisible({ timeout: 20_000 });
  await page.getByRole('link', { name: '详情' }).click();
  await expect(page.getByText('退款单详情加载失败（INVALID_RESPONSE）')).toBeVisible({ timeout: 20_000 });
});
