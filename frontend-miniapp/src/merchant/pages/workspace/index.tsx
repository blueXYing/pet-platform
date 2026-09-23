import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { MerchantWorkspace } from '../../workspace'
import { realDeps } from '../../admission-runtime'
import './index.css'

const reasonText: Record<string, string> = {
  NO_APPLICATION: '尚未提交入驻申请',
  APPLICATION_DRAFT: '入驻申请尚未提交审核',
  APPLICATION_PENDING: '入驻申请审核中，请耐心等待',
  APPLICATION_REJECTED: '入驻申请未通过，请查看审核意见',
  SIGNING_REQUIRED: '入驻申请已通过，请先签署商家协议',
  MERCHANT_OFFLINE: '商家已下线，暂停新经营业务',
  STORE_OFFLINE: '门店已下线，暂停新经营业务',
  MERCHANT_FROZEN: '商家已冻结，受限访问',
  STORE_FROZEN: '门店已冻结，受限访问',
}
const stepText: Record<string, string> = {
  APPLY: '去申请入驻',
  VIEW_APPLICATION: '查看入驻申请',
  COMPLETE_SIGNING: '去签署商家协议',
  VIEW_EXISTING_ORDERS: '查看存量订单',
  VIEW_AFTERSALES: '查看售后',
  APPEAL: '查看处罚与申诉',
  CONTACT_SUPPORT: '联系平台支持',
}
// Whitelist routing only; admission nextSteps never carry arbitrary redirect URLs.
function routeForStep(step: string, merchantId: string): string | null {
  if (step === 'COMPLETE_SIGNING') return `/consumer/pages/merchant-application/signing?merchantId=${merchantId}`
  if (step === 'VIEW_APPLICATION' || step === 'APPLY') return '/consumer/pages/merchant-application/index'
  return null
}

// No Figma original exists for the admission screen states (design registry §4); this page
// follows the application-page language and is not a 1:1 restoration or VIS pass.
export default function MerchantWorkbenchPage() {
  const { scope, context } = useWorkspace('real')
  const [controller] = useState(() => new MerchantWorkspace(scope))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const enter = () => { void controller.enter(realDeps()) }
  useDidShow(enter)
  useDidHide(() => controller.leave())
  useEffect(() => () => controller.dispose(), [controller])
  async function back() {
    controller.leave()
    await Taro.reLaunch({ url: '/consumer/pages/shell/index' })
  }
  function takeStep(step: string, merchantId: string) {
    const url = routeForStep(step, merchantId)
    if (url) void Taro.navigateTo({ url })
    else void Taro.showToast({ title: '该入口将在后续切片提供', icon: 'none' })
  }
  const view = state.view
  return <View className='merchant-workbench'>
    <View className='workbench-header'>
      <Button className='workbench-back' ariaLabel='返回' onClick={() => void back()}>返回</Button>
      <Text>商家工作台</Text>
    </View>
    <View className='workbench-body' data-state={state.status}>
      {state.status === 'idle' && <Text className='workbench-line'>正在进入工作台…</Text>}
      {state.status === 'checking' && <View className='workbench-line' role='status'>正在校验工作台准入…</View>}
      {state.status === 'error' && <View className='workbench-card workbench-error' role='alert'>
        <Text>{state.notice || '准入查询失败，未放行。'}</Text>
        <Button onClick={enter}>重试</Button>
      </View>}
      {state.status === 'no-stores' && <View className='workbench-card'>
        <Text>当前账号还没有可进入的商家门店。</Text>
        <Text className='workbench-hint'>完成入驻申请并审核通过、签署商家协议后即可进入。</Text>
        <Button onClick={() => void Taro.navigateTo({ url: '/consumer/pages/merchant-application/index' })}>去申请入驻</Button>
      </View>}
      {state.status === 'choose-store' && <View className='workbench-card'>
        <Text>请选择要进入的门店</Text>
        {state.stores?.map(store => <Button key={store.storeId} className='workbench-store'
          onClick={() => void controller.select(store.merchantId, store.storeId, realDeps())}>
          {store.storeName}<Text className='workbench-hint'>{store.merchantName}</Text>
        </Button>)}
      </View>}
      {view && (state.status === 'allowed' || state.status === 'limited' || state.status === 'denied') && <View className='workbench-card'>
        {state.status === 'allowed' && <Text className='workbench-title'>工作台已就绪</Text>}
        {state.status === 'limited' && <Text className='workbench-title'>门店受限访问</Text>}
        {state.status === 'denied' && <Text className='workbench-title'>暂不能进入工作台</Text>}
        {view.reasonCodes.length > 0 && <View className='workbench-reasons' role='status'>
          {view.reasonCodes.map(code => <Text key={code}>· {reasonText[code] || code}</Text>)}
        </View>}
        {state.status === 'limited' && <Text className='workbench-hint'>存量订单与售后读取不受影响；新经营业务暂停。处理中的订单请继续履约。</Text>}
        {state.status === 'allowed' && view.allowedActions.length > 0 && <View className='workbench-actions'>
          {view.allowedActions.map(action => <Text key={action} className='workbench-chip'>{action}</Text>)}
          <Text className='workbench-hint'>以上为入口提示；完整工作台功能（订单/排期）在后续切片交付。</Text>
        </View>}
        {state.status === 'allowed' && <View className='workbench-steps'>
          {/* M-002 service management entry (NAVIGATION-BASIS: workbench is the approved hub;
              the entry stays hidden for LIMITED stores — new-business writes are suspended). */}
          <Button id='workbench-services' onClick={() => void Taro.navigateTo({ url: '/merchant/pages/services/index' })}>服务管理</Button>
        </View>}
        {view.nextSteps.length > 0 && <View className='workbench-steps'>
          {view.nextSteps.map(step => <Button key={step.type} onClick={() => takeStep(step.type, view.merchantId)}>{stepText[step.type] || step.type}</Button>)}
        </View>}
        <Text className='workbench-meta'>校验时间 {view.checkedAt} · authzVersion {view.authzVersion}</Text>
      </View>}
      <Text className='workbench-meta'>当前工作区：{context?.workspace ?? '未登录'}{context?.merchantId ? ` · ${context.merchantId}` : ''}</Text>
    </View>
  </View>
}
