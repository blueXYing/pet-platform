import Taro from '@tarojs/taro'

/** 底部 tab 切换的一次性交接标记：目标页不是工作台的子页，onHide 不能把它当成
 *  「离开商家端」而清掉商户坐标。标记只活 3 秒，防止过期误判。 */
export const TAB_HANDOFF_KEY = 'pet.merchant.tab-handoff.v1'
const TAB_HANDOFF_TTL_MS = 3000

export function markTabHandoff() {
  try {
    Taro.setStorageSync(TAB_HANDOFF_KEY, String(Date.now()))
  } catch {
    // best effort: a failed marker must not block navigation
  }
}

export function consumeTabHandoff(): boolean {
  try {
    const raw = Taro.getStorageSync(TAB_HANDOFF_KEY)
    Taro.removeStorageSync(TAB_HANDOFF_KEY)
    const at = Number(raw)
    const age = Date.now() - at
    return Number.isFinite(at) && age >= 0 && age <= TAB_HANDOFF_TTL_MS
  } catch {
    return false
  }
}

export function clearTabHandoff() {
  try {
    Taro.removeStorageSync(TAB_HANDOFF_KEY)
  } catch {
    // best effort
  }
}
