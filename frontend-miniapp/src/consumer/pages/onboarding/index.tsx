import { Image, Swiper, SwiperItem, View } from '@tarojs/components'
import type { ITouchEvent } from '@tarojs/components'
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
import './onboarding.css'

// 开屏引导（用户 2026-10-21 裁决新增；设计源：登记表「登录广告展示界面1/2/3」10:108 / 33:40 /
// 33:58，402x874）。三屏静态引导，仅首次打开显示（home onLoad 探测首启标记 navigateTo 进来，
// 跳过/「开始体验」写 consumer:onboarding-done 后 switchTab 回首页）。第三屏文案保留原稿
// 「发动态、晒萌宠 找同城伙伴」（含社区表述；社区 V1 经 2026-10-14 裁决不做，宣传口径偏差
// 已随本裁决接受）。布局按 Figma 节点绝对坐标 ×(windowWidth/402) 还原；底部圆点/按钮锚定屏底
// （设计底部留白 47/48 设计px），避免长屏设备溢出。按钮为 View 无表单语义，规避原生 button
// 样式重置坑（前端技术基线 v0.7）；禁 data-*。
//
// 翻页动画（2026-10-21 三轮用户反馈定稿）：
// ① 两种翻页方式、两个方向都播放同一组入场——点「下一页」、手指左滑、手指右滑一致。
// ② 两档强度：full（点按钮：整屏缩放淡入 + 图片淡入上浮 + 文字错峰）/ swipe（手指滑动：图片
//    纯缩放 ob-zoom，不改透明度 + 文字错峰）。swipe 不用透明度，是为了保证拖动过程中两屏同时
//    可见时中间不露白（用户 2026-10-21 截图反馈）；纯缩放同样有明显动效，不会显得"没动画"。
// ③ 滑动方向在触摸阶段预测目标屏并立即起播（touchmove 过阈值即触发，touchend 兜底），动画与
//    滑入同步；落定后 onChange 用真值纠偏，预测失败也不会缺席。
// ④ animation 走运行时内联 style（WXSS keyframes 内 calc(var()) 不生效，位移用 rpx 固定值）。
// ⑤ 各屏按钮带静态标记类（ob-skip-1/ob-next-1/ob-next-2/ob-start-3），供自动化按屏定位。

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
  // 本轮入场目标屏与模式；首屏 full（首启完整入场）。ref 与 state 同源：触摸回调需要即时真值。
  const [animAt, setAnimAt] = useState(0)
  const [mode, setMode] = useState<Mode>('full')
  const animRef = useRef<{ index: number; mode: Mode }>({ index: 0, mode: 'full' })
  // 触摸回调里不能用渲染期闭包的 current（手势期间可能已变），用 ref 取实时值。
  const currentRef = useRef(0)

  const applyAnim = (index: number, nextMode: Mode) => {
    const prev = animRef.current
    if (prev.index === index && prev.mode === nextMode) return
    animRef.current = { index, mode: nextMode }
    setAnimAt(index)
    setMode(nextMode)
  }

  // 结束引导：写标记后 switchTab 回首页（home 为 tab 页，switchTab 同时清空返回栈，
  // 用户不能再退回引导；实测本页 Taro.navigateBack 静默不生效，改用 switchTab 语义更贴合）。
  const finish = useCallback(() => {
    markOnboardingDone()
    void Taro.switchTab({ url: '/consumer/pages/home/index' })
  }, [])

  const goNext = () => {
    const next = Math.min(currentRef.current + 1, LAST)
    currentRef.current = next
    setCurrent(next)
    applyAnim(next, 'full')
  }

  // 滑动：touchmove 越过阈值即预测目标屏并起播，touchend 兜底（快速轻扫可能没有 move 事件）。
  const touchStartX = useRef(0)
  const gestured = useRef(false)
  const predict = (dx: number) => {
    const target = predictOnboardingTarget(currentRef.current, dx, LAST)
    if (target === null) return
    gestured.current = true
    applyAnim(target, 'swipe')
  }
  const onTouchStart = (e: ITouchEvent) => {
    touchStartX.current = e.touches[0]?.clientX ?? e.changedTouches[0]?.clientX ?? touchStartX.current
    gestured.current = false
  }
  const onTouchMove = (e: ITouchEvent) => {
    if (gestured.current) return
    const x = e.touches[0]?.clientX ?? touchStartX.current
    predict(x - touchStartX.current)
  }
  const onTouchEnd = (e: ITouchEvent) => {
    const x = e.changedTouches[0]?.clientX ?? touchStartX.current
    predict(x - touchStartX.current)
  }
  // 落定真值纠偏：预测错（回弹、跨屏）时按真实索引补播；与方向、触发方式无关。
  const onSettle = (index: number) => {
    currentRef.current = index
    if (animRef.current.index === index) return
    applyAnim(index, 'swipe')
  }

  const text = (index: number) => animAt === index
  const img = (index: number) => animAt === index
  const imgKey = mode === 'full' ? 'ob-pop' : 'ob-zoom'

  return (
    <View className="ob-page" style={style}>
      <View
        className="ob-touch"
        onTouchStart={(e) => onTouchStart(e as unknown as ITouchEvent)}
        onTouchMove={(e) => onTouchMove(e as unknown as ITouchEvent)}
        onTouchEnd={(e) => onTouchEnd(e as unknown as ITouchEvent)}
      >
        <Swiper
          className="ob-swiper"
          current={current}
          duration={450}
          onChange={(e) => onSettle(e.detail.current)}
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
              <View className="ob-title ob-s1-title" style={enter(text(0), 'ob-rise', 0.26)}>{'一站式养宠生活圈\n上门喂养 遛狗 洗护'}</View>
              <View className="ob-subtitle ob-s1-subtitle" style={enter(text(0), 'ob-rise', 0.36)}>{'Your nearby pet spirit \nwork platform'}</View>
              <View className="ob-dots" style={enter(text(0), 'ob-rise', 0.46)}>
                <View className="ob-dot ob-dot-on" />
                <View className="ob-dot" />
                <View className="ob-dot" />
              </View>
              <View className="ob-btn-skip ob-skip-1" style={enter(text(0), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={finish}>跳过</View>
              <View className="ob-btn-next ob-next-1" style={enter(text(0), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={goNext}>下一页</View>
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
              <View className="ob-title ob-s2-title" style={enter(text(1), 'ob-rise', 0.26)}>{'一键召唤\n附近的宠托师'}</View>
              <View className="ob-subtitle ob-s2-subtitle" style={enter(text(1), 'ob-rise', 0.36)}>{'One-click\nsummon nearby pet trainers'}</View>
              <View className="ob-dots" style={enter(text(1), 'ob-rise', 0.46)}>
                <View className="ob-dot" />
                <View className="ob-dot ob-dot-on" />
                <View className="ob-dot" />
              </View>
              <View className="ob-btn-skip ob-skip-2" style={enter(text(1), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={finish}>跳过</View>
              <View className="ob-btn-next ob-next-2" style={enter(text(1), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={goNext}>下一页</View>
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
              <View className="ob-title ob-s3-title" style={enter(text(2), 'ob-rise', 0.26)}>{'发动态、晒萌宠\n找同城伙伴'}</View>
              <View className="ob-s3-sub-cn" style={enter(text(2), 'ob-rise', 0.36)}>你需要的, 这里全都有</View>
              <View className="ob-s3-sub-en" style={enter(text(2), 'ob-rise', 0.36)}>{'Service appointment\nSuper worry-free'}</View>
              <View className="ob-btn-start ob-start-3" style={enter(text(2), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={finish}>开始体验</View>
            </View>
          </SwiperItem>
        </Swiper>
      </View>
    </View>
  )
}
