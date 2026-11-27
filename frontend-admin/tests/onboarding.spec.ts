import { expect, test, type Page } from '@playwright/test';
import type {
  OnboardingAuditView, OnboardingDefaultsView, OnboardingDetailView, OnboardingRevealView,
} from '../src/api/onboardingAdmin';

// Contract 81 §4 商家收单开通页（真实生产 UI/transport + 拦截 HTTP，同 service-category.spec.ts 纪律）。
// 覆盖：
// - 详情 404 → 「新开通」空表单并按 defaults 带出默认值；未建档时发起开通禁用；
// - §4.4 前端校验拦截（不发请求）与完整保存：multipart 上传回填 assetId、PUT 幂等键、九键 attachments；
// - 发起开通 → EC_SIGNING：四步时间线、签约链接与提示、allowedActions 严格渲染（仅 SYNC）；
// - CONTRACT_UNKNOWN：补录进件 ID / 确认拉卡拉未受理按 allowedActions 出现，补录提交 contractId+原因；
// - BIND_REJECTED：驳回与渠道错误提示、重新提交（更换合作协议）、受审明文（填原因→临时展示→隐藏清除）；
// - 未准入（eligible=false）：显示原因并禁用保存/发起。

test.use({ baseURL: 'http://127.0.0.1:4174' });

const MER = '7103000000000001';
const ROOT = '/api/v1/admin/settlement/onboardings';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64');

function envelope(data: unknown, status = 200) {
  return { status, json: { code: 'SUCCESS', message: 'ok', data, traceId: 'fixture-onboarding' } };
}
function failure(code: string, status: number) {
  return { status, json: { code, message: code, data: null, traceId: 'fixture-onboarding' } };
}

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

const stepNone = (step: string) => ({ step, status: 'NONE', submittedAt: null, finishedAt: null, summary: null, reason: null });

const defaultsBase: OnboardingDefaultsView = {
  merchantId: MER,
  eligible: true,
  ineligibleReason: null,
  merchantName: '示例宠物医院',
  merchantTypeCode: 'PET_HOSPITAL',
  cityCode: 'chengdu',
  districtCode: '510104',
  configReady: true,
  missingConfig: [],
  platform: {
    posType: '01', merBusiContent: '654', settlePeriod: 'T+1', mccCode: '0742',
    feeRates: [], splitLowestRatio: '90.00', platformReceiverConfigured: true,
  },
};

function defaultsView(overrides: Partial<OnboardingDefaultsView> = {}): OnboardingDefaultsView {
  return { ...defaultsBase, ...overrides, platform: { ...defaultsBase.platform, ...(overrides.platform ?? {}) } };
}

const detailBase: OnboardingDetailView = {
  onboardingId: '8100000000000001',
  merchantId: MER,
  status: 'DRAFT',
  version: 1,
  merchant: {
    merchantTypeCode: 'PET_HOSPITAL', merRegName: '示例宠物医院', merBizName: null,
    merRegDistCode: '510104', merRegAddr: '天府大道1号',
    merBlisName: '示例宠物医院', merBlisNoMasked: '9111****68', merBlisStDt: '2020-01-01', merBlisExpDt: '2040-01-01',
    larName: '张*', larIdType: '01', larIdcardMasked: '3425****21', larIdcardStDt: '1999-05-29', larIdcardExpDt: '2039-05-29',
    larMobileMasked: '138****1234', contactName: '李*', contactMobileMasked: '139****0000',
  },
  settlement: {
    acctTypeCode: '58', acctName: '张*', acctNoMasked: '6212****0000', acctNoLast4: '0000',
    acctIdType: null, acctIdcardMasked: null, acctIdExpDt: null,
    openningBankCode: '102100000030', openningBankName: '中国工商银行股份有限公司北京市分行营业部', clearingBankCode: '102100099996',
  },
  platform: {
    posType: '01', mccCode: '0742', merBusiContent: '654', settlePeriod: 'T+1',
    feeRates: [{ feeRateTypeCode: '302', feeRateTypeName: '微信', feeRatePct: '0.60' }], splitLowestRatio: '90.00',
  },
  attachments: {
    FR_ID_CARD_FRONT: null, FR_ID_CARD_BEHIND: null, BANK_CARD: null, BUSINESS_LICENCE: null,
    MERCHANT_PHOTO: null, SHOPINNER: null, ID_CARD_FRONT: null, ID_CARD_BEHIND: null, NO_LEGAL_PERSON_SETT_AUTH_LETTER: null,
  },
  cooperationAssetId: null,
  steps: [stepNone('EC'), stepNone('CONTRACT'), stepNone('LEDGER'), stepNone('BIND')],
  ecSignUrl: null, ecNo: null, contractId: null, merCupNo: null, termNo: null,
  rejectReason: null, lastErrorCode: null, lastErrorMessage: null,
  allowedActions: ['SAVE_PROFILE', 'SUBMIT'],
  submittedAt: null, completedAt: null,
  createdAt: '2026-11-27T00:00:00.000Z', updatedAt: '2026-11-27T00:30:00.000Z',
};

