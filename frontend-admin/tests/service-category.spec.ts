import { expect, test, type Page } from '@playwright/test';

// Contract-74 §4 service category dictionary page (F-A-01) — real production UI/transport
// with intercepted HTTP (same discipline as ops.spec.ts). Covers: list rendering with icon
// preview, create (scheme-A iconUrl), update tri-state patch semantics (absent=keep /
// null=clear), enable/disable with expectedVersion, fresh UUID X-Request-Id per write,
// readable 409/400 messages with auto list refresh, empty state and the manage-permission guard.
test.use({ baseURL: 'http://127.0.0.1:4174' });
const OPERATOR = '901';
const ROOT = '/api/v1/admin/service-categories';
// 1x1 transparent PNG so the icon <img> decodes deterministically offline.
const ICON_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64');

function envelope(data: unknown, status = 200) {
  return { status, json: { code: 'SUCCESS', message: 'ok', data, traceId: 'fixture-svc-cat' } };
}
function failure(code: string, status: number) {
  return { status, json: { code, message: code, data: null, traceId: 'fixture-svc-cat' } };
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const grooming = {
  categoryId: '910000000000901', categoryCode: 'GROOMING', categoryName: '洗护美容',
  parentCode: null, iconUrl: 'https://cdn.example.com/icons/grooming.png',
  description: '洗澡、造型、护理', sortNo: 1, status: 'ENABLED', version: 3,
  createdAt: '2026-10-30T01:00:00.000Z', updatedAt: '2026-10-30T01:00:00.000Z',
};
const food = {
  categoryId: '910000000000902', categoryCode: 'FOOD', categoryName: '主粮零食',
  parentCode: null, iconUrl: null, description: null, sortNo: 2, status: 'DISABLED', version: 7,
  createdAt: '2026-10-30T01:00:00.000Z', updatedAt: '2026-10-30T01:00:00.000Z',
};
const rows = [grooming, food];
const pageEnvelope = (items: unknown[]) => ({ page: 1, pageSize: 50, total: items.length, items });

type Write = { method: string; path: string; requestId: string | null; body: Record<string, unknown> | null };

function captureWrites(page: Page): Write[] {
  const writes: Write[] = [];
  page.on('request', req => {
    const url = new URL(req.url());
    if (url.pathname.startsWith(ROOT) && req.method() !== 'GET') {
      let body: Record<string, unknown> | null = null;
      try { body = JSON.parse(req.postData() ?? 'null') as Record<string, unknown>; } catch { body = null; }
      writes.push({ method: req.method(), path: url.pathname, requestId: req.headers()['x-request-id'] ?? null, body });
    }
  });
  return writes;
}

async function signIn(page: Page, actions = ['service.category.read', 'service.category.manage']) {
  await page.route('**/api/v1/admin/auth/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/attempts')) return route.fulfill(envelope({ attemptId: '801', attemptToken: 'fixture-binding', expiresAt: '2026-10-31T02:00:00.000Z', nextStep: 'PROVE_IDENTITY' }, 201));
    if (path.endsWith('/requirements')) return route.fulfill(envelope({ requiredVerification: 'NONE' }));
    if (path.endsWith('/login')) return route.fulfill(envelope({ sessionId: 's1', operatorId: OPERATOR, audience: 'ADMIN_WEB', tokenType: 'Bearer', accessToken: 'fixture-admin-token', expiresAt: '2026-10-31T02:00:00.000Z' }));
    if (path.endsWith('/session')) return route.fulfill(envelope({ sessionId: 's1', operatorId: OPERATOR, audience: 'ADMIN_WEB', expiresAt: '2026-10-31T02:00:00.000Z', idleExpiresAt: '2026-10-31T01:00:00.000Z', authzVersion: 'v1' }));
    if (path.endsWith('/permissions')) return route.fulfill(envelope({ operatorId: OPERATOR, authzVersion: 'v1', checkedAt: '2026-10-30T02:00:00.000Z', roles: ['OPERATOR'], dataScope: 'ALL', actionCodes: actions }));
    return route.fulfill(envelope(null));
  });
  await page.route('**/cdn.example.com/**', route => route.fulfill({ status: 200, contentType: 'image/png', body: ICON_PNG }));
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill('fixture-ops');
  await page.getByLabel('密码', { exact: true }).fill('fixture-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('link', { name: '服务分类' }).click();
  await expect(page.getByRole('heading', { name: '服务分类字典' })).toBeVisible();
}

async function openEditor(page: Page) {
  await page.locator('tbody tr').first().getByRole('button', { name: '编辑' }).click();
  const editor = page.locator('section[aria-label="编辑分类"]');
  await expect(editor).toBeVisible();
  return editor;
}

