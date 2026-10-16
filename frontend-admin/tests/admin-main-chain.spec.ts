import { expect, test } from '@playwright/test';

// ADMIN WEB MAIN-CHAIN live walkthrough (QA-005, 2026-10-16): real browser against the real
// round-4 E2eCoreLoopServer through the controlled API proxy (+ the QA origin shim, see
// scripts/e2e-origin-shim.mjs). Runs ONLY when LIVE_JOINT_BASE is configured (same opt-in
// contract as live.spec.ts), so CI and the normal suite skip it:
//   ADMIN_API_PROXY_TARGET=http://127.0.0.1:18083 \
//   ADMIN_API_PROXY_ORIGIN=http://127.0.0.1:18082 vite preview --host 127.0.0.1 --port 18082
//   LIVE_JOINT_BASE=http://127.0.0.1:18082 \
//   E2E_ADMIN_ACCOUNT=... E2E_ADMIN_PASSWORD=... E2E_ADMIN_EVIDENCE_DIR=<dir> \
//   npx playwright test tests/admin-main-chain.spec.ts
//
// Disclosed mock boundary: NONE — every page loads through the real backend. Navigation stays
// INSIDE the SPA (header links + row links) because the admin token is in-memory only; a full
// page.goto would drop the session by design. Known findings surface as their real UI states:
// FA-5 (status-logs/verification reads 400) and FA-3 (ops reads 403 behind the security chain).
const base = process.env.LIVE_JOINT_BASE;
test.skip(!base, 'LIVE_JOINT_BASE not configured; admin main-chain live is opt-in');
test.use({ baseURL: base });

const ACCOUNT = process.env.E2E_ADMIN_ACCOUNT ?? 'e2e-round2-operator';
const PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? 'E2E_Round2_Operator_91!';
const EVIDENCE = process.env.E2E_ADMIN_EVIDENCE_DIR;
const STORE_ID = process.env.E2E_STORE_ID ?? '710302';
const MERCHANT_ID = process.env.E2E_MERCHANT_ID ?? '710301';

async function shot(page: import('@playwright/test').Page, name: string) {
  if (EVIDENCE) await page.screenshot({ path: `${EVIDENCE}/${name}.png`, fullPage: true });
}

async function nav(page: import('@playwright/test').Page, label: string) {
  await page.getByRole('link', { name: label }).click();
}

