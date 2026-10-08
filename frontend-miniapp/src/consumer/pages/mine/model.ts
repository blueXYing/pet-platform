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
  { key: 'preferences', label: '通知偏好', url: '/consumer/pages/notification-preferences/index' },
  { key: 'pets', label: '宠物档案', url: '/consumer/pages/pet-archive/index' },
  { key: 'profile', label: '编辑资料', url: '/consumer/pages/profile-edit/index' },
] as const
export type MineHubKey = typeof mineHubEntries[number]['key']

// 商家侧入口沿现有工作台路径（不新造导航层级）。
export const MINE_MERCHANT_WORKSPACE_URL = '/merchant/pages/workspace/index'
// 「工程验证」壳降级为调试入口（direct path 保留，preview 夹具通道原样保留）。
export const MINE_DEVELOPER_SHELL_URL = '/consumer/pages/shell/index'

export const phoneGuideCopy = {
  title: '授权手机号，完善账号',
  body: '使用微信手机号完成授权即可登录下单；也可先跳过，浏览门店与服务。',
  authorize: '微信手机号快捷授权',
  skip: '暂不授权，先逛逛',
  collapsed: '完成手机号授权，即可登录下单',
} as const
