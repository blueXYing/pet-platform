import { Button, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useState, type CSSProperties } from 'react'
import { ConsumerPageLayout } from '../../components/page-layout'
import {
  applicationEntryUrl, isProviderTypeCode, providerCopy, providerTypeChoices,
  providerTypeHint, stageProviderType, type ProviderTypeCode,
} from './model'
import './become-provider.css'

// 服务类型选择页（设计源 129:15547：同款半屏面板 + 服务类型下拉展开态，登记表 §4.16）。
// 选项 = 既有申请契约 merchantTypeOptions（宠物生活馆/宠物医院/宠物美容院/宠物寄养
// 中心/宠物训练机构/其他）；原稿选项为「请选择」占位文案，选项集以契约为准（偏差登记）。
// 选定即返回落地页（模块暂存回传）；URL 直入无落地页在栈时，兜底直接转既有申请页。
// 零接口调用、零写操作。
export default function ProviderServiceTypePage() {
  const route = useRouter()
  const [current, setCurrent] = useState<ProviderTypeCode | null>(() =>
    isProviderTypeCode(route.params.current) ? route.params.current : null)
  const [windowInfo] = useState(() => Taro.getWindowInfo())
  const unit = windowInfo.windowWidth / 402
  const style = { '--bep-unit': `${unit}px` } as CSSProperties

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: '/consumer/pages/mine/index' })
  }
  async function choose(code: ProviderTypeCode) {
    setCurrent(code)
    stageProviderType(code)
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: applicationEntryUrl(code) })
  }
  return <ConsumerPageLayout page='becomeProvider' unit={unit} className='bep-page' style={style}>
    <View className='bep-design'>
      <View className='bep-mask' ariaLabel='关闭服务类型选择' onClick={() => void back()} catchMove />
      <View className='bep-sheet' catchMove>
        <View className='bep-head'>
          <Text className='bep-title'>{providerCopy.title}</Text>
          <Button id='bept-close' className='bep-close' hoverClass='none' ariaLabel='关闭' onClick={() => void back()}>
            <View className='bep-close-icon' />
          </Button>
        </View>
        <Text className='bep-intro'>{providerCopy.intro}</Text>
        <View className='bep-note'>
          <View className='bep-note-icon' />
          <Text className='bep-note-text'>{providerCopy.note}</Text>
        </View>
        <View className='bep-field'>
          <Text className='bep-label'>{providerCopy.selectLabel}</Text>
          <View className='bep-select is-open'>
            <Text className='bep-select-value'>{providerTypeHint(current)}</Text>
            <View className='bep-select-chevron is-open' />
          </View>
          <View className='bep-options'>
            {providerTypeChoices.map(choice => <Button key={choice.code} id={`bept-option-${choice.code}`}
              className={`bep-option${current === choice.code ? ' is-current' : ''}`} hoverClass='none'
              ariaLabel={choice.label} onClick={() => void choose(choice.code)}>
              <Text className='bep-option-text'>{choice.label}</Text>
            </Button>)}
          </View>
        </View>
      </View>
    </View>
  </ConsumerPageLayout>
}
