import { expect, test, type Locator, type Page } from '@playwright/test';

// F-A-02 / F-A-03 (contract 71 v0.3): campaign service-category multi-select fed by the
// service-category dictionary face, the HOT_SERVICE_TOP placement position with its
// platform-wide single-ENABLED limit surfaced as readable 409 text, and the HOT_SERVICE
// home-block target whose targetValue is intentionally blank. Real production UI/transport
// with intercepted HTTP — same discipline as service-category.spec.ts. Every write must
// carry a fresh UUID X-Request-Id (request layer default).
//
// 2026-11 营销中心改版：分区懒加载、新建表单收进抽屉、详情收进抽屉。下面前 9 条沿用改版前的
// 行为断言（请求形状 / UUID / expectedVersion / 分类语义 / 可读报错），只为抽屉补上打开动作；
// 其后是改版新增行为的用例。
test.use({ baseURL: 'http://127.0.0.1:4174' });
const OPERATOR = '902';
const MARKETING = '/api/v1/admin/marketing';
const COUPONS = '/api/v1/admin/coupon-campaigns';
const CATEGORIES = '/api/v1/admin/service-categories';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function envelope(data: unknown, status = 200) {
  return { status, json: { code: 'SUCCESS', message: 'ok', data, traceId: 'fixture-mkt' } };
}
function failure(code: string, status: number, message = code) {
  return { status, json: { code, message, data: null, traceId: 'fixture-mkt' } };
}
const pageEnvelope = (items: unknown[]) => ({ page: 1, pageSize: 50, total: items.length, items });
const receipt = (id: string) => ({ id, status: 'DRAFT', version: '1', pageVersion: 0, occurredAt: '2026-10-30T02:00:00.000Z' });

const categories = [
  { categoryId: '910000000000901', categoryCode: 'BEAUTY', categoryName: '洗护美容', parentCode: null, iconUrl: null, description: null, sortNo: 1, status: 'ENABLED', version: 2, createdAt: '2026-10-30T01:00:00.000Z', updatedAt: '2026-10-30T01:00:00.000Z' },
  { categoryId: '910000000000902', categoryCode: 'FOOD', categoryName: '主粮零食', parentCode: null, iconUrl: null, description: null, sortNo: 2, status: 'ENABLED', version: 1, createdAt: '2026-10-30T01:00:00.000Z', updatedAt: '2026-10-30T01:00:00.000Z' },
];

const campaign = {
  campaignId: '7100000000000001', campaignCode: 'CAMP-01', name: '秋季美容活动',
  ownerType: 'PLATFORM', campaignType: 'DISPLAY', participationMode: 'PLATFORM_SELECTED',
  status: 'DRAFT', title: '秋季洗护节', subtitle: null, tagCode: null, tagName: null, coverUrl: null,
  cityCodes: [] as string[], serviceCategoryCodes: ['BEAUTY', 'LEGACY_OLD'],
  startAt: '2026-11-01T00:00:00.000Z', endAt: '2026-11-30T00:00:00.000Z',
  enrollStartAt: null, enrollEndAt: null, enrollmentQuota: null, couponCampaignId: null,
  pageVersion: 0, storeCount: 0, sortWeight: 0, version: '7',
  createdAt: '2026-10-30T01:00:00.000Z', updatedAt: '2026-10-30T01:00:00.000Z',
};
const campaignDetail = { campaign, stores: [], pageSchema: null, publishedPageSchema: null, pageVersions: [] };

const placement = {
  placementId: '7200000000000001', positionCode: 'HOT_SERVICE_TOP', campaignId: campaign.campaignId,
  campaignTitle: campaign.name, cityCode: null, titleOverride: null, subtitleOverride: null,
  imageUrlOverride: null, templateCode: null, sortWeight: 0,
  startAt: '2026-11-01T00:00:00.000Z', endAt: '2026-11-30T00:00:00.000Z',
  status: 'DISABLED', version: '3',
};

const store = {
  storeId: '7300000000000001', merchantId: '7400000000000001', storeName: '幸福路宠物店', merchantName: '幸福宠物',
  cityCode: 'shanghai', address: '幸福路 1 号', promotion: null,
};

type Write = { method: string; path: string; requestId: string | null; body: Record<string, unknown> | null };

function captureWrites(page: Page, root: string): Write[] {
  const writes: Write[] = [];
  page.on('request', req => {
    const url = new URL(req.url());
    if (url.pathname.startsWith(root) && req.method() !== 'GET') {
      let body: Record<string, unknown> | null = null;
      try { body = JSON.parse(req.postData() ?? 'null') as Record<string, unknown>; } catch { body = null; }
      writes.push({ method: req.method(), path: url.pathname, requestId: req.headers()['x-request-id'] ?? null, body });
    }
  });
  return writes;
}

const BASE_PERMISSIONS = ['marketing.campaign.read', 'marketing.campaign.manage', 'marketing.campaign.publish'];

async function signIn(page: Page, extraPermissions: string[] = []) {
  await page.route('**/api/v1/admin/auth/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/attempts')) return route.fulfill(envelope({ attemptId: '801', attemptToken: 'fixture-binding', expiresAt: '2026-10-31T02:00:00.000Z', nextStep: 'PROVE_IDENTITY' }, 201));
    if (path.endsWith('/requirements')) return route.fulfill(envelope({ requiredVerification: 'NONE' }));
    if (path.endsWith('/login')) return route.fulfill(envelope({ sessionId: 's1', operatorId: OPERATOR, audience: 'ADMIN_WEB', tokenType: 'Bearer', accessToken: 'fixture-admin-token', expiresAt: '2026-10-31T02:00:00.000Z' }));
    if (path.endsWith('/session')) return route.fulfill(envelope({ sessionId: 's1', operatorId: OPERATOR, audience: 'ADMIN_WEB', expiresAt: '2026-10-31T02:00:00.000Z', idleExpiresAt: '2026-10-31T01:00:00.000Z', authzVersion: 'v1' }));
    if (path.endsWith('/permissions')) return route.fulfill(envelope({ operatorId: OPERATOR, authzVersion: 'v1', checkedAt: '2026-10-30T02:00:00.000Z', roles: ['OPERATOR'], dataScope: 'ALL', actionCodes: [...BASE_PERMISSIONS, ...extraPermissions] }));
    return route.fulfill(envelope(null));
  });
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill('fixture-ops');
  await page.getByLabel('密码', { exact: true }).fill('fixture-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('link', { name: '营销活动' }).click();
  await expect(page.getByRole('heading', { name: '营销活动中心' })).toBeVisible();
}

