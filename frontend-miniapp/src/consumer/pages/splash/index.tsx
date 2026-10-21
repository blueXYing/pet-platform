import { Image, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { readOnboardingDone } from '../onboarding/model'
import splashArt from '../../assets/splash/splash@2x.jpg'
import './splash.css'

// 启动页「初始界面」（设计源：Figma 10:89，402x874；2026-10-21 用户裁决补齐）。
// 作为小程序启动页（app.config pages[0]），承担两件事：
// ① 冷启动首帧直接呈现品牌画面——此前 home 是启动页，首启要先渲染首页再 navigateTo 引导页，
//    会闪一下首页（用户 2026-10-21 截图反馈）；
// ② 统一启动路由：未看过引导 → redirectTo 引导页；已看过 → switchTab 首页。
// 整幅画板按原稿导出为单张 2x 图（aspectFill 铺满）保证一比一；固定最短停留 1100ms
// （含淡入），与图片加载解耦，素材异常也不阻断启动。

const SHOW_MS = 1100

export default function SplashPage() {
  const info = Taro.getWindowInfo()
  const routed = useRef(false)
  const [entered, setEntered] = useState(false)

  useEffect(() => {
    const fade = setTimeout(() => setEntered(true), 16)
    const route = setTimeout(() => {
      if (routed.current) return
      routed.current = true
      if (readOnboardingDone()) {
        void Taro.switchTab({ url: '/consumer/pages/home/index' })
      } else {
        void Taro.redirectTo({ url: '/consumer/pages/onboarding/index' })
      }
    }, SHOW_MS)
    return () => { clearTimeout(fade); clearTimeout(route) }
  }, [])

  const style = { '--sp-w': `${info.windowWidth}px`, '--sp-h': `${info.windowHeight}px` } as CSSProperties

  return (
    <View className={entered ? 'sp-page sp-in' : 'sp-page'} style={style}>
      <Image className="sp-art" src={splashArt} mode="aspectFill" />
    </View>
  )
}
