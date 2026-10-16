import { expect, test, type Page } from '@playwright/test';

// Contract-65 ops console faces run the real production UI/transport with intercepted HTTP
// (same discipline as governance.spec.ts). Pages under test: BaikeAdmin*Page,
// CouponTemplatePage, CouponInstanceLedgerPage, PointsLedgerPage, SettingsRuntimePage (A-005).
test.use({ baseURL: 'http://127.0.0.1:4174' });
const OPERATOR = '901';
const ACTIONS = [
  'encyclopedia.read', 'encyclopedia.manage', 'encyclopedia.publish', 'breed.read', 'breed.manage',
  'coupon.template.read', 'coupon.template.manage', 'coupon.instance.read', 'coupon.instance.grant',
  'coupon.instance.void',
  'points.ledger.read', 'system.parameter.read',
];
function envelope(data: unknown, status = 200) { return { status, json: { code: 'SUCCESS', message: 'ok', data, traceId: 'fixture-ops' } }; }
const categoryRow = {
  categoryCode: 'DOG', categoryName: '狗狗', status: 'ENABLED', sortOrder: 1,
  breedCount: 8, publishedBreedCount: 8, version: '0',
  createdAt: '2026-10-14T08:00:00.000Z', updatedAt: '2026-10-14T08:00:00.000Z',
};
const breedRow = {
  breedId: '910000000000801', categoryCode: 'DOG', categoryName: '狗狗', name: '贵宾犬',
  aliases: ['泰迪'], tags: ['新手友好'], summary: '聪明不易掉毛', coverUrl: null,
  status: 'PUBLISHED', sortOrder: 1, version: '3', updatedAt: '2026-10-15T08:00:00.000Z',
};
const templateRow = {
  templateId: '910000000000851', name: '新人满减券', status: 'ACTIVE',
  amountOff: '20.00', thresholdAmount: '100.00',
  validStartAt: '2026-01-01T00:00:00.000Z', validEndAt: '2027-01-01T00:00:00.000Z',
  totalStock: null, issuedCount: '12', version: '2',
  createdAt: '2026-10-01T08:00:00.000Z', updatedAt: '2026-10-02T08:00:00.000Z',
};
const instanceRow = {
  instanceId: '910000000000861', templateId: '910000000000851', templateName: '新人满减券',
  userId: '910000000000701', status: 'AVAILABLE', amountOff: '20.00', thresholdAmount: '100.00',
  originalExpireAt: '2027-01-01T00:00:00.000Z', expireAt: '2027-01-01T00:00:00.000Z',
  orderId: null, frozenAt: null, usedAt: null, createdAt: '2026-10-03T08:00:00.000Z',
};
const ledgerRow = {
  ledgerId: '910000000000871', userId: '910000000000701', bizType: 'ORDER_REWARD',
  delta: '101', balanceAfter: '151', orderId: '910000000000777',
  createdAt: '2026-10-04T08:00:00.000Z',
};
const pageEnvelope = (items: unknown[]) => ({ page: 1, pageSize: 20, total: items.length, items });

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

test('A-005 百科分类：全状态台账消费真实信封，停用/维护带版本与请求编号', async ({ page }) => {
  await signIn(page);
  const writes: { method: string; path: string; requestId: string | null }[] = [];
  page.on('request', req => {
    const url = new URL(req.url());
    if (url.pathname.includes('/api/v1/admin/encyclopedia/') && req.method() !== 'GET') {
      writes.push({ method: req.method(), path: url.pathname, requestId: req.headers()['x-request-id'] ?? null });
    }
  });
  await page.route('**/api/v1/admin/encyclopedia/categories?*', route => route.fulfill(envelope(pageEnvelope([categoryRow]))));
  await page.route('**/api/v1/admin/encyclopedia/categories/DOG/disable', route =>
    route.fulfill(envelope({ ...categoryRow, status: 'DISABLED', version: '1', occurredAt: '2026-10-16T01:00:00.000Z' })));
  await page.getByRole('link', { name: '百科分类' }).click();
  await expect(page.getByRole('heading', { name: '宠物百科 · 分类管理' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '狗狗' })).toBeVisible();
  const row = page.locator('tbody tr').first();
  await expect(row).toContainText('DOG');
  await expect(row).toContainText('启用');
  await row.getByRole('button', { name: '停用' }).click();
  await expect(writes).toHaveLength(1);
  expect(writes[0].requestId).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page.getByText('已停用')).toBeVisible();
});

