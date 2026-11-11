import { Button, Image, Input, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useRouter } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { consumerApi, consumerSession, consumerStorage } from '../../../shared/consumer-runtime'
import { merchantStorefrontDeps } from '../../storefront/repository'
import { MerchantCustomersController } from '../../storefront/read-controllers'
import { lastVisitLabel } from '../../storefront/model'
import { previewStatsDeps } from '../../workbench/preview'
import { MerchantTabBar } from '../../components/tab-bar'
import navBack from '../aftersale/assets/nav-back@3x.png'
import './page.css'

function useCustomers(preview = false) {
  // ?preview=1：先注入商户坐标，再用本地客户样本；真实路径不变。
  useState(() => {
    if (!preview) return false
    const current = consumerApi.scope.current
    if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      consumerApi.scope.replace({
        userId: '9007199254740993', workspace: 'merchant',
        merchantId: '9007199254740995', storeId: '9007199254740996',
      })
    }
    return true
  })
  const [controller] = useState(() => {
    const context = consumerApi.scope.current
    if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      throw new Error('MERCHANT_ENTRY_REQUIRED')
    }
    return new MerchantCustomersController(
      preview ? previewStatsDeps() : merchantStorefrontDeps(consumerApi, consumerStorage),
      { merchantId: context.merchantId, storeId: context.storeId })
  })
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => {
    void (async () => {
      try { await consumerSession.launch() } catch { /* controller.load() renders the error state */ }
      await controller.load()
    })()
  })
  useDidHide(() => controller.hide())
  useEffect(() => () => controller.dispose(), [controller])
  const [info] = useState(() => Taro.getWindowInfo())
  const style = { '--mcu-unit': `${info.windowWidth / 402}px`, '--mcu-status-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  return { controller, state, style }
}

async function backToWorkbench() {
  try {
    // 栈上有上一页时优先返回；否则 reLaunch 重建工作台（P-03：redirectTo 进工作台会残留
    // 已被 dispose 的旧工作台实例，返回即 stale placeholder 白屏）。
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.reLaunch({ url: '/merchant/pages/workspace/index' })
  } catch { await Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) }
}

// 客户管理 M 面（69号 + 2026-10-19 扩展 / PRD 5.12，帧 23:12738 偏差已登记）：只读派生客户
// 列表——脱敏手机号、昵称、消费笔数/累计/客单价、最近到店、常用宠物（名字/品种级）。
// 搜索（扩展）：昵称包含匹配 / 订单号尾号（仅命中本店订单尾号，2-10 位数字，非隐私面）。
// 隐私红线不变：不导出、不营销触达、不含健康字段、无明文手机号/完整手机号搜索。
export default function MerchantCustomersPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const { controller, state, style } = useCustomers(preview)
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  return <View className='mcu-page' style={style}>
    <View className='mcu-status-area' />
    <View className='mcu-header'>
      <Button className='mcu-back' ariaLabel='返回' onClick={() => void backToWorkbench()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mcu-title'>客户管理</Text>
    </View>
    {state.phase !== 'ready' ? <View className='mcu-state' role='status'>
      <Text>{state.phase === 'loading' || state.phase === 'idle' ? '正在读取本店客户…' : state.notice || (state.phase === 'entry' ? '仅商家 OWNER 可查看本店客户档案' : '页面暂时不可用')}</Text>
      {state.phase === 'error' && <Button className='mcu-primary' onClick={() => void controller.load()}>重新加载</Button>}
      {state.phase === 'entry' && <Button className='mcu-primary' onClick={() => void Taro.reLaunch({ url: '/merchant/pages/workspace/index' })}>去商家工作台</Button>}
    </View> : <>
      {state.frozen && <View className='mcu-alert'>门店受限，客户档案只读。</View>}
      <View className='mcu-search-row' role='tablist'>
        <Button id='mcu-mode-nickname' className={state.searchMode === 'nickname' ? 'mcu-search-mode mcu-search-mode-on' : 'mcu-search-mode'}
          onClick={() => controller.switchSearchMode('nickname')}>昵称</Button>
        <Button id='mcu-mode-suffix' className={state.searchMode === 'orderNoSuffix' ? 'mcu-search-mode mcu-search-mode-on' : 'mcu-search-mode'}
          onClick={() => controller.switchSearchMode('orderNoSuffix')}>订单尾号</Button>
      </View>
      <View className='mcu-search-row'>
        <Input className='mcu-search' value={state.search}
          placeholder={state.searchMode === 'nickname' ? '搜索客户昵称' : '输入本店订单号尾号（2-10 位数字）'}
          onInput={event => controller.editSearch(String(event.detail.value))}
          onConfirm={() => void controller.search()} />
        <Button id='mcu-search-btn' className='mcu-search-action' onClick={() => void controller.search()}>搜索</Button>
      </View>
      <Text className='mcu-count'>当前门店 · 共 {state.total} 位客户（来自本店订单）</Text>
      <View className='mcu-list'>
        {state.items.length === 0 && <View className='mcu-empty'>本店暂无客户</View>}
        {state.items.map(item => <Button key={item.userId} id={`mcu-customer-${item.userId}`}
          className='mcu-card' onClick={() => void Taro.navigateTo({ url: `/merchant/pages/customers/detail?userId=${item.userId}${preview ? '&preview=1' : ''}` })}>
          <View className='mcu-line mcu-card-head'>
            <View className='mcu-avatar'><Text>{Array.from(item.nickname ?? '客')[0] ?? '客'}</Text></View>
            <View className='mcu-identity'>
              <Text className='mcu-name'>{item.nickname ?? '未命名用户'}</Text>
              <Text className='mcu-phone'>{item.phoneMasked ?? '未绑定手机号'}</Text>
            </View>
            <Text className='mcu-visit'>{lastVisitLabel(item.lastVisitAt, new Date())}到店</Text>
          </View>
          <View className='mcu-line mcu-card-body'>
            <Text className='mcu-pet'>{item.topPet ? `${item.topPet.breedName ?? item.topPet.petType} · ${item.topPet.petName}` : '—'}</Text>
            <View className='mcu-metrics'>
              <View className='mcu-metric'><Text className='mcu-metric-value'>¥{item.totalSpend}</Text><Text className='mcu-metric-label'>累计消费</Text></View>
              <View className='mcu-metric'><Text className='mcu-metric-value'>{item.orderCount}</Text><Text className='mcu-metric-label'>累积订单</Text></View>
              <View className='mcu-metric'><Text className='mcu-metric-value'>{item.avgTicket ? `¥${item.avgTicket}` : '--'}</Text><Text className='mcu-metric-label'>客单价</Text></View>
            </View>
          </View>
        </Button>)}
        {state.items.length < state.total && <Button id='mcu-more' className='mcu-more' disabled={state.loadingMore}
          onClick={() => void controller.loadMore()}>{state.loadingMore ? '正在加载…' : '加载更多'}</Button>}
        {state.notice && <View className='mcu-notice' role='status'><Text>{state.notice}</Text></View>}
      </View>
      <Text className='mcu-tail'>仅展示本店订单派生数据 · 手机号默认脱敏（69号）</Text>
      <View className='mcu-bottom-space' />
      <View className='mcu-tab-space' />
    </>}
    <MerchantTabBar active='home' unit={unit} />
  </View>
}
