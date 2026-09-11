import { expect, test } from '@playwright/test';

test('WEB-001 routes, independent Mock and string values', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const apiCalls: string[] = [];
  page.on('request', request => { if (request.url().includes('/api/v1/')) apiCalls.push(request.url()); });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '运营网页基础工程' })).toBeVisible();
  await page.getByLabel('测试身份').selectOption('editor');
  await page.getByRole('link', { name: '权限示例' }).click();
  await page.getByRole('button', { name: '读取示例' }).click();
  await expect(page.getByRole('status')).toHaveText('2019267812367810561 / 128.00 / 示例完成');
  await page.getByRole('button', { name: '执行示例动作' }).click();
  await expect(page.getByRole('status')).toContainText('示例完成');
  await page.goto('/missing');
  await expect(page.getByRole('heading', { name: '404 · 页面不存在' })).toBeVisible();
  expect(errors).toEqual([]);
  expect(apiCalls).toEqual([]);
});

test('WEB-002 direct route, menus, buttons and fail-closed session', async ({ page }) => {
  await page.goto('/example');
  await expect(page.getByRole('heading', { name: '未登录' })).toBeVisible();
  await page.getByLabel('测试身份').selectOption('denied');
  await expect(page.getByRole('heading', { name: '403 · 无权限' })).toBeVisible();
  await expect(page.getByRole('link', { name: '权限示例' })).toHaveCount(0);
  await page.getByLabel('测试身份').selectOption('reader');
  await expect(page.getByRole('heading', { name: '权限示例' })).toBeVisible();
  await expect(page.getByRole('button', { name: '执行示例动作' })).toHaveCount(0);
  await page.getByLabel('测试身份').selectOption('editor');
  await expect(page.getByRole('button', { name: '执行示例动作' })).toBeVisible();
  await page.getByLabel('测试身份').selectOption('failed');
  await expect(page.getByRole('heading', { name: '权限查询失败，访问已关闭' })).toBeVisible();
  await expect(page.getByRole('button')).toHaveCount(0);
  await page.getByLabel('测试身份').selectOption('anonymous');
  await expect(page.getByRole('heading', { name: '未登录' })).toBeVisible();
});

test('PERM-005 fixture only: finance follows explicit grants, transport rejects bypass', async ({ page }) => {
  await page.goto('/example');
  await page.getByLabel('测试身份').selectOption('finance');
  await expect(page.getByRole('button', { name: '执行示例动作' })).toHaveCount(0);
  await page.getByLabel('测试身份').selectOption('financeGranted');
  await page.getByRole('button', { name: '执行示例动作' }).click();
  await expect(page.getByRole('status')).toContainText('示例完成');
  const result = await page.evaluate(async () => {
    const fixtureModule = '/src/fixture.ts';
    const { profiles, can, createFixtureTransport, fixturePath } = await import(fixtureModule) as typeof import('../src/fixture');
    const attempt = async (key: string) => (await createFixtureTransport(profiles[key])(new Request(location.origin + fixturePath, { method: 'POST', headers: { 'X-Request-Id': crypto.randomUUID() } }))).status;
    return { denied: await attempt('finance'), granted: await attempt('financeGranted'), ordinary: await attempt('denied'), anonymous: await attempt('anonymous'), superuser: await attempt('superuser'), unknown: can(profiles.superuser, 'unapproved.withdraw'), renamed: can({ ...profiles.finance, label: '平台超级管理员' }, 'sample.execute') };
  });
  expect(result).toEqual({ denied: 403, granted: 200, ordinary: 403, anonymous: 401, superuser: 200, unknown: false, renamed: false });
});

