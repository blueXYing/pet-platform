import { expect, test, type Page, type Route } from '@playwright/test';
import { createHash } from 'node:crypto';

// Contract mocks against the production build (preview server): W2-FE-001/W2-FE-002 for A-002.
// Every payload mirrors the approved application/private-asset projections; no fixture chunk is involved.
test.use({ baseURL: 'http://127.0.0.1:4174' });

const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
const PNG_SHA256 = createHash('sha256').update(PNG).digest('hex');
const TOKEN = 'test-admin-token';

const APPLICATION_ID = '3012345678901234567';
const SUMMARY = {
  applicationId: APPLICATION_ID, applicationNo: 'MA202609200001', reservedMerchantId: '4012345678901234567',
  status: 'REVIEWING', version: '4', merchantName: '星河宠物医院', merchantTypeCode: 'PET_HOSPITAL', cityCode: 'CHENGDU',
  submittedRevisionId: '5012345678901234567', submittedAt: '2026-09-20T10:00:00.000Z', subjectVerificationStatus: 'PENDING',
};
function detail(options: { taskStatus?: 'AVAILABLE' | 'CLAIMED'; taskVersion?: string; verification?: 'PENDING' | 'VERIFIED' } = {}) {
  return {
    ...SUMMARY, subjectVerificationStatus: options.verification ?? 'PENDING',
    submittedRevision: {
      revisionId: SUMMARY.submittedRevisionId, revisionNo: 1, createdAt: '2026-09-20T09:30:00.000Z',
      snapshot: {
        merchantName: SUMMARY.merchantName, merchantTypeCode: SUMMARY.merchantTypeCode, cityCode: SUMMARY.cityCode,
        address: '成都市高新区示例街道1号', longitude: '104.065735', latitude: '30.659462', introduction: '社区宠物医疗服务',
        storePhotoAssetIds: ['6011000000000000001', '6011000000000000002'], businessLicenseAssetId: '6011000000000000003',
        idCardFrontAssetId: '6011000000000000004', idCardBackAssetId: '6011000000000000005', industryLicenseAssetId: '6011000000000000006',
        contactNameMasked: '张*', contactPhoneMasked: '138****8000', emailMasked: 'a***@example.com',
      },
    },
    task: {
      applicationId: APPLICATION_ID, taskId: '7012345678901234567', submittedRevisionId: SUMMARY.submittedRevisionId,
      status: options.taskStatus ?? 'AVAILABLE', version: options.taskVersion ?? '2', claimedByOperatorId: null,
    },
    latestDecision: null,
  };
}
const RECEIPT = { applicationId: APPLICATION_ID, applicationNo: SUMMARY.applicationNo, reservedMerchantId: SUMMARY.reservedMerchantId, status: 'REVIEWING', version: '5', currentRevisionId: SUMMARY.submittedRevisionId };

