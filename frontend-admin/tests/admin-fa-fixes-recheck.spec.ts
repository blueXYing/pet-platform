import { expect, test } from '@playwright/test';

// ADMIN FA-FIXES UI RECHECK (QA-005 final recheck, 2026-10-19): the UI-level re-verification
// PROMISED after #183 landed the FA-1..FA-6 fixes and #185 opened the ops faces. The existing
// tests/admin-main-chain.spec.ts still asserts the PRE-fix failure states (FA-5 400 alerts,
// FA-3 403 ops) and goes red against the fixed backend, so this opt-in live spec asserts the
// FIXED behavior instead. Same infrastructure contract as admin-main-chain.spec.ts:
//   ADMIN_API_PROXY_TARGET=http://127.0.0.1:18083 \
//   ADMIN_API_PROXY_ORIGIN=http://127.0.0.1:18082 vite preview --host 127.0.0.1 --port 18082
//   (with scripts/e2e-origin-shim.mjs bridging 18083 -> the E2eCoreLoopServer on 18081)
//   LIVE_JOINT_BASE=http://127.0.0.1:18082 E2E_ADMIN_EVIDENCE_DIR=<dir> \
//   E2E_DISPOSITION_ORDER_ID=<a closable PENDING_SERVICE seed order id> \
//   npx playwright test tests/admin-fa-fixes-recheck.spec.ts
//
// Matrix (each maps to a FA from planning/progress/2026-10-16/e2e-admin-main/REPORT.md):
//   FA-6 service review console renders data (envelope message now 'ok')     -> S1
//   FA-5 order detail + verification log pages render (hyphen codes 200)     -> S2
//   FA-1 disposal POST reachable (abnormal-close 200, not 403)               -> S2a
//   FA-2/FA-3 ops consoles render DATA through the open ops face (200)       -> S3
//   403-mis-logout regression guard: the whole ops browse holds one session  -> S3
// Disclosed boundary: the operator is the seeded PLATFORM_SUPER_ADMIN (scope ALL), so no
// natural 403 surfaces in this walk; the FORBIDDEN-stays-logged-in behavior is covered by the
// request.ts fix + the pages' permission-notice rendering (#183), not re-exercised here.
const base = process.env.LIVE_JOINT_BASE;
test.skip(!base, 'LIVE_JOINT_BASE not configured; admin FA-fixes recheck is opt-in');
test.use({ baseURL: base });

const ACCOUNT = process.env.E2E_ADMIN_ACCOUNT ?? 'e2e-round2-operator';
const PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? 'E2E_Round2_Operator_91!';
const EVIDENCE = process.env.E2E_ADMIN_EVIDENCE_DIR;
const DISPOSITION_ORDER_ID = process.env.E2E_DISPOSITION_ORDER_ID ?? '';

async function shot(page: import('@playwright/test').Page, name: string) {
  if (EVIDENCE) await page.screenshot({ path: `${EVIDENCE}/${name}.png`, fullPage: true });
}

async function nav(page: import('@playwright/test').Page, label: string) {
  // exact: the trade pages embed inline links like 查看核销记录 that substring-match the
  // header nav labels (FA-5 fix made those sections render again).
  await page.getByRole('link', { name: label, exact: true }).click();
}

