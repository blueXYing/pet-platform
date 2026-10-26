import Taro from '@tarojs/taro'

type CustomTabBarInstance = { setSelected(index: number): void }

// Custom tabBar instances are per tab page; each root page publishes its selected index on show.
export function syncCustomTabBar(index: number) {
  const page = Taro.getCurrentInstance().page
  if (!page) return
  Taro.getTabBar<CustomTabBarInstance>(page)?.setSelected(index)
}