function unified(data: unknown, status = 200) { return { status, contentType: 'application/json', body: JSON.stringify({ success: true, code: 'SUCCESS', message: '成功', data, traceId: 'test' }) }; }
// /auth/* predates the unified wrapper: no success field (CONTRACT.md §4.1).
function authEnvelope(data: unknown, status = 200) { return { status, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', message: '成功', data, traceId: 'test' }) }; }
function failure(code: string, status: number) { return { status, contentType: 'application/json', body: JSON.stringify({ success: false, code, message: code, data: null, traceId: 'test' }) }; }

type Interaction = { method: string; path: string; body?: unknown; requestId?: string };
function recorder(page: Page) {
  const calls: Interaction[] = [];
  page.on('request', request => {
    if (!request.url().includes('/api/v1/admin/')) return;
    calls.push({ method: request.method(), path: new URL(request.url()).pathname, body: request.postData() ? JSON.parse(request.postData()!) : undefined, requestId: request.headers()['x-request-id'] });
  });
  return calls;
}

async function grantLogin(page: Page, options: { actions?: string[] } = {}) {
  const actions = options.actions ?? ['merchant.application.read', 'merchant.application.decide', 'merchant.identity.reveal'];
  await page.route('**/api/v1/admin/auth/attempts', route => route.fulfill(authEnvelope({ attemptId: '801', attemptToken: 'at', expiresAt: '2026-09-20T18:00:00.000Z', nextStep: 'PROVE_IDENTITY' }, 201)));
  await page.route('**/api/v1/admin/auth/attempts/801/requirements', route => route.fulfill(authEnvelope({ requiredVerification: 'NONE' })));
  await page.route('**/api/v1/admin/auth/login', route => route.fulfill(authEnvelope({ sessionId: 's1', operatorId: '9001', audience: 'ADMIN_WEB', tokenType: 'Bearer', accessToken: TOKEN, expiresAt: '2026-09-20T18:00:00.000Z' })));
  await page.route('**/api/v1/admin/auth/session', route => route.fulfill(authEnvelope({ sessionId: 's1', operatorId: '9001', audience: 'ADMIN_WEB', expiresAt: '2026-09-20T18:00:00.000Z', idleExpiresAt: '2026-09-20T17:30:00.000Z', authzVersion: 'v1' })));
  await page.route('**/api/v1/admin/auth/permissions', route => route.fulfill(authEnvelope({ operatorId: '9001', authzVersion: 'v1', checkedAt: '2026-09-20T16:00:00.000Z', roles: ['OPERATOR'], dataScope: 'ALL', actionCodes: actions })));
}

async function login(page: Page) {
  await page.goto('/login');
  await page.getByLabel('账号').fill('ops');
  await page.getByLabel('密码').fill('secret-password');
  await page.getByRole('button', { name: '登录' }).click();
}

test('W2-FE-002 unauthenticated deep link redirects to login without business calls', async ({ page }) => {
  const calls = recorder(page);
  await page.goto(`/merchant-applications/${APPLICATION_ID}`);
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '运营登录' })).toBeVisible();
  expect(calls).toEqual([]);
});

test('login reaches a closed panel without read permission; login post carries requestId', async ({ page }) => {
  const calls = recorder(page);
  let listCalls = 0;
  await grantLogin(page, { actions: [] });
  await page.route('**/api/v1/admin/merchant-applications**', route => { listCalls++; route.fulfill(unified({ items: [], page: 1, pageSize: 20, total: 0 })); });
  await login(page);
  await expect(page.getByRole('heading', { name: '403 · 无申请审核权限' })).toBeVisible();
  expect(listCalls).toBe(0);
  const loginCall = calls.find(call => call.path.endsWith('/auth/login'));
  expect(loginCall?.body).toEqual({ attemptId: '801', account: 'ops', password: 'secret-password', captchaProof: '' });
  expect(loginCall?.requestId).toBeTruthy();
});

test('review list renders contract summaries and paginates server-side', async ({ page }) => {
  await grantLogin(page);
  let requestedPage = 1;
  await page.route('**/api/v1/admin/merchant-applications**', route => {
    requestedPage = Number(new URL(route.request().url()).searchParams.get('page') ?? '1');
    route.fulfill(unified({ items: requestedPage === 1 ? [SUMMARY] : [], page: requestedPage, pageSize: 20, total: 21 }));
  });
  await login(page);
  await expect(page.getByRole('heading', { name: '商家申请审核' })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'MA202609200001' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '星河宠物医院' })).toBeVisible();
  await expect(page.getByText('第 1 / 2 页 · 共 21 条')).toBeVisible();
  await page.getByRole('button', { name: '下一页' }).click();
  await expect(page.getByText('没有符合条件的申请。')).toBeVisible();
  expect(requestedPage).toBe(2);
});

