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

// FINDING-driven quirk (documented, not worked around silently): the admin clients treat a
// 403 FORBIDDEN as a lost session (onAuthLost) — combined with the ops reads being denied
// (FA-3), merely OPENING an ops console signs the operator out. The walkthrough logs back in
// whenever the app fell back to the login page, and the transcript keeps the 403 evidence.
async function ensureLoggedIn(page: import('@playwright/test').Page) {
  if (await page.getByRole('button', { name: '登录' }).count() === 0) return;
  await page.getByLabel('账号').fill(ACCOUNT);
  await page.getByLabel('密码').fill(PASSWORD);
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page.getByRole('heading', { name: '商家申请审核' })).toBeVisible({ timeout: 30_000 });
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

  // S1 ADM-001 faces. FA-6 finding in the UI: the services console requires envelope
  // message==='ok' but the services admin controller replies message='成功', so EVERY
  // services-face response (empty or not) is rejected as INVALID_RESPONSE and the console
  // can never render data — asserted as the error state, recorded as the finding evidence.
  await nav(page, '服务审核');
  await expect(page.getByRole('heading', { name: '服务审核' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('alert').first()).toBeVisible({ timeout: 20_000 });
  await shot(page, '02-services-list-fa6-error-state');

  // S2 contract-63 trade read face: orders list + detail (SPA-internal navigation).
  await nav(page, '订单总览');
  await expect(page.getByRole('heading', { name: '订单总览' })).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 20_000 });
  await shot(page, '04-trade-orders');
  await page.getByRole('link', { name: '详情' }).first().click();
  // FA-5 blast radius in the UI: the detail page loads getOrder fine but its status-log
  // timeline call 400s on the hyphenated action code, so the whole console errors out —
  // asserted as the error state (the HTTP-level 200 for getOrder is probe evidence).
  await expect(page.getByRole('heading', { name: '订单详情' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('alert').first()).toBeVisible({ timeout: 20_000 });
  await shot(page, '05-trade-order-detail-fa5-error-state');
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
  // (FA-3) so each page lands on its error/empty state — recorded, not hidden. Each 403 also
  // signs the operator out (the clients treat FORBIDDEN as auth-lost), so the walkthrough
  // logs back in between the blocks and says so in the report.
  for (const block of [
    { nav: '百科分类', heading: '宠物百科 · 分类管理', shot: '14-ops-baike-categories' },
    { nav: '品种库', heading: '宠物百科 · 品种库', shot: '15-ops-baike-breeds' },
    { nav: '券模板', heading: '优惠券 · 券模板', shot: '16-ops-coupon-templates' },
    { nav: '积分台账', heading: '台账 · 积分流水', shot: '17-ops-points-ledger' },
    { nav: '系统设置', heading: '系统设置 · 运行时开关', shot: '18-ops-settings-runtime' },
  ] as const) {
    await ensureLoggedIn(page);
    await nav(page, block.nav);
    // The 403 flips the app back to /login as soon as it lands, so the console shell and the
    // login page are both legitimate terminal states of this navigation; the screenshot
    // records whichever won the race and the apiStatus assertion below keeps the FA-3 proof.
    await expect(page.getByRole('heading', { name: block.heading })
      .or(page.getByRole('button', { name: '登录' }))).toBeVisible({ timeout: 30_000 });
    await shot(page, block.shot);
  }

  // Finding cross-check from the live API transcript: every ops GET must have been denied.
  const opsPaths = apiStatus.filter(entry => entry.path.startsWith('/api/v1/admin/encyclopedia')
    || entry.path.startsWith('/api/v1/admin/coupon')
    || entry.path.startsWith('/api/v1/admin/points/ledger')
    || entry.path.startsWith('/api/v1/admin/settings'));
  expect(opsPaths.length).toBeGreaterThan(0);
  for (const entry of opsPaths) expect(entry.status, entry.path).toBe(403);

  expect(errors).toEqual([]);
});
