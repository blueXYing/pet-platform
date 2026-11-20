/**
 * 2026-11-20 用户裁决：商家工作台角色记忆——切换到商家端后，下次打开小程序直接进商家端，
 * 不再从用户端首页重新进入。显式点"返回用户端"时清除。
 *
 * 不直接 import Taro（会拖入 @tarojs/runtime 导致纯 node 测试环境崩——
 * ENABLE_INNER_HTML 未定义）；改用条件访问 wx 全局。
 */
const KEY = 'pet.workspace.memory'

type WxLike = {
  getStorageSync(key: string): unknown
  setStorageSync(key: string, value: unknown): void
  removeStorageSync(key: string): void
}

function wx(): WxLike | null {
  const g = globalThis as Record<string, unknown>
  return typeof g.wx === 'object' && g.wx !== null ? (g.wx as WxLike) : null
}

export type WorkspaceMemory = 'merchant' | null

export function readWorkspaceMemory(): WorkspaceMemory {
  try {
    const storage = wx()
    return storage && storage.getStorageSync(KEY) === 'merchant' ? 'merchant' : null
  } catch { return null }
}

export function writeWorkspaceMemory(value: WorkspaceMemory): void {
  try {
    const storage = wx()
    if (!storage) return
    if (value === null) storage.removeStorageSync(KEY)
    else storage.setStorageSync(KEY, value)
  } catch { /* 存储不可用时静默降级为无记忆 */ }
}
