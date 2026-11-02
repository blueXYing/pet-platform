// Taro 4.1.5 无法把 React 挂进框架 custom-tab-bar 槽位（React #130，
// $taroInstances 恒空 —— 2026-11-02 控制台实测）。五个根 tab 页改为内联渲染
// consumer/components/navigation/tab-bar.tsx 的 <TabBar/>；本槽位保持空，
// tabBar.custom 维持 true 以免微信再画一条不响应点击的占位原生栏。
import './index.css'

export default function EmptyCustomTabBar() {
  return null
}
