import { Image, Swiper, SwiperItem, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useCallback, useState, type CSSProperties } from 'react'
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
// 跳过/「开始体验」写 consumer:onboarding-done 后 navigateBack）。第三屏文案保留原稿
// 「发动态、晒萌宠 找同城伙伴」（含社区表述；社区 V1 经 2026-10-14 裁决不做，宣传口径偏差
// 已随本裁决接受）。布局按 Figma 节点绝对坐标 ×(windowWidth/402) 还原；底部圆点/按钮锚定屏底
// （设计底部留白 47/48 设计px），避免长屏设备溢出。按钮为 View 无表单语义，规避原生 button
// 样式重置坑（前端技术基线 v0.7）；禁 data-*。
//
// 翻页动画（用户 2026-10-21 反馈「翻页交互太普通」）：切屏时当前屏整体轻缩放淡入 + 元素错峰
// 入场（插画上浮缩放→花瓣/logo→标题→副标题→圆点/按钮），主插画入场后接呼吸浮动；圆点按屏
// 静态渲染（各屏自带一组，当前屏对应项高亮），按钮 hoverClass 按压缩放。动画一律走运行时
// 内联 style（animation 与屏状态同源下发），不依赖祖先类选择器匹配——WXSS 的 keyframes 内
// calc(var()) 不生效，位移用 rpx 固定值；内联 animation 引用 WXSS 中的 @keyframes。
// 各屏按钮另带静态标记类（ob-skip-1/ob-next-1/ob-next-2/ob-start-3）：自动化按屏定位用，
// 同名按钮在第一屏之外不可达（隐藏屏按钮仍在 DOM），静态类不参与视觉与状态。

const EASE = 'cubic-bezier(0.22, 1, 0.36, 1)'

/** 元素入场：激活屏下发延迟播放指定 keyframes；非激活屏置透明（回翻即重播）。 */
function enter(active: boolean, keyframes: 'ob-rise' | 'ob-pop', delay: number): CSSProperties {
  return active
    ? { animation: `${keyframes} 0.55s ${delay}s ${EASE} backwards` }
    : { opacity: 0 }
}

/** 整屏入场：轻缩放淡入，与元素错峰叠加形成层次。 */
function slideEnter(active: boolean): CSSProperties {
  return active
    ? { animation: `ob-enter 0.45s ${EASE}` }
    : { opacity: 0 }
}

