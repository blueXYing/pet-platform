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
  await expect(page.getByRole('cell', { name: '联调合成宠物生活馆' })).toBeVisible({ timeout: 10000 });
  await expect(page.locator('header')).toContainText('运营');
  await expect(page.getByText('当前账号仅可查看。')).toBeHidden(); // superadmin carries decide grants

  const requirements = apiStatus.find(entry => entry.path.endsWith('/requirements'));
  expect(requirements?.status).toBe(200);
  const attemptCookie = (await context.cookies()).find(cookie => cookie.name === '__Host-pet-admin-attempt');
  expect(attemptCookie?.httpOnly).toBeTruthy();

  await page.getByRole('button', { name: '退出' }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(errors).toEqual([]);
});

test('real backend full review flow: detail, watermarked reads, verification, approve', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));

  await page.goto('/login');
  await page.getByLabel('账号').fill(ACCOUNT);
  await page.getByLabel('密码').fill(PASSWORD);
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page.getByRole('heading', { name: '商家申请审核' })).toBeVisible({ timeout: 20000 });

  // The synthetic application seeded through the normal C-end business flow.
  const row = page.getByRole('cell', { name: '联调合成宠物生活馆' });
  await expect(row).toBeVisible({ timeout: 10000 });
  await page.getByRole('link', { name: '打开' }).first().click();
  await expect(page.getByRole('heading', { name: /申请/ })).toBeVisible();

  // Task claim (idempotent across reruns: an earlier partial run may have
  // claimed, verified or decided already), then single-use watermarked reads.
  await expect(page.getByText(/任务状态：/)).toBeVisible({ timeout: 20000 });
  const claimButton = page.getByRole('button', { name: '领取任务' });
  if (await claimButton.count() > 0) {
    await claimButton.click();
    await expect(page.getByRole('button', { name: '领取任务' })).toHaveCount(0, { timeout: 20000 });
  }
  await expect(page.getByRole('heading', { name: '人工核验（主体/证件）' })).toBeVisible();
  for (const label of ['门店照片', '营业执照', '身份证（人像面）', '身份证（国徽面）']) {
    const viewButton = page.locator('ul.materials li', { hasText: label }).getByRole('button', { name: '申请一次性查看' });
    if (await viewButton.count() > 0) {
      await viewButton.click();
      await expect(page.locator('ul.materials li', { hasText: label }).getByRole('img')).toBeVisible({ timeout: 30000 });
    }
  }

  // Manual verification quoting the authoritative material references
  // (skipped when a previous partial run already verified).
  if (await page.getByText('待核验').first().isVisible().catch(() => false)) {
    await page.getByLabel('营业执照 证件主体').fill('联调合成宠物生活馆');
    await page.getByLabel('营业执照 证号').fill('91510100MA0000000H');
    await page.getByLabel('营业执照 生效日').fill('2024-01-01');
    await page.getByLabel('营业执照 到期日').fill('2034-01-01');
    await page.getByLabel('身份证（正反面合并核验） 证件主体').fill('李四');
    await page.getByLabel('身份证（正反面合并核验） 证号').fill('510104199001010019');
    await page.getByLabel('身份证（正反面合并核验） 生效日').fill('2015-01-01');
    await page.getByLabel('身份证（正反面合并核验） 到期日').fill('2035-01-01');
    await page.getByPlaceholder('如：已核对证件原件与主体一致，编号与有效期无误').fill('联调人工核验：证件原件、主体、编号与有效期逐项核对一致');
    await page.getByLabel('我已逐项核对上述证件信息与所见材料一致').check();
    await page.getByRole('button', { name: '提交人工核验' }).click();
  }
  await expect(page.getByText('已核验').first()).toBeVisible({ timeout: 20000 });

  // Approve decision on the verified application (skipped when already decided).
  if (await page.getByRole('option', { name: /通过（建立商家档案）/ }).isEnabled().catch(() => false)) {
    await page.getByLabel('决定类型').selectOption('APPROVE');
    await page.getByLabel(/审核意见/).fill('联调通过：材料齐全、核验一致');
    await page.getByLabel('我确认本决定基于已查看的材料与核验结果').check();
    await page.getByRole('button', { name: '提交审核决定' }).click();
    await expect(page.getByText('审核决定已提交')).toBeVisible({ timeout: 20000 });
  }
  await expect(page.getByText('已通过').first()).toBeVisible();
  expect(errors).toEqual([]);
});