function detailView(overrides: Partial<OnboardingDetailView> = {}): OnboardingDetailView {
  return { ...detailBase, ...overrides };
}

const auditsFixture: OnboardingAuditView[] = [
  {
    auditId: '9001', actionType: 'CONTRACT_REJECTED', operatorType: 'SYSTEM', operatorId: null,
    reason: null, detail: { contractStatus: 'INNER_CHECK_REJECTED' }, createdAt: '2026-11-27T03:00:00.000Z',
  },
];

const revealFixture: OnboardingRevealView = {
  merchantId: MER, merBlisNo: '91110108MA2G7LK368', larName: '张三', larIdcard: '342523199905298212',
  larMobile: '13800001234', contactName: '李四', contactMobile: '13900000000',
  acctName: '张三', acctNo: '6212260200102400000', acctIdType: null, acctIdcard: null,
};

async function signIn(page: Page, actions = ['settlement.report.read', 'settlement.onboarding.write', 'settlement.onboarding.read-sensitive']) {
  await page.route('**/api/v1/admin/auth/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/attempts')) return route.fulfill(envelope({ attemptId: '801', attemptToken: 'fixture-binding', expiresAt: '2026-11-28T02:00:00.000Z', nextStep: 'PROVE_IDENTITY' }, 201));
    if (path.endsWith('/requirements')) return route.fulfill(envelope({ requiredVerification: 'NONE' }));
    if (path.endsWith('/login')) return route.fulfill(envelope({ sessionId: 's1', operatorId: '901', audience: 'ADMIN_WEB', tokenType: 'Bearer', accessToken: 'fixture-admin-token', expiresAt: '2026-11-28T02:00:00.000Z' }));
    if (path.endsWith('/session')) return route.fulfill(envelope({ sessionId: 's1', operatorId: '901', audience: 'ADMIN_WEB', expiresAt: '2026-11-28T02:00:00.000Z', idleExpiresAt: '2026-11-28T01:00:00.000Z', authzVersion: 'v1' }));
    if (path.endsWith('/permissions')) return route.fulfill(envelope({ operatorId: '901', authzVersion: 'v1', checkedAt: '2026-11-27T02:00:00.000Z', roles: ['OPERATOR'], dataScope: 'ALL', actionCodes: actions }));
    return route.fulfill(envelope(null));
  });
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill('fixture-ops');
  await page.getByLabel('密码', { exact: true }).fill('fixture-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('link', { name: '商家收单开通' }).click();
  await expect(page.getByRole('heading', { name: '商家收单开通' })).toBeVisible();
}

async function queryMerchant(page: Page) {
  await page.getByLabel('商家 ID', { exact: true }).fill(MER);
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByRole('heading', { name: `商家准入与平台级参数 · 商家 ${MER}` })).toBeVisible();
}

