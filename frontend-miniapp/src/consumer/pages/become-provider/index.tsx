import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useState, type CSSProperties } from 'react'
import { ConsumerPageLayout } from '../../components/page-layout'
import { switchConsumerTab } from '../../components/navigation/switch'
import {
  applicationEntryUrl, isProviderTypeCode, providerCopy, providerTypeHint,
  serviceTypePageUrl, takeStagedProviderType, type ProviderTypeCode,
} from './model'
import './become-provider.css'

// 成为服务者落地页（32号裁决 §2 七页批次；设计源 129:14566，登记表 §4.16）。
// 「我的」更多服务格位进入的半屏面板：标题/简介/说明行按原稿一比一；表单区只保留
// 服务类型选择（下一步进既有 merchant-application 申请页按其完整表单+材料填报）。
// 原稿把整套入驻表单画在面板内并主张「与开店商家是两种不同身份」——V1 契约只有
// 既有商家入驻申请一条链路，按任务口径以既有契约为准：选择结果以 merchantTypeCode
// （既有 DraftInput 字段）传入申请页，不造第二套入驻后端（偏差登记 §4.16）。
// 本页零接口调用、零写操作；「我的」#150 格位为本页唯一正式入口。
export default function BecomeProviderPage() {
  const route = useRouter()
  const [selected, setSelected] = useState<ProviderTypeCode | null>(() =>
    isProviderTypeCode(route.params.type) ? route.params.type : null)
  // service-type 页返回时取回选择（一次取回即清空，无跨会话残留）。
  useDidShow(() => {
    const staged = takeStagedProviderType()
    if (staged) setSelected(staged)
  })
  const [windowInfo] = useState(() => Taro.getWindowInfo())
  const unit = windowInfo.windowWidth / 402
  const style = { '--bep-unit': `${unit}px` } as CSSProperties

  async function close() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: '/consumer/pages/mine/index' })
  }
  async function openTypePage() {
    await Taro.navigateTo({ url: serviceTypePageUrl(selected) })
  }
  async function enterApplication() {
    if (!selected) {
      void Taro.showToast({ title: providerCopy.toastPickType, icon: 'none' })
      return
    }
    await Taro.navigateTo({ url: applicationEntryUrl(selected) })
  }
  return <ConsumerPageLayout page='becomeProvider' unit={unit} className='bep-page' style={style}
    navigation={{ idPrefix: 'bepro', onSelect: key => { void switchConsumerTab(key) } }}>
    <View className='bep-design'>
      <View className='bep-mask' ariaLabel='关闭成为服务者' onClick={() => void close()} catchMove />
      <View className='bep-sheet' catchMove>
        <View className='bep-head'>
          <Text className='bep-title'>{providerCopy.title}</Text>
          <Button id='bep-close' className='bep-close' hoverClass='none' ariaLabel='关闭' onClick={() => void close()}>
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
          <Button id='bep-type' className='bep-select' hoverClass='none' ariaLabel='选择服务类型' onClick={() => void openTypePage()}>
            <Text className='bep-select-value'>{providerTypeHint(selected)}</Text>
            <View className='bep-select-chevron' />
          </Button>
        </View>
        <Button id='bep-enter' className='bep-primary' hoverClass='none' onClick={() => void enterApplication()}>
          {providerCopy.primary}
        </Button>
      </View>
    </View>
  </ConsumerPageLayout>
}
