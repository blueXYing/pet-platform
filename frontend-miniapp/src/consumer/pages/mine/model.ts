// 「我的」页投影（用户 2026-10-08 裁决的登录体验层 UI 状态机）。纯函数、无平台依赖，
// 页面只负责把投影渲染出来；node 测试直接驱动本模块。
import type { SilentLoginOutcome } from '../../../shared/silent-login'

export type MineStats = Readonly<{ coupons: number | null; points: string | null }>

export type MineCard =
  | Readonly<{ state: 'pending' }>
  | Readonly<{ state: 'authenticated'; nickname: string; phoneMasked: string; stats: MineStats }>
  | Readonly<{ state: 'phone-required'; guideOpen: boolean }>
  | Readonly<{ state: 'unavailable' }>

export type MineCardInput = Readonly<{
  outcome: SilentLoginOutcome | 'pending'
  nickname: string
  phoneMasked: string | null
  guideDismissed: boolean
  stats: MineStats
}>

export function mineCard(input: MineCardInput): MineCard {
  if (input.outcome === 'pending') return { state: 'pending' }
  if (input.outcome === 'authenticated') {
    // 服务端已验证的会话必然有绑定手机号（wechat-login 无手机号不发 SessionGrant）；
    // 资料昵称缺失时用中性兜底，不虚构会员等级等未交付字段。
    return {
      state: 'authenticated',
      nickname: input.nickname.trim() || '宠友',
      phoneMasked: input.phoneMasked?.trim() || '',
      stats: input.stats,
    }
  }
  if (input.outcome === 'phone-required') {
    // 首次进「我的」展示授权引导；用户跳过后折叠为一行入口（守卫仍会在需要时拦截）。
    return { state: 'phone-required', guideOpen: !input.guideDismissed }
  }
  return { state: 'unavailable' }
}

// 功能枢纽（静态路由表）：全部指向已交付的真实页面，不虚构未交付功能。
export const mineHubEntries = [
  { key: 'orders', label: '我的订单', url: '/consumer/pages/orders/list' },
  { key: 'coupons', label: '优惠券', url: '/consumer/pages/coupon-points/coupons' },
  { key: 'points', label: '积分', url: '/consumer/pages/coupon-points/points' },
  // 收藏/足迹（contract 58，32号裁决第二节批次）：统计条两格与"我的收藏"格位的目标页。
  { key: 'favorites', label: '我的收藏', url: '/consumer/pages/favtrack/favorites' },
  { key: 'footprints', label: '浏览足迹', url: '/consumer/pages/favtrack/footprints' },
  { key: 'preferences', label: '通知偏好', url: '/consumer/pages/notification-preferences/index' },
  { key: 'pets', label: '宠物档案', url: '/consumer/pages/pet-archive/index' },
  { key: 'profile', label: '编辑资料', url: '/consumer/pages/profile-edit/index' },
] as const
export type MineHubKey = typeof mineHubEntries[number]['key']

// 商家侧入口沿现有工作台路径（不新造导航层级）。
export const MINE_MERCHANT_WORKSPACE_URL = '/merchant/pages/workspace/index'
// 成为服务者入口（32号 §2 七页批次）：落地页半屏面板，接既有商家入驻申请流程。
export const MINE_BECOME_PROVIDER_URL = '/consumer/pages/become-provider/index'
// 「工程验证」壳降级为调试入口（direct path 保留，preview 夹具通道原样保留）。
export const MINE_DEVELOPER_SHELL_URL = '/consumer/pages/shell/index'
// 地址管理（契约 59 切片，32号第二批）：mine 网格「地址管理」格位的真实入口。
export const MINE_ADDRESS_BOOK_URL = '/consumer/pages/address/index'
// 宠物百科（契约 62 / PRD 5.1.4）：mine 网格「我的服务」格位真实入口，匿名可浏览。
export const MINE_BAIKE_URL = '/consumer/pages/baike/index'
// 积分商城展示态页（32号裁决第二节：只赚不花，商城=能看不能换；126:675 我的服务第三格原稿格位）。
export const MINE_POINTS_MALL_URL = '/consumer/pages/points-mall/index'

export const phoneGuideCopy = {
  title: '授权手机号，完善账号',
  body: '使用微信手机号完成授权即可登录下单；也可先跳过，浏览门店与服务。',
  authorize: '微信手机号快捷授权',
  skip: '暂不授权，先逛逛',
  collapsed: '完成手机号授权，即可登录下单',
} as const