test('WEB-001 request protocol, HTTP errors and stale context isolation', async ({ page }) => {
  await page.goto('/');
  const result = await page.evaluate(async () => {
    const requestModule = '/src/request.ts';
    const { createWebClient } = await import(requestModule) as typeof import('../src/request');
    const captures: { path: string; requestId: string | null; token: string | null; body: unknown }[] = [];
    const client = createWebClient(async (request: Request) => {
      captures.push({ path: new URL(request.url).pathname, requestId: request.headers.get('X-Request-Id'), token: request.headers.get('Authorization'), body: await request.json() });
      return Response.json({ success: true, data: { id: '2019267812367810561', amount: '128.00', displayStatus: 'server-value', actions: [] } });
    });
    client.resetContext('test-only');
    const data = await client.request('/api/v1/admin/test', { method: 'POST', requestId: 'dc65e5e4-a95e-4993-a60e-44c7e62718a0', body: { id: '2019267812367810561', amount: '128.00' } });
    const errorCode = async (promise: Promise<unknown>) => { try { await promise; return 'unexpected success'; } catch (e) { return (e as Error).message; } };
    let resolve!: (response: Response) => void;
    const delayed = createWebClient(() => new Promise<Response>(r => { resolve = r; }));
    const pending = delayed.request('/api/v1/admin/test');
    delayed.resetContext();
    resolve(Response.json({ success: true, data: 'old-user' }));
    return { captures, data, stale: await errorCode(pending), badPath: await errorCode(client.request('/api/v1/c/test')), traversal: await errorCode(client.request('/api/v1/admin/../c/test')), unauthorized: await errorCode(createWebClient(async () => new Response(null, { status: 401 })).request('/api/v1/admin/test')), forbidden: await errorCode(createWebClient(async () => new Response(null, { status: 403 })).request('/api/v1/admin/test')), malformed: await errorCode(createWebClient(async () => new Response('bad json')).request('/api/v1/admin/test')) };
  });
  expect(result.captures).toEqual([{ path: '/api/v1/admin/test', requestId: 'dc65e5e4-a95e-4993-a60e-44c7e62718a0', token: 'Bearer test-only', body: { id: '2019267812367810561', amount: '128.00' } }]);
  expect(result.data).toEqual({ id: '2019267812367810561', amount: '128.00', displayStatus: 'server-value', actions: [] });
  expect(result.stale).toBe('STALE_CONTEXT');
  expect(result.badPath).toBe('INVALID_ADMIN_PATH');
  expect(result.traversal).toBe('INVALID_ADMIN_PATH');
  expect(result.unauthorized).toBe('UNAUTHENTICATED');
  expect(result.forbidden).toBe('FORBIDDEN');
  expect(result.malformed).toBe('INVALID_RESPONSE');
});

test('WEB-001 real fetch transport with intercepted HTTP contract', async ({ page }) => {
  await page.goto('/');
  const requests: { requestId: string | undefined; body: unknown; authorization: string | undefined }[] = [];
  await page.route('**/api/v1/admin/test-transport', async route => {
    const request = route.request();
    requests.push({ requestId: request.headers()['x-request-id'], body: request.postDataJSON(), authorization: request.headers().authorization });
    await route.fulfill({ json: { success: true, code: 'SUCCESS', message: 'OK', traceId: 'intercepted-test', data: { id: '2019267812367810561', amount: '128.00', displayStatus: 'server-only', actions: [] } } });
  });
  const data = await page.evaluate(async () => {
    const modulePath = '/src/request.ts';
    const { createWebClient } = await import(modulePath) as typeof import('../src/request');
    const client = createWebClient();
    client.resetContext('test-token-never-persisted');
    return client.request('/api/v1/admin/test-transport', { method: 'POST', body: { id: '2019267812367810561', amount: '128.00' } });
  });
  expect(requests).toHaveLength(1);
  expect(requests[0].requestId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  expect(requests[0].authorization).toBe('Bearer test-token-never-persisted');
  expect(requests[0].body).toEqual({ id: '2019267812367810561', amount: '128.00' });
  expect(data).toEqual({ id: '2019267812367810561', amount: '128.00', displayStatus: 'server-only', actions: [] });
});

test('WEB-001 production build stays closed without fixture identity', async ({ page }) => {
  const modules: string[] = [];
  page.on('request', request => modules.push(request.url()));
  await page.goto('http://127.0.0.1:4174/example');
  await expect(page.getByRole('heading', { name: '运营服务尚未接入' })).toBeVisible();
  await expect(page.getByLabel('测试身份')).toHaveCount(0);
  expect(modules.some(url => url.includes('FixtureApp'))).toBe(false);
});