test('81 §4 新开通：defaults 带出空表单，校验拦截后完整保存（PUT 幂等键）并发起开通', async ({ page }) => {
  const writes = captureWrites(page);
  let uploads = 0;
  await page.route(`**${ROOT}/${MER}/audits`, route => route.fulfill(envelope([])));
  await page.route(`**${ROOT}/${MER}/assets`, route => {
    uploads++;
    return route.fulfill(envelope({ assetId: `A${uploads}`, mediaType: 'image/png', bytes: PNG.byteLength }));
  });
  await page.route(`**${ROOT}/${MER}/submit`, route => route.fulfill(envelope(detailView({
    status: 'EC_SIGNING', version: 2, allowedActions: ['SYNC'],
    steps: [
      { step: 'EC', status: 'WAITING_SIGN', submittedAt: '2026-11-27T05:00:00.000Z', finishedAt: null, summary: '待商家法人签署', reason: null },
      stepNone('CONTRACT'), stepNone('LEDGER'), stepNone('BIND'),
    ],
    ecSignUrl: 'https://ec.lakala.example.com/sign/abc123',
    submittedAt: '2026-11-27T05:00:00.000Z', updatedAt: '2026-11-27T05:00:00.000Z',
  }))));
  await page.route(`**${ROOT}/${MER}`, route => {
    if (route.request().method() === 'GET') return route.fulfill(failure('COMMON_NOT_FOUND', 404));
    return route.fulfill(envelope(detailView({
      status: 'DRAFT', version: 1,
      attachments: {
        FR_ID_CARD_FRONT: 'A1', FR_ID_CARD_BEHIND: 'A2', BANK_CARD: 'A3', BUSINESS_LICENCE: 'A4',
        MERCHANT_PHOTO: 'A5', SHOPINNER: 'A6', ID_CARD_FRONT: null, ID_CARD_BEHIND: null, NO_LEGAL_PERSON_SETT_AUTH_LETTER: null,
      },
    })));
  });
  await page.route(`**${ROOT}/${MER}/defaults`, route => route.fulfill(envelope(defaultsView())));
  await signIn(page);
  await queryMerchant(page);

  // defaults 带出：商家名称/类型/城市/地区码/MCC 进表单；未建档时发起开通禁用
  await expect(page.getByRole('heading', { name: /编辑开通资料（新开通/ })).toBeVisible();
  await expect(page.locator('#ob-merRegName')).toHaveValue('示例宠物医院');
  await expect(page.locator('#ob-merRegDistCode')).toHaveValue('510104');
  await expect(page.locator('#ob-mccCode')).toHaveValue('0742');
  await expect(page.getByRole('button', { name: '发起开通' })).toBeDisabled();

  // 空表单校验拦截：可读提示且不发 PUT
  await page.getByRole('button', { name: '保存资料' }).click();
  await expect(page.getByRole('alert').filter({ hasText: '注册地址' })).toContainText('注册地址须为 6～80 字');
  expect(writes.filter(w => w.method === 'PUT')).toHaveLength(0);

  // 完整填写 §4.4 资料并上传六个必传附件
  await page.locator('#ob-merRegAddr').fill('天府大道1号');
  await page.locator('#ob-merBlisNo').fill('91110108MA2G7LK368');
  await page.locator('#ob-merBlisStDt').fill('2020-01-01');
  await page.locator('#ob-merBlisExpDt').fill('2040-01-01');
  await page.locator('#ob-larName').fill('张三');
  await page.locator('#ob-larIdcard').fill('342523199905298212');
  await page.locator('#ob-larIdcardStDt').fill('1999-05-29');
  await page.locator('#ob-larIdcardExpDt').fill('2039-05-29');
  await page.locator('#ob-larMobile').fill('13800001234');
  await page.locator('#ob-contactName').fill('李四');
  await page.locator('#ob-contactMobile').fill('13900000000');
  await page.locator('#ob-acctName').fill('张三');
  await page.locator('#ob-acctNo').fill('6212260200102400000');
  await page.locator('#ob-openningBankCode').fill('102100000030');
  await page.locator('#ob-openningBankName').fill('中国工商银行股份有限公司北京市分行营业部');
  await page.locator('#ob-clearingBankCode').fill('102100099996');
  for (const type of ['FR_ID_CARD_FRONT', 'FR_ID_CARD_BEHIND', 'BANK_CARD', 'BUSINESS_LICENCE', 'MERCHANT_PHOTO', 'SHOPINNER']) {
    await page.locator(`[data-att-type="${type}"] input[type="file"]`).setInputFiles({ name: `${type}.png`, mimeType: 'image/png', buffer: PNG });
  }
  await expect(page.locator('[data-att-type="SHOPINNER"]')).toContainText('assetId: A6');
  expect(uploads).toBe(6);

  await page.getByRole('button', { name: '保存资料' }).click();
  await expect(page.getByText(/开通资料已保存/)).toBeVisible();
  const puts = writes.filter(w => w.method === 'PUT');
  expect(puts).toHaveLength(1);
  expect(puts[0].requestId).toMatch(UUID);
  expect(puts[0].path).toBe(`${ROOT}/${MER}`);
  expect(puts[0].body).toEqual({
    expectedVersion: 0,
    merchant: {
      merRegName: '示例宠物医院', merBizName: null, merRegDistCode: '510104', merRegAddr: '天府大道1号', mccCode: '0742',
      merBlisName: '示例宠物医院', merBlisNo: '91110108MA2G7LK368', merBlisStDt: '2020-01-01', merBlisExpDt: '2040-01-01',
      larName: '张三', larIdcard: '342523199905298212', larIdcardStDt: '1999-05-29', larIdcardExpDt: '2039-05-29',
      larMobile: '13800001234', contactName: '李四', contactMobile: '13900000000',
    },
    settlement: {
      acctTypeCode: '58', acctName: '张三', acctNo: '6212260200102400000',
      acctIdType: null, acctIdcard: null, acctIdExpDt: null,
      openningBankCode: '102100000030', openningBankName: '中国工商银行股份有限公司北京市分行营业部', clearingBankCode: '102100099996',
    },
    attachments: {
      FR_ID_CARD_FRONT: 'A1', FR_ID_CARD_BEHIND: 'A2', BANK_CARD: 'A3', BUSINESS_LICENCE: 'A4',
      MERCHANT_PHOTO: 'A5', SHOPINNER: 'A6', ID_CARD_FRONT: null, ID_CARD_BEHIND: null, NO_LEGAL_PERSON_SETT_AUTH_LETTER: null,
    },
    cooperationAssetId: null,
    reason: '运营代填开通资料',
  });

  // 建档后发起开通（Popconfirm 确认）→ EC_SIGNING：时间线 + 签约链接
  await expect(page.getByRole('button', { name: '发起开通' })).toBeEnabled();
  await page.getByRole('button', { name: '发起开通' }).click();
  await page.getByRole('button', { name: '确认发起' }).click();
  await expect(page.getByText(/已发起开通/)).toBeVisible();
  await expect(page.getByRole('textbox', { name: '签约链接' })).toHaveValue('https://ec.lakala.example.com/sign/abc123');
  await expect(page.getByText('请将签约链接发给商家法人在微信内打开完成人脸签署。')).toBeVisible();
  await expect(page.getByText('电子合同待签署', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '同步', exact: true })).toBeVisible();
  const submits = writes.filter(w => w.method === 'POST' && w.path === `${ROOT}/${MER}/submit`);
  expect(submits).toHaveLength(1);
  expect(submits[0].requestId).toMatch(UUID);
  expect(submits[0].body).toEqual({ expectedVersion: 1 });
});