/** Fixture wiring for every read the marketing center can perform (no unhandled 404 noise). */
async function stubReads(page: Page, overrides: {
  campaigns?: unknown[]; placements?: unknown[]; categories?: unknown[]; stores?: unknown[];
  categoryFailure?: { code: string; status: number };
} = {}) {
  await page.route(`**${MARKETING}/campaigns?*`, route => route.fulfill(envelope(pageEnvelope(overrides.campaigns ?? []))));
  await page.route(`**${MARKETING}/campaigns/${campaign.campaignId}`, route => route.fulfill(envelope(campaignDetail)));
  await page.route(`**${MARKETING}/stores?*`, route => route.fulfill(envelope(pageEnvelope(overrides.stores ?? []))));
  await page.route(`**${MARKETING}/placements?*`, route => route.fulfill(envelope(pageEnvelope(overrides.placements ?? []))));
  await page.route(`**${MARKETING}/home-blocks?*`, route => route.fulfill(envelope(pageEnvelope([]))));
  await page.route(`**${MARKETING}/enrollments?*`, route => route.fulfill(envelope(pageEnvelope([]))));
  await page.route(`**${COUPONS}?*`, route => route.fulfill(envelope(pageEnvelope([]))));
  await page.route(`**${COUPONS}/*/claims?*`, route => route.fulfill(envelope(pageEnvelope([]))));
  await page.route('**/api/v1/admin/coupon-templates?*', route => route.fulfill(envelope({ page: 1, pageSize: 20, total: 0, items: [] })));
  await page.route(`**${CATEGORIES}?*`, route => {
    if (overrides.categoryFailure) return route.fulfill(failure(overrides.categoryFailure.code, overrides.categoryFailure.status));
    return route.fulfill(envelope(pageEnvelope(overrides.categories ?? categories)));
  });
}

const section = (page: Page, title: string) => page.locator('section').filter({ has: page.getByRole('heading', { name: title }) });
const nav = (page: Page) => page.getByRole('navigation', { name: '营销中心分区' });
const navStatus = (page: Page) => nav(page).getByRole('status');
const navAlert = (page: Page) => nav(page).getByRole('alert');
const categoryBox = (scope: Locator, code: string) => scope.locator(`input[name="serviceCategoryCode"][value="${code}"]`);
/** 新建表单在抽屉里：先点列表工具栏上的新建按钮。 */
const openCreate = (page: Page, button: string) => page.getByRole('button', { name: button, exact: true }).click();
/** 列表里的活动行：按角色定位（antd Table 表体内有一行 aria-hidden 的列宽测量行，不能按 tbody tr 取第一行）。 */
const campaignRow = (page: Page) => page.getByRole('row').filter({ hasText: campaign.name });

test('F-A-02 创建活动：分类多选取自字典（启用项）并随表单提交', async ({ page }) => {
  const writes = captureWrites(page, MARKETING);
  const categoryReads: string[] = [];
  await stubReads(page);
  await page.route(`**${CATEGORIES}?*`, route => {
    categoryReads.push(new URL(route.request().url()).search);
    return route.fulfill(envelope(pageEnvelope(categories)));
  });
  await page.route(`**${MARKETING}/campaigns`, route => route.fulfill(envelope(receipt('7100000000000009'))));
  await signIn(page);
  await openCreate(page, '新建活动');
  const form = section(page, '新建活动');
  await expect(categoryBox(form, 'BEAUTY')).toBeVisible();
  await expect(categoryBox(form, 'FOOD')).toBeVisible();
  expect(categoryReads.length).toBeGreaterThanOrEqual(1);
  expect(categoryReads[0]).toContain('status=ENABLED');
  await expect(form.getByText(/不勾选任何分类 = 活动面向全部分类/)).toBeVisible();
  await form.getByLabel('活动名称（2-100）').fill('秋季美容活动');
  await form.getByLabel('标题（1-100）').fill('秋季洗护节');
  await categoryBox(form, 'BEAUTY').check();
  await categoryBox(form, 'FOOD').check();
  await form.getByLabel('开始时间').fill('2026-11-01T09:00');
  await form.getByLabel('结束时间').fill('2026-11-30T18:00');
  await form.getByRole('button', { name: '创建草稿' }).click();
  await expect(navStatus(page)).toContainText('活动草稿已创建');
  const posts = writes.filter(w => w.method === 'POST' && w.path === `${MARKETING}/campaigns`);
  expect(posts).toHaveLength(1);
  expect(posts[0].requestId).toMatch(UUID);
  expect(posts[0].body?.serviceCategoryCodes).toEqual(['BEAUTY', 'FOOD']);
  // No hand-typed category field anymore.
  expect(await form.locator('input[name="serviceCategoryCodes"]').count()).toBe(0);
});

test('F-A-02 空数组=全部：不勾选任何分类提交空列表', async ({ page }) => {
  const writes = captureWrites(page, MARKETING);
  await stubReads(page);
  await page.route(`**${MARKETING}/campaigns`, route => route.fulfill(envelope(receipt('7100000000000010'))));
  await signIn(page);
  await openCreate(page, '新建活动');
  const form = section(page, '新建活动');
  await expect(categoryBox(form, 'BEAUTY')).toBeVisible();
  await form.getByLabel('活动名称（2-100）').fill('全分类活动');
  await form.getByLabel('标题（1-100）').fill('全分类');
  await form.getByLabel('开始时间').fill('2026-11-01T09:00');
  await form.getByLabel('结束时间').fill('2026-11-30T18:00');
  await form.getByRole('button', { name: '创建草稿' }).click();
  await expect(navStatus(page)).toContainText('活动草稿已创建');
  const posts = writes.filter(w => w.method === 'POST' && w.path === `${MARKETING}/campaigns`);
  expect(posts).toHaveLength(1);
  expect(posts[0].body?.serviceCategoryCodes).toEqual([]);
});

test('F-A-02 编辑活动：字典外既有编码保留勾选，未改动不丢数据', async ({ page }) => {
  const writes = captureWrites(page, MARKETING);
  await stubReads(page, { campaigns: [campaign] });
  await page.route(`**${MARKETING}/campaigns/${campaign.campaignId}`, route => {
    if (route.request().method() === 'PUT') return route.fulfill(envelope({ ...receipt(campaign.campaignId), version: '8' }));
    return route.fulfill(envelope(campaignDetail));
  });
  await signIn(page);
  await campaignRow(page).getByRole('button', { name: '详情' }).click();
  const detail = page.locator('section[aria-label="活动详情"]');
  await expect(detail).toBeVisible();
  await detail.getByText('编辑活动基础信息').click();
  const form = detail.locator('form');
  await expect(categoryBox(form, 'BEAUTY')).toBeChecked();
  await expect(categoryBox(form, 'FOOD')).not.toBeChecked();
  await expect(categoryBox(form, 'LEGACY_OLD')).toBeChecked();
  await expect(form.getByText('LEGACY_OLD（字典外或已停用）')).toBeVisible();
  await form.getByRole('button', { name: '保存基础信息' }).click();
  await expect(navStatus(page)).toContainText('活动基础信息已保存');
  const puts = writes.filter(w => w.method === 'PUT' && w.path === `${MARKETING}/campaigns/${campaign.campaignId}`);
  expect(puts).toHaveLength(1);
  expect(puts[0].requestId).toMatch(UUID);
  expect(puts[0].body?.expectedVersion).toBe('7');
  expect(((puts[0].body?.serviceCategoryCodes ?? []) as string[]).sort()).toEqual(['BEAUTY', 'LEGACY_OLD']);
});