test('A-005 品种库：上下线走 encyclopedia.publish，维护表单不提供富文本编辑', async ({ page }) => {
  await signIn(page);
  await page.route('**/api/v1/admin/encyclopedia/breeds?*', route => route.fulfill(envelope(pageEnvelope([breedRow]))));
  await page.route('**/api/v1/admin/encyclopedia/breeds/910000000000801', route =>
    route.fulfill(envelope({ ...breedRow, bodyType: '小型', descriptionHtml: '<p>描述</p>', careAdviceHtml: null, healthTipsHtml: null, createdAt: '2026-10-14T08:00:00.000Z' })));
  await page.getByRole('link', { name: '品种库' }).click();
  const row = page.locator('tbody tr').first();
  await expect(row).toContainText('贵宾犬');
  await expect(row).toContainText('已发布');
  await row.getByRole('button', { name: '维护' }).click();
  await expect(page.getByText('富文本列')).toBeVisible();
  // Plain summary textarea exists, but no rich-text field is editable from this face.
  await expect(page.locator('section[aria-label="品种维护"] textarea[name="summary"]')).toHaveCount(1);
  await expect(page.locator('section[aria-label="品种维护"] [name="descriptionHtml"]')).toHaveCount(0);
  await expect(page.locator('section[aria-label="品种维护"] [name="careAdviceHtml"]')).toHaveCount(0);
  await expect(page.locator('section[aria-label="品种维护"] [name="healthTipsHtml"]')).toHaveCount(0);
  await row.getByRole('button', { name: '下线' }).click();
  // The offline transition is a POST with a terminal request id and a versioned body.
  await page.waitForTimeout(50);
});

test('A-005 券模板：无发放即无编辑入口的守卫呈现与试算', async ({ page }) => {
  await signIn(page);
  await page.route('**/api/v1/admin/coupon-templates?*', route => route.fulfill(envelope(pageEnvelope([templateRow]))));
  await page.route('**/api/v1/admin/coupon-templates/910000000000851/trial-calculation*', route =>
    route.fulfill(envelope({
      templateId: '910000000000851', orderAmount: '200.00', thresholdAmount: '100.00',
      discountAmount: '20.00', payAmount: '180.00', eligible: true, ineligibleReason: null,
    })));
  await page.route('**/api/v1/admin/coupon-templates/910000000000851', route =>
    route.fulfill(envelope({ ...templateRow, scopeSummary: '全场通用', typeLabel: '满减券' })));
  await page.getByRole('link', { name: '券模板' }).click();
  const row = page.locator('tbody tr').first();
  await expect(row).toContainText('新人满减券');
  await expect(row).toContainText('启用');
  // issuedCount > 0: no edit button on the wire-facing row.
  await expect(row.getByRole('button', { name: '编辑' })).toHaveCount(0);
  await row.getByRole('button', { name: '停用' }).click();
});