test('81 §4 EC_SIGNING 详情：四步时间线与签约链接，操作严格按 allowedActions（仅同步）', async ({ page }) => {
  const writes = captureWrites(page);
  await page.route(`**${ROOT}/${MER}/audits`, route => route.fulfill(envelope(auditsFixture)));
  await page.route(`**${ROOT}/${MER}/sync`, route => route.fulfill(envelope(detailView({
    status: 'CONTRACT_APPLYING', version: 4, allowedActions: ['SYNC'],
    steps: [
      { step: 'EC', status: 'DONE', submittedAt: '2026-11-27T01:00:00.000Z', finishedAt: '2026-11-27T02:00:00.000Z', summary: '已签署', reason: null },
      { step: 'CONTRACT', status: 'APPLYING', submittedAt: '2026-11-27T02:00:00.000Z', finishedAt: null, summary: '渠道受理中', reason: null },
      stepNone('LEDGER'), stepNone('BIND'),
    ],
    ecSignUrl: null, ecNo: 'QT2026112700001',
    updatedAt: '2026-11-27T06:00:00.000Z',
  }))));
  await page.route(`**${ROOT}/${MER}`, route => route.fulfill(envelope(detailView({
    status: 'EC_SIGNING', version: 3, allowedActions: ['SYNC'],
    steps: [
      { step: 'EC', status: 'WAITING_SIGN', submittedAt: '2026-11-27T01:00:00.000Z', finishedAt: null, summary: '待商家法人签署', reason: null },
      stepNone('CONTRACT'), stepNone('LEDGER'), stepNone('BIND'),
    ],
    ecSignUrl: 'https://ec.lakala.example.com/sign/abc123',
    submittedAt: '2026-11-27T01:00:00.000Z',
  }))));
  await page.route(`**${ROOT}/${MER}/defaults`, route => route.fulfill(envelope(defaultsView())));
  await signIn(page);
  await queryMerchant(page);

  await expect(page.getByText('电子合同待签署', { exact: true })).toBeVisible();
  for (const stepName of ['电子合同', '进件', '开通分账', '绑定']) {
    await expect(page.getByText(stepName, { exact: true })).toBeVisible();
  }
  await expect(page.getByRole('textbox', { name: '签约链接' })).toHaveValue('https://ec.lakala.example.com/sign/abc123');
  await expect(page.getByText('请将签约链接发给商家法人在微信内打开完成人脸签署。')).toBeVisible();
  await expect(page.getByText('审计记录（1 条）')).toBeVisible();

  // allowedActions=['SYNC']：只出现同步，不出现其它写操作与资料编辑
  await expect(page.getByRole('button', { name: '同步', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '发起开通' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '重新提交' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '补录进件 ID' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '确认拉卡拉未受理' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '保存资料' })).toHaveCount(0);

  await page.getByRole('button', { name: '同步', exact: true }).click();
  await expect(page.getByText(/已同步商家/)).toBeVisible();
  await expect(page.getByText('进件申请中', { exact: true })).toBeVisible();
  const syncs = writes.filter(w => w.method === 'POST' && w.path === `${ROOT}/${MER}/sync`);
  expect(syncs).toHaveLength(1);
  expect(syncs[0].requestId).toMatch(UUID);
  expect(syncs[0].body).toEqual({});
});

