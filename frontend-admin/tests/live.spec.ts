import { expect, test } from '@playwright/test';

// LIVE integration against the real PR60 backend (no route mocks). Runs ONLY when
// LIVE_JOINT_BASE is set, so CI and the normal suite never execute it:
//   ADMIN_API_PROXY_TARGET=http://192.168.1.44:18082 \
//   ADMIN_API_PROXY_ORIGIN=http://127.0.0.1:18082 vite preview --host 127.0.0.1 --port 18082
//   LIVE_JOINT_BASE=http://127.0.0.1:18082 npx playwright test tests/live.spec.ts
const base = process.env.LIVE_JOINT_BASE;
test.skip(!base, 'LIVE_JOINT_BASE not configured; live integration is opt-in');
test.use({ baseURL: base });

const ACCOUNT = process.env.LIVE_JOINT_ACCOUNT ?? 'jointest-admin';
const PASSWORD = process.env.LIVE_JOINT_PASSWORD ?? 'Jointest#2026pwd';

test('real browser: proxy origin, __Host cookie, login, session, permissions, list, logout', async ({ page, context }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const apiStatus: { path: string; status: number }[] = [];
  page.on('response', response => {
    const path = new URL(response.url()).pathname;
    if (path.startsWith('/api/v1/admin/')) apiStatus.push({ path, status: response.status() });
  });

  await page.goto('/login');
  await expect(page.getByRole('heading', { name: '运营登录' })).toBeVisible();
  await page.getByLabel('账号').fill(ACCOUNT);
  await page.getByLabel('密码').fill(PASSWORD);
  await page.getByRole('button', { name: '登录' }).click();

  // Real login through the controlled proxy (requirements GET needs the injected Origin).
  await expect(page.getByRole('heading', { name: '商家申请审核' })).toBeVisible({ timeout: 20000 });
  await expect(page.getByText('没有符合条件的申请。')).toBeVisible();
  await expect(page.getByText(/运营 9000000000000000001/)).toBeVisible();
  await expect(page.getByText('当前账号仅可查看。')).toBeHidden(); // superadmin carries decide grants

  const requirements = apiStatus.find(entry => entry.path.endsWith('/requirements'));
  expect(requirements?.status).toBe(200);
  const attemptCookie = (await context.cookies()).find(cookie => cookie.name === '__Host-pet-admin-attempt');
  expect(attemptCookie?.httpOnly).toBeTruthy();

  await page.getByRole('button', { name: '退出' }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(errors).toEqual([]);
});