test('F-A-02 SERVICE_CATEGORY_INVALID 400：可读中文提示，不裸抛错误码', async ({ page }) => {
  await stubReads(page);
  await page.route(`**${MARKETING}/campaigns`, route => route.fulfill(failure('SERVICE_CATEGORY_INVALID', 400)));
  await signIn(page);
  await openCreate(page, '新建活动');
  const form = section(page, '新建活动');
  await expect(categoryBox(form, 'BEAUTY')).toBeVisible();
  await form.getByLabel('活动名称（2-100）').fill('分类失效活动');
  await form.getByLabel('标题（1-100）').fill('分类失效');
  await categoryBox(form, 'BEAUTY').check();
  await form.getByLabel('开始时间').fill('2026-11-01T09:00');
  await form.getByLabel('结束时间').fill('2026-11-30T18:00');
  await form.getByRole('button', { name: '创建草稿' }).click();
  const alert = navAlert(page);
  await expect(alert).toContainText('服务分类');
  await expect(alert).toContainText('不存在或未启用');
  await expect(alert).not.toContainText('SERVICE_CATEGORY_INVALID');
});

test('F-A-02 分类列表失败：可读失败态与重试，成功后选项出现', async ({ page }) => {
  await stubReads(page, { categoryFailure: { code: 'COMMON_INTERNAL_ERROR', status: 500 } });
  await signIn(page);
  await openCreate(page, '新建活动');
  const form = section(page, '新建活动');
  const pickerAlert = form.getByRole('alert');
  await expect(pickerAlert).toContainText('分类列表读取失败');
  await expect(pickerAlert).toContainText('不勾选任何分类 = 面向全部分类');
  await page.route(`**${CATEGORIES}?*`, route => route.fulfill(envelope(pageEnvelope(categories))));
  await pickerAlert.getByRole('button', { name: '重试' }).click();
  await expect(categoryBox(form, 'BEAUTY')).toBeVisible();
  await expect(categoryBox(form, 'FOOD')).toBeVisible();
});

test('F-A-03 投放位：HOT_SERVICE_TOP 可选并随表单提交', async ({ page }) => {
  const writes = captureWrites(page, MARKETING);
  await stubReads(page);
  await page.route(`**${MARKETING}/placements`, route => route.fulfill(envelope(receipt('7200000000000009'))));
  await signIn(page);
  await nav(page).getByRole('button', { name: '投放位' }).click();
  await openCreate(page, '新建投放位');
  const form = section(page, '新建投放位');
  const positionSelect = form.getByLabel('位置');
  await expect(positionSelect.locator('option')).toHaveText([
    '首页活动位（活动钜惠）', '首页 Banner', '商家列表置顶（商家钜惠）', '热门服务顶部 Banner（全平台最多启用 1 个）',
  ]);
  await expect(form.getByText(/热门服务顶部 Banner 全平台最多同时启用 1 个/)).toBeVisible();
  await positionSelect.selectOption('HOT_SERVICE_TOP');
  await form.getByLabel('活动 ID').fill(campaign.campaignId);
  await form.getByLabel('开始时间').fill('2026-11-01T09:00');
  await form.getByLabel('结束时间').fill('2026-11-30T18:00');
  await form.getByRole('button', { name: '创建投放位' }).click();
  await expect(navStatus(page)).toContainText('投放位已创建');
  const posts = writes.filter(w => w.method === 'POST' && w.path === `${MARKETING}/placements`);
  expect(posts).toHaveLength(1);
  expect(posts[0].requestId).toMatch(UUID);
  expect(posts[0].body?.positionCode).toBe('HOT_SERVICE_TOP');
});

test('F-A-03 投放位编辑：PUT 携带 expectedVersion 与 requestId', async ({ page }) => {
  const writes = captureWrites(page, MARKETING);
  await stubReads(page, { placements: [placement] });
  await page.route(`**${MARKETING}/placements/${placement.placementId}`, route => {
    if (route.request().method() === 'PUT') return route.fulfill(envelope(receipt(placement.placementId)));
    return route.continue();
  });
  await signIn(page);
  await nav(page).getByRole('button', { name: '投放位' }).click();
  await page.getByRole('button', { name: '编辑', exact: true }).click();
  const form = section(page, `编辑投放位：${placement.placementId}`);
  await expect(form.getByLabel('活动 ID')).toHaveValue(placement.campaignId);
  await form.getByLabel('标题覆盖', { exact: true }).fill('新标题');
  await form.getByRole('button', { name: '保存修改' }).click();
  await expect(navStatus(page)).toContainText('投放位已保存');
  const puts = writes.filter(w => w.method === 'PUT' && w.path === `${MARKETING}/placements/${placement.placementId}`);
  expect(puts).toHaveLength(1);
  expect(puts[0].requestId).toMatch(UUID);
  expect(puts[0].body?.expectedVersion).toBe('3');
  expect(puts[0].body?.titleOverride).toBe('新标题');
});

test('F-A-03 启用 HOT_SERVICE_TOP 超限：409 给出全平台最多 1 条的可读提示', async ({ page }) => {
  await stubReads(page, { placements: [placement] });
  let enables = 0;
  await page.route(`**${MARKETING}/placements/${placement.placementId}/enable`, route => {
    enables++;
    return route.fulfill(failure('PLACEMENT_LIMIT_EXCEEDED', 409));
  });
  await signIn(page);
  await nav(page).getByRole('button', { name: '投放位' }).click();
  await page.getByRole('button', { name: '启用', exact: true }).click();
  const alert = navAlert(page);
  await expect(alert).toContainText('热门服务顶部 Banner 全平台最多同时启用 1 个');
  await expect(alert).not.toContainText('PLACEMENT_LIMIT_EXCEEDED');
  expect(enables).toBe(1);
});

test('F-A-03 首页内容位：HOT_SERVICE 目标留空禁用输入且不提交 targetValue', async ({ page }) => {
  const writes = captureWrites(page, MARKETING);
  await stubReads(page);
  await page.route(`**${MARKETING}/home-blocks`, route => route.fulfill(envelope(receipt('7300000000000009'))));
  await signIn(page);
  await nav(page).getByRole('button', { name: '首页内容位' }).click();
  await openCreate(page, '新建首页内容位');
  const form = section(page, '新建首页内容位');
  // Non-regression: all prior target types stay selectable, HOT_SERVICE appended.
  await expect(form.getByLabel('落地类型').locator('option')).toHaveText([
    '服务目录', '服务分类关键词', '门店', '单次服务', '活动', '宠友圈', '小程序路径', '无跳转', '热门服务',
  ]);
  const targetValue = form.getByLabel(/目标值/);
  await expect(targetValue).toBeEnabled();
  await form.getByLabel('落地类型').selectOption('HOT_SERVICE');
  await expect(form.getByLabel('目标值（此类型留空即可，后端不保存目标值）')).toBeDisabled();
  await form.getByLabel('内容位编码（2-64，字母数字/下划线/短横线）').fill('hot_service_banner_01');
  await form.getByLabel('标题', { exact: true }).fill('本周热门服务');
  await form.getByLabel('开始时间').fill('2026-11-01T09:00');
  await form.getByLabel('结束时间').fill('2026-11-30T18:00');
  await form.getByRole('button', { name: '创建草稿' }).click();
  await expect(navStatus(page)).toContainText('首页内容位草稿已创建');
  const posts = writes.filter(w => w.method === 'POST' && w.path === `${MARKETING}/home-blocks`);
  expect(posts).toHaveLength(1);
  expect(posts[0].requestId).toMatch(UUID);
  expect(posts[0].body?.targetType).toBe('HOT_SERVICE');
  expect(posts[0].body).not.toHaveProperty('targetValue');
});