export default function OnboardingPage() {
  const info = Taro.getWindowInfo()
  const unit = info.windowWidth / 402
  const style = { '--ob-unit': `${unit}px` } as CSSProperties
  const [current, setCurrent] = useState(0)

  // 结束引导：写标记后 switchTab 回首页（home 为 tab 页，switchTab 同时清空返回栈，
  // 用户不能再退回引导；实测本页 Taro.navigateBack 静默不生效，改用 switchTab 语义更贴合）。
  const finish = useCallback(() => {
    markOnboardingDone()
    void Taro.switchTab({ url: '/consumer/pages/home/index' })
  }, [])

  const onSlide = (index: number) => current === index

  return (
    <View className="ob-page" style={style}>
      <Swiper
        className="ob-swiper"
        current={current}
        duration={450}
        onChange={(e) => setCurrent(e.detail.current)}
      >
        {/* 屏1（10:108）：橘猫举鱼 + 花瓣装饰 + 「一站式养宠生活圈」 */}
        <SwiperItem>
          <View className="ob-slide" style={slideEnter(onSlide(0))}>
            <Image className="ob-s1-decor" style={enter(onSlide(0), 'ob-rise', 0)} src={decorBand} mode="scaleToFill" />
            <Image className="ob-s1-petal-tl" style={enter(onSlide(0), 'ob-rise', 0.08)} src={petalTl} mode="scaleToFill" />
            <Image className="ob-s1-petal-mr" style={enter(onSlide(0), 'ob-rise', 0.16)} src={petalMr} mode="scaleToFill" />
            <Image className="ob-s1-petal-bl" style={enter(onSlide(0), 'ob-rise', 0.16)} src={petalBl} mode="scaleToFill" />
            <View className="ob-s1-cat-wrap" style={enter(onSlide(0), 'ob-pop', 0.12)}>
              <Image className="ob-s1-cat ob-float" src={catFish} mode="scaleToFill" />
            </View>
            <Image className="ob-logo-s" style={enter(onSlide(0), 'ob-rise', 0.08)} src={brandLogoS} mode="scaleToFill" />
            <View className="ob-title ob-s1-title" style={enter(onSlide(0), 'ob-rise', 0.26)}>{'一站式养宠生活圈\n上门喂养 遛狗 洗护'}</View>
            <View className="ob-subtitle ob-s1-subtitle" style={enter(onSlide(0), 'ob-rise', 0.36)}>{'Your nearby pet spirit \nwork platform'}</View>
            <View className="ob-dots" style={enter(onSlide(0), 'ob-rise', 0.46)}>
              <View className="ob-dot ob-dot-on" />
              <View className="ob-dot" />
              <View className="ob-dot" />
            </View>
            <View className="ob-btn-skip ob-skip-1" style={enter(onSlide(0), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={finish}>跳过</View>
            <View className="ob-btn-next ob-next-1" style={enter(onSlide(0), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={() => setCurrent(c => Math.min(c + 1, 2))}>下一页</View>
          </View>
        </SwiperItem>
        {/* 屏2（33:40）：花环背景 + 端坐橘猫 + 「一键召唤 附近的宠托师」 */}
        <SwiperItem>
          <View className="ob-slide" style={slideEnter(onSlide(1))}>
            <Image className="ob-s2-bg" style={enter(onSlide(1), 'ob-rise', 0)} src={wreathBg} mode="scaleToFill" />
            <View className="ob-s2-cat-wrap" style={enter(onSlide(1), 'ob-pop', 0.12)}>
              <Image className="ob-s2-cat ob-float" src={catSit} mode="scaleToFill" />
            </View>
            <Image className="ob-logo-m" style={enter(onSlide(1), 'ob-rise', 0.08)} src={brandLogoM} mode="scaleToFill" />
            <View className="ob-title ob-s2-title" style={enter(onSlide(1), 'ob-rise', 0.26)}>{'一键召唤\n附近的宠托师'}</View>
            <View className="ob-subtitle ob-s2-subtitle" style={enter(onSlide(1), 'ob-rise', 0.36)}>{'One-click\nsummon nearby pet trainers'}</View>
            <View className="ob-dots" style={enter(onSlide(1), 'ob-rise', 0.46)}>
              <View className="ob-dot" />
              <View className="ob-dot ob-dot-on" />
              <View className="ob-dot" />
            </View>
            <View className="ob-btn-skip ob-skip-2" style={enter(onSlide(1), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={finish}>跳过</View>
            <View className="ob-btn-next ob-next-2" style={enter(onSlide(1), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={() => setCurrent(c => Math.min(c + 1, 2))}>下一页</View>
          </View>
        </SwiperItem>
        {/* 屏3（33:58）：主人撸猫插画 + 「发动态、晒萌宠 找同城伙伴」（原稿文案保留）+ 开始体验 */}
        <SwiperItem>
          <View className="ob-slide" style={slideEnter(onSlide(2))}>
            <Image className="ob-s3-bg" style={enter(onSlide(2), 'ob-rise', 0)} src={sceneBg} mode="aspectFill" />
            <View className="ob-s3-strip-wrap" style={enter(onSlide(2), 'ob-pop', 0.12)}>
              <Image className="ob-s3-strip ob-float" src={ownerCat} mode="scaleToFill" />
            </View>
            <Image className="ob-logo-m" style={enter(onSlide(2), 'ob-rise', 0.08)} src={brandLogoM} mode="scaleToFill" />
            <View className="ob-title ob-s3-title" style={enter(onSlide(2), 'ob-rise', 0.26)}>{'发动态、晒萌宠\n找同城伙伴'}</View>
            <View className="ob-s3-sub-cn" style={enter(onSlide(2), 'ob-rise', 0.36)}>你需要的, 这里全都有</View>
            <View className="ob-s3-sub-en" style={enter(onSlide(2), 'ob-rise', 0.36)}>{'Service appointment\nSuper worry-free'}</View>
            <View className="ob-btn-start ob-start-3" style={enter(onSlide(2), 'ob-rise', 0.46)} hoverClass="ob-pressed" hoverStayTime={100} onClick={finish}>开始体验</View>
          </View>
        </SwiperItem>
      </Swiper>
    </View>
  )
}