test('F-A-01 列表：全状态字典、图标预览、版本与空态文案', async ({ page }) => {
  await page.route(`**${ROOT}?*`, route => route.fulfill(envelope(pageEnvelope(rows))));
  await signIn(page);
  const first = page.locator('tbody tr').first();
  await expect(first).toContainText('GROOMING');
  await expect(first).toContainText('洗护美容');
  await expect(first).toContainText('洗澡、造型、护理');
  await expect(first).toContainText('启用');
  await expect(first.getByRole('img', { name: 'GROOMING 图标' })).toBeVisible();
  const second = page.locator('tbody tr').nth(1);
  await expect(second).toContainText('停用');
  await expect(second).toContainText('无图标');
  await expect(page.locator('tbody tr')).toHaveCount(2);
  await expect(page.getByText('共 2 条')).toBeVisible();
});

test('F-A-01 列表空态：不伪造数据', async ({ page }) => {
  await signIn(page);
  await page.route(`**${ROOT}?*`, route => route.fulfill(envelope(pageEnvelope([]))));
  await page.getByRole('button', { name: '查询' }).click();
  await expect(page.getByText('暂无分类')).toBeVisible();
});

test('F-A-01 新建：仅提交非空可选字段，写操作携带 UUID 请求编号', async ({ page }) => {
  const writes = captureWrites(page);
  let listReads = 0;
  await page.route(`**${ROOT}?*`, route => { listReads++; return route.fulfill(envelope(pageEnvelope(rows))); });
  await page.route(`**${ROOT}`, route => route.fulfill(envelope({ categoryId: '910000000000999', categoryCode: 'HOSPITAL', status: 'ENABLED', version: 4 })));
  await signIn(page);
  await page.getByRole('button', { name: '新增分类' }).click();
  const form = page.locator('section[aria-label="新增分类"]');
  await form.getByLabel(/^分类编码/).fill('HOSPITAL');
  await form.getByLabel(/^分类名称/).fill('上门医疗');
  await form.getByLabel(/图标 URL/).fill('https://cdn.example.com/hospital.png');
  await form.getByLabel(/^排序/).fill('5');
  await form.getByRole('button', { name: '创建' }).click();
  await expect(page.getByText('已创建：HOSPITAL（版本 4）')).toBeVisible();
  expect(listReads).toBeGreaterThanOrEqual(2); // initial load + post-create refresh
  const posts = writes.filter(w => w.method === 'POST' && w.path === ROOT);
  expect(posts).toHaveLength(1);
  expect(posts[0].requestId).toMatch(UUID);
  expect(posts[0].body).toEqual({
    categoryCode: 'HOSPITAL', categoryName: '上门医疗',
    iconUrl: 'https://cdn.example.com/hospital.png', sortNo: 5,
  });
});

test('F-A-01 新建校验：http 图标地址给出可读提示且不发请求', async ({ page }) => {
  const writes = captureWrites(page);
  await page.route(`**${ROOT}?*`, route => route.fulfill(envelope(pageEnvelope(rows))));
  await page.route(`**${ROOT}`, route => route.fulfill(envelope({ categoryId: '910000000000999', categoryCode: 'HOSPITAL', status: 'ENABLED', version: 4 })));
  await signIn(page);
  await page.getByRole('button', { name: '新增分类' }).click();
  const form = page.locator('section[aria-label="新增分类"]');
  await form.getByLabel(/^分类编码/).fill('HOSPITAL');
  await form.getByLabel(/^分类名称/).fill('上门医疗');
  await form.getByLabel(/图标 URL/).fill('http://cdn.example.com/hospital.png');
  await form.getByRole('button', { name: '创建' }).click();
  await expect(page.getByRole('alert')).toContainText('图标地址须为 https:// 开头');
  expect(writes).toHaveLength(0);
});