test('81 §4 CONTRACT_UNKNOWN：补录进件 ID 与确认未受理按 allowedActions 出现，补录提交 contractId 与原因', async ({ page }) => {
  const writes = captureWrites(page);
  await page.route(`**${ROOT}/${MER}/audits`, route => route.fulfill(envelope([])));
  await page.route(`**${ROOT}/${MER}/contract/adopt`, route => route.fulfill(envelope(detailView({
    status: 'CONTRACT_APPLYING', version: 5, allowedActions: ['SYNC'],
    steps: [
      { step: 'EC', status: 'DONE', submittedAt: '2026-11-27T01:00:00.000Z', finishedAt: '2026-11-27T02:00:00.000Z', summary: '已签署', reason: null },
      { step: 'CONTRACT', status: 'APPLYING', submittedAt: '2026-11-27T06:00:00.000Z', finishedAt: null, summary: '按补录进件 ID 查询', reason: null },
      stepNone('LEDGER'), stepNone('BIND'),
    ],
    ecSignUrl: null, ecNo: 'QT2026112700001', contractId: '202104062502898611',
  }))));
  await page.route(`**${ROOT}/${MER}`, route => route.fulfill(envelope(detailView({
    status: 'CONTRACT_UNKNOWN', version: 4, allowedActions: ['ADOPT_CONTRACT', 'DISCARD_CONTRACT'],
    steps: [
      { step: 'EC', status: 'DONE', submittedAt: '2026-11-27T01:00:00.000Z', finishedAt: '2026-11-27T02:00:00.000Z', summary: '已签署', reason: null },
      { step: 'CONTRACT', status: 'UNKNOWN', submittedAt: '2026-11-27T02:00:00.000Z', finishedAt: null, summary: '渠道查询无结果', reason: null },
      stepNone('LEDGER'), stepNone('BIND'),
    ],
    ecSignUrl: null, ecNo: 'QT2026112700001',
    lastErrorCode: 'QUERY_TIMEOUT', lastErrorMessage: '渠道查询暂不可用',
  }))));
  await page.route(`**${ROOT}/${MER}/defaults`, route => route.fulfill(envelope(defaultsView())));
  await signIn(page);
  await queryMerchant(page);

  await expect(page.getByText('进件结果未知', { exact: true })).toBeVisible();
  await expect(page.getByText(/最近渠道错误：QUERY_TIMEOUT（渠道查询暂不可用）/)).toBeVisible();
  await expect(page.getByRole('button', { name: '补录进件 ID' })).toBeVisible();
  await expect(page.getByRole('button', { name: '确认拉卡拉未受理' })).toBeVisible();
  await expect(page.getByRole('button', { name: '同步', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '重新提交' })).toHaveCount(0);

  await page.getByRole('button', { name: '补录进件 ID' }).click();
  await page.locator('#ob-adopt-contract-id').fill('202104062502898611');
  await page.locator('#ob-adopt-reason').fill('已与拉卡拉核实进件已受理');
  await page.getByRole('button', { name: '确认补录' }).click();
  await expect(page.getByText(/已补录进件 ID/)).toBeVisible();
  await expect(page.getByText('进件申请中', { exact: true })).toBeVisible();
  const adopts = writes.filter(w => w.path === `${ROOT}/${MER}/contract/adopt`);
  expect(adopts).toHaveLength(1);
  expect(adopts[0].method).toBe('POST');
  expect(adopts[0].requestId).toMatch(UUID);
  expect(adopts[0].body).toEqual({ expectedVersion: 4, contractId: '202104062502898611', reason: '已与拉卡拉核实进件已受理' });
});

