import { expect, test, type Locator, type Page } from '@playwright/test';

// F-A-02 / F-A-03 (contract 71 v0.3): campaign service-category multi-select fed by the
// service-category dictionary face, the HOT_SERVICE_TOP placement position with its
// platform-wide single-ENABLED limit surfaced as readable 409 text, and the HOT_SERVICE
// home-block target whose targetValue is intentionally blank. Real production UI/transport
// with intercepted HTTP — same discipline as service-category.spec.ts. Every write must
// carry a fresh UUID X-Request-Id (request layer default).
test.use({ baseURL: 'http://127.0.0.1:4174' });
const OPERATOR = '902';
const MARKETING = '/api/v1/admin/marketing';
const CATEGORIES = '/api/v1/admin/service-categories';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function envelope(data: unknown, status = 200) {
  return { status, json: { code: 'SUCCESS', message: 'ok', data, traceId: 'fixture-mkt' } };
}
function failure(code: string, status: number) {
  return { status, json: { code, message: code, data: null, traceId: 'fixture-mkt' } };
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

async function signIn(page: Page) {
  await page.route('**/api/v1/admin/auth/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/attempts')) return route.fulfill(envelope({ attemptId: '801', attemptToken: 'fixture-binding', expiresAt: '2026-10-31T02:00:00.000Z', nextStep: 'PROVE_IDENTITY' }, 201));
    if (path.endsWith('/requirements')) return route.fulfill(envelope({ requiredVerification: 'NONE' }));
    if (path.endsWith('/login')) return route.fulfill(envelope({ sessionId: 's1', operatorId: OPERATOR, audience: 'ADMIN_WEB', tokenType: 'Bearer', accessToken: 'fixture-admin-token', expiresAt: '2026-10-31T02:00:00.000Z' }));
    if (path.endsWith('/session')) return route.fulfill(envelope({ sessionId: 's1', operatorId: OPERATOR, audience: 'ADMIN_WEB', expiresAt: '2026-10-31T02:00:00.000Z', idleExpiresAt: '2026-10-31T01:00:00.000Z', authzVersion: 'v1' }));
    if (path.endsWith('/permissions')) return route.fulfill(envelope({ operatorId: OPERATOR, authzVersion: 'v1', checkedAt: '2026-10-30T02:00:00.000Z', roles: ['OPERATOR'], dataScope: 'ALL', actionCodes: ['marketing.campaign.read', 'marketing.campaign.manage', 'marketing.campaign.publish'] }));
    return route.fulfill(envelope(null));
  });
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill('fixture-ops');
  await page.getByLabel('密码', { exact: true }).fill('fixture-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('link', { name: '营销活动' }).click();
  await expect(page.getByRole('heading', { name: '营销活动中心' })).toBeVisible();
}

/** Fixture wiring for every read the marketing center performs on mount (no unhandled 404 noise). */
async function stubReads(page: Page, overrides: {
  campaigns?: unknown[]; placements?: unknown[]; categories?: unknown[];
  categoryFailure?: { code: string; status: number };
} = {}) {
  await page.route(`**${MARKETING}/campaigns?*`, route => route.fulfill(envelope(pageEnvelope(overrides.campaigns ?? []))));
  await page.route(`**${MARKETING}/campaigns/${campaign.campaignId}`, route => route.fulfill(envelope(campaignDetail)));
  await page.route(`**${MARKETING}/placements?*`, route => route.fulfill(envelope(pageEnvelope(overrides.placements ?? []))));
  await page.route(`**${MARKETING}/home-blocks?*`, route => route.fulfill(envelope(pageEnvelope([]))));
  await page.route(`**${MARKETING}/enrollments?*`, route => route.fulfill(envelope(pageEnvelope([]))));
  await page.route('**/api/v1/admin/coupon-campaigns?*', route => route.fulfill(envelope(pageEnvelope([]))));
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
  expect(form.locator('input[name="serviceCategoryCodes"]')).toHaveCount(0);
});

test('F-A-02 空数组=全部：不勾选任何分类提交空列表', async ({ page }) => {
  const writes = captureWrites(page, MARKETING);
  await stubReads(page);
  await page.route(`**${MARKETING}/campaigns`, route => route.fulfill(envelope(receipt('7100000000000010'))));
  await signIn(page);
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
  await page.locator('tbody tr').first().getByRole('button', { name: '详情' }).click();
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
