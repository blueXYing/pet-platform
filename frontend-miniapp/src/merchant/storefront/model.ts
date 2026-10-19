// M 端三页共享的准入/文案工具（69/70/27号 M 面）：与 review-appeal/model 的 ownerAccess 同语义。

import type { MerchantAdmission } from '../../shared/merchant-repositories'

export type OwnerAccess = { readable: boolean; writable: boolean; frozen: boolean }

/** OWNER 准入：读取沿存量读取家族（LIMITED 可读），写入只对 ALLOWED 放行。 */
export function ownerAccess(view: MerchantAdmission, merchantId: string, storeId: string): OwnerAccess {
  if (view.membershipKind !== 'OWNER' || view.merchantId !== merchantId || view.storeId !== storeId) {
    return { readable: false, writable: false, frozen: false }
  }
  if (view.admission === 'ALLOWED') return { readable: true, writable: true, frozen: false }
  if (view.admission === 'LIMITED') return { readable: true, writable: false, frozen: true }
  return { readable: false, writable: false, frozen: false }
}

/** 相对到店时间（设计稿语言：今天/昨天/N天前）；跨 30 天显示日期。 */
export function lastVisitLabel(iso: string | null, now: Date): string {
  if (!iso) return '未曾到店'
  const visited = new Date(iso)
  if (Number.isNaN(visited.getTime())) return '未曾到店'
  const day = (value: Date) => Date.UTC(value.getUTCFullYear(), value.getUTCMonth(), value.getUTCDate())
  const gap = Math.round((day(now) - day(visited)) / 86_400_000)
  if (gap <= 0) return '今天'
  if (gap === 1) return '昨天'
  if (gap < 7) return `${gap}天前`
  if (gap < 30) return `${Math.floor(gap / 7)}周前`
  return iso.slice(0, 10)
}

export const operatingLabel: Record<string, string> = {
  OPERATING: '营业中',
  OFFLINE: '已下线',
  FROZEN: '已冻结',
  RESTING: '休息中',
}

export const businessStatusLabel: Record<string, string> = {
  OPEN: '营业中',
  RESTING: '休息中',
}

export const rangeLabel: Record<string, string> = {
  TODAY: '今日',
  LAST_7D: '近 7 天',
  LAST_30D: '近 30 天',
  CUSTOM: '自定义',
}

/** 自定义区间的展示语言：YYYY-MM-DD 起；CUSTOM 由页面直接渲染 from/to。 */
export function windowLabel(range: string, from?: string, to?: string): string {
  if (range === 'CUSTOM') return from && to ? `${from} ~ ${to}` : '自定义'
  return rangeLabel[range] ?? range
}

/** 90 天内最近可用的自定义区间默认值（护栏 ≤92 天）。 */
export function defaultCustomWindow(now: Date): { from: string; to: string } {
  const day = (value: Date) => value.toISOString().slice(0, 10)
  const to = day(now)
  const from = day(new Date(now.getTime() - 29 * 86_400_000))
  return { from, to }
}

/** 校验客户端自定义区间：合法日期、from<=to、≤92 个 UTC 日（与 70号 §1 护栏一致）。 */
export function validCustomWindow(from: string, to: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to)) return false
  const a = Date.parse(`${from}T00:00:00Z`)
  const b = Date.parse(`${to}T00:00:00Z`)
  if (!Number.isFinite(a) || !Number.isFinite(b) || a > b) return false
  return (b - a) / 86_400_000 + 1 <= 92
}

/** 环比展示语言：↑/↓ + 百分比；null 显示 --（PRD 5.14 异常边界）。 */
export function deltaLabel(value: string | null): string {
  if (value === null) return '--'
  const number = Number(value)
  if (!Number.isFinite(number) || number === 0) return '持平'
  return `${number > 0 ? '↑' : '↓'} ${Math.abs(number)}%`
}

export const profileMessage: Record<string, string> = {
  MERCHANT_ENTRY_REQUIRED: '请从商家工作台进入',
  INVALID_RESPONSE: '店铺资料返回异常，请稍后重试',
  WORKSPACE_PATH_MISMATCH: '工作区已切换，请重新进入',
}

export function profileSaveNotice(error: unknown): string {
  const code = (error as { code?: string })?.code
  if (code === 'COMMON_CONFLICT') return '店铺资料已被其他修改更新，请刷新后重试'
  if (code === 'IDEMPOTENCY_KEY_CONFLICT') return '保存请求已被占用，请刷新后重试'
  if (code === 'COMMON_FORBIDDEN') return '仅商家主账号可维护店铺资料'
  return '保存失败，请稍后重试'
}
