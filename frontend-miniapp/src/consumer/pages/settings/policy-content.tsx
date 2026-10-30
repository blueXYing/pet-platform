import { Button, ScrollView, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useState, type CSSProperties, type ReactNode } from 'react'
import { privacyPolicyDoc, userAgreementDoc, type PolicyDoc } from '../../settings/model'
import { SubpageHeader, SubpageHeaderSpacer } from '../../components/navigation/subpage-header'
import './policy.css'

// 静态协议内容页骨架（隐私政策/用户协议共用；微信审核硬门槛——隐私政策入口必须可到达可读）。
// 文案为 V1 基线版本文本（settings/model.ts），纯静态渲染，无请求。
const MINE_TAB_URL = '/consumer/pages/mine/index'

export function PolicyContentPage({ doc }: { doc: PolicyDoc }): ReactNode {
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = {
    '--st-top': `${platformInfo.statusBarHeight || 0}px`,
    '--st-unit': `${unit}px`,
  } as CSSProperties
  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: MINE_TAB_URL })
  }
  return <View className='cpn-page st-page st-subpage' style={style}>
    <SubpageHeader title={doc.title} backId='plc-back' height={46} onBack={() => void back()} />
    <SubpageHeaderSpacer height={46} />
    <ScrollView className='plc-body' scrollY enhanced showScrollbar={false}>
      <View className='plc-meta'>
        <Text className='plc-version'>{doc.title} · {doc.version}</Text>
        <Text className='plc-updated'>更新日期：{doc.updatedAt}</Text>
      </View>
      {doc.sections.map((section, index) => <View key={index} className='plc-section' id={`plc-section-${index}`}>
        <Text className='plc-heading'>{section.heading}</Text>
        <Text className='plc-body-text'>{section.body}</Text>
      </View>)}
    </ScrollView>
  </View>
}
