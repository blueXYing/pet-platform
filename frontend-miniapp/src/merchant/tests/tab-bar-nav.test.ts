import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { MERCHANT_TAB_ROUTES, merchantTabNavMethod, merchantTabRoute } from '../components/tab-bar/nav'
import { MINE_MERCHANT_WORKSPACE_URL, mineEntryNavMethod } from '../../consumer/pages/mine/model'

// P-03（2026-11-11）：navigateTo/redirectTo 进入商家工作台会触发 Taro dispose 隐藏的工作台 React 树，
// 复访 resurrect stale 页对象 → 白屏（pending-issues P-03，与 members/order-confirm/services 的
// 「返回工作台必须 reLaunch」同族）。本文件收口两类入口的回归护栏：
//   1) 商家端底部自定义 tab-bar 切到工作台（home）tab；
//   2) 各 C 端/子页「去商家工作台」入口（含 consumer「我的」页通用入口 open()、开发者壳）。
// 注意：order*/services/schedule/members 页的同族 redirectTo 由其他组收口，不在本护栏文件清单内。

test('tab bar: switching to the workbench (home) tab navigates via reLaunch', () => {
  assert.equal(merchantTabRoute('home'), '/merchant/pages/workspace/index')
  assert.equal(merchantTabNavMethod('home'), 'reLaunch')
})

test('tab bar: sibling tabs keep the single-alive-page redirectTo and never target the workbench', () => {
  for (const key of ['orders', 'verify', 'messages', 'mine'] as const) {
    assert.equal(merchantTabNavMethod(key), 'redirectTo')
    assert.notEqual(MERCHANT_TAB_ROUTES[key], '/merchant/pages/workspace/index')
  }
  // 五 tab 路由表唯一且齐全（回归：reLaunch 改造不改变路由登记本身）。
  assert.deepEqual(Object.keys(MERCHANT_TAB_ROUTES).sort(), ['home', 'messages', 'mine', 'orders', 'verify'])
  assert.equal(new Set(Object.values(MERCHANT_TAB_ROUTES)).size, 5)
})

test('tab bar component wires switchTo through the nav-method decision', () => {
  const source = readFileSync(new URL('../components/tab-bar/index.tsx', import.meta.url), 'utf8')
  assert.match(source, /merchantTabNavMethod\(key\) === 'reLaunch'/, 'home tab must branch on the nav-method decision')
  assert.match(source, /Taro\.reLaunch\(\{ url: merchantTabRoute\(key\) \}\)/, 'home tab must reLaunch')
  assert.match(source, /Taro\.redirectTo\(\{ url: merchantTabRoute\(key\) \}\)/, 'sibling tabs must keep redirectTo')
  // 组件内不得再出现以工作台为目标的 navigateTo/redirectTo 字面调用。
  assert.doesNotMatch(source, /Taro\.(navigateTo|redirectTo)\(\{[^}]*\/merchant\/pages\/workspace\/index/)
})

test('consumer mine hub: the merchant-workspace entry uses reLaunch, other entries keep navigateTo', () => {
  assert.equal(MINE_MERCHANT_WORKSPACE_URL, '/merchant/pages/workspace/index')
  assert.equal(mineEntryNavMethod(MINE_MERCHANT_WORKSPACE_URL), 'reLaunch')
  assert.equal(mineEntryNavMethod('/consumer/pages/orders/list'), 'navigateTo')
  assert.equal(mineEntryNavMethod('/consumer/pages/settings/index'), 'navigateTo')
})

test('no navigateTo/redirectTo to the workbench remains in the C-scope entry files', () => {
  // 修复方（本组）+ 已正确 reLaunch 家族页（messages/marketing）的静态门禁。
  const files = [
    '../components/tab-bar/index.tsx',
    '../pages/aftersale/page.tsx',
    '../pages/customers/index.tsx',
    '../pages/messages/index.tsx',
    '../pages/marketing/index.tsx',
    '../pages/refund/page.tsx',
    '../pages/reviews/index.tsx',
    '../pages/shop-info/index.tsx',
    '../pages/staff-verify/index.tsx',
    '../pages/staff-workbench/index.tsx',
    '../pages/stats/index.tsx',
    '../../consumer/pages/shell/index.tsx',
  ]
  const offenders: string[] = []
  for (const file of files) {
    const source = readFileSync(new URL(file, import.meta.url), 'utf8')
    for (const match of source.matchAll(/Taro\.(navigateTo|redirectTo)\(\{[^}]*?\/merchant\/pages\/workspace\/index/g)) {
      offenders.push(`${file}: ${match[1]}`)
    }
  }
  assert.deepEqual(offenders, [], `workspace must only be entered via reLaunch:\n${offenders.join('\n')}`)
})

test('merchant pages never navigateBack (P-03 follow-up 2026-11-12: dead disposed-tree landings)', () => {
  // 用户实测卡死：子页 navigateBack 落到被 dispose 旧树的工作台/列表页=界面在、事件全无。
  // 商家域一律 reLaunch 到明确目标页；唯一豁免=staff-workbench 回 consumer 页（树安全）。
  const offenders: string[] = []
  const walk = (dir: string): string[] => {
    const entries = readdirSync(new URL(dir, import.meta.url), { withFileTypes: true })
    return entries.flatMap(entry => {
      const full = `${dir}/${entry.name}`
      return entry.isDirectory() ? walk(full) : (/\.(tsx|ts)$/.test(entry.name) ? [full] : [])
    })
  }
  for (const file of walk('../pages')) {
    const source = readFileSync(new URL(file, import.meta.url), 'utf8')
    if (file.endsWith('staff-workbench/index.tsx')) continue // guarded consumer-only navigateBack
    for (const match of source.matchAll(/Taro\.navigateBack/g)) {
      offenders.push(`${file}: ${match[0]}`)
    }
  }
  assert.deepEqual(offenders, [], `merchant pages must reLaunch instead of navigateBack:\n${offenders.join('\n')}`)
})

test('consumer entries route the workbench through reLaunch (not platform.navigate / bare navigateTo)', () => {
  // 开发者壳：进商家工作区必须 Taro.reLaunch，不得走 platform.navigate（内部是 navigateTo）。
  const shell = readFileSync(new URL('../../consumer/pages/shell/index.tsx', import.meta.url), 'utf8')
  assert.match(shell, /Taro\.reLaunch\(\{ url: '\/merchant\/pages\/workspace\/index' \}\)/)
  assert.doesNotMatch(shell, /platform\.navigate\(\s*'\/merchant\/pages\/workspace/)
  // 「我的」页通用入口 open()：工作台目标必须经 mineEntryNavMethod 分流到 reLaunch。
  const mine = readFileSync(new URL('../../consumer/pages/mine/index.tsx', import.meta.url), 'utf8')
  assert.match(mine, /mineEntryNavMethod\(url\) === 'reLaunch'/)
})
