// 城市选择共享模块（HTTP10 §3.3.4，2026-10-19 城市选择裁决，设计稿 76:761）。
// V1 无 C 端定位能力（§4.4 裁剪维持）：城市仅手动选择，默认展示服务端目录首位；
// 选择持久化于本地存储，门店目录页（stores）在每次展示时读取并按城市刷新（PRD 5.1.2
// "切换城市……刷新商家"——百科/内容入口无城市维度契约，登记为范围外）。
// Taro 惰性获取（仓库可测模块惯例：模块顶层不进 @tarojs/taro，node 单测只走纯函数）。
import type { StoreCity } from '../store/model'

const STORAGE_KEY = 'consumer:city'

/** 全部开放城市（未选择态）：目录查询省略 city 参数。 */
export const ALL_CITIES = '__all__'

type TaroStorage = {
  getStorageSync: (key: string) => unknown
  setStorageSync: (key: string, value: unknown) => void
  removeStorageSync: (key: string) => void
}

function taro(): TaroStorage | null {
  try {
    // weapp 构建由 webpack 静态打包；node 单测环境下模块初始化失败被吞掉，返回 null 走回退。
    return require('@tarojs/taro') as TaroStorage
  } catch {
    return null
  }
}

export function readSelectedCity(): { cityCode?: string } {
  const t = taro()
  if (t) {
    try {
      const raw = t.getStorageSync(STORAGE_KEY)
      if (typeof raw === 'string' && /^[a-z][a-z0-9_-]{0,31}$/.test(raw)) return { cityCode: raw }
    } catch { /* 存储不可用时退回未选择态 */ }
  }
  return {}
}

export function writeSelectedCity(cityCode: string | undefined): void {
  const t = taro()
  if (!t) return
  try {
    if (cityCode === undefined) t.removeStorageSync(STORAGE_KEY)
    else t.setStorageSync(STORAGE_KEY, cityCode)
  } catch { /* 存储不可用时静默（下次展示重读） */ }
}

/** 当前生效城市：本地选择优先；无选择时默认目录首位（PRD 默认定位能力缺失的手动兜底）。 */
export function effectiveCity(catalog: readonly StoreCity[]): string | undefined {
  const selected = readSelectedCity().cityCode
  if (selected && catalog.some(entry => entry.cityCode === selected)) return selected
  return catalog[0]?.cityCode
}

export function cityName(catalog: readonly StoreCity[], cityCode: string | undefined): string {
  if (cityCode === undefined) return '全部开放城市'
  return catalog.find(entry => entry.cityCode === cityCode)?.cityName || cityCode
}
