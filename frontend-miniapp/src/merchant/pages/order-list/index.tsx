import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealMerchantOrderListRepository } from '../../order/repository'
import { MerchantOrderListController, type MerchantOrderListTab } from '../../order/list-controller'
import { merchantAppointmentWindow, merchantDisplayStatusLabels, merchantOrderTimeText } from '../../order/model'
import navBack from '../members/assets/nav-back@2x.png'
import './page.css'

// 商家订单列表页（10号 §4.1 增补，#129 后续切片）：把 #129 的“按单号处理”升级为真列表。
// OWNER 主账号按工作台商家坐标读取本店订单（服务端在门店 guard 事务内重验归属，跨店隔离
// 与防枚举 403 均在服务端）；「待接单」tab = displayStatus=PENDING_CONFIRM（重点面板，30 分钟
// 确认窗口）、「全部」tab 不发送过滤参数；分页沿固定排序 created_at DESC, id DESC 稳定加载。
// displayStatus 由服务端统一计算，页面不按底层字段推导；卡面仅契约最小字段集（订单号/状态/
// 预约时间窗/支付金额/支付时间——paidAt 是 30 分钟规则的锚点事实，只呈现不推导截止）。
// 点单进入 #129 处理页（orderId 透传，处理页身份每次服务端复验）；从处理页返回后 did-show
// 重读当前桶。403（开关未开/无权限/非本店）与 503 依赖故障整页失败关闭；无商家工作台坐标
// 引导回工作台。沿 M 端现行规范（order-confirm/aftersale 列表页）实现，非设计稿一比一还原
// （登记表无 M 端订单列表原稿帧）；状态样式只用 className 变体，不用 data-*。

export default function MerchantOrderListPage() {
  const { scope } = useWorkspace('real')
  const [controller] = useState(() => {
    const repository = new RealMerchantOrderListRepository(consumerApi)
    return new MerchantOrderListController({ list: query => repository.list(query), scope })
  })
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [notice, setNotice] = useState('')
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--mol-status-top': `${platformInfo.statusBarHeight || 0}px`, '--mol-unit': `${unit}px` } as CSSProperties

  useDidShow(() => { setNotice(''); void controller.load() })
  useDidHide(() => { /* the controller keeps its page state; did-show reloads the tab */ })
  useEffect(() => () => controller.dispose(), [controller])

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack().catch(() => setNotice('返回失败，请重试'))
    else await Taro.redirectTo({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('返回失败，请重试'))
  }
  function goWorkspace() {
    Taro.redirectTo({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function openProcess(orderId: string) {
    // 点单进入 #129 处理页：orderId 透传（处理页仍按单服务端复验 OWNER 归属）。
    Taro.navigateTo({ url: `/merchant/pages/order-confirm/index?orderId=${encodeURIComponent(orderId)}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }

  const tabs: readonly { key: MerchantOrderListTab; label: string }[] = [
    { key: 'PENDING_CONFIRM', label: '待接单' },
    { key: null, label: '全部' },
  ]
  return <View className='mol-page' style={style}>
    <View className='mol-status-area' />
    <View className='mol-header'>
      <Button id='mol-back' ariaLabel='返回' className='mol-back' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mol-title'>订单处理</Text>
    </View>
    {state.status !== 'entry' && state.status !== 'closed' && <View className='mol-tabs'>
      {tabs.map(tab => <Button key={tab.label} id={`mol-tab-${tab.key ?? 'all'}`}
        className={state.tab === tab.key ? 'mol-tab mol-tab-active' : 'mol-tab'}
        disabled={state.status === 'loading'}
        onClick={() => controller.chooseTab(tab.key)}><Text>{tab.label}</Text></Button>)}
    </View>}
    <ScrollView className='mol-body' scrollY enhanced showScrollbar={false}>
      {(state.status === 'idle' || state.status === 'loading') && <View className='mol-state' role='status'><Text id='mol-loading'>正在读取订单…</Text></View>}
      {state.status === 'entry' && <View className='mol-state' role='status'>
        <Text id='mol-entry-hint'>{state.notice || '请从商家工作台进入订单处理。'}</Text>
        <Button id='mol-go-workspace' className='mol-state-action' onClick={goWorkspace}>去商家工作台</Button>
      </View>}
      {state.status === 'closed' && <View className='mol-panel mol-panel-closed' role='alert'>
        <Text className='mol-panel-title'>订单列表暂不可用</Text>
        <Text className='mol-panel-line'>{state.notice}</Text>
        <Text className='mol-panel-line'>通道开关默认由平台开启；无权限、非本店坐标与依赖故障同样整页失败关闭，不渲染任何订单数据。</Text>
      </View>}
      {state.status === 'load-error' && <View className='mol-state' role='status'>
        <Text id='mol-error'>{state.notice || '订单读取失败，请稍后重试。'}</Text>
        <Button id='mol-retry' className='mol-state-action' onClick={() => void controller.load()}>重新加载</Button>
      </View>}
      {state.status === 'ready' && state.items.length === 0 && <View className='mol-state'>
        <Text id='mol-empty'>{state.tab === null ? '本店暂无订单。' : '暂无待接单订单；顾客支付后 30 分钟内未处理将由系统自动接单。'}</Text>
      </View>}
      {state.status === 'ready' && state.items.map(item => <Button key={item.orderId} id={`mol-card-${item.orderId}`}
        className='mol-card'
        ariaLabel={`订单 ${item.orderNo}，${merchantDisplayStatusLabels[item.displayStatus]}，${merchantAppointmentWindow(item)}，${item.payAmount} 元`}
        onClick={() => openProcess(item.orderId)}>
        <View className='mol-card-head'>
          <Text className='mol-card-no'>订单号 {item.orderNo}</Text>
          <Text className={`mol-badge mol-badge-${item.displayStatus}`}>{merchantDisplayStatusLabels[item.displayStatus]}</Text>
        </View>
        <Text className='mol-card-time'>服务时间 {merchantAppointmentWindow(item)}</Text>
        <Text className='mol-card-time'>支付时间 {item.paidAt ? merchantOrderTimeText(item.paidAt) : '—'}</Text>
        <View className='mol-card-foot'>
          <Text className='mol-card-id'>ID {item.orderId}</Text>
          <Text className='mol-card-amount'>¥{item.payAmount}</Text>
        </View>
        {item.displayStatus === 'PENDING_CONFIRM' && <Text className='mol-card-action-hint'>点按进入接单 / 拒单</Text>}
      </Button>)}
      {state.status === 'ready' && state.items.length < state.total
        && <Button id='mol-load-more' className='mol-more' disabled={state.loadingMore} onClick={() => void controller.loadMore()}>
          {state.loadingMore ? '正在加载…' : '加载更多'}
        </Button>}
      {state.status === 'ready' && <Text className='mol-count'>本店 · 共 {state.total} 笔订单</Text>}
      {state.notice && state.status !== 'load-error' && <Text id='mol-notice' className='mol-notice'>{state.notice}</Text>}
      {notice && <Text className='mol-notice'>{notice}</Text>}
      <View className='mol-tail'><Text>页面数据：真实接口（contract 10 §4.1 商家订单列表读侧，平台开关默认关闭时接口失败关闭）</Text></View>
    </ScrollView>
  </View>
}