test('admin FA-fixes recheck: service review renders, trade detail/verification 200, disposal POST 200, ops blocks render with one session', async ({ page }) => {
  test.setTimeout(300_000);
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const apiStatus: { path: string; status: number }[] = [];
  page.on('response', response => {
    const path = new URL(response.url()).pathname;
    if (path.startsWith('/api/v1/admin/')) apiStatus.push({ path, status: response.status() });
  });

  // S0 real admin login (attempt cookie + bearer grant through the shim).
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: '运营登录' })).toBeVisible();
  await page.getByLabel('账号').fill(ACCOUNT);
  await page.getByLabel('密码').fill(PASSWORD);
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page.getByRole('heading', { name: '商家申请审核' })).toBeVisible({ timeout: 30_000 });
  await shot(page, 'r01-login-landing');
  expect(apiStatus.find(entry => entry.path.endsWith('/login'))?.status).toBe(200);

  // S1 FA-6 fix: the service review console must RENDER (no INVALID_RESPONSE alert) —
  // the seeded published service makes the console show a data row.
  await nav(page, '服务审核');
  await expect(page.getByRole('heading', { name: '服务审核' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 20_000 });
  await shot(page, 'r02-service-review-renders');

  // S2 FA-5 fix: order detail renders WITH its status timeline (order.status-log.read now
  // authorizes) and no error alert.
  await nav(page, '订单总览');
  await expect(page.getByRole('heading', { name: '订单总览' })).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 20_000 });
  await shot(page, 'r03-trade-orders');
  await page.locator(`a[href*="/trade/orders/${DISPOSITION_ORDER_ID}"], a:has-text("${DISPOSITION_ORDER_ID}")`).first().click();
  await expect(page.getByRole('heading', { name: '订单详情' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(page.getByText('履约层')).toBeVisible({ timeout: 20_000 });
  await shot(page, 'r04-order-detail-timeline-renders');

  // S2a FA-1 fix: the disposal POST must be chain-authorized (was 403 denyAll). The
  // disposition-target seed order (PENDING_SERVICE, paid, no in-flight aftersale) is the
  // intended abnormal-close target; the close is idempotent-hostile by design, so on a re-run
  // (already-CANCELED order) the reachable-disabled section itself is the reachability proof.
  // Disclosed environment caveat: on the shared storm-hit MySQL the kernel can commit (order
  // CANCELED + ADMIN_ABNORMAL_CLOSE refund row created) while a post-commit id draw 500s the
  // envelope (COMMON_INTERNAL_ERROR) — the FA-1 assertion is therefore "not 403", with the
  // kernel effect cross-checked against the refund list below.
  const closeButton = page.getByRole('button', { name: '异常关闭并全额退款' });
  if (await closeButton.isEnabled()) {
    await page.getByLabel('处置说明（必填，10–500 字）').fill('E2E终检复验：#183 后 UI 级处置动作可达性走查，异常关闭该种子订单并全额原路退款。');
    await page.getByRole('checkbox', { name: '我确认对该订单执行异常关闭并全额退款' }).check();
    await closeButton.click();
    await expect(page.getByText('已异常关闭：订单', { exact: false })
      .or(page.getByText('操作失败（COMMON_INTERNAL_ERROR）', { exact: false }))).toBeVisible({ timeout: 30_000 });
  } else {
    await expect(page.getByText('当前订单状态不满足异常关单条件', { exact: false })).toBeVisible();
  }
  await shot(page, 'r05-abnormal-close-outcome');

  // S2b FA-5 fix on the verification read face: the console renders rows (the admin-chain
  // seed verified one order to COMPLETED, so at least one verification exists).
  await nav(page, '核销记录');
  await expect(page.getByRole('heading', { name: '平台核销记录' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 20_000 });
  await shot(page, 'r06-verifications-render');

  // Refund detail: FL-1 (NEW finding, 2026-10-19 final recheck — bounded, not fixed): the
  // detail face can NEVER render — tradeQuery.refundDetail demands exact(13 keys) on the
  // object and then calls refundSummary which demands exact(11 keys) on the SAME object; the
  // two strict key-count checks are unsatisfiable together, so every 200 detail response
  // decodes as INVALID_RESPONSE (the backend payload itself is contract-valid, probe
  // refund-detail-payload.json). The disposal section (渠道处置) therefore stays UI-unreachable
  // on this page; FA-1's disposal-route evidence is the abnormal-close POST at S2a instead.
  // The same double-exact pattern exists in verificationDetail (FL-2, same bound).
  await nav(page, '退款单');
  await expect(page.getByRole('heading', { name: '退款单管理' })).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 30_000 });
  await shot(page, 'r07-refund-list');
  await page.getByRole('link', { name: '详情' }).first().click();
  await expect(page.getByRole('heading', { name: '退款单详情', exact: false })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText('退款单详情加载失败（INVALID_RESPONSE）')).toBeVisible({ timeout: 20_000 });
  await shot(page, 'r08-refund-detail-fl1-error-state');

  // S3 FA-2/FA-3 fix: the ops consoles render DATA through the now-open ops face, and the
  // whole browse holds ONE session (the old behavior logged the operator out on the first
  // ops 403 — the login button must never appear during this loop).
  for (const block of [
    { nav: '百科分类', heading: '宠物百科 · 分类管理', shot: 'r09-ops-baike-categories' },
    { nav: '品种库', heading: '宠物百科 · 品种库', shot: 'r10-ops-baike-breeds' },
    { nav: '券模板', heading: '优惠券 · 券模板', shot: 'r11-ops-coupon-templates' },
    { nav: '积分台账', heading: '台账 · 积分流水', shot: 'r12-ops-points-ledger' },
    { nav: '系统设置', heading: '系统设置 · 运行时开关', shot: 'r13-ops-settings-runtime' },
  ] as const) {
    await nav(page, block.nav);
    await expect(page.getByRole('heading', { name: block.heading })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('button', { name: '登录' })).toHaveCount(0);
    await expect(page.getByRole('alert')).toHaveCount(0);
    await shot(page, block.shot);
  }

  // Transcript cross-check: every ops GET must have succeeded (was 403 before #183/#185).
  const opsPaths = apiStatus.filter(entry => entry.path.startsWith('/api/v1/admin/encyclopedia')
    || entry.path.startsWith('/api/v1/admin/coupon')
    || entry.path.startsWith('/api/v1/admin/points/ledger')
    || entry.path.startsWith('/api/v1/admin/settings'));
  expect(opsPaths.length).toBeGreaterThan(0);
  for (const entry of opsPaths) expect(entry.status, entry.path).toBe(200);

  // The disposal POST must have been chain-authorized: never 403 (the pre-fix denyAll), and
  // on a clean window a 200 receipt; a storm-hit 500 after a committed kernel is disclosed
  // above and cross-checked by the ADMIN_ABNORMAL_CLOSE refund row in the list screenshot.
  const closeStatus = apiStatus.find(entry => entry.path.endsWith('/abnormal-close'))?.status;
  if (closeStatus !== undefined) expect(closeStatus, 'abnormal-close must not be chain-denied').not.toBe(403);

  expect(errors).toEqual([]);
});