// ---------------------------------------------------------------------------
// 改版新增行为
// ---------------------------------------------------------------------------

test('分区懒加载：进页面只拉活动列表，切到投放位才拉投放位（权限报错只落在对应分区）', async ({ page }) => {
  await stubReads(page);
  const reads: string[] = [];
  page.on('request', req => {
    const path = new URL(req.url()).pathname;
    if (req.method() === 'GET' && path.startsWith(MARKETING)) reads.push(path);
  });
  await signIn(page);
  await expect(page.getByRole('columnheader', { name: '活动', exact: true })).toBeVisible();
  expect(reads).toContain(`${MARKETING}/campaigns`);
  expect(reads).not.toContain(`${MARKETING}/placements`);
  expect(reads).not.toContain(`${MARKETING}/enrollments`);
  await nav(page).getByRole('button', { name: '投放位' }).click();
  await expect.poll(() => reads.includes(`${MARKETING}/placements`)).toBe(true);
  expect(reads).not.toContain(`${MARKETING}/enrollments`);
});

test('列表优先：进页面第一屏是列表与工具栏，新建表单不在页面上铺开', async ({ page }) => {
  await stubReads(page, { campaigns: [campaign] });
  await signIn(page);
  await expect(page.getByRole('button', { name: '新建活动', exact: true })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: '活动', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '创建草稿' })).toHaveCount(0);
  // 状态页签按生命周期给出入口，待审核即运营的处理队列。
  await expect(page.getByRole('tab', { name: '待审核' })).toBeVisible();
});

test('危险操作需二次确认：点「结束」不发请求，确认后才发出并带 expectedVersion', async ({ page }) => {
  const online = { ...campaign, status: 'ONLINE', serviceCategoryCodes: [], storeCount: 2 };
  const writes = captureWrites(page, MARKETING);
  await stubReads(page, { campaigns: [online] });
  await page.route(`**${MARKETING}/campaigns/${campaign.campaignId}`, route => route.fulfill(envelope({ ...campaignDetail, campaign: online })));
  await page.route(`**${MARKETING}/campaigns/${campaign.campaignId}/end`, route =>
    route.fulfill(envelope({ id: campaign.campaignId, status: 'ENDED', version: '8', pageVersion: 1, occurredAt: '2026-10-30T02:00:00.000Z' })));
  await signIn(page);
  const row = campaignRow(page);
  await row.getByRole('button', { name: '结束', exact: true }).click();
  const confirm = page.getByRole('button', { name: '确认结束' });
  await expect(confirm).toBeVisible();
  expect(writes.filter(w => w.path.endsWith('/end'))).toHaveLength(0);
  await confirm.click();
  await expect(navStatus(page)).toContainText('活动已结束');
  const ends = writes.filter(w => w.path.endsWith('/end'));
  expect(ends).toHaveLength(1);
  expect(ends[0].requestId).toMatch(UUID);
  expect(ends[0].body?.expectedVersion).toBe('7');
});

