// 开屏引导首启标记（用户 2026-10-21 裁决：三屏静态引导，仅首次打开显示，
// 完成或跳过后写本地标记，之后直达首页；清缓存/重装会再现）。
// 设计源：登记表「登录广告展示界面1/2/3」10:108 / 33:40 / 33:58，第三屏文案保留原稿
// （含社区表述，社区 V1 经 2026-10-14 裁决不做——宣传口径偏差已裁决接受）。
// Taro 惰性获取（仓库可测模块惯例：模块顶层不进 @tarojs/taro，node 单测只走纯函数）。

const STORAGE_KEY = 'consumer:onboarding-done'

type TaroStorage = {
  getStorageSync: (key: string) => unknown
  setStorageSync: (key: string, value: unknown) => void
}

function taro(): TaroStorage | null {
  try {
    // weapp 构建由 webpack 静态打包；node 单测环境下模块初始化失败被吞掉，返回 null 走回退。
    return require('@tarojs/taro') as TaroStorage
  } catch {
    return null
  }
}

/** 是否已完成引导（看过或跳过）。读取失败按未完成处理，确保引导至少展示一次。 */
export function readOnboardingDone(): boolean {
  const t = taro()
  if (!t) return false
  try {
    return t.getStorageSync(STORAGE_KEY) === true
  } catch {
    return false
  }
}

/** 标记引导完成：跳过与「开始体验」都视为已完成。 */
export function markOnboardingDone(): void {
  const t = taro()
  if (!t) return
  try {
    t.setStorageSync(STORAGE_KEY, true)
  } catch {
    // 写入失败不阻断返回首页；下次启动会再看一次引导，无业务副作用。
  }
}
