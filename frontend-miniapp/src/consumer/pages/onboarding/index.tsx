import { Image, Swiper, SwiperItem, View } from '@tarojs/components'
import type { BaseEventOrig } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useCallback, useRef, useState, type CSSProperties } from 'react'
import { markOnboardingDone, predictOnboardingTarget } from './model'
import decorBand from './assets/decor-band@2x.png'
import petalTl from './assets/petal-tl@2x.png'
import petalMr from './assets/petal-mr@2x.png'
import petalBl from './assets/petal-bl@2x.png'
import catFish from './assets/cat-fish@2x.png'
import wreathBg from './assets/wreath-bg@2x.jpg'
import catSit from './assets/cat-sit@2x.png'
import sceneBg from './assets/scene-bg@2x.jpg'
import ownerCat from './assets/owner-cat@2x.png'
import brandLogoS from './assets/brand-logo-s@2x.png'
import brandLogoM from './assets/brand-logo-m@2x.png'
import title1 from './assets/text/title-1@3x.png'
import title2 from './assets/text/title-2@3x.png'
import title3 from './assets/text/title-3@3x.png'
import sub1 from './assets/text/sub-1@3x.png'
import sub2 from './assets/text/sub-2@3x.png'
import sub3Cn from './assets/text/sub-3-cn@3x.png'
import sub3En from './assets/text/sub-3-en@3x.png'
import labelSkip from './assets/text/btn-skip@3x.png'
import labelNext from './assets/text/btn-next@3x.png'
import labelStart from './assets/text/btn-start@3x.png'
import './onboarding.css'

// 开屏引导（用户 2026-10-21 裁决新增；设计源：登记表「登录广告展示界面1/2/3」10:108 / 33:40 /
// 33:58，402x874）。三屏静态引导，仅首次打开显示（启动页按首启标记 redirectTo 进来，
// 跳过/「开始体验」写 consumer:onboarding-done 后 switchTab 回首页）。第三屏文案保留原稿
// 「发动态、晒萌宠 找同城伙伴」（含社区表述；社区 V1 经 2026-10-14 裁决不做，宣传口径偏差
// 已随本裁决接受）。布局按 Figma 节点绝对坐标 ×(windowWidth/402) 还原；底部圆点/按钮锚定屏底。
//
// 字体口径（2026-10-21 用户裁决）：设计字体 Lovely Little Jelly / 喵喵绘本体 未随稿提供，
// 按前端技术基线「缺字体先登记、不私自替换」——保留系统字体，只落实可对齐的排版参数
// （字号 30/20/14、行高 36.36/18.2/11.34、锁定两行不自动换行、开始体验字距 2.88）；
// 字体本身差异在登记表 §4.19 记为已知偏差。
//
// 翻页动画：点「下一页」为 full 档（整屏缩放淡入 + 图片淡入上浮 + 文字错峰），手指滑动为
// swipe 档（图片纯缩放不改透明度，避免拖动中露白 + 文字错峰）。页码以触摸位移判定为主、
// swiper 的 onChange/onAnimationFinish 作落定校正；手势信号只更新圆点与动画，不驱动翻页。
// 分页圆点为页面级共享指示器（高亮项胶囊伸缩 + 弹性放大），屏3 整组淡出。

const EASE = 'cubic-bezier(0.22, 1, 0.36, 1)'
const LAST = 2
/** swipe：手指滑动（图片纯缩放）；full：点按钮（图片淡入上浮）。 */
type Mode = 'swipe' | 'full'

function enter(play: boolean, keyframes: string, delay: number): CSSProperties {
  return play ? { animation: `${keyframes} 0.55s ${delay}s ${EASE} backwards` } : {}
}

/** 整屏入场：只在 full 模式播放（swipe 时整屏缩放会在边缘露出底色）。 */
function slideEnter(play: boolean): CSSProperties {
  return play ? { animation: `ob-enter 0.45s ${EASE}` } : {}
}