test('F-A-01 编辑三态：缺省不发送（保持）、显式 null（清空）、新值（设置）', async ({ page }) => {
  const writes = captureWrites(page);
  await page.route(`**${ROOT}?*`, route => route.fulfill(envelope(pageEnvelope(rows))));
  await page.route(`**${ROOT}/GROOMING`, route => route.fulfill(envelope({ categoryCode: 'GROOMING', status: 'ENABLED', version: 4 })));
  await signIn(page);
  const editor = await openEditor(page);

  // (1) all-keep: nullable patch fields absent from the PUT body entirely.
  await editor.getByRole('button', { name: '保存' }).click();
  await expect(page.getByText('已保存：GROOMING（版本 4）')).toBeVisible();
  let puts = writes.filter(w => w.method === 'PUT');
  expect(puts).toHaveLength(1);
  expect(puts[0].requestId).toMatch(UUID);
  expect(puts[0].body).toEqual({ categoryName: '洗护美容', sortNo: 1, expectedVersion: 3 });
  expect(puts[0].path).toBe(`${ROOT}/GROOMING`);

  // (2) explicit clear: iconUrl mode 清空 sends null; others stay absent.
  await page.locator('tbody tr').first().getByRole('button', { name: '编辑' }).click();
  const editor2 = page.locator('section[aria-label="编辑分类"]');
  await editor2.locator('select').nth(1).selectOption({ label: '清空（移除图标）' });
  await editor2.getByRole('button', { name: '保存' }).click();
  await expect(page.getByText('已保存：GROOMING（版本 4）')).toBeVisible();
  puts = writes.filter(w => w.method === 'PUT');
  expect(puts).toHaveLength(2);
  expect(puts[1].body).toEqual({ categoryName: '洗护美容', sortNo: 1, iconUrl: null, expectedVersion: 3 });

  // (3) set-new-value: description 设为新值 sends the entered string.
  await page.locator('tbody tr').first().getByRole('button', { name: '编辑' }).click();
  const editor3 = page.locator('section[aria-label="编辑分类"]');
  await editor3.locator('select').nth(2).selectOption({ label: '设为新值' });
  await editor3.locator('input[name="description"]').fill('全犬种洗护美容');
  await editor3.getByRole('button', { name: '保存' }).click();
  await expect(page.getByText('已保存：GROOMING（版本 4）')).toBeVisible();
  puts = writes.filter(w => w.method === 'PUT');
  expect(puts).toHaveLength(3);
  expect(puts[2].body).toEqual({ categoryName: '洗护美容', sortNo: 1, description: '全犬种洗护美容', expectedVersion: 3 });
});

test('F-A-01 启停：expectedVersion 随行携带，成功后行状态刷新', async ({ page }) => {
  const writes = captureWrites(page);
  await page.route(`**${ROOT}?*`, route => route.fulfill(envelope(pageEnvelope(rows))));
  await page.route(`**${ROOT}/GROOMING/disable`, route => route.fulfill(envelope({ categoryCode: 'GROOMING', status: 'DISABLED', version: 4 })));
  await signIn(page);
  await page.locator('tbody tr').first().getByRole('button', { name: '停用' }).click();
  await expect(page.getByText('停用（版本 4）')).toBeVisible();
  const posts = writes.filter(w => w.path === `${ROOT}/GROOMING/disable`);
  expect(posts).toHaveLength(1);
  expect(posts[0].requestId).toMatch(UUID);
  expect(posts[0].body).toEqual({ expectedVersion: 3 });
});

test('F-A-01 版本冲突 409：可读提示并自动刷新列表', async ({ page }) => {
  const writes = captureWrites(page);
  let conflicts = 0;
  await page.route(`**${ROOT}?*`, route => route.fulfill(envelope(pageEnvelope(rows))));
  await page.route(`**${ROOT}/GROOMING/disable`, route => {
    if (conflicts++ === 0) return route.fulfill(failure('CATEGORY_VERSION_CONFLICT', 409));
    return route.fulfill(envelope({ categoryCode: 'GROOMING', status: 'DISABLED', version: 4 }));
  });
  await signIn(page);
  await page.locator('tbody tr').first().getByRole('button', { name: '停用' }).click();
  await expect(page.getByRole('alert')).toContainText('版本冲突：该分类刚被其他人修改过，已为你刷新列表，请重试');
  // The readable-text promise is kept: the list was re-fetched after the conflict.
  await page.locator('tbody tr').first().getByRole('button', { name: '停用' }).click();
  await expect(page.getByText('停用（版本 4）')).toBeVisible();
  expect(writes.filter(w => w.method === 'POST')).toHaveLength(2);
});

test('F-A-01 参数错误 400：可读提示，不裸抛错误码', async ({ page }) => {
  await page.route(`**${ROOT}?*`, route => route.fulfill(envelope(pageEnvelope(rows))));
  await page.route(`**${ROOT}`, route => route.fulfill(failure('COMMON_INVALID_ARGUMENT', 400)));
  await signIn(page);
  await page.getByRole('button', { name: '新增分类' }).click();
  const form = page.locator('section[aria-label="新增分类"]');
  await form.getByLabel(/^分类编码/).fill('HOSPITAL');
  await form.getByLabel(/^分类名称/).fill('上门医疗');
  await form.getByRole('button', { name: '创建' }).click();
  await expect(page.getByRole('alert')).toContainText('填写内容不符合要求');
  await expect(page.getByRole('alert')).not.toContainText('COMMON_INVALID_ARGUMENT');
});

test('F-A-01 权限：仅授予 read 时写操作被拦截且不发请求', async ({ page }) => {
  const writes = captureWrites(page);
  await page.route(`**${ROOT}?*`, route => route.fulfill(envelope(pageEnvelope(rows))));
  await signIn(page, ['service.category.read']);
  await page.locator('tbody tr').first().getByRole('button', { name: '停用' }).click();
  await expect(page.getByRole('alert')).toContainText('当前账号未授予 service.category.manage');
  expect(writes).toHaveLength(0);
});
