import { expect, test, type Page } from '@playwright/test';

// Contract 64 governance read faces run the real production UI/transport with intercepted
// HTTP (same discipline as review-appeals.spec.ts). These tests do not assert a live backend
// or production enablement. Pages under test: MerchantLedger*Page, ScheduleViewPage,
// StaffGovernancePage (A-002 phase 2).
test.use({ baseURL: 'http://127.0.0.1:4174' });
const OPERATOR = '901';
const MERCHANT = '910000000000101';
const ACTIONS = ['merchant.read', 'schedule.read', 'merchant.staff.read', 'merchant.disable'];
function envelope(data: unknown, status = 200) { return { status, json: { code: 'SUCCESS', message: 'ok', data, traceId: 'fixture-governance' } }; }
const ledgerRow = {
  merchantId: MERCHANT, merchantName: '治理测试商家一', status: 'ACTIVE',
  merchantTypeCode: 'PET_LIFE_STORE', cityCode: 'CITY_A', storeCount: 2, activeStoreCount: 1,
  applicationStatus: 'APPROVED', signingStatus: 'SIGNED',
  providerMerchantNoMasked: '****5678', createdAt: '2026-10-01T08:00:00.000Z',
};
const windowRow = {
  windowId: '910000000000901', merchantId: MERCHANT, storeId: '910000000000201',
  serviceId: '910000000000301', windowKind: 'GENERAL', startAt: '2030-01-01T01:00:00.000Z',
  endAt: '2030-01-01T02:00:00.000Z', configuredCapacity: 3, status: 'OPEN',
  version: '5', updatedAt: '2026-10-15T08:00:00.000Z', confirmedCount: 1, tempLockedCount: 1,
};
const staffRow = {
  staffId: '910000000000301', storeId: '910000000000201', staffName: '员工甲',
  phoneMasked: '139****1111', employmentStatus: 'ACTIVE', serviceEnabled: true,
  version: '1', createdAt: '2026-10-02T08:00:00.000Z', updatedAt: '2026-10-02T08:00:00.000Z',
};
const memberRow = {
  memberId: '910000000000501', storeId: '910000000000201', status: 'ENABLED',
  memberName: '成员甲', phoneMasked: '136****1111', grantedActions: ['merchant.order.verify'],
  grantRevoked: false, memberVersion: '1', grantVersion: '3',
  createdAt: '2026-10-04T09:00:00.000Z', updatedAt: '2026-10-09T08:00:00.000Z',
};
const invitationRow = {
  invitationId: '910000000000403', storeId: '910000000000201', memberName: '成员丙',
  phoneMasked: '136****3333', status: 'INVITED', grantedActions: ['merchant.order.verify'],
  invitedBy: '910000000000610', invitedAt: '2026-10-12T08:00:00.000Z',
  updatedAt: '2026-10-12T08:00:00.000Z',
};
const pageEnvelope = (items: unknown[]) => ({ page: 1, pageSize: 20, total: items.length, items });
const detail = {
  ...ledgerRow,
  stores: [{ storeId: '910000000000201', storeName: '旗舰一店', address: '治理路1号', status: 'ACTIVE' }],
  application: {
    applicationId: '2102', applicationNo: 'SQ20261015AAAAAAAA', status: 'APPROVED',
    submittedAt: '2026-10-10T09:00:00.000Z', reviewedAt: '2026-10-11T09:00:00.000Z',
  },
  signing: { signingStatus: 'SIGNED', agreementVersion: 'merchant-agreement-v1', acceptedAt: '2026-10-02T08:00:00.000Z' },
};
async function signIn(page: Page, actions = ACTIONS) {
  await page.route('**/api/v1/admin/auth/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/attempts')) return route.fulfill(envelope({ attemptId: '801', attemptToken: 'fixture-binding', expiresAt: '2026-10-16T02:00:00.000Z', nextStep: 'PROVE_IDENTITY' }, 201));
    if (path.endsWith('/requirements')) return route.fulfill(envelope({ requiredVerification: 'NONE' }));
    if (path.endsWith('/login')) return route.fulfill(envelope({ sessionId: 's1', operatorId: OPERATOR, audience: 'ADMIN_WEB', tokenType: 'Bearer', accessToken: 'fixture-admin-token', expiresAt: '2026-10-16T02:00:00.000Z' }));
    if (path.endsWith('/session')) return route.fulfill(envelope({ sessionId: 's1', operatorId: OPERATOR, audience: 'ADMIN_WEB', expiresAt: '2026-10-16T02:00:00.000Z', idleExpiresAt: '2026-10-16T01:00:00.000Z', authzVersion: 'v1' }));
    if (path.endsWith('/permissions')) return route.fulfill(envelope({ operatorId: OPERATOR, authzVersion: 'v1', checkedAt: '2026-10-15T02:00:00.000Z', roles: ['OPERATOR'], dataScope: 'ALL', actionCodes: actions }));
    return route.fulfill(envelope(null));
  });
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill('fixture-ops');
  await page.getByLabel('密码', { exact: true }).fill('fixture-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
}
function captureGovernance(page: Page) {
  const calls: { path: string; authorization: string | null }[] = [];
  page.on('request', req => {
    const url = new URL(req.url());
    if (url.pathname.includes('/api/v1/admin/governance/')) calls.push({ path: url.pathname + url.search, authorization: req.headers()['authorization'] ?? null });
  });
  return calls;
}

