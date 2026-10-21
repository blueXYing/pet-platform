import { Image, Swiper, SwiperItem, View } from '@tarojs/components'
import type { ITouchEvent } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useCallback, useRef, useState, type CSSProperties } from 'react'
import { markOnboardingDone } from './model'
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
// 翻页动画（2026-10-21 两轮用户反馈定稿）：
// ① 文字层（标题/副题/圆点/按钮）在两种翻页方式下都做错峰入场——点「下一页」与手指滑动一致；
//    滑动时由 Swiper onChanging（拖动过程）提前触发，动画与滑入同步，而不是落定后才播。
// ② 图片层（背景/插画/logo）只在点「下一页」时随屏缩放淡入；滑动时图片常驻可见——因为拖动
//    过程中两屏同处可视区，图片若从透明起播会在中间露出一条空白（用户 2026-10-21 截图反馈）。
// ③ 全部 animation 走运行时内联 style（WXSS keyframes 内 calc(var()) 不生效，位移用 rpx 固定值）；
//    非目标屏不施加任何隐藏，避免露白与落定闪跳。
// ④ 各屏按钮带静态标记类（ob-skip-1/ob-next-1/ob-next-2/ob-start-3），供自动化按屏定位——
//    隐藏屏同名按钮仍在 DOM，静态类不参与视觉与状态。

const EASE = 'cubic-bezier(0.22, 1, 0.36, 1)'
const LAST = 2

/** 入场动画：play 为真时按延迟播放指定 keyframes；否则不施加任何样式（元素常态可见）。 */
function enter(play: boolean, keyframes: 'ob-rise' | 'ob-pop', delay: number): CSSProperties {
  return play ? { animation: `${keyframes} 0.55s ${delay}s ${EASE} backwards` } : {}
}

/** 整屏入场：轻缩放淡入。 */
function slideEnter(play: boolean): CSSProperties {
  return play ? { animation: `ob-enter 0.45s ${EASE}` } : {}
}

export default function OnboardingPage() {
  const info = Taro.getWindowInfo()
  const unit = info.windowWidth / 402
  const style = { '--ob-unit': `${unit}px` } as CSSProperties
  const [current, setCurrent] = useState(0)
  // textAt：文字层本轮入场屏；fullAt：图片层本轮入场屏（-1 = 本轮不播图片动画）。
  // 首屏两者皆为 0（首启完整入场）。ref 与 state 同时维护：onChanging 是原生事件回调，
  // 需要即时真值判断，不能依赖可能滞后一帧的 state。
  const [textAt, setTextAt] = useState(0)
  const [fullAt, setFullAt] = useState(0)
  const animRef = useRef({ text: 0, full: 0 })

  // 结束引导：写标记后 switchTab 回首页（home 为 tab 页，switchTab 同时清空返回栈，
  // 用户不能再退回引导；实测本页 Taro.navigateBack 静默不生效，改用 switchTab 语义更贴合）。
  const finish = useCallback(() => {
    markOnboardingDone()
    void Taro.switchTab({ url: '/consumer/pages/home/index' })
  }, [])

  const goNext = () => {
    const next = Math.min(current + 1, LAST)
    animRef.current = { text: next, full: next }
    setTextAt(next)
    setFullAt(next)
    setCurrent(next)
  }

  // 滑动路径的目标屏：文字层入场、图片层保持常态可见（避免拖动中露白）。
  const animateSlideText = (next: number) => {
    if (animRef.current.text === next) return
    animRef.current = { text: next, full: -1 }
    setTextAt(next)
    setFullAt(-1)
  }

  // 松手即按滑动方向预测目标屏并触发文字入场——与 Swiper 滑入同步，而非等落定。
  // 预测偏差由 onChange（落定真值）纠正：真索引不同则再触发一次，同索引被守卫挡掉。
  const touchStartX = useRef(0)
  const onTouchStart = (e: ITouchEvent) => {
    touchStartX.current = e.touches[0]?.clientX ?? 0
  }
  const onTouchEnd = (e: ITouchEvent) => {
    const dx = (e.changedTouches[0]?.clientX ?? touchStartX.current) - touchStartX.current
    if (Math.abs(dx) < 40) return
    const next = dx < 0 ? Math.min(current + 1, LAST) : Math.max(current - 1, 0)
    animateSlideText(next)
  }

  const text = (index: number) => textAt === index
  const full = (index: number) => fullAt === index

  return (
    <View className="ob-page" style={style}>
      <View className="ob-touch" onTouchStart={(e) => onTouchStart(e as unknown as ITouchEvent)} onTouchEnd={(e) => onTouchEnd(e as unknown as ITouchEvent)}>
      <Swiper
        className="ob-swiper"
        current={current}
        duration={450}
        onChange={(e) => animateSlideText(e.detail.current)}
      >
        {/* 屏1（10:108）：橘猫举鱼 + 花瓣装饰 + 「一站式养宠生活圈」 */}
        <SwiperItem>
          <View className="ob-slide" style={slideEnter(full(0))}>
            <Image className="ob-s1-decor" style={enter(full(0), 'ob-rise', 0)} src={decorBand} mode="scaleToFill" />
            <Image className="ob-s1-petal-tl" style={enter(full(0), 'ob-rise', 0.08)} src={petalTl} mode="scaleToFill" />
            <Image className="ob-s1-petal-mr" style={enter(full(0), 'ob-rise', 0.16)} src={petalMr} mode="scaleToFill" />
            <Image className="ob-s1-petal-bl" style={enter(full(0), 'ob-rise', 0.16)} src={petalBl} mode="scaleToFill" />
            <View className="ob-s1-cat-wrap" style={enter(full(0), 'ob-pop', 0.12)}>
              <Image className="ob-s1-cat ob-float" src={catFish} mode="scaleToFill" />
            </View>
            <Image className="ob-logo-s" style={enter(full(0), 'ob-rise', 0.08)} src={brandLogoS} mode="scaleToFill" />
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
          <View className="ob-slide" style={slideEnter(full(1))}>
            <Image className="ob-s2-bg" style={enter(full(1), 'ob-rise', 0)} src={wreathBg} mode="scaleToFill" />
            <View className="ob-s2-cat-wrap" style={enter(full(1), 'ob-pop', 0.12)}>
              <Image className="ob-s2-cat ob-float" src={catSit} mode="scaleToFill" />
            </View>
            <Image className="ob-logo-m" style={enter(full(1), 'ob-rise', 0.08)} src={brandLogoM} mode="scaleToFill" />
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
          <View className="ob-slide" style={slideEnter(full(2))}>
            <Image className="ob-s3-bg" style={enter(full(2), 'ob-rise', 0)} src={sceneBg} mode="aspectFill" />
            <View className="ob-s3-strip-wrap" style={enter(full(2), 'ob-pop', 0.12)}>
              <Image className="ob-s3-strip ob-float" src={ownerCat} mode="scaleToFill" />
            </View>
            <Image className="ob-logo-m" style={enter(full(2), 'ob-rise', 0.08)} src={brandLogoM} mode="scaleToFill" />
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