test('81 §4 BIND_REJECTED：驳回与渠道错误提示、重新提交（更换合作协议）与受审明文临时查看', async ({ page }) => {
  const writes = captureWrites(page);
  await page.route(`**${ROOT}/${MER}/audits`, route => route.fulfill(envelope([])));
  await page.route(`**${ROOT}/${MER}/reveal`, route => route.fulfill(envelope(revealFixture)));
  await page.route(`**${ROOT}/${MER}/retry`, route => route.fulfill(envelope(detailView({
    status: 'BIND_APPLYING', version: 6, allowedActions: ['SYNC'],
    steps: [
      { step: 'EC', status: 'DONE', submittedAt: '2026-11-27T01:00:00.000Z', finishedAt: '2026-11-27T02:00:00.000Z', summary: '已签署', reason: null },
      { step: 'CONTRACT', status: 'DONE', submittedAt: '2026-11-27T02:00:00.000Z', finishedAt: '2026-11-27T03:00:00.000Z', summary: '进件完成', reason: null },
      { step: 'LEDGER', status: 'DONE', submittedAt: '2026-11-27T03:00:00.000Z', finishedAt: '2026-11-27T04:00:00.000Z', summary: '分账已开通', reason: null },
      { step: 'BIND', status: 'APPLYING', submittedAt: '2026-11-27T07:00:00.000Z', finishedAt: null, summary: '重新提交绑定审核', reason: null },
    ],
    cooperationAssetId: 'C901', contractId: '202104062502898611', ecNo: 'QT2026112700001',
    merCupNo: '82229007011009E', termNo: 'B0100551',
    rejectReason: null, lastErrorCode: null, lastErrorMessage: null,
    submittedAt: '2026-11-27T01:00:00.000Z', updatedAt: '2026-11-27T07:00:00.000Z',
  }))));
  await page.route(`**${ROOT}/${MER}`, route => route.fulfill(envelope(detailView({
    status: 'BIND_REJECTED', version: 5, allowedActions: ['RETRY'],
    steps: [
      { step: 'EC', status: 'DONE', submittedAt: '2026-11-27T01:00:00.000Z', finishedAt: '2026-11-27T02:00:00.000Z', summary: '已签署', reason: null },
      { step: 'CONTRACT', status: 'DONE', submittedAt: '2026-11-27T02:00:00.000Z', finishedAt: '2026-11-27T03:00:00.000Z', summary: '进件完成', reason: null },
      { step: 'LEDGER', status: 'DONE', submittedAt: '2026-11-27T03:00:00.000Z', finishedAt: '2026-11-27T04:00:00.000Z', summary: '分账已开通', reason: null },
      { step: 'BIND', status: 'REJECTED', submittedAt: '2026-11-27T04:00:00.000Z', finishedAt: null, summary: '绑定审核被驳回', reason: '合作协议签署人与结算账户不符' },
    ],
    cooperationAssetId: 'C900', contractId: '202104062502898611', ecNo: 'QT2026112700001',
    merCupNo: '82229007011009E', termNo: 'B0100551',
    rejectReason: '合作协议签署人与结算账户不符',
    lastErrorCode: 'BIND_AUDIT_REJECTED', lastErrorMessage: '绑定审核未通过',
    submittedAt: '2026-11-27T01:00:00.000Z',
  }))));
  await page.route(`**${ROOT}/${MER}/defaults`, route => route.fulfill(envelope(defaultsView())));
  await signIn(page);
  await queryMerchant(page);

  await expect(page.getByText('绑定已驳回', { exact: true })).toBeVisible();
  await expect(page.getByText(/渠道驳回原因：合作协议签署人与结算账户不符/)).toBeVisible();
  await expect(page.getByText(/最近渠道错误：BIND_AUDIT_REJECTED（绑定审核未通过）/)).toBeVisible();
  await expect(page.getByRole('button', { name: '重新提交' })).toBeVisible();

  await page.getByRole('button', { name: '重新提交' }).click();
  await expect(page.locator('#ob-retry-coop')).toHaveValue('C900');
  await page.locator('#ob-retry-coop').fill('C901');
  await page.locator('#ob-retry-reason').fill('已更换合作协议');
  await page.getByRole('button', { name: '确认重新提交' }).click();
  await expect(page.getByText(/已重新提交渠道审核/)).toBeVisible();
  const retries = writes.filter(w => w.path === `${ROOT}/${MER}/retry`);
  expect(retries).toHaveLength(1);
  expect(retries[0].requestId).toMatch(UUID);
  expect(retries[0].body).toEqual({ expectedVersion: 5, cooperationAssetId: 'C901', reason: '已更换合作协议' });

  // 受审明文：填原因 → 临时展示 → 隐藏即清除
  await page.getByRole('button', { name: '查看明文' }).click();
  await page.locator('#ob-reveal-reason').fill('商家来电核对结算卡号');
  await page.getByRole('button', { name: '确认查看并记审计' }).click();
  await expect(page.getByRole('heading', { name: '明文资料（临时显示，隐藏即清除）' })).toBeVisible();
  await expect(page.getByText('6212260200102400000')).toBeVisible();
  await page.getByRole('button', { name: '隐藏明文' }).click();
  await expect(page.getByText('6212260200102400000')).toHaveCount(0);
  const reveals = writes.filter(w => w.path === `${ROOT}/${MER}/reveal`);
  expect(reveals).toHaveLength(1);
  expect(reveals[0].requestId).toMatch(UUID);
  expect(reveals[0].body).toEqual({ reason: '商家来电核对结算卡号' });
});