test('A-002 商家台账：列表+详情消费真实信封，脱敏与事实透传', async ({ page }) => {
  await signIn(page);
  const calls = captureGovernance(page);
  await page.route('**/api/v1/admin/governance/merchants?*', route => route.fulfill(envelope(pageEnvelope([ledgerRow]))));
  await page.route(`**/api/v1/admin/governance/merchants/${MERCHANT}`, route => route.fulfill(envelope(detail)));
  // The token is memory-only: in-app navigation goes through the workbench nav links.
  await page.getByRole('link', { name: '商家台账' }).click();
  await expect(page.getByRole('heading', { name: '商家台账' })).toBeVisible();
  await expect(page.getByText('治理测试商家一')).toBeVisible();
  await expect(page.getByText('****5678')).toBeVisible(); // 子商户号仅后 4 位
  await page.getByRole('link', { name: '详情' }).click();
  await expect(page.getByRole('heading', { name: '商家详情' })).toBeVisible();
  await expect(page.getByText('旗舰一店')).toBeVisible();
  await expect(page.getByText('merchant-agreement-v1')).toBeVisible();
  expect(calls.every(call => call.authorization === 'Bearer fixture-admin-token')).toBe(true);
  expect(calls[0]?.path.startsWith('/api/v1/admin/governance/merchants?')).toBe(true);
});

test('A-002 商家治理详情：停用入口（36 号）带二次确认，走 63 号既有路由', async ({ page }) => {
  await signIn(page);
  const disables: { body: unknown; requestId: string | null; path: string }[] = [];
  page.on('request', req => {
    if (req.url().includes('/api/v1/admin/merchants/') && req.url().endsWith('/disable') && req.method() === 'POST') {
      disables.push({ body: req.postDataJSON(), requestId: req.headers()['x-request-id'] ?? null, path: new URL(req.url()).pathname });
    }
  });
  await page.route('**/api/v1/admin/governance/merchants?*', route => route.fulfill(envelope(pageEnvelope([ledgerRow]))));
  await page.route(`**/api/v1/admin/governance/merchants/${MERCHANT}`, route => route.fulfill(envelope(detail)));
  await page.route(`**/api/v1/admin/merchants/${MERCHANT}/disable`, route =>
    route.fulfill({ status: 200, json: { code: 'SUCCESS', message: 'ok', data: { merchantId: MERCHANT, status: 'OFFLINE', occurredAt: '2026-10-16T03:00:00.000Z' }, traceId: 'fixture-governance' } }));
  await page.getByRole('link', { name: '商家台账' }).click();
  await page.locator('tbody tr.ant-table-row').first().getByRole('link', { name: '详情' }).click();
  const section = page.locator('section[aria-label="商家停用"]');
  await expect(section).toBeVisible();
  // Double confirm: submit stays disabled until reason + checkbox are present.
  await expect(section.getByRole('button', { name: '下线该商家' })).toBeDisabled();
  await expect(disables).toHaveLength(0);
  await section.getByLabel('下线原因（必填，10–500 字）').fill('商家长期失联且门店停业，予以立即下线');
  await section.getByRole('checkbox').check();
  await section.getByRole('button', { name: '下线该商家' }).click();
  expect(disables).toHaveLength(1);
  expect(disables[0].body).toEqual({ reason: '商家长期失联且门店停业，予以立即下线' });
  expect(disables[0].requestId).toMatch(/^[0-9a-f-]{36}$/);
  expect(disables[0].path).toBe(`/api/v1/admin/merchants/${MERCHANT}/disable`);
  await expect(section.getByRole('status')).toContainText('已下线');
});