export default function OnboardingPage() {
  const info = Taro.getWindowInfo()
  const unit = info.windowWidth / 402
  const style = { '--ob-unit': `${unit}px` } as CSSProperties
  const [current, setCurrent] = useState(0)
  // viewIndex：当前实际展示的屏（驱动圆点与入场动画）。Swiper 的 change 事件在手指滑动路径
  // 上不送达（实测），故以触摸位移判定为主、事件为落定校正。
  const [viewIndex, setViewIndex] = useState(0)
  const viewRef = useRef(0)
  const [animAt, setAnimAt] = useState(0)
  const [mode, setMode] = useState<Mode>('full')
  const animRef = useRef<{ index: number; mode: Mode }>({ index: 0, mode: 'full' })
  // 最近一次起播时间：按钮设定的 full 优先级高于随后到达的事件，避免同一跳被重复判定。
  const appliedAt = useRef(0)
  const gestureLock = useRef(0)

  const applyAnim = (index: number, nextMode: Mode) => {
    const prev = animRef.current
    if (prev.index === index && prev.mode === nextMode) return
    animRef.current = { index, mode: nextMode }
    appliedAt.current = Date.now()
    setAnimAt(index)
    setMode(nextMode)
  }

  // 结束引导：写标记后 switchTab 回首页（home 为 tab 页，switchTab 同时清空返回栈；
  // 实测本页 Taro.navigateBack 静默不生效，改用 switchTab 语义更贴合）。
  const finish = useCallback(() => {
    markOnboardingDone()
    void Taro.switchTab({ url: '/consumer/pages/home/index' })
  }, [])

  /** 切到某屏：圆点与动画的唯一入口。 */
  const goTo = (index: number, nextMode: Mode) => {
    viewRef.current = index
    setViewIndex(index)
    applyAnim(index, nextMode)
  }

  const goNext = () => {
    const next = Math.min(viewRef.current + 1, LAST)
    setCurrent(next)
    goTo(next, 'full')
  }

  // 手指滑动：松手按位移判定目标屏（阈值 60px），只更新圆点与动画；翻页由原生手势完成，
  // 此处若 setCurrent 会与手势本身叠加成两页（曾出现"想滑到第2页却直接到第3页"）。
  const touchStartX = useRef(0)
  const applyGesture = (dx: number) => {
    if (Date.now() - gestureLock.current < 600) return
    if (Date.now() - appliedAt.current < 400) return
    const target = predictOnboardingTarget(viewRef.current, dx, LAST)
    if (target === null) return
    gestureLock.current = Date.now()
    goTo(target, 'swipe')
  }
  const onTouchStart = (e: BaseEventOrig) => {
    const detail = e as unknown as { touches?: Array<{ clientX: number }> }
    touchStartX.current = detail.touches?.[0]?.clientX ?? touchStartX.current
  }
  const onTouchEnd = (e: BaseEventOrig) => {
    const detail = e as unknown as { changedTouches?: Array<{ clientX: number }> }
    applyGesture((detail.changedTouches?.[0]?.clientX ?? touchStartX.current) - touchStartX.current)
  }
  // 落定真值校正（按钮路径 700ms 内不覆盖）。
  const onSettle = (index: number) => {
    if (index === viewRef.current) return
    if (Date.now() - appliedAt.current < 700) return
    setCurrent(index)
    goTo(index, 'swipe')
  }

  const text = (index: number) => animAt === index
  const img = (index: number) => animAt === index
  const imgKey = mode === 'full' ? 'ob-pop' : 'ob-zoom'

  return (
    <View className="ob-page" style={style} onTouchStart={onTouchStart} onTouchEnd={onTouchEnd}>
      <Swiper
        className="ob-swiper"
        current={current}
        duration={450}
        onChange={(e) => onSettle(e.detail.current)}
        onAnimationFinish={(e) => onSettle(e.detail.current)}
      >
        {/* 屏1（10:108）：橘猫举鱼 + 花瓣装饰 + 「一站式养宠生活圈」 */}
        <SwiperItem>
          <View className="ob-slide" style={slideEnter(mode === 'full' && img(0))}>
            <Image className="ob-s1-decor" style={enter(img(0), imgKey, 0)} src={decorBand} mode="scaleToFill" />
            <Image className="ob-s1-petal-tl" style={enter(img(0), imgKey, 0.08)} src={petalTl} mode="scaleToFill" />
            <Image className="ob-s1-petal-mr" style={enter(img(0), imgKey, 0.16)} src={petalMr} mode="scaleToFill" />
            <Image className="ob-s1-petal-bl" style={enter(img(0), imgKey, 0.16)} src={petalBl} mode="scaleToFill" />
            <View className="ob-s1-cat-wrap" style={enter(img(0), imgKey, 0.12)}>
              <Image className="ob-s1-cat ob-float" src={catFish} mode="scaleToFill" />
            </View>
            <Image className="ob-logo-s" style={enter(img(0), imgKey, 0.08)} src={brandLogoS} mode="scaleToFill" />
            <Image className="ob-title ob-s1-title" style={enter(text(0), 'ob-rise', 0.26)} src={title1} mode="scaleToFill" />
            <Image className="ob-subtitle ob-s1-subtitle" style={enter(text(0), 'ob-rise', 0.36)} src={sub1} mode="scaleToFill" />
            <View className="ob-btn-skip ob-skip-1" style={enter(text(0), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={finish}><Image className="ob-btn-label" src={labelSkip} mode="scaleToFill" /></View>
            <View className="ob-btn-next ob-next-1" style={enter(text(0), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={goNext}><Image className="ob-btn-label" src={labelNext} mode="scaleToFill" /></View>
          </View>
        </SwiperItem>
        {/* 屏2（33:40）：花环背景 + 端坐橘猫 + 「一键召唤 附近的宠托师」 */}
        <SwiperItem>
          <View className="ob-slide" style={slideEnter(mode === 'full' && img(1))}>
            <Image className="ob-s2-bg" style={enter(mode === 'full' && img(1), imgKey, 0)} src={wreathBg} mode="scaleToFill" />
            <View className="ob-s2-cat-wrap" style={enter(img(1), imgKey, 0.12)}>
              <Image className="ob-s2-cat ob-float" src={catSit} mode="scaleToFill" />
            </View>
            <Image className="ob-logo-m" style={enter(img(1), imgKey, 0.08)} src={brandLogoM} mode="scaleToFill" />
            <Image className="ob-title ob-s2-title" style={enter(text(1), 'ob-rise', 0.26)} src={title2} mode="scaleToFill" />
            <Image className="ob-subtitle ob-s2-subtitle" style={enter(text(1), 'ob-rise', 0.36)} src={sub2} mode="scaleToFill" />
            <View className="ob-btn-skip ob-skip-2" style={enter(text(1), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={finish}><Image className="ob-btn-label" src={labelSkip} mode="scaleToFill" /></View>
            <View className="ob-btn-next ob-next-2" style={enter(text(1), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={goNext}><Image className="ob-btn-label" src={labelNext} mode="scaleToFill" /></View>
          </View>
        </SwiperItem>
        {/* 屏3（33:58）：主人撸猫插画 + 「发动态、晒萌宠 找同城伙伴」（原稿文案保留）+ 开始体验 */}
        <SwiperItem>
          <View className="ob-slide" style={slideEnter(mode === 'full' && img(2))}>
            <Image className="ob-s3-bg" style={enter(mode === 'full' && img(2), imgKey, 0)} src={sceneBg} mode="aspectFill" />
            <View className="ob-s3-strip-wrap" style={enter(img(2), imgKey, 0.12)}>
              <Image className="ob-s3-strip ob-float" src={ownerCat} mode="scaleToFill" />
            </View>
            <Image className="ob-logo-m" style={enter(img(2), imgKey, 0.08)} src={brandLogoM} mode="scaleToFill" />
            <Image className="ob-title ob-s3-title" style={enter(text(2), 'ob-rise', 0.26)} src={title3} mode="scaleToFill" />
            <Image className="ob-s3-sub-cn" style={enter(text(2), 'ob-rise', 0.36)} src={sub3Cn} mode="scaleToFill" />
            <Image className="ob-s3-sub-en" style={enter(text(2), 'ob-rise', 0.36)} src={sub3En} mode="scaleToFill" />
            <View className="ob-btn-start ob-start-3" style={enter(text(2), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={finish}><Image className="ob-btn-label" src={labelStart} mode="scaleToFill" /></View>
          </View>
        </SwiperItem>
      </Swiper>
      {/* 分页圆点：页面级共享指示器；屏3 原稿无圆点 → 淡出 */}
      <View className={viewIndex >= LAST ? 'ob-dots ob-dots-hidden' : 'ob-dots'}>
        {[0, 1, 2].map((index) => (
          <View key={index} className={viewIndex === index ? 'ob-dot ob-dot-on' : 'ob-dot'} />
        ))}
      </View>
    </View>
  )
}