test('claim, single-use watermarked reads, manual verification gates approve, decision posted', async ({ page }) => {
  const calls = recorder(page);
  await grantLogin(page);
  let taskStatus: 'AVAILABLE' | 'CLAIMED' = 'AVAILABLE';
  let taskVersion = '2';
  let verification: 'PENDING' | 'VERIFIED' = 'PENDING';
  let grantSerial = 0;
  const usedTokens = new Set<string>();
  await page.route('**/api/v1/admin/merchant-applications**', (route: Route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/admin/merchant-applications') route.fulfill(unified({ items: [SUMMARY], page: 1, pageSize: 20, total: 1 }));
    else if (path === `/api/v1/admin/merchant-applications/${APPLICATION_ID}`) route.fulfill(unified(detail({ taskStatus, taskVersion, verification })));
    else if (path.endsWith('/claim')) { taskStatus = 'CLAIMED'; taskVersion = '3'; route.fulfill(unified({ ...detail().task, status: 'CLAIMED', version: '3', claimedByOperatorId: '9001' })); }
    else if (path.endsWith('/manual-verification')) { verification = 'VERIFIED'; route.fulfill(unified(RECEIPT)); }
    else if (path.endsWith('/decision')) route.fulfill(unified({ ...RECEIPT, status: 'REJECTED' }));
    else route.fulfill(failure('NOT_FOUND', 404));
  });
  await page.route(/\/private-assets\/6011\d+\/read-grants$/, route => {
    grantSerial += 1;
    route.fulfill(unified({ readUrl: `/api/v1/admin/private-asset-read-grants/token${grantSerial}_aaaaaaaaaaaaaaaaaaaaaaaaaaaa`, expiresAt: '2026-09-20T17:00:00.000Z' }));
  });
  await page.route(/\/private-asset-read-grants\/.+/, route => {
    const token = new URL(route.request().url()).pathname.split('/').pop()!;
    if (usedTokens.has(token)) route.fulfill(failure('GRANT_ALREADY_USED', 409));
    else { usedTokens.add(token); route.fulfill({ status: 200, headers: { 'Content-Type': 'image/png' }, body: PNG }); }
  });

  await login(page);
  await page.getByRole('link', { name: '打开' }).click();
  await expect(page.getByRole('heading', { name: `申请 ${SUMMARY.applicationNo}` })).toBeVisible();
  await expect(page.getByText('待领取').first()).toBeVisible();
  await page.getByRole('button', { name: '领取任务' }).click();
  const claim = calls.find(call => call.path.endsWith('/claim'));
  expect(claim?.body).toEqual({ expectedTaskVersion: '2' });
  expect(claim?.requestId).toBeTruthy();

  await expect(page.getByRole('heading', { name: '人工核验（主体/证件）' })).toBeVisible();
  await expect(page.getByText('主体核验未完成')).toBeVisible();
  // option.disabled is not exposed as an enabled/disabled role state; assert the attribute.
  await expect(page.getByRole('option', { name: /通过（建立商家档案）/ })).toHaveAttribute('disabled');

  for (const label of ['营业执照', '身份证（人像面）', '身份证（国徽面）', '行业许可证']) {
    await page.locator('ul.materials li', { hasText: label }).getByRole('button', { name: '申请一次性查看' }).click();
    await expect(page.locator('ul.materials li', { hasText: label }).getByRole('img')).toBeVisible();
  }
  const grant = calls.find(call => call.path.includes('/private-assets/6011000000000000003/read-grants'));
  const grantBody = grant?.body as { submissionRevisionId?: string; purposeCode?: string; confirmed?: boolean; reason?: string };
  expect(grantBody).toMatchObject({ submissionRevisionId: SUMMARY.submittedRevisionId, purposeCode: 'MERCHANT_APPLICATION_REVIEW', confirmed: true });
  expect(String(grantBody?.reason ?? '').length).toBeGreaterThanOrEqual(10);

  await page.getByLabel('营业执照 证件主体').fill('星河宠物医院有限公司');
  await page.getByLabel('营业执照 证号').fill('91310101TEST0001X');
  await page.getByLabel('营业执照 生效日').fill('2020-01-01');
  await page.getByLabel('身份证（人像面） 证件主体').fill('张三');
  await page.getByLabel('身份证（人像面） 证号').fill('310101199001010010');
  await page.getByLabel('身份证（人像面） 生效日').fill('2015-01-01');
  await page.getByLabel('身份证（人像面） 到期日').fill('2035-01-01');
  await page.getByLabel('身份证（国徽面） 证件主体').fill('张三');
  await page.getByLabel('身份证（国徽面） 证号').fill('310101199001010010');
  await page.getByLabel('身份证（国徽面） 有效期类型').selectOption('LONG_TERM');
  await page.getByLabel('行业许可证 证件主体').fill('星河宠物医院有限公司');
  await page.getByLabel('行业许可证 证号').fill('DY-2026-001');
  await page.getByLabel('行业许可证 生效日').fill('2026-01-01');
  await page.getByLabel('行业许可证 到期日').fill('2028-01-01');
  await page.getByPlaceholder('如：已核对证件原件与主体一致，编号与有效期无误').fill('已逐项核对证件原件、主体、编号与有效期一致');
  await page.getByLabel('我已逐项核对上述证件信息与所见材料一致').check();
  await page.getByRole('button', { name: '提交人工核验' }).click();
  const verificationCall = calls.find(call => call.path.endsWith('/manual-verification'));
  expect(verificationCall?.body).toMatchObject({ submissionRevisionId: SUMMARY.submittedRevisionId, expectedVersion: '4', expectedTaskVersion: '3', confirmed: true });
  expect((verificationCall?.body as { evidenceItems: unknown[] }).evidenceItems)
    .toContainEqual(expect.objectContaining({ materialId: '6011000000000000003', materialSha256: PNG_SHA256 }));
  expect(verificationCall?.requestId).toBeTruthy();

  await expect(page.getByText('已核验').first()).toBeVisible();
  await expect(page.getByRole('option', { name: /通过（建立商家档案）/ })).not.toHaveAttribute('disabled');
  await page.getByLabel('决定类型').selectOption('REQUEST_CORRECTION');
  await expect(page.getByRole('button', { name: '提交审核决定' })).toBeDisabled();
  await page.getByLabel(/审核意见/).fill('请补充三个月内水电费单据作为经营地址证明');
  await page.getByLabel('我确认本决定基于已查看的材料与核验结果').check();
  await page.getByRole('button', { name: '提交审核决定' }).click();
  const decision = calls.find(call => call.path.endsWith('/decision'));
  expect(decision?.body).toMatchObject({ decisionType: 'REQUEST_CORRECTION', submissionRevisionId: SUMMARY.submittedRevisionId, expectedVersion: '4', expectedTaskVersion: '3', opinion: '请补充三个月内水电费单据作为经营地址证明', confirmed: true });
  expect(decision?.requestId).toBeTruthy();
});