test('A-002 商家治理详情：未授予 merchant.disable 时停用提交 fail-closed', async ({ page }) => {
  await signIn(page, ACTIONS.filter(code => code !== 'merchant.disable'));
  await page.route('**/api/v1/admin/governance/merchants?*', route => route.fulfill(envelope(pageEnvelope([ledgerRow]))));
  await page.route(`**/api/v1/admin/governance/merchants/${MERCHANT}`, route => route.fulfill(envelope(detail)));
  await page.getByRole('link', { name: '商家台账' }).click();
  await page.locator('tbody tr.ant-table-row').first().getByRole('link', { name: '详情' }).click();
  const section = page.locator('section[aria-label="商家停用"]');
  await expect(section).toBeVisible();
  await expect(section.getByText('未授予 merchant.disable')).toBeVisible();
  await expect(section.getByRole('button', { name: '下线该商家' })).toHaveCount(0);
  await expect(section.getByRole('checkbox')).toHaveCount(0);
});

test('A-002 排期查看：三个只读 Tab 与占用事实', async ({ page }) => {
  await signIn(page);
  await page.route('**/api/v1/admin/governance/schedule/windows?*', route => route.fulfill(envelope(pageEnvelope([windowRow]))));
  await page.route('**/api/v1/admin/governance/schedule/staff-windows?*', route => route.fulfill(envelope(pageEnvelope([{
    windowId: '910000000000801', storeId: '910000000000201', staffId: '910000000000401',
    startAt: '2030-01-01T01:00:00.000Z', endAt: '2030-01-01T02:00:00.000Z',
    status: 'AVAILABLE', version: '4', updatedAt: '2026-10-15T08:00:00.000Z',
  }]))));
  await page.route('**/api/v1/admin/governance/schedule/reservations?*', route => route.fulfill(envelope(pageEnvelope([{
    reservationId: '910000000000601', orderId: '910000000000701', storeId: '910000000000201',
    serviceId: '910000000000301', fulfillmentType: 'IN_STORE',
    startAt: '2030-01-01T01:00:00.000Z', endAt: '2030-01-01T02:00:00.000Z',
    pickupStartAt: null, returnStartAt: null, status: 'TEMP_LOCKED',
    lockExpireAt: '2030-01-01T00:30:00.000Z', capacitySnapshot: 3,
    qualifiedStaffCountSnapshot: 2, createdAt: '2026-10-15T08:00:00.000Z',
  }]))));
  await page.getByRole('link', { name: '排期查看' }).click();
  await expect(page.getByRole('heading', { name: '排期查看' })).toBeVisible();
  await expect(page.getByText('910000000000901')).toBeVisible();
  await expect(page.getByText('1/3')).toBeVisible(); // 利用率 = confirmed/capacity
  await page.getByRole('button', { name: '员工排班' }).click();
  await expect(page.getByText('910000000000401')).toBeVisible();
  await page.getByRole('button', { name: '预约占用/锁定' }).click();
  await expect(page.getByText('910000000000601')).toBeVisible();
  await expect(page.getByText('临时锁定')).toBeVisible();
});

test('A-002 员工治理：档案/成员/邀请三视图，手机号恒掩码', async ({ page }) => {
  await signIn(page);
  await page.route(`**/api/v1/admin/governance/merchants/${MERCHANT}/staff?*`, route => route.fulfill(envelope(pageEnvelope([staffRow]))));
  await page.route(`**/api/v1/admin/governance/merchants/${MERCHANT}/staff-members?*`, route => route.fulfill(envelope(pageEnvelope([memberRow]))));
  await page.route(`**/api/v1/admin/governance/merchants/${MERCHANT}/staff-members/invitations?*`, route => route.fulfill(envelope(pageEnvelope([invitationRow]))));
  await page.getByRole('link', { name: '员工治理' }).click();
  await page.getByLabel('商家编号').fill(MERCHANT);
  await page.getByRole('button', { name: '查询员工' }).click();
  await expect(page.getByText('139****1111')).toBeVisible();
  await page.getByRole('button', { name: '成员绑定' }).click();
  await expect(page.getByText('136****1111')).toBeVisible();
  await expect(page.getByText('merchant.order.verify')).toBeVisible();
  await page.getByRole('button', { name: '邀请记录' }).click();
  await expect(page.getByText('成员丙')).toBeVisible();
  await expect(page.getByText('136****3333')).toBeVisible();
});

test('A-002 fail-closed：未授予动作则入口隐藏，深链回登录（token 仅内存）', async ({ page }) => {
  await signIn(page, ['order.read']);
  await expect(page.getByRole('link', { name: '商家台账' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: '排期查看' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: '员工治理' })).toHaveCount(0);
  // Deep link with no in-memory session fails closed to the login page.
  await page.goto('/governance/merchants');
  await expect(page.getByRole('heading', { name: '运营登录' })).toBeVisible();
});