test('81 §4 未准入：显示原因并禁用保存/发起', async ({ page }) => {
  const writes = captureWrites(page);
  await page.route(`**${ROOT}/${MER}/audits`, route => route.fulfill(envelope([])));
  await page.route(`**${ROOT}/${MER}`, route => route.fulfill(failure('COMMON_NOT_FOUND', 404)));
  await page.route(`**${ROOT}/${MER}/defaults`, route => route.fulfill(envelope(defaultsView({
    eligible: false,
    ineligibleReason: 'APPLICATION_NOT_APPROVED',
    configReady: false,
    missingConfig: ['posType', 'feeRates'],
  }))));
  await signIn(page, ['settlement.report.read', 'settlement.onboarding.write']);
  await queryMerchant(page);

  await expect(page.getByText(/商家未准入：入驻申请未审核通过/).first()).toBeVisible();
  await expect(page.getByText(/平台级配置缺失：POS 终端类型/).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '发起开通' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '保存资料' })).toBeDisabled();
  expect(writes).toHaveLength(0);
});

// 81 §4.4 编辑已有资料：敏感字段留空保持原值（PUT 对留空字段传 null），只改非敏感字段即可保存。
test('81 §4 编辑已有资料：敏感字段留空保持原值，仅改非敏感字段即可保存', async ({ page }) => {
  const writes = captureWrites(page);
  const saved: OnboardingDetailView = detailView({
    version: 2,
    merchant: { ...detailBase.merchant, merBizName: '示例宠物医院高新店' },
    attachments: {
      FR_ID_CARD_FRONT: '101', FR_ID_CARD_BEHIND: '102', BANK_CARD: '103', BUSINESS_LICENCE: '104',
      MERCHANT_PHOTO: '105', SHOPINNER: '106', ID_CARD_FRONT: null, ID_CARD_BEHIND: null, NO_LEGAL_PERSON_SETT_AUTH_LETTER: null,
    },
    updatedAt: '2026-11-27T07:00:00.000Z',
  });
  await page.route(`**${ROOT}/${MER}/audits`, route => route.fulfill(envelope([])));
  await page.route(`**${ROOT}/${MER}/defaults`, route => route.fulfill(envelope(defaultsView())));
  await page.route(`**${ROOT}/${MER}`, route => route.fulfill(envelope(
    route.request().method() === 'PUT' ? saved : detailView({ attachments: saved.attachments }),
  )));
  await signIn(page);
  await queryMerchant(page);

  // 已有资料：提示"留空保持原值"，不再要求重新填写明文
  await expect(page.getByText(/留空表示保持原值/)).toBeVisible();
  // 敏感字段全部留空，只改经营名称即可保存；PUT 对留空的敏感字段传 null
  await page.locator('#ob-merBizName').fill('示例宠物医院高新店');
  await page.getByRole('button', { name: '保存资料' }).click();
  await expect(page.getByText(/已保存/)).toBeVisible();
  const puts = writes.filter(w => w.method === 'PUT' && w.path === `${ROOT}/${MER}`);
  expect(puts).toHaveLength(1);
  const body = puts[0].body as unknown as {
    expectedVersion: number; merchant: Record<string, unknown>; settlement: Record<string, unknown>;
  };
  expect(body.expectedVersion).toBe(1);
  expect(body.merchant.merBizName).toBe('示例宠物医院高新店');
  for (const field of ['merBlisNo', 'larName', 'larIdcard', 'larMobile', 'contactName', 'contactMobile']) {
    expect(body.merchant[field], field).toBeNull();
  }
  expect(body.settlement.acctName).toBeNull();
  expect(body.settlement.acctNo).toBeNull();
  expect(body.settlement.acctIdType).toBeNull();
});