test('admin main chain: login -> ADM-001 -> trade -> aftersale -> governance -> ops blocks', async ({ page }) => {
  test.setTimeout(300_000);
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const apiStatus: { path: string; status: number }[] = [];
  page.on('response', response => {
    const path = new URL(response.url()).pathname;
    if (path.startsWith('/api/v1/admin/')) apiStatus.push({ path, status: response.status() });
  });

  // S0 real admin login through the controlled proxy (attempt cookie + bearer grant).
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: '运营登录' })).toBeVisible();
  await page.getByLabel('账号').fill(ACCOUNT);
  await page.getByLabel('密码').fill(PASSWORD);
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page.getByRole('heading', { name: '商家申请审核' })).toBeVisible({ timeout: 30_000 });
  await shot(page, '01-login-landing-review-list');
  expect(apiStatus.find(entry => entry.path.endsWith('/login'))?.status).toBe(200);

  // S1 ADM-001 faces: service review list + detail (the reviewed ACTIVE service).
  await nav(page, '服务审核');
  await expect(page.getByRole('heading', { name: '服务审核' })).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 20_000 });
  await shot(page, '02-services-list');
  await page.getByRole('link', { name: '审核' }).first().click();
  await expect(page.getByText('E2E到店精细洗护').first()).toBeVisible({ timeout: 20_000 });
  await shot(page, '03-service-detail');

  // S2 contract-63 trade read face: orders list + detail (SPA-internal navigation).
  await nav(page, '订单总览');
  await expect(page.getByRole('heading', { name: '订单总览' })).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 20_000 });
  await shot(page, '04-trade-orders');
  await page.getByRole('link', { name: '详情' }).first().click();
  await expect(page.locator('table, [role="table"], dl, section').first()).toBeVisible({ timeout: 20_000 });
  await shot(page, '05-trade-order-detail');
  // FA-5 in the UI: the verification console loads but its data face 400s on every call.
  await nav(page, '核销记录');
  await expect(page.getByRole('heading', { name: '平台核销记录' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('alert').first()).toBeVisible({ timeout: 20_000 });
  await shot(page, '06-trade-verifications-fa5-error-state');
  await nav(page, '退款单');
  await expect(page.getByRole('heading', { name: '退款单管理' })).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 30_000 });
  await shot(page, '07-trade-refunds');

  // A-004 aftersale console: store-scoped list, then the adjudicated case detail (RESOLVED).
  await nav(page, '售后工单');
  await expect(page.getByRole('heading', { name: '售后工单' })).toBeVisible({ timeout: 20_000 });
  await page.getByLabel('商家编号').fill(MERCHANT_ID);
  await page.getByLabel('门店编号').fill(STORE_ID);
  await page.getByRole('button', { name: '查询售后' }).click();
  const caseLink = page.getByRole('link', { name: '查看工单' }).first();
  await expect(caseLink).toBeVisible({ timeout: 30_000 });
  await shot(page, '08-aftersales-list');
  await caseLink.click();
  await expect(page.getByText('已裁决').first()).toBeVisible({ timeout: 30_000 });
  await shot(page, '09-aftersale-detail-resolved');

  // Contract-64 governance faces.
  await nav(page, '商家台账');
  await expect(page.getByText('E2E体验商家').first()).toBeVisible({ timeout: 20_000 });
  await shot(page, '10-governance-merchants');
  await page.getByRole('link', { name: '详情' }).first().click();
  await expect(page.getByText('E2E体验门店').first()).toBeVisible({ timeout: 20_000 });
  await shot(page, '11-governance-merchant-detail');
  await nav(page, '排期查看');
  await expect(page.getByRole('heading', { name: '排期查看' })).toBeVisible({ timeout: 20_000 });
  await shot(page, '12-governance-schedule');
  await nav(page, '员工治理');
  await expect(page.getByRole('heading', { name: '员工治理' })).toBeVisible({ timeout: 20_000 });
  await shot(page, '13-governance-staff');

  // Contract-65 ops blocks: the four consoles render their shells; their reads are denied
  // (FA-3) so each page lands on its error/empty state — recorded, not hidden.
  await nav(page, '百科分类');
  await expect(page.getByRole('heading', { name: '宠物百科 · 分类管理' })).toBeVisible({ timeout: 20_000 });
  await shot(page, '14-ops-baike-categories');
  await nav(page, '品种库');
  await expect(page.getByRole('heading', { name: '宠物百科 · 品种库' })).toBeVisible({ timeout: 20_000 });
  await shot(page, '15-ops-baike-breeds');
  await nav(page, '券模板');
  await expect(page.getByRole('heading', { name: '优惠券 · 券模板' })).toBeVisible({ timeout: 20_000 });
  await shot(page, '16-ops-coupon-templates');
  await nav(page, '积分台账');
  await expect(page.getByRole('heading', { name: '台账 · 积分流水' })).toBeVisible({ timeout: 20_000 });
  await shot(page, '17-ops-points-ledger');
  await nav(page, '系统设置');
  await expect(page.getByRole('heading', { name: '系统设置 · 运行时开关' })).toBeVisible({ timeout: 20_000 });
  await shot(page, '18-ops-settings-runtime');

  // Finding cross-check from the live API transcript: every ops GET must have been denied.
  const opsPaths = apiStatus.filter(entry => entry.path.startsWith('/api/v1/admin/encyclopedia')
    || entry.path.startsWith('/api/v1/admin/coupon')
    || entry.path.startsWith('/api/v1/admin/points/ledger')
    || entry.path.startsWith('/api/v1/admin/settings'));
  expect(opsPaths.length).toBeGreaterThan(0);
  for (const entry of opsPaths) expect(entry.status, entry.path).toBe(403);

  expect(errors).toEqual([]);
});