test('stale auth (401 on business query) fails closed and returns to login', async ({ page }) => {
  await grantLogin(page);
  let calls = 0;
  await page.route('**/api/v1/admin/merchant-applications**', route => {
    calls += 1;
    route.fulfill(calls === 1 ? unified({ items: [SUMMARY], page: 1, pageSize: 20, total: 21 }) : failure('UNAUTHENTICATED', 401));
  });
  await login(page);
  await expect(page.getByRole('cell', { name: 'MA202609200001' })).toBeVisible();
  await page.getByRole('button', { name: '下一页' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '运营登录' })).toBeVisible();
});

test('version conflict on claim surfaces a refresh hint instead of silent overwrite', async ({ page }) => {
  await grantLogin(page);
  await page.route('**/api/v1/admin/merchant-applications**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/admin/merchant-applications') route.fulfill(unified({ items: [SUMMARY], page: 1, pageSize: 20, total: 1 }));
    else if (path.endsWith('/claim')) route.fulfill(failure('COMMON_CONFLICT', 409));
    else route.fulfill(unified(detail()));
  });
  await login(page);
  await page.getByRole('link', { name: '打开' }).click();
  await page.getByRole('button', { name: '领取任务' }).click();
  await expect(page.getByRole('alert').first()).toContainText('版本冲突');
});