test('驳回走弹窗：不填原因不能确认，填写后携带原因与 expectedVersion 提交（不再用 window.prompt）', async ({ page }) => {
  const merchantPending = { ...campaign, ownerType: 'MERCHANT', status: 'PENDING_REVIEW', serviceCategoryCodes: [] };
  const writes = captureWrites(page, MARKETING);
  await stubReads(page, { campaigns: [merchantPending] });
  let prompts = 0;
  page.on('dialog', async dialog => { prompts++; await dialog.dismiss(); });
  await page.route(`**${MARKETING}/campaigns/${campaign.campaignId}/reject`, route =>
    route.fulfill(envelope({ id: campaign.campaignId, status: 'REJECTED', version: '8', pageVersion: 0, occurredAt: '2026-10-30T02:00:00.000Z' })));
  await signIn(page);
  await campaignRow(page).getByRole('button', { name: '驳回', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  const confirm = dialog.getByRole('button', { name: '确认驳回' });
  await expect(confirm).toBeDisabled();
  await dialog.getByPlaceholder('请填写驳回原因，对方会看到这段文字').fill('活动素材不符合规范');
  await confirm.click();
  await expect(navStatus(page)).toContainText('活动已驳回');
  expect(prompts).toBe(0);
  const rejects = writes.filter(w => w.path.endsWith('/reject'));
  expect(rejects).toHaveLength(1);
  expect(rejects[0].requestId).toMatch(UUID);
  expect(rejects[0].body).toMatchObject({ reason: '活动素材不符合规范', expectedVersion: '7' });
});

test('失败原因说人话：活动发布被拒时透出服务端的具体原因，而不是投放位那句或裸错误码', async ({ page }) => {
  await stubReads(page, { campaigns: [campaign] });
  await page.route(`**${MARKETING}/campaigns/${campaign.campaignId}/publish`, route =>
    route.fulfill(failure('CAMPAIGN_LANDING_INVALID', 409, '活动至少需要一家可见参与门店')));
  await signIn(page);
  await campaignRow(page).getByRole('button', { name: '发布', exact: true }).click();
  const alert = navAlert(page);
  await expect(alert).toContainText('活动至少需要一家可见参与门店');
  await expect(alert).not.toContainText('CAMPAIGN_LANDING_INVALID');
  await expect(alert).not.toContainText('投放窗口');
});

test('重名冲突不再误导：券活动重名时提示「名称已存在」，而不是「被其他人修改」', async ({ page }) => {
  // 券活动接口在 /coupon-campaigns 下，不在 marketing 前缀里，单独抓。
  const writes = captureWrites(page, COUPONS);
  await stubReads(page);
  const template = {
    templateId: '9100000000000001', name: '新客立减券', status: 'ACTIVE', amountOff: '10.00', thresholdAmount: null,
    validStartAt: '2026-01-01T00:00:00.000Z', validEndAt: '2026-12-31T15:59:59.000Z', totalStock: null,
    issuedCount: '0', version: '1', createdAt: '2026-01-01T00:00:00.000Z', updatedAt: '2026-01-01T00:00:00.000Z',
  };
  await page.route('**/api/v1/admin/coupon-templates?*', route => route.fulfill(envelope({ page: 1, pageSize: 20, total: 1, items: [template] })));
  await page.route(`**${COUPONS}`, route => route.fulfill(failure('COMMON_CONFLICT', 409, '券活动名称已存在')));
  await signIn(page, ['coupon.campaign.manage', 'coupon.campaign.publish']);
  await nav(page).getByRole('button', { name: '券活动' }).click();
  await openCreate(page, '新建券活动');
  const form = section(page, '新建券活动');
  await form.getByLabel('活动名称（2-50 字，不可与已有券活动重名）').fill('双十一直减');
  await form.getByLabel('券模板').selectOption(template.templateId);
  await form.getByLabel('总发放上限（张）').fill('100');
  await form.getByLabel('预算上限（元）').fill('1000');
  await form.getByLabel('开始时间').fill('2026-11-01T09:00');
  await form.getByLabel('结束时间').fill('2026-11-30T18:00');
  await form.getByRole('button', { name: '创建券活动' }).click();
  const alert = navAlert(page);
  await expect(alert).toContainText('券活动名称已存在');
  await expect(alert).not.toContainText('数据已被其他人修改');
  const posts = writes.filter(w => w.method === 'POST' && w.path === COUPONS);
  expect(posts).toHaveLength(1);
  expect(posts[0].requestId).toMatch(UUID);
  expect(posts[0].body).toMatchObject({ name: '双十一直减', templateId: template.templateId, perUserLimit: 1, totalLimit: 100, budgetLimit: 1000 });
});

test('发布前自检：非招商活动没有参与门店时，详情里直接指出发布会失败的原因', async ({ page }) => {
  await stubReads(page, { campaigns: [campaign] });
  await signIn(page);
  await campaignRow(page).getByRole('button', { name: '详情' }).click();
  const detail = page.locator('section[aria-label="活动详情"]');
  await expect(detail).toBeVisible();
  await expect(detail.getByText('参与门店 0 家（本活动发布至少需要 1 家）')).toBeVisible();
  await expect(detail.locator('li.is-warn')).toHaveCount(1);
});

test('实际展示情况：已过结束时间的「进行中」活动在列表里标出「已到期」', async ({ page }) => {
  const expired = {
    ...campaign, status: 'ONLINE', serviceCategoryCodes: [], storeCount: 1,
    startAt: '2020-01-01T00:00:00.000Z', endAt: '2020-02-01T00:00:00.000Z',
  };
  await stubReads(page, { campaigns: [expired] });
  await signIn(page);
  const row = campaignRow(page);
  await expect(row.getByText('进行中', { exact: true })).toBeVisible();
  await expect(row.getByText('已到期', { exact: true })).toBeVisible();
});

test('投放窗口预检：投放窗口落在活动窗口之外时就地拦下，不发请求并说明原因', async ({ page }) => {
  const writes = captureWrites(page, MARKETING);
  await stubReads(page, { campaigns: [campaign] });
  await signIn(page);
  await nav(page).getByRole('button', { name: '投放位' }).click();
  await openCreate(page, '新建投放位');
  const form = section(page, '新建投放位');
  await form.getByLabel('活动 ID').fill(campaign.campaignId);
  await form.getByLabel('开始时间').fill('2026-12-01T09:00');
  await form.getByLabel('结束时间').fill('2026-12-05T18:00');
  await expect(form.getByText(/当前投放窗口超出了关联活动/)).toBeVisible();
  await form.getByRole('button', { name: '创建投放位' }).click();
  await expect(navAlert(page)).toContainText('投放窗口必须完全落在关联活动「秋季美容活动」的时间窗口内');
  expect(writes.filter(w => w.path === `${MARKETING}/placements`)).toHaveLength(0);
});

test('参与门店：从搜索结果逐家添加后整体保存，携带 expectedVersion（取代「用搜索结果替换门店」）', async ({ page }) => {
  const writes = captureWrites(page, MARKETING);
  await stubReads(page, { campaigns: [campaign], stores: [store] });
  await page.route(`**${MARKETING}/campaigns/${campaign.campaignId}/stores`, route =>
    route.fulfill(envelope({ id: campaign.campaignId, status: campaign.status, version: '8', pageVersion: 0, occurredAt: '2026-10-30T02:00:00.000Z' })));
  await signIn(page);
  await campaignRow(page).getByRole('button', { name: '详情' }).click();
  const detail = page.locator('section[aria-label="活动详情"]');
  await expect(detail).toBeVisible();
  const save = detail.getByRole('button', { name: '保存门店', exact: true });
  await expect(save).toBeDisabled();
  await detail.getByPlaceholder('门店名称关键词').fill('幸福');
  await detail.getByRole('button', { name: '搜索门店' }).click();
  await detail.getByRole('button', { name: '添加', exact: true }).click();
  await expect(detail.getByRole('button', { name: '已加入' })).toBeDisabled();
  // 添加后计数从 0 变 1（搜索结果表里也有同名门店，不能用全文定位）。
  await expect(detail.getByRole('heading', { name: '参与门店（1）' })).toBeVisible();
  await expect(detail.locator('.mkt-entity-name').filter({ hasText: store.storeName })).toBeVisible();
  await save.click();
  await expect(navStatus(page)).toContainText('参与门店已保存');
  const posts = writes.filter(w => w.path === `${MARKETING}/campaigns/${campaign.campaignId}/stores`);
  expect(posts).toHaveLength(1);
  expect(posts[0].requestId).toMatch(UUID);
  expect(posts[0].body?.expectedVersion).toBe('7');
  expect((posts[0].body?.stores as { storeId: string }[]).map(item => item.storeId)).toEqual([store.storeId]);
});

test('首页内容位：落地类型为「门店」时目标值必填（旧界面不标必填，只能提交后报错）', async ({ page }) => {
  await stubReads(page);
  await signIn(page);
  await nav(page).getByRole('button', { name: '首页内容位' }).click();
  await openCreate(page, '新建首页内容位');
  const form = section(page, '新建首页内容位');
  await expect(form.getByLabel(/目标值/)).toHaveJSProperty('required', false);
  await form.getByLabel('落地类型').selectOption('STORE');
  await expect(form.getByLabel(/目标值/)).toHaveJSProperty('required', true);
  await expect(form.getByText(/必填/)).toBeVisible();
});

// ---- 图片读写一体存 OSS Key（2026-11-24 用户裁决）----
// 服务端：coverUrl 是拼好的可访问地址（预览用）；coverStoredKey 是库里存的原始值（编辑表单回显、保存时原样提交）。
// 老后端不返回 coverStoredKey —— 这时回退用 coverUrl（那时它本身就是完整地址），页面照常工作。

const OSS_KEY = 'marketing/fullcard/grooming-clean.png';
const OSS_URL = 'https://mtxoss2.oss-cn-hangzhou.aliyuncs.com/marketing/fullcard/grooming-clean.png';

test('封面存的是 Key：列表缩略图与详情放大镜用服务端拼好的地址，正常显示', async ({ page }) => {
  const keyed = { ...campaign, coverUrl: OSS_URL, coverStoredKey: OSS_KEY };
  await stubReads(page, { campaigns: [keyed] });
  await page.route(`**${MARKETING}/campaigns/${campaign.campaignId}`, route =>
    route.fulfill(envelope({ ...campaignDetail, campaign: keyed })));
  await signIn(page);
  const row = campaignRow(page);
  await expect(row.locator('img.mkt-entity-cover')).toHaveAttribute('src', OSS_URL);
  await expect(row.locator('.mkt-entity-cover-empty')).toHaveCount(0);
  await row.getByRole('button', { name: '详情' }).click();
  const detail = page.locator('section[aria-label="活动详情"]');
  const trigger = detail.getByRole('button', { name: '活动封面' });
  await expect(trigger.locator('img')).toHaveAttribute('src', OSS_URL);
});

test('封面有存储值但服务端没给出可预览地址：占位并说明，绝不裂图', async ({ page }) => {
  // 例：对象存储未配置时，解析器把 Key 原样返回。
  const unresolved = { ...campaign, coverUrl: OSS_KEY, coverStoredKey: OSS_KEY };
  await stubReads(page, { campaigns: [unresolved] });
  await page.route(`**${MARKETING}/campaigns/${campaign.campaignId}`, route =>
    route.fulfill(envelope({ ...campaignDetail, campaign: unresolved })));
  await signIn(page);
  const row = campaignRow(page);
  await expect(row.getByText('已设图片', { exact: true })).toBeVisible();
  expect(await row.locator('img.mkt-entity-cover').count()).toBe(0);
  await row.getByRole('button', { name: '详情' }).click();
  const detail = page.locator('section[aria-label="活动详情"]');
  // 先等详情数据回来（「数据版本」是最后一行），再读「封面」单元格的真实内容。
  // Descriptions 两列一行：th(标签) 与 td(内容) 成对相邻，封面紧跟在「排序权重」之后。
  const labels = detail.locator('.ant-descriptions-item-label');
  await expect(labels.filter({ hasText: /^数据版本$/ })).toBeVisible();
  const coverIndex = await labels.evaluateAll((nodes) => nodes.findIndex(node => node.textContent?.trim() === '封面'));
  expect(coverIndex, '应存在「封面」事实行').toBeGreaterThanOrEqual(0);
  const coverText = await labels.nth(coverIndex).evaluate(node => node.nextElementSibling?.textContent?.trim() ?? '');
  expect(coverText, '封面单元格内容').toBe(`已设置（存储标识「${OSS_KEY}」），服务端暂未给出可预览的地址`);
  expect(await detail.getByRole('button', { name: '活动封面' }).count()).toBe(0);
});

test('老后端（只有 coverUrl、没有 coverStoredKey）：完整地址照常显示缩略图', async ({ page }) => {
  const legacy = { ...campaign, coverUrl: 'https://img.example.invalid/c1.png' };
  await stubReads(page, { campaigns: [legacy] });
  await signIn(page);
  await expect(campaignRow(page).locator('img.mkt-entity-cover')).toHaveAttribute('src', legacy.coverUrl);
});

test('编辑活动只改标题：封面提交的仍是存储 Key，不会被拼好的地址覆盖', async ({ page }) => {
  // 这是「读写一体」要守住的链条：回显 coverStoredKey（Key）→ 保存 → 请求体里的 coverUrl 还是 Key。
  const keyed = { ...campaign, coverUrl: OSS_URL, coverStoredKey: OSS_KEY };
  const writes = captureWrites(page, MARKETING);
  await stubReads(page, { campaigns: [keyed] });
  await page.route(`**${MARKETING}/campaigns/${campaign.campaignId}`, route => {
    if (route.request().method() === 'PUT') return route.fulfill(envelope({ ...receipt(campaign.campaignId), version: '8' }));
    return route.fulfill(envelope({ ...campaignDetail, campaign: keyed }));
  });
  await signIn(page);
  await campaignRow(page).getByRole('button', { name: '详情' }).click();
  const detail = page.locator('section[aria-label="活动详情"]');
  await detail.getByText('编辑活动基础信息').click();
  const form = detail.locator('form');
  // 回显的是存储值（Key），不是拼好的地址
  await expect(form.getByLabel('活动封面', { exact: true })).toHaveValue(OSS_KEY);
  // 编辑表单里能按拼好的地址预览
  await expect(form.locator('img.mkt-asset-preview')).toHaveAttribute('src', OSS_URL);
  await form.getByLabel('标题（1-100）').fill('改个标题');
  await form.getByRole('button', { name: '保存基础信息' }).click();
  await expect(navStatus(page)).toContainText('活动基础信息已保存');
  const puts = writes.filter(w => w.method === 'PUT' && w.path === `${MARKETING}/campaigns/${campaign.campaignId}`);
  expect(puts).toHaveLength(1);
  expect(puts[0].body?.coverUrl).toBe(OSS_KEY);
});

test('上传封面后表单回填的是对象存储 Key（不是地址），并能预览', async ({ page }) => {
  await stubReads(page);
  await page.route(`**${MARKETING}/assets`, route => route.fulfill(envelope({
    assetId: '7900000000000001', url: 'http://127.0.0.1:18081/api/v1/c/marketing/assets/7900000000000001',
    objectKey: 'marketing-assets/7900000000000001.png', previewUrl: 'https://mtxoss2.oss-cn-hangzhou.aliyuncs.com/marketing-assets/7900000000000001.png',
    contentType: 'image/png', bytes: 1024, width: 100, height: 60, sha256: 'x'.repeat(64), createdAt: '2026-11-24T00:00:00.000Z',
  })));
  await signIn(page);
  await openCreate(page, '新建活动');
  const form = section(page, '新建活动');
  await form.locator('input[type="file"]').setInputFiles({
    name: 'cover.png', mimeType: 'image/png',
    buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64'),
  });
  const field = form.getByLabel('活动封面', { exact: true });
  await expect(field).toHaveValue('marketing-assets/7900000000000001.png');
  await expect(form.locator('img.mkt-asset-preview')).toHaveAttribute('src', 'https://mtxoss2.oss-cn-hangzhou.aliyuncs.com/marketing-assets/7900000000000001.png');
});

// ---- 契约 76：节日营销扩展字段（并入重做版，2026-11-24）----

test('契约 76：5 个扩展字段随活动表单提交；全部留空时一个都不上送', async ({ page }) => {
  const writes = captureWrites(page, MARKETING);
  await stubReads(page);
  await page.route(`**${MARKETING}/campaigns`, route => route.fulfill(envelope(receipt('7100000000000020'))));
  await signIn(page);
  await openCreate(page, '新建活动');
  const form = section(page, '新建活动');
  await form.getByLabel('活动名称（2-100）').fill('中秋月饼礼盒');
  await form.getByLabel('标题（1-100）').fill('中秋佳节');
  await form.getByLabel('开始时间').fill('2026-09-20T09:00');
  await form.getByLabel('结束时间').fill('2026-10-08T18:00');
  // 节日主题：下拉是后端白名单的 7 个
  const themeOptions = form.getByLabel('节日主题').locator('option');
  await expect(themeOptions).toHaveText(['未设置', '春节（SPRING_FESTIVAL）', '中秋（MID_AUTUMN）', '国庆（NATIONAL_DAY）', '圣诞（CHRISTMAS）', '元旦（NEW_YEAR）', '双11（DOUBLE_11）', '其他（OTHER）']);
  await form.getByLabel('节日主题').selectOption('MID_AUTUMN');
  await form.getByLabel('说明（≤300）').fill('活动期间到店消费满 199 元赠送宠物月饼礼盒一份。');
  await form.getByLabel('主打项目（≤100）').fill('宠物月饼礼盒');
  await form.getByLabel('玩法', { exact: false }).selectOption('THEME');
  await form.getByLabel('封面素材 ID（商家上传的封面资产，可空）').fill('7800000000000001');
  await form.getByRole('button', { name: '创建草稿' }).click();
  await expect(navStatus(page)).toContainText('活动草稿已创建');
  const posts = writes.filter(w => w.method === 'POST' && w.path === `${MARKETING}/campaigns`);
  expect(posts).toHaveLength(1);
  expect(posts[0].body).toMatchObject({
    themeCode: 'MID_AUTUMN', description: '活动期间到店消费满 199 元赠送宠物月饼礼盒一份。',
    highlightText: '宠物月饼礼盒', playType: 'THEME', coverAssetId: '7800000000000001',
  });
});

test('契约 76：扩展字段全部留空时，这 5 个键一个都不上送', async ({ page }) => {
  // 留空即不提交（后端保持已存值的决定权），也不编造默认值。独立成一个用例：
  // 不与上一个用例共享抽屉实例，避免「上一次提交后抽屉还在关闭」时把输入落进旧表单。
  const writes = captureWrites(page, MARKETING);
  await stubReads(page);
  await page.route(`**${MARKETING}/campaigns`, route => route.fulfill(envelope(receipt('7100000000000021'))));
  await signIn(page);
  await openCreate(page, '新建活动');
  const form = section(page, '新建活动');
  await form.getByLabel('活动名称（2-100）').fill('普通平台活动');
  await form.getByLabel('标题（1-100）').fill('不填扩展字段');
  await form.getByLabel('开始时间').fill('2026-11-01T09:00');
  await form.getByLabel('结束时间').fill('2026-11-30T18:00');
  await form.getByRole('button', { name: '创建草稿' }).click();
  await expect(navStatus(page)).toContainText('活动草稿已创建');
  const posts = writes.filter(w => w.method === 'POST' && w.path === `${MARKETING}/campaigns`);
  expect(posts).toHaveLength(1);
  for (const key of ['description', 'themeCode', 'highlightText', 'playType', 'coverAssetId']) {
    expect(posts[0].body, `空字段 ${key} 不应上送`).not.toHaveProperty(key);
  }
});

test('连续两次新建：第二次提交不会带上第一次填的扩展字段', async ({ page }) => {
  // 回归守卫：这条用例曾把两轮写在一起、且不等第一次的抽屉关闭就重开，在 CI 慢机器上
  // 把输入落进了还在关闭的旧表单。真人的连续操作是同样的路径——这里按真人节奏走一遍：
  // 第一次填满扩展字段并提交，等抽屉完全收起，再新建第二个、扩展字段全部留空。
  const writes = captureWrites(page, MARKETING);
  await stubReads(page);
  await page.route(`**${MARKETING}/campaigns`, route => route.fulfill(envelope(receipt('7100000000000030'))));
  await signIn(page);

  const openBlank = async () => {
    await openCreate(page, '新建活动');
    const opened = section(page, '新建活动');
    await expect(opened).toBeVisible();
    return opened;
  };
  const closeDrawer = async () => {
    await expect(page.locator('.ant-drawer-content-wrapper')).toHaveCount(0, { timeout: 10_000 });
  };

  const first = await openBlank();
  await first.getByLabel('活动名称（2-100）').fill('中秋月饼礼盒');
  await first.getByLabel('标题（1-100）').fill('中秋佳节');
  await first.getByLabel('节日主题').selectOption('MID_AUTUMN');
  await first.getByLabel('说明（≤300）').fill('活动期间到店消费满 199 元赠送宠物月饼礼盒一份。');
  await first.getByLabel('主打项目（≤100）').fill('宠物月饼礼盒');
  await first.getByLabel('开始时间').fill('2026-09-20T09:00');
  await first.getByLabel('结束时间').fill('2026-10-08T18:00');
  await first.getByRole('button', { name: '创建草稿' }).click();
  await expect(navStatus(page)).toContainText('活动草稿已创建');
  await closeDrawer();

  const second = await openBlank();
  // 新抽屉是干净的：任何扩展字段都不带上一个活动的值
  await expect(second.getByLabel('说明（≤300）')).toHaveValue('');
  await expect(second.getByLabel('节日主题')).toHaveValue('');
  await expect(second.getByLabel('主打项目（≤100）')).toHaveValue('');
  await second.getByLabel('活动名称（2-100）').fill('普通平台活动');
  await second.getByLabel('标题（1-100）').fill('不填扩展字段');
  await second.getByLabel('开始时间').fill('2026-11-01T09:00');
  await second.getByLabel('结束时间').fill('2026-11-30T18:00');
  await second.getByRole('button', { name: '创建草稿' }).click();
  await expect.poll(() => writes.filter(w => w.method === 'POST' && w.path === `${MARKETING}/campaigns`).length).toBe(2);

  const posts = writes.filter(w => w.method === 'POST' && w.path === `${MARKETING}/campaigns`);
  // 第一个活动：填了的扩展字段如实上送
  expect(posts[0].body).toMatchObject({
    name: '中秋月饼礼盒', themeCode: 'MID_AUTUMN',
    description: '活动期间到店消费满 199 元赠送宠物月饼礼盒一份。', highlightText: '宠物月饼礼盒',
  });
  // 第二个活动：只填了基本信息，扩展字段一个都不上送
  expect(posts[1].body).toMatchObject({ name: '普通平台活动', title: '不填扩展字段' });
  for (const key of ['description', 'themeCode', 'highlightText', 'playType', 'coverAssetId']) {
    expect(posts[1].body, `第二个活动的空字段 ${key} 不应上送`).not.toHaveProperty(key);
  }
});

test('契约 76：列表显示节日主题；详情只在有值时显示扩展字段；发布前自检提示封面素材会校验', async ({ page }) => {
  const themed = {
    ...campaign, themeCode: 'MID_AUTUMN', themeLabel: '中秋', playType: 'THEME',
    highlightText: '宠物月饼礼盒', description: '活动期间到店消费满 199 元赠送宠物月饼礼盒一份。\n每人限领一份。', coverAssetId: '7800000000000001',
  };
  const plain = { ...campaign, campaignId: '7100000000000009', name: '普通平台活动', title: '没有扩展字段', serviceCategoryCodes: [] };
  await stubReads(page, { campaigns: [themed, plain] });
  await page.route(`**${MARKETING}/campaigns/${themed.campaignId}`, route => route.fulfill(envelope({ ...campaignDetail, campaign: themed })));
  await page.route(`**${MARKETING}/campaigns/${plain.campaignId}`, route => route.fulfill(envelope({ ...campaignDetail, campaign: plain })));
  await signIn(page);

  const themedRow = page.getByRole('row').filter({ hasText: themed.name });
  await expect(themedRow.getByText('🏮 中秋')).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: plain.name }).getByText('🏮')).toHaveCount(0);

  await themedRow.getByRole('button', { name: '详情' }).click();
  const detail = page.locator('section[aria-label="活动详情"]');
  // 详情里的扩展字段行。antd 6 的 Descriptions 直接渲染成 th.ant-descriptions-item-label + td.ant-descriptions-item-content
  //（没有 .ant-descriptions-item 容器），所以按「同一行里标签 + 内容」定位；折叠的编辑表单里也有同名 label，不能按文字全局找。
  const factContent = (label: string) => detail
    .locator('.ant-descriptions-item-label', { hasText: new RegExp(`^${label}$`) })
    .locator('xpath=following-sibling::td[1]');
  const factLabels = () => detail.locator('.ant-descriptions-item-label');
  await expect(factContent('节日主题')).toHaveText('中秋');
  await expect(factContent('主打项目')).toHaveText('宠物月饼礼盒');
  await expect(factContent('玩法')).toHaveText('节日主题');
  await expect(factContent('封面素材')).toHaveText('ID 7800000000000001');
  await expect(factContent('说明')).toContainText('每人限领一份');
  // 契约 76 §5：绑了封面素材，发布时会校验素材可用——自检里如实提示
  await expect(detail.getByText('已绑定封面素材（ID 7800000000000001），发布时会校验该素材是否仍然可用')).toBeVisible();
  await page.getByRole('button', { name: '关闭', exact: true }).click();

  // 没填扩展字段的活动：详情里不堆一排「—」
  await page.getByRole('row').filter({ hasText: plain.name }).getByRole('button', { name: '详情' }).click();
  await expect(page.locator('section[aria-label="活动详情"]')).toBeVisible();
  await expect(factLabels()).not.toContainText(['节日主题', '主打项目', '玩法', '说明', '封面素材']);
  await expect(factLabels()).toHaveCount(16);
});

