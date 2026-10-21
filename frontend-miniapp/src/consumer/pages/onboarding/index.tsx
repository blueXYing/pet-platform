import { Image, Swiper, SwiperItem, Text, View } from '@tarojs/components'
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
// 样式重置坑（前端技术基线 v0.7）；状态一律 className 变体，禁 data-*。

export default function OnboardingPage() {
  const info = Taro.getWindowInfo()
  const unit = info.windowWidth / 402
  const style = { '--ob-unit': `${unit}px` } as CSSProperties
  const [current, setCurrent] = useState(0)

  const finish = useCallback(() => {
    markOnboardingDone()
    Taro.navigateBack()
  }, [])

  return (
    <View className="ob-page" style={style}>
      <Swiper
        className="ob-swiper"
        current={current}
        duration={300}
        onChange={(e) => setCurrent(e.detail.current)}
      >
        {/* 屏1（10:108）：橘猫举鱼 + 花瓣装饰 + 「一站式养宠生活圈」 */}
        <SwiperItem>
          <View className="ob-slide">
            <Image className="ob-s1-decor" src={decorBand} mode="scaleToFill" />
            <Image className="ob-s1-petal-tl" src={petalTl} mode="scaleToFill" />
            <Image className="ob-s1-petal-mr" src={petalMr} mode="scaleToFill" />
            <Image className="ob-s1-petal-bl" src={petalBl} mode="scaleToFill" />
            <Image className="ob-s1-cat" src={catFish} mode="scaleToFill" />
            <Image className="ob-logo-s" src={brandLogoS} mode="scaleToFill" />
            <View className="ob-title ob-s1-title">{'一站式养宠生活圈\n上门喂养 遛狗 洗护'}</View>
            <View className="ob-subtitle ob-s1-subtitle">{'Your nearby pet spirit \nwork platform'}</View>
            <View className="ob-dots">
              <View className="ob-dot ob-dot-on" />
              <View className="ob-dot" />
              <View className="ob-dot" />
            </View>
            <View className="ob-btn-skip" onClick={finish}>跳过</View>
            <View className="ob-btn-next" onClick={() => setCurrent(1)}>下一页</View>
          </View>
        </SwiperItem>
        {/* 屏2（33:40）：花环背景 + 端坐橘猫 + 「一键召唤 附近的宠托师」 */}
        <SwiperItem>
          <View className="ob-slide">
            <Image className="ob-s2-bg" src={wreathBg} mode="scaleToFill" />
            <Image className="ob-s2-cat" src={catSit} mode="scaleToFill" />
            <Image className="ob-logo-m" src={brandLogoM} mode="scaleToFill" />
            <View className="ob-title ob-s2-title">{'一键召唤\n附近的宠托师'}</View>
            <View className="ob-subtitle ob-s2-subtitle">{'One-click\nsummon nearby pet trainers'}</View>
            <View className="ob-dots">
              <View className="ob-dot" />
              <View className="ob-dot ob-dot-on" />
              <View className="ob-dot" />
            </View>
            <View className="ob-btn-skip" onClick={finish}>跳过</View>
            <View className="ob-btn-next" onClick={() => setCurrent(2)}>下一页</View>
          </View>
        </SwiperItem>
        {/* 屏3（33:58）：主人撸猫插画 + 「发动态、晒萌宠 找同城伙伴」（原稿文案保留）+ 开始体验 */}
        <SwiperItem>
          <View className="ob-slide">
            <Image className="ob-s3-bg" src={sceneBg} mode="aspectFill" />
            <Image className="ob-s3-strip" src={ownerCat} mode="scaleToFill" />
            <Image className="ob-logo-m" src={brandLogoM} mode="scaleToFill" />
            <View className="ob-title ob-s3-title">{'发动态、晒萌宠\n找同城伙伴'}</View>
            <View className="ob-s3-sub-cn">你需要的, 这里全都有</View>
            <View className="ob-s3-sub-en">{'Service appointment\nSuper worry-free'}</View>
            <View className="ob-btn-start" onClick={finish}>开始体验</View>
          </View>
        </SwiperItem>
      </Swiper>
    </View>
  )
}
