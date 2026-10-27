import { View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import './root-tab-bar-spacer.css'

// Root pages use a custom tabBar overlay; reserve its native-height row at the end of scroll
// content so the last card and safe-area home indicator never hide behind the bar.
function safeAreaBottom() {
  try {
    const info = Taro.getWindowInfo()
    return Math.max(0, info.screenHeight - (info.safeArea?.bottom ?? info.screenHeight))
  } catch { return 0 }
}

export function RootTabBarSpacer() {
  return <View className='root-tab-bar-spacer' style={{ height: `${50 + safeAreaBottom()}px` }} />
}