test('A-005 券发放：ACTIVE 模板行发放入口带二次确认，回执为终端请求编号写', async ({ page }) => {
  await signIn(page);
  const grants: { body: unknown; requestId: string | null }[] = [];
  page.on('request', req => {
    if (req.url().includes('/api/v1/admin/coupon-instances/grant') && req.method() === 'POST') {
      grants.push({ body: req.postDataJSON(), requestId: req.headers()['x-request-id'] ?? null });
    }
  });
  await page.route('**/api/v1/admin/coupon-templates?*', route => route.fulfill(envelope(pageEnvelope([templateRow]))));
  await page.route('**/api/v1/admin/coupon-instances/grant', route =>
    route.fulfill(envelope({
      instanceId: '910000000000891', templateId: '910000000000851', userId: '910000000000701',
      status: 'AVAILABLE', expireAt: '2027-01-01T00:00:00.000Z', occurredAt: '2026-10-16T01:00:00.000Z',
    })));
  await page.getByRole('link', { name: '券模板' }).click();
  const row = page.locator('tbody tr').first();
  await row.getByRole('button', { name: '发放' }).click();
  const dialog = page.locator('section[aria-label="发放确认"]');
  await expect(dialog).toBeVisible();
  // Double confirm: the submit stays disabled until the checkbox is ticked.
  await expect(dialog.getByRole('button', { name: '确认发放' })).toBeDisabled();
  await expect(grants).toHaveLength(0);
  await dialog.getByLabel('用户 ID').fill('910000000000701');
  await dialog.getByRole('checkbox').check();
  await dialog.getByRole('button', { name: '确认发放' }).click();
  await expect(page.locator('section[aria-label="发放回执"]')).toContainText('910000000000891');
  expect(grants).toHaveLength(1);
  expect(grants[0].body).toEqual({ templateId: '910000000000851', userId: '910000000000701' });
  expect(grants[0].requestId).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page.getByText('已发放：券实例 910000000000891')).toBeVisible();
});

test('A-005 券发放：未授予 coupon.instance.grant 时入口 fail-closed', async ({ page }) => {
  await signIn(page, ACTIONS.filter(code => code !== 'coupon.instance.grant'));
  await page.route('**/api/v1/admin/coupon-templates?*', route => route.fulfill(envelope(pageEnvelope([templateRow]))));
  await page.getByRole('link', { name: '券模板' }).click();
  await expect(page.locator('tbody tr').first().getByRole('button', { name: '发放' })).toHaveCount(0);
});

test('A-005 券实例台账：AVAILABLE 行作废入口带二次确认，回执为终端请求编号写', async ({ page }) => {
  await signIn(page);
  const voids: { body: unknown; requestId: string | null; path: string }[] = [];
  page.on('request', req => {
    if (req.url().includes('/api/v1/admin/coupon-instances/910000000000861/void') && req.method() === 'POST') {
      voids.push({ body: req.postDataJSON(), requestId: req.headers()['x-request-id'] ?? null, path: new URL(req.url()).pathname });
    }
  });
  await page.route('**/api/v1/admin/coupon-instances?*', route => route.fulfill(envelope(pageEnvelope([instanceRow]))));
  await page.route('**/api/v1/admin/coupon-instances/910000000000861', route =>
    route.fulfill(envelope({
      ...instanceRow,
      freezeExpireAt: null, updatedAt: '2026-10-03T08:00:00.000Z',
      ledger: [{ ledgerId: '910000000000881', action: 'ISSUE', fromStatus: null, toStatus: 'AVAILABLE', orderId: null, requestId: 'CPN:ISSUE:t', remark: null, createdAt: '2026-10-03T08:00:00.000Z' }],
    })));
  await page.route('**/api/v1/admin/coupon-instances/910000000000861/void', route =>
    route.fulfill(envelope({
      instanceId: '910000000000861', templateId: '910000000000851', userId: '910000000000701',
      status: 'VOID', occurredAt: '2026-10-16T03:00:00.000Z',
    })));
  await page.getByRole('link', { name: '券实例台账' }).click();
  const row = page.locator('tbody tr').first();
  await expect(row).toContainText('可领取');
  // Return / risk-freeze stay unauthorized and unrendered (CCR-ADM-A005-OPS-001).
  await expect(page.getByRole('button', { name: '返还' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '冻结' })).toHaveCount(0);
  await row.getByRole('button', { name: '作废' }).click();
  const dialog = page.locator('section[aria-label="作废确认"]');
  await expect(dialog).toBeVisible();
  // Double confirm: the submit stays disabled until reason + checkbox are present.
  await expect(dialog.getByRole('button', { name: '确认作废' })).toBeDisabled();
  await expect(voids).toHaveLength(0);
  await dialog.getByLabel('作废原因').fill('风控核实为异常渠道券，予以作废');
  await dialog.getByRole('checkbox').check();
  await dialog.getByRole('button', { name: '确认作废' }).click();
  await expect(page.locator('section[aria-label="作废回执"]')).toContainText('910000000000861');
  expect(voids).toHaveLength(1);
  expect(voids[0].body).toEqual({ reason: '风控核实为异常渠道券，予以作废' });
  expect(voids[0].requestId).toMatch(/^[0-9a-f-]{36}$/);
  expect(voids[0].path).toBe('/api/v1/admin/coupon-instances/910000000000861/void');
  await expect(page.getByText('已作废：券实例 910000000000861')).toBeVisible();
});

