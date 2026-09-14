import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { loadEngineeringFixture } from '../../../shared/fixture'
import { fixtureAdmission, type AdmissionFixture } from '../../admission'
import { MerchantWorkspace } from '../../workspace'
import './index.css'

const statusText = { idle: '尚未校验', checking: '正在校验内部样本', allowed: '内部样本：允许',
  denied: '内部样本：拒绝', error: '查询失败，未放行' }

export default function MerchantExample() {
  const { scope, context } = useWorkspace()
  const [controller] = useState(() => new MerchantWorkspace(scope))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  // Never read query parameters as admission or identity. Each new page defaults to deny.
  const scenario = useRef<AdmissionFixture>('deny')
  const enter = () => { void controller.enter(fixtureAdmission(scenario.current)) }
  useDidShow(enter)
  useDidHide(() => controller.leave())
  useEffect(() => () => controller.dispose(), [controller])
  function inject(next: AdmissionFixture) { scenario.current = next; enter() }
  async function back() {
    controller.leave()
    await Taro.reLaunch({ url: '/consumer/pages/shell/index' })
  }
  return <View className='shell merchant-example'>
    <Text className='line'>M-001 商家工程示例</Text>
    <Text className='line'>内部 fixture · 非产品页面 · 非真实准入</Text>
    <Button onClick={back}>返回用户工作区</Button>
    <Text className='line'>{statusText[state.status]}</Text>
    <Text className='line'>当前工作区：{context?.workspace ?? '已清除'}</Text>
    <View className='fixture-controls'>
      <Button onClick={() => inject('allow')}>注入允许样本</Button>
      <Button onClick={() => inject('deny')}>注入拒绝样本</Button>
      <Button onClick={() => inject('error')}>注入查询失败</Button>
      <Button onClick={enter}>重新校验</Button>
    </View>
    {state.status === 'allowed' && <View>
      <Text className='line'>样本门店：{context?.storeId}</Text>
      <Button onClick={() => inject('other-store')}>切换样本门店</Button>
      <Button onClick={() => void controller.loadSample(() => new Promise(resolve => {
        setTimeout(() => { void loadEngineeringFixture().then(resolve) }, 1600)
      }))}>读取延迟样本</Button>
      {state.loading && <Text className='line'>等待内部样本</Text>}
      {state.sample && <Text className='line'>{JSON.stringify(state.sample)}</Text>}
    </View>}
  </View>
}