test('契约 76：玩法不在允许范围时，发布前自检直接指出不能发布', async ({ page }) => {
  // 历史数据可能带着非法玩法（新写入后端会拦）；发布闸门会拒，自检提前说清楚。
  // 夹具活动给 1 家门店，这样「参与门店」那条自检是绿的——警告只来自玩法这一条。
  const illegal = { ...campaign, playType: 'BLIND_BOX', storeCount: 1 };
  await stubReads(page, { campaigns: [illegal] });
  await page.route(`**${MARKETING}/campaigns/${campaign.campaignId}`, route => route.fulfill(envelope({ ...campaignDetail, campaign: illegal })));
  await signIn(page);
  await campaignRow(page).getByRole('button', { name: '详情' }).click();
  const detail = page.locator('section[aria-label="活动详情"]');
  await expect(detail.getByText('玩法「BLIND_BOX」不在允许范围内（仅支持节日主题），不能发布')).toBeVisible();
  await expect(detail.locator('li.is-warn')).toHaveCount(1);
});

test('投放位：卡片模板可选「整卡图」（full_card）；不认识的模板码不会被显示成「通用模板」', async ({ page }) => {
  const writes = captureWrites(page, MARKETING);
  const legacyTemplate = { ...placement, placementId: '7200000000000007', positionCode: 'HOME_BANNER', templateCode: 'season_v9' };
  await stubReads(page, { placements: [placement, legacyTemplate] });
  await page.route(`**${MARKETING}/placements`, route => route.fulfill(envelope(receipt('7200000000000009'))));
  await signIn(page);
  await nav(page).getByRole('button', { name: '投放位' }).click();
  await openCreate(page, '新建投放位');
  const form = section(page, '新建投放位');
  const options = form.getByLabel('卡片模板').locator('option');
  await expect(options).toHaveText(['通用模板', '整卡图', '新客活动卡', '美容活动卡']);
  await form.getByLabel('位置').selectOption('HOME_ACTIVITY');
  await form.getByLabel('活动 ID').fill(campaign.campaignId);
  await form.getByLabel('卡片模板').selectOption('full_card');
  await form.getByLabel('开始时间').fill('2026-11-01T09:00');
  await form.getByLabel('结束时间').fill('2026-11-30T18:00');
  await form.getByRole('button', { name: '创建投放位' }).click();
  await expect(navStatus(page)).toContainText('投放位已创建');
  const posts = writes.filter(w => w.method === 'POST' && w.path === `${MARKETING}/placements`);
  expect(posts).toHaveLength(1);
  expect(posts[0].body?.templateCode).toBe('full_card');
  // 先关掉新建抽屉再切分区（抽屉开着时遮罩盖住页面，点不到页签）
  await expect(page.locator('.ant-drawer-content-wrapper')).toHaveCount(0);

  // 首页内容位的模板下拉不包含 full_card（用户裁决：只加到投放位）
  await nav(page).getByRole('button', { name: '首页内容位' }).click();
  await openCreate(page, '新建首页内容位');
  await expect(section(page, '新建首页内容位').getByLabel('模板').locator('option')).toHaveText(['热门服务大卡', '宠友圈热门容器', '植物&异宠小卡']);
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  await expect(page.locator('.ant-drawer-content-wrapper')).toHaveCount(0);

  // 列表：未知模板码显示原码，不冒充「通用模板」；编辑下拉里保留它（值不被静默改掉）
  await nav(page).getByRole('button', { name: '投放位' }).click();
  const legacyRow = page.getByRole('row').filter({ hasText: 'season_v9' });
  await expect(legacyRow.getByText('模板 season_v9')).toBeVisible();
  await legacyRow.getByRole('button', { name: '编辑', exact: true }).click();
  await expect(section(page, '编辑投放位').getByLabel('卡片模板')).toHaveValue('season_v9');
});


