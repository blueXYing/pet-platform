import { expect, test, type Page, type Route } from '@playwright/test';

// Contract mocks against the production build (preview server): W2-FE-001/W2-FE-002 for A-002.
// Every payload mirrors the approved application/private-asset projections; no fixture chunk is involved.
test.use({ baseURL: 'http://127.0.0.1:4174' });

const TOKEN = 'test-admin-token';
const ATTEMPT_TOKEN = 'at-test-binding';

const APPLICATION_ID = '3012345678901234567';
const SUMMARY = {
  applicationId: APPLICATION_ID, applicationNo: 'MA202609200001', reservedMerchantId: '4012345678901234567',
  status: 'REVIEWING', version: '4', merchantName: '星河宠物医院', merchantTypeCode: 'PET_HOSPITAL', cityCode: 'CHENGDU',
  submittedRevisionId: '5012345678901234567', submittedAt: '2026-09-20T10:00:00.000Z', subjectVerificationStatus: 'PENDING',
};
// Authoritative submitted-material references (CCR-A002-MATERIAL-REF-001):
// registered material ids and digests differ from asset ids; watermarked bytes
// are never substitutes.
const SHA = (suffix: string) => suffix.padEnd(64, '0').slice(0, 64);
const PNG_BYTES = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
const MATERIAL_REFERENCES = [
  { materialId: '9001000000000000001', assetId: '6011000000000000001', materialSha256: SHA('aa'), materialType: 'STORE_PHOTO', position: 1 },
  { materialId: '9001000000000000002', assetId: '6011000000000000002', materialSha256: SHA('bb'), materialType: 'STORE_PHOTO', position: 2 },
  { materialId: '9001000000000000003', assetId: '6011000000000000003', materialSha256: SHA('cc'), materialType: 'BUSINESS_LICENSE', position: 1 },
  { materialId: '9001000000000000004', assetId: '6011000000000000005', materialSha256: SHA('dd'), materialType: 'ID_CARD_BACK', position: 1 },
  { materialId: '9001000000000000005', assetId: '6011000000000000006', materialSha256: SHA('ee'), materialType: 'INDUSTRY_LICENSE', position: 1 },
];
function detail(options: { taskStatus?: 'AVAILABLE' | 'CLAIMED'; taskVersion?: string; verification?: 'PENDING' | 'VERIFIED'; materialReferences?: unknown[]; omitReferences?: boolean } = {}) {
  const references = 'materialReferences' in options ? options.materialReferences : options.omitReferences ? undefined : MATERIAL_REFERENCES;
  return {
    ...SUMMARY, subjectVerificationStatus: options.verification ?? 'PENDING',
    submittedRevision: {
      revisionId: SUMMARY.submittedRevisionId, revisionNo: 1, createdAt: '2026-09-20T09:30:00.000Z',
      ...(references === undefined ? {} : { materialReferences: references }),
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
const RECEIPT = { applicationId: APPLICATION_ID, applicationNo: SUMMARY.applicationNo, reservedMerchantId: SUMMARY.reservedMerchantId, status: 'REJECTED', version: '5', currentRevisionId: SUMMARY.submittedRevisionId };

function unified(data: unknown, status = 200) { return { status, contentType: 'application/json', body: JSON.stringify({ success: true, code: 'SUCCESS', message: '成功', data, traceId: 'test' }) }; }
// /auth/* predates the unified wrapper: no success field (CONTRACT.md §4.1).
function authEnvelope(data: unknown, status = 200) { return { status, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', message: '成功', data, traceId: 'test' }) }; }
function failure(code: string, status: number) { return { status, contentType: 'application/json', body: JSON.stringify({ success: false, code, message: code, data: null, traceId: 'test' }) }; }

type Interaction = { method: string; path: string; body?: unknown; requestId?: string; attemptHeader?: string };
function recorder(page: Page) {
  const calls: Interaction[] = [];
  page.on('request', request => {
    if (!request.url().includes('/api/v1/admin/')) return;
    calls.push({
      method: request.method(), path: new URL(request.url()).pathname,
      body: request.postData() ? JSON.parse(request.postData()!) : undefined,
      requestId: request.headers()['x-request-id'], attemptHeader: request.headers()['x-auth-attempt'],
    });
  });
  return calls;
}

async function grantLogin(page: Page, options: { actions?: string[] } = {}) {
  const actions = options.actions ?? ['merchant.application.read', 'merchant.application.decide', 'merchant.identity.reveal'];
  await page.route('**/api/v1/admin/auth/attempts', route => route.fulfill(authEnvelope({ attemptId: '801', attemptToken: ATTEMPT_TOKEN, expiresAt: '2026-09-20T18:00:00.000Z', nextStep: 'PROVE_IDENTITY' }, 201)));
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

test('real login protocol: empty JSON body and X-Auth-Attempt on every bound call', async ({ page }) => {
  const calls = recorder(page);
  await grantLogin(page, { actions: [] });
  await login(page);
  await expect(page.getByRole('heading', { name: '403 · 无申请审核权限' })).toBeVisible();
  const attempts = calls.find(call => call.path.endsWith('/auth/attempts'));
  expect(attempts?.method).toBe('POST');
  expect(attempts?.body).toEqual({});
  const requirements = calls.find(call => call.path.endsWith('/requirements'));
  expect(requirements?.attemptHeader).toBe(ATTEMPT_TOKEN);
  const loginCall = calls.find(call => call.path.endsWith('/auth/login'));
  expect(loginCall?.attemptHeader).toBe(ATTEMPT_TOKEN);
  // captchaProof is OMITTED when unused: the backend digest() rejects blank proofs.
  expect(loginCall?.body).toEqual({ attemptId: '801', account: 'ops', password: 'secret-password' });
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

test('claim, single-use reads, manual verification quotes authoritative references, then decision', async ({ page }) => {
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
    else if (path.endsWith('/decision')) route.fulfill(unified(RECEIPT));
    else route.fulfill(failure('NOT_FOUND', 404));
  });
  await page.route(/\/private-assets\/6011\d+\/read-grants$/, route => {
    grantSerial += 1;
    route.fulfill(unified({ readUrl: `/api/v1/admin/private-asset-read-grants/token${grantSerial}_aaaaaaaaaaaaaaaaaaaaaaaaaaaa`, expiresAt: '2026-09-20T17:00:00.000Z' }));
  });
  await page.route(/\/private-asset-read-grants\/.+/, route => {
    const token = new URL(route.request().url()).pathname.split('/').pop()!;
    if (usedTokens.has(token)) route.fulfill(failure('GRANT_ALREADY_USED', 409));
    else { usedTokens.add(token); route.fulfill({ status: 200, headers: { 'Content-Type': 'image/png' }, body: PNG_BYTES }); }
  });

  await login(page);
  await page.getByRole('link', { name: '打开' }).click();
  await expect(page.getByRole('heading', { name: `申请 ${SUMMARY.applicationNo}` })).toBeVisible();
  await page.getByRole('button', { name: '领取任务' }).click();
  const claim = calls.find(call => call.path.endsWith('/claim'));
  expect(claim?.body).toEqual({ expectedTaskVersion: '2' });
  expect(claim?.requestId).toBeTruthy();

  // Single-use watermarked reads: identity requires BOTH sides viewed before the row unlocks.
  await expect(page.getByRole('heading', { name: '人工核验（主体/证件）' })).toBeVisible();
  for (const label of ['营业执照', '身份证（人像面）', '身份证（国徽面）', '行业许可证']) {
    await page.locator('ul.materials li', { hasText: label }).getByRole('button', { name: '申请一次性查看' }).click();
    await expect(page.locator('ul.materials li', { hasText: label }).getByRole('img')).toBeVisible();
  }
  const grant = calls.find(call => call.path.includes('/private-assets/6011000000000000003/read-grants'));
  const grantBody = grant?.body as { submissionRevisionId?: string; purposeCode?: string; confirmed?: boolean; reason?: string };
  expect(grantBody).toMatchObject({ submissionRevisionId: SUMMARY.submittedRevisionId, purposeCode: 'MERCHANT_APPLICATION_REVIEW', confirmed: true });
  expect(String(grantBody?.reason ?? '').length).toBeGreaterThanOrEqual(10);

  // Evidence quotes the REGISTERED materialId + materialSha256, never asset ids or read bytes.
  await page.getByLabel('营业执照 证件主体').fill('星河宠物医院有限公司');
  await page.getByLabel('营业执照 证号').fill('91310101TEST0001X');
  await page.getByLabel('营业执照 生效日').fill('2020-01-01');
  await page.getByLabel('身份证（正反面合并核验） 证件主体').fill('张三');
  await page.getByLabel('身份证（正反面合并核验） 证号').fill('310101199001010010');
  await page.getByLabel('身份证（正反面合并核验） 生效日').fill('2015-01-01');
  await page.getByLabel('身份证（正反面合并核验） 到期日').fill('2035-01-01');
  await page.getByLabel('行业许可证 证件主体').fill('星河宠物医院有限公司');
  await page.getByLabel('行业许可证 证号').fill('DY-2026-001');
  await page.getByLabel('行业许可证 生效日').fill('2026-01-01');
  await page.getByLabel('行业许可证 到期日').fill('2028-01-01');
  await expect(page.getByRole('button', { name: '提交人工核验' })).toBeDisabled();
  await page.getByPlaceholder('如：已核对证件原件与主体一致，编号与有效期无误').fill('已逐项核对证件原件、主体、编号与有效期一致');
  await page.getByLabel('我已逐项核对上述证件信息与所见材料一致').check();
  await page.getByRole('button', { name: '提交人工核验' }).click();
  const verificationCall = calls.find(call => call.path.endsWith('/manual-verification'));
  expect(verificationCall?.body).toMatchObject({ submissionRevisionId: SUMMARY.submittedRevisionId, expectedVersion: '4', expectedTaskVersion: '3', reason: '已逐项核对证件原件、主体、编号与有效期一致', confirmed: true });
  expect((verificationCall?.body as { evidenceItems: unknown[] }).evidenceItems).toEqual([
    { materialId: '9001000000000000003', materialSha256: SHA('cc'), credentialType: 'CREDIT_CODE', subjectName: '星河宠物医院有限公司', identifier: '91310101TEST0001X', validityKind: 'DATED', validFrom: '2020-01-01', validTo: null },
    { materialId: '9001000000000000004', materialSha256: SHA('dd'), credentialType: 'IDENTITY_NUMBER', subjectName: '张三', identifier: '310101199001010010', validityKind: 'DATED', validFrom: '2015-01-01', validTo: '2035-01-01' },
    { materialId: '9001000000000000005', materialSha256: SHA('ee'), credentialType: 'INDUSTRY_LICENSE', subjectName: '星河宠物医院有限公司', identifier: 'DY-2026-001', validityKind: 'DATED', validFrom: '2026-01-01', validTo: '2028-01-01' },
  ]);
  expect(verificationCall?.requestId).toBeTruthy();

  // VERIFIED unlocks APPROVE only then; REQUEST_CORRECTION is checked afterwards.
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

test('absent materialReferences (older backend) keeps verification closed, correction still available', async ({ page }) => {
  const calls = recorder(page);
  await grantLogin(page);
  await page.route('**/api/v1/admin/merchant-applications**', (route: Route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/admin/merchant-applications') route.fulfill(unified({ items: [SUMMARY], page: 1, pageSize: 20, total: 1 }));
    else if (path === `/api/v1/admin/merchant-applications/${APPLICATION_ID}`) route.fulfill(unified(detail({ taskStatus: 'CLAIMED', materialReferences: undefined })));
    else route.fulfill(failure('NOT_FOUND', 404));
  });

  await login(page);
  await page.getByRole('link', { name: '打开' }).click();
  await expect(page.getByText('详情未返回材料引用投影（后端未含已批准契约或版本过旧），人工核验提交保持禁用。')).toBeVisible();
  await expect(page.getByRole('button', { name: '提交人工核验（材料引用不可用）' })).toBeDisabled();
  expect(calls.some(call => call.path.endsWith('/manual-verification'))).toBe(false);
  // Approve stays gated; correction path remains usable without verification.
  await expect(page.getByRole('option', { name: /通过（建立商家档案）/ })).toHaveAttribute('disabled');
  await page.getByLabel('决定类型').selectOption('REQUEST_CORRECTION');
  await page.getByLabel(/审核意见/).fill('材料投影缺失，先要求补正');
  await page.getByLabel('我确认本决定基于已查看的材料与核验结果').check();
  await expect(page.getByRole('button', { name: '提交审核决定' })).toBeEnabled();
});

test('corrupt material reference digest fails closed', async ({ page }) => {
  await grantLogin(page);
  const corrupted = MATERIAL_REFERENCES.map(item => item.materialType === 'ID_CARD_BACK' ? { ...item, materialSha256: 'not-a-sha' } : item);
  await page.route('**/api/v1/admin/merchant-applications**', (route: Route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/admin/merchant-applications') route.fulfill(unified({ items: [SUMMARY], page: 1, pageSize: 20, total: 1 }));
    else if (path === `/api/v1/admin/merchant-applications/${APPLICATION_ID}`) route.fulfill(unified(detail({ taskStatus: 'CLAIMED', materialReferences: corrupted })));
    else route.fulfill(failure('NOT_FOUND', 404));
  });

  await login(page);
  await page.getByRole('link', { name: '打开' }).click();
  await expect(page.getByText(/材料引用摘要不合法/)).toBeVisible();
  await expect(page.getByRole('button', { name: '提交人工核验（材料引用不可用）' })).toBeDisabled();
});

test('unknown-outcome write retries reuse the SAME requestId for an idempotent receipt', async ({ page }) => {
  const calls = recorder(page);
  await grantLogin(page);
  let claimCalls = 0;
  await page.route('**/api/v1/admin/merchant-applications**', (route: Route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/admin/merchant-applications') route.fulfill(unified({ items: [SUMMARY], page: 1, pageSize: 20, total: 1 }));
    else if (path === `/api/v1/admin/merchant-applications/${APPLICATION_ID}`) route.fulfill(unified(detail({ taskStatus: claimCalls > 0 ? 'CLAIMED' : 'AVAILABLE', taskVersion: claimCalls > 0 ? '3' : '2' })));
    else if (path.endsWith('/claim')) {
      claimCalls += 1;
      if (claimCalls === 1) route.abort('connectionreset');
      else route.fulfill(unified({ ...detail().task, status: 'CLAIMED', version: '3', claimedByOperatorId: '9001' }));
    } else route.fulfill(unified(detail()));
  });

  await login(page);
  await page.getByRole('link', { name: '打开' }).click();
  await page.getByRole('button', { name: '领取任务' }).click();
  await expect(page.getByRole('alert').first()).toContainText('结果未知');
  await expect(page.getByRole('button', { name: /重试原操作/ })).toBeVisible();
  await page.getByRole('button', { name: /重试原操作/ }).click();
  await expect(page.getByText('任务已领取')).toBeVisible();
  const claimCallsRecorded = calls.filter(call => call.path.endsWith('/claim'));
  expect(claimCallsRecorded).toHaveLength(2);
  expect(claimCallsRecorded[0].requestId).toBeTruthy();
  expect(claimCallsRecorded[1].requestId).toBe(claimCallsRecorded[0].requestId);
});

test('503 dependency-unavailable is unknown outcome: retry kept, new writes blocked until resolved', async ({ page }) => {
  const calls = recorder(page);
  await grantLogin(page);
  let claimCalls = 0;
  await page.route('**/api/v1/admin/merchant-applications**', (route: Route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/admin/merchant-applications') route.fulfill(unified({ items: [SUMMARY], page: 1, pageSize: 20, total: 1 }));
    else if (path === `/api/v1/admin/merchant-applications/${APPLICATION_ID}`) route.fulfill(unified(detail({ taskStatus: claimCalls > 0 ? 'CLAIMED' : 'AVAILABLE', taskVersion: claimCalls > 0 ? '3' : '2' })));
    else if (path.endsWith('/claim')) {
      claimCalls += 1;
      // Backend returns 503 COMMON_DEPENDENCY_UNAVAILABLE when the commit outcome is unknown.
      if (claimCalls === 1) route.fulfill(failure('COMMON_DEPENDENCY_UNAVAILABLE', 503));
      else route.fulfill(unified({ ...detail().task, status: 'CLAIMED', version: '3', claimedByOperatorId: '9001' }));
    } else route.fulfill(unified(detail()));
  });

  await login(page);
  await page.getByRole('link', { name: '打开' }).click();
  await page.getByRole('button', { name: '领取任务' }).click();
  await expect(page.getByRole('alert').first()).toContainText('结果未知');
  await expect(page.getByRole('button', { name: /重试原操作/ })).toBeVisible();

  // While the intent is unresolved, conflicting writes stay disabled…
  await expect(page.getByRole('button', { name: '领取任务' })).toBeDisabled();
  await expect(page.locator('ul.materials li').first().getByRole('button', { name: '申请一次性查看' })).toBeDisabled();
  // …and resolving via retry reuses the SAME requestId.
  await page.getByRole('button', { name: /重试原操作/ }).click();
  await expect(page.getByText('任务已领取')).toBeVisible();
  const claimCallsRecorded = calls.filter(call => call.path.endsWith('/claim'));
  expect(claimCallsRecorded).toHaveLength(2);
  expect(claimCallsRecorded[1].requestId).toBe(claimCallsRecorded[0].requestId);
});

test('refresh never clears a pending intent (stale read then late landing); only same-requestId replay resolves', async ({ page }) => {
  const calls = recorder(page);
  await grantLogin(page);
  let claimCalls = 0;
  let detailCalls = 0;
  await page.route('**/api/v1/admin/merchant-applications**', (route: Route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/admin/merchant-applications') route.fulfill(unified({ items: [SUMMARY], page: 1, pageSize: 20, total: 1 }));
    else if (path === `/api/v1/admin/merchant-applications/${APPLICATION_ID}`) {
      detailCalls += 1;
      // The original claim "lands" only on the third read (initial load, stale
      // refresh #1, landed refresh #2) — exactly the reviewer's race.
      const landed = claimCalls > 0 && detailCalls >= 3;
      route.fulfill(unified(detail({ taskStatus: landed ? 'CLAIMED' : 'AVAILABLE', taskVersion: landed ? '3' : '2' })));
    }
    else if (path.endsWith('/claim')) {
      claimCalls += 1;
      if (claimCalls === 1) route.fulfill(failure('COMMON_DEPENDENCY_UNAVAILABLE', 503));
      else route.fulfill(unified({ ...detail().task, status: 'CLAIMED', version: '3', claimedByOperatorId: '9001' }));
    } else route.fulfill(unified(detail()));
  });

  await login(page);
  await page.getByRole('link', { name: '打开' }).click();
  await page.getByRole('button', { name: '领取任务' }).click();
  await expect(page.getByRole('button', { name: /重试原操作/ })).toBeVisible();

  // Refresh #1 returns the STALE state (task still AVAILABLE): pending must stay.
  await page.getByRole('button', { name: '刷新申请状态' }).click();
  await expect(page.getByRole('button', { name: /重试原操作/ })).toBeVisible();
  await expect(page.getByText('任务状态：待领取')).toBeVisible();

  // Refresh #2 now sees the landed CLAIMED state — the snapshot has NO
  // requestId correlation, so it still must NOT clear the pending intent.
  await page.getByRole('button', { name: '刷新申请状态' }).click();
  await expect(page.getByText('任务状态：已领取').first()).toBeVisible();
  await expect(page.getByRole('button', { name: /重试原操作/ })).toBeVisible();
  expect(calls.filter(call => call.path.endsWith('/claim'))).toHaveLength(1);

  // Only replaying the SAME requestId resolves the intent via the idempotent receipt.
  await page.getByRole('button', { name: /重试原操作/ }).click();
  await expect(page.getByText('任务已领取')).toBeVisible();
  await expect(page.getByRole('button', { name: /重试原操作/ })).toHaveCount(0);
  const claimCallsRecorded = calls.filter(call => call.path.endsWith('/claim'));
  expect(claimCallsRecorded).toHaveLength(2);
  expect(claimCallsRecorded[1].requestId).toBe(claimCallsRecorded[0].requestId);
});

test('grant issuance stays pending: snapshot cannot determine it, only idempotent retry recovers', async ({ page }) => {
  const calls = recorder(page);
  await grantLogin(page);
  let grantCalls = 0;
  await page.route('**/api/v1/admin/merchant-applications**', (route: Route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/admin/merchant-applications') route.fulfill(unified({ items: [SUMMARY], page: 1, pageSize: 20, total: 1 }));
    else route.fulfill(unified(detail()));
  });
  await page.route(/\/private-assets\/6011\d+\/read-grants$/, route => {
    grantCalls += 1;
    if (grantCalls === 1) route.fulfill(failure('COMMON_DEPENDENCY_UNAVAILABLE', 503));
    else route.fulfill(unified({ readUrl: `/api/v1/admin/private-asset-read-grants/token${grantCalls}_aaaaaaaaaaaaaaaaaaaaaaaaaaaa`, expiresAt: '2026-09-20T17:00:00.000Z' }));
  });
  await page.route(/\/private-asset-read-grants\/.+/, route =>
    route.fulfill({ status: 200, headers: { 'Content-Type': 'image/png' }, body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64') }));

  await login(page);
  await page.getByRole('link', { name: '打开' }).click();
  await page.locator('ul.materials li', { hasText: '营业执照' }).getByRole('button', { name: '申请一次性查看' }).click();
  await expect(page.getByRole('alert').first()).toContainText('结果未知');
  await expect(page.getByText(/材料授权结果无法由申请快照判定/)).toBeVisible();

  // An authoritative refresh must NOT clear this intent…
  await page.getByRole('button', { name: '刷新申请状态' }).click();
  await expect(page.getByRole('button', { name: /重试原操作/ })).toBeVisible();
  // …only replaying the SAME requestId recovers.
  await page.getByRole('button', { name: /重试原操作/ }).click();
  await expect(page.locator('ul.materials li', { hasText: '营业执照' }).getByRole('img')).toBeVisible();
  const grantCallsRecorded = calls.filter(call => call.path.includes('/read-grants') && call.path.includes('/private-assets/'));
  expect(grantCallsRecorded).toHaveLength(2);
  expect(grantCallsRecorded[1].requestId).toBe(grantCallsRecorded[0].requestId);
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
