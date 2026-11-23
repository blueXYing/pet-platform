import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  MINE_BECOME_PROVIDER_URL, MINE_MERCHANT_APPLICATION_URL, MINE_MERCHANT_WORKSPACE_URL, mineEntryNavMethod, mineMerchantEntry,
} from '../pages/mine/model'

// 2026-11-19 用户裁决：商家横幅按身份分流——已是商家显示「商家控制台」直达工作台；
// 已提交过入驻申请直达申请页（跳过 become-provider 服务类型选择前置，二次裁决
// "提交后就应该无需选择了"）；从未申请才「成为商家」进入入驻流程，文案与落点一致。
test('merchant banner routes owners to the console, applicants to the application, others to onboarding', () => {
  assert.deepEqual(mineMerchantEntry(true), {
    title: '商家控制台', subtitle: '管理门店、订单与服务', url: MINE_MERCHANT_WORKSPACE_URL,
  })
  assert.deepEqual(mineMerchantEntry(false), {
    title: '成为商家', subtitle: '入驻开店，让更多宠友找到你', url: MINE_BECOME_PROVIDER_URL,
  })
  // 未登录/查询失败按非商家/无申请处理（申请页与工作台各有准入兜底）。
  assert.equal(mineMerchantEntry(null).url, MINE_BECOME_PROVIDER_URL)
  assert.equal(mineMerchantEntry(null).title, '成为商家')
  // 已有申请：全部状态都直达申请页，不再经过服务类型选择半屏。
  for (const status of ['DRAFT', 'REVIEWING', 'APPROVED', 'REJECTED'] as const) {
    assert.equal(mineMerchantEntry(false, status).url, MINE_MERCHANT_APPLICATION_URL)
  }
  assert.equal(mineMerchantEntry(false, 'REVIEWING').title, '入驻审核中')
  assert.equal(mineMerchantEntry(false, 'APPROVED').title, '入驻申请已通过')
  assert.equal(mineMerchantEntry(false, 'REJECTED').title, '申请需修改')
  assert.equal(mineMerchantEntry(false, 'DRAFT').title, '继续入驻申请')
  // 商家身份优先于申请状态（已入驻后横幅仍指向工作台）。
  assert.equal(mineMerchantEntry(true, 'REVIEWING').url, MINE_MERCHANT_WORKSPACE_URL)
  // 工作台沿 P-03 裁决 reLaunch，申请页与入驻落地页保持 navigateTo 子页栈。
  assert.equal(mineEntryNavMethod(mineMerchantEntry(true).url), 'reLaunch')
  assert.equal(mineEntryNavMethod(mineMerchantEntry(false).url), 'navigateTo')
  assert.equal(mineEntryNavMethod(mineMerchantEntry(false, 'REVIEWING').url), 'navigateTo')
})

test('mine page loads memberships and the current application, then shares one entry', () => {
  const page = readFileSync(new URL('../pages/mine/index.tsx', import.meta.url), 'utf8')
  assert.match(page, /MerchantAdmissionRepository, MerchantApplicationRepository/)
  assert.match(page, /admissionRepository\.memberships\(1, 1\)\.then\(page => page\.total > 0\)\.catch\(\(\) => null\)/)
  // 已有申请时入口直达申请页：页面读取 current 的状态并交给同一分发函数。
  assert.match(page, /applicationRepository\.current\(\)\.then\(detail => detail\.status\)\.catch\(\(\) => null\)/)
  assert.match(page, /mineMerchantEntry\(isMerchant, applicationStatus\)/)
  // 横幅与「成为服务者」格位共用 merchantEntry，避免任一入口再回到服务类型选择前置。
  assert.match(page, /onClick=\{\(\) => open\(merchantEntry\.url\)\}/)
})