test('打开活动详情时回填门店名称的分页大小不超过接口上限 50（超过会 400）', async ({ page }) => {
  await stubReads(page, { campaigns: [campaign] });
  const storeReads: string[] = [];
  page.on('request', req => {
    const url = new URL(req.url());
    if (url.pathname === `${MARKETING}/stores`) storeReads.push(url.search);
  });
  await signIn(page);
  await campaignRow(page).getByRole('button', { name: '详情' }).click();
  await expect(page.locator('section[aria-label="活动详情"]')).toBeVisible();
  await expect.poll(() => storeReads.length).toBeGreaterThan(0);
  for (const search of storeReads) {
    expect(Number(new URLSearchParams(search).get('pageSize'))).toBeLessThanOrEqual(50);
  }
});

test('列表读取遇到 404：如实提示接口不存在或功能未开通，不套用「数据已被删除、已刷新列表」', async ({ page }) => {
  await stubReads(page);
  // 本地 E2E 实例里券活动控制器因功能开关未开而不注册，列表接口返回 COMMON_NOT_FOUND。
  await page.route(`**${COUPONS}?*`, route => route.fulfill(failure('COMMON_NOT_FOUND', 404)));
  await signIn(page, ['coupon.campaign.read', 'coupon.campaign.manage', 'coupon.campaign.publish']);
  await nav(page).getByRole('button', { name: '券活动' }).click();
  const alert = navAlert(page);
  await expect(alert).toContainText('读取券活动失败：接口不存在或功能未开通');
  await expect(alert).not.toContainText('已被删除');
  await expect(page.getByText('列表加载失败')).toBeVisible();
});