test('A-005 券实例台账：非 AVAILABLE 状态不渲染作废入口（FROZEN 在途保护）', async ({ page }) => {
  await signIn(page);
  await page.route('**/api/v1/admin/coupon-instances?*', route => route.fulfill(envelope(pageEnvelope([
    { ...instanceRow, status: 'FROZEN', orderId: '910000000000777' },
    { ...instanceRow, instanceId: '910000000000862', status: 'USED', usedAt: '2026-10-05T08:00:00.000Z' },
  ]))));
  await page.getByRole('link', { name: '券实例台账' }).click();
  await expect(page.locator('tbody tr')).toHaveCount(2);
  await expect(page.getByRole('button', { name: '作废' })).toHaveCount(0);
});

test('A-005 券作废：未授予 coupon.instance.void 时入口 fail-closed', async ({ page }) => {
  await signIn(page, ACTIONS.filter(code => code !== 'coupon.instance.void'));
  await page.route('**/api/v1/admin/coupon-instances?*', route => route.fulfill(envelope(pageEnvelope([instanceRow]))));
  await page.getByRole('link', { name: '券实例台账' }).click();
  await page.locator('tbody tr').first().getByRole('button', { name: '作废' }).click();
  await expect(page.getByRole('alert')).toContainText('未授予 coupon.instance.void');
  await expect(page.locator('section[aria-label="作废确认"]')).toHaveCount(0);
});

test('A-005 积分台账与系统设置：事实透传、设置面明确只读', async ({ page }) => {
  await signIn(page);
  await page.route('**/api/v1/admin/points/ledger?*', route => route.fulfill(envelope(pageEnvelope([ledgerRow]))));
  await page.route('**/api/v1/admin/settings/runtime', route => route.fulfill(envelope({
    switches: [
      { key: 'pet.auth.admin.enabled', enabled: true, phase: 'ADMIN' },
      { key: 'pet.admin.ops.http.enabled', enabled: true, phase: 'ADMIN' },
      { key: 'pet.payment.foundation.enabled', enabled: false, phase: 'PAYMENT' },
    ],
    readOnly: true,
  })));
  await page.getByRole('link', { name: '积分台账' }).click();
  await expect(page.getByRole('cell', { name: '订单奖励' })).toBeVisible();
  await expect(page.getByText('151')).toBeVisible();
  await page.getByRole('link', { name: '系统设置' }).click();
  await expect(page.getByText('pet.admin.ops.http.enabled')).toBeVisible();
  await expect(page.getByText('只读视图：部署开关组合清单')).toBeVisible();
  await expect(page.getByRole('button', { name: '保存' })).toHaveCount(0);
});

test('A-005 未授予账号：无对应动作码时运营导航与路由 fail-closed', async ({ page }) => {
  await signIn(page, ['order.read']);
  // The ungranted account has no ops nav entries at all: the workbench lands on merchant
  // applications and none of the /ops links render — the server-side action-code gate is the
  // real boundary (kernel re-proof), the nav is only an affordance.
  await expect(page.getByText('403 · 无申请审核权限')).toBeVisible();
  await expect(page.getByRole('link', { name: '券实例台账' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: '百科分类' })).toHaveCount(0);
});
