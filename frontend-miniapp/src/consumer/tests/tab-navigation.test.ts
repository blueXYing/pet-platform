import test from 'node:test'
import assert from 'node:assert/strict'
import { consumerNavigationItems, consumerTabRoutes, type ConsumerNavigationKey } from '../components/navigation/model'
import { mineCard, mineHubEntries, MINE_DEVELOPER_SHELL_URL, MINE_MERCHANT_WORKSPACE_URL } from '../pages/mine/model'

// tab 路由登记 + 「我的」页投影（用户 2026-10-08 裁决）。

test('the five navigation keys register exactly one native tab route each', () => {
  assert.deepEqual(consumerNavigationItems.map(item => item.key), ['home', 'services', 'community', 'messages', 'mine'])
  for (const item of consumerNavigationItems) {
    assert.match(consumerTabRoutes[item.key], /^\/consumer\/pages\/(home|services|community|messages|mine)\/index$/)
  }
  assert.equal(new Set(Object.values(consumerTabRoutes)).size, 5)
})

test('app.config registers the tab pages in the main package with the home page as launch entry', async () => {
  ;(globalThis as Record<string, unknown>).defineAppConfig = (value: unknown) => value
  const config = (await import('../../app.config')).default as {
    pages: string[]
    tabBar: { list: Array<{ pagePath: string; text: string; iconPath: string; selectedIconPath: string }> }
    subPackages: Array<{ root: string }>
  }
  assert.equal(config.pages[0], 'consumer/pages/home/index')
  const tabPages = config.tabBar.list.map(tab => tab.pagePath)
  assert.equal(tabPages.length, 5)
  for (const page of tabPages) {
    assert.ok(config.pages.includes(page), `tab page must be a main-package page: ${page}`)
    assert.ok(!config.subPackages.some(pack => page.startsWith(pack.root + '/')), `tab page must not live in a subpackage: ${page}`)
  }
  for (const tab of config.tabBar.list) {
    assert.equal(tab.iconPath, tab.selectedIconPath) // 无选中态变体：登记的设计偏差
    assert.ok(tab.iconPath.startsWith('consumer/assets/navigation/'))
  }
  assert.deepEqual(config.tabBar.list.map(tab => tab.text), ['首页', '服务', '宠友圈', '消息', '我的'])
})

test('mine card projection: authenticated shows masked phone, phone-required honors dismissal', () => {
  const authenticated = mineCard({
    outcome: 'authenticated', nickname: ' 宠友小白 ', phoneMasked: '138****0100', guideDismissed: false,
    stats: { coupons: 6, points: '1280' },
  })
  assert.equal(authenticated.state, 'authenticated')
  if (authenticated.state === 'authenticated') {
    assert.equal(authenticated.nickname, '宠友小白')
    assert.equal(authenticated.phoneMasked, '138****0100')
    assert.deepEqual(authenticated.stats, { coupons: 6, points: '1280' })
  }
  const noNickname = mineCard({ outcome: 'authenticated', nickname: '', phoneMasked: null, guideDismissed: false, stats: { coupons: null, points: null } })
  if (noNickname.state === 'authenticated') {
    assert.equal(noNickname.nickname, '宠友') // 中性兜底，不虚构会员等级等未交付字段
    assert.equal(noNickname.phoneMasked, '')
  }
  const guideOpen = mineCard({ outcome: 'phone-required', nickname: '', phoneMasked: null, guideDismissed: false, stats: { coupons: null, points: null } })
  assert.deepEqual(guideOpen, { state: 'phone-required', guideOpen: true })
  const guideSkipped = mineCard({ outcome: 'phone-required', nickname: '', phoneMasked: null, guideDismissed: true, stats: { coupons: null, points: null } })
  assert.deepEqual(guideSkipped, { state: 'phone-required', guideOpen: false })
  assert.deepEqual(mineCard({ outcome: 'unavailable', nickname: '', phoneMasked: null, guideDismissed: false, stats: { coupons: null, points: null } }), { state: 'unavailable' })
  assert.deepEqual(mineCard({ outcome: 'pending', nickname: '', phoneMasked: null, guideDismissed: false, stats: { coupons: null, points: null } }), { state: 'pending' })
})

test('mine hub routes only to delivered real pages', () => {
  const routes = [
    ...mineHubEntries.map(entry => entry.url),
    MINE_MERCHANT_WORKSPACE_URL,
    MINE_DEVELOPER_SHELL_URL,
  ]
  assert.deepEqual(routes, [
    '/consumer/pages/orders/list',
    '/consumer/pages/coupon-points/coupons',
    '/consumer/pages/coupon-points/points',
    '/consumer/pages/notification-preferences/index',
    '/consumer/pages/pet-archive/index',
    '/consumer/pages/profile-edit/index',
    '/merchant/pages/workspace/index',
    '/consumer/pages/shell/index',
  ])
})

test('tab route registry stays in sync with the navigation keys type', () => {
  const keys: ConsumerNavigationKey[] = ['home', 'services', 'community', 'messages', 'mine']
  assert.deepEqual(Object.keys(consumerTabRoutes).sort(), keys.slice().sort())
})