test('活动详情：封面缩略图带放大镜，点击弹大图，ESC 关闭', async ({ page }) => {
  const hosted = { ...campaign, coverUrl: 'https://img.example.invalid/c1.png' };
  await stubReads(page, { campaigns: [hosted] });
  // 详情接口单独桩：返回带封面的数据（stubReads 里的详情桩是无封面版本）
  await page.route(`**${MARKETING}/campaigns/${campaign.campaignId}`, route =>
    route.fulfill(envelope({ ...campaignDetail, campaign: hosted })));
  await signIn(page);
  await campaignRow(page).getByRole('button', { name: '详情' }).click();
  const detail = page.locator('section[aria-label="活动详情"]');
  const trigger = detail.getByRole('button', { name: '活动封面' });
  await expect(trigger).toBeVisible();
  await expect(trigger.locator('img')).toHaveAttribute('src', hosted.coverUrl);
  await trigger.click();
  // 注意：antd 6 的抽屉面板自带 role="dialog"，弹窗要按标题名精确定位
  const dialog = page.getByRole('dialog', { name: '活动封面' });
  await expect(dialog).toBeVisible();
  await expect(dialog.locator('img')).toHaveAttribute('src', hosted.coverUrl);
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  // 「没有可预览地址时不出现放大镜」的场景见上方「封面有存储值但服务端没给出可预览地址」用例。
});
