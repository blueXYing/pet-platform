import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { StoreProfileClient, type StoreProfileView } from '../../../shared/merchant-storefront-api'
import { RealMerchantOrderListRepository } from '../../order/repository'
import { MerchantOrderListController, type MerchantOrderListTab } from '../../order/list-controller'
import {
  merchantAppointmentWindow, merchantDisplayStatusLabels, merchantOrderTimeText,
} from '../../order/model'
import { businessStatusLabel } from '../../storefront/model'
import { previewOrderListDeps, previewStoreProfile } from '../../workbench/preview'
import { MerchantTabBar } from '../../components/tab-bar'
import bell from '../workspace/assets/bell.svg'
import './page.css'

// 商家订单列表页（10号 §4.1 增补）。视觉按 Figma 23:14926 外壳还原：店招头带 86
// #fff6e5 + 营业状态 + 铃铛、奶油底筛选 tab、白卡 #c0ecff 描边、金额右置、状态胶囊、
// 底部五 tab（订单 active）。功能仍按 45号/10号契约：displayStatus 服务端投影、
// PENDING_CONFIRM 30 分钟确认窗口、点卡进入 order-confirm 接单/拒单。
// 原稿的客户姓名/手机号/服务名/商家取消按钮不在商家读侧或与 45号冲突，不渲染。
// `?preview=1` 为开发者工具视觉验收夹具，使用 workbench/preview 样本，不接真实接口。

type TabKey = MerchantOrderListTab | 'REFUND'
const TABS: readonly { key: TabKey; label: string }[] = [
  { key: 'PENDING_CONFIRM', label: '待接单' },
  { key: null, label: '全部' },
  { key: 'PENDING_SERVICE', label: '待服务' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'REFUND', label: '退款' },
]

export default function MerchantOrderListPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const { scope } = useWorkspace('real')
  // 预览夹具先给 scope 一个商户坐标，控制器才能通过 ticket()；真实路径不变。
  const [previewStore] = useState<StoreProfileView | null>(() => {
    if (!preview) return null
    const current = scope.current
    if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      scope.replace({
        userId: '9007199254740993', workspace: 'merchant',
        merchantId: '9007199254740995', storeId: '9007199254740996',
      })
    }
    return previewStoreProfile()
  })
  const [controller] = useState(() => preview
    ? new MerchantOrderListController(previewOrderListDeps(scope))
    : new MerchantOrderListController({
      scope,
      list: query => new RealMerchantOrderListRepository(consumerApi).list(query),
    }))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [store, setStore] = useState<StoreProfileView | null>(previewStore)
  const [notice, setNotice] = useState('')
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = {
    '--mol-status-top': `${platformInfo.statusBarHeight || 0}px`,
    '--mol-unit': `${unit}px`,
  } as CSSProperties

  const loadStore = useCallback(() => {
    if (preview) return
    new StoreProfileClient(consumerApi).get().then(setStore).catch(() => setStore(null))
  }, [preview])
  const load = useCallback(() => {
    setNotice('')
    if (preview) { void controller.load(null); return }
    const current = scope.current
    if (!current) { controller.load(); setNotice('请先登录后从商家工作台进入。'); return }
    if (current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      controller.load(); setNotice('请从商家工作台进入订单处理。'); return
    }
    void controller.load()
  }, [controller, scope, preview])
  useDidShow(() => { loadStore(); load() })
  useEffect(() => () => controller.dispose(), [controller])

  function goWorkspace() {
    Taro.redirectTo({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function openProcess(orderId: string) {
    Taro.navigateTo({ url: `/merchant/pages/order-confirm/index?orderId=${encodeURIComponent(orderId)}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }
  function openRefund() {
    Taro.navigateTo({ url: '/merchant/pages/refund/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function openMessages() {
    const pages = Taro.getCurrentPages()
    const index = pages.findIndex(page => page.route === 'merchant/pages/messages/index')
    if (index >= 0) {
      const delta = pages.length - 1 - index
      if (delta > 0) Taro.navigateBack({ delta }).catch(() => setNotice('返回失败，请重试'))
      return
    }
    Taro.navigateTo({ url: '/merchant/pages/messages/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function choose(tab: TabKey) {
    if (tab === 'REFUND') { openRefund(); return }
    controller.chooseTab(tab)
  }

  const storeName = store?.readonlyFacts.storeName ?? '商家订单'
  const initial = Array.from(storeName)[0] ?? '商'
  const statusLabel = store ? businessStatusLabel[store.profile.businessStatus] ?? '' : ''
  const statusResting = store?.profile.businessStatus === 'RESTING'
  return <View className='mol-page' style={style}>
    <View className='mol-status-area' />
    <View className='mol-header'>
      <View className='mol-store'>
        <View className='mol-avatar'><Text>{initial}</Text></View>
        <View className='mol-store-main'>
          <Text className='mol-store-name'>{storeName}</Text>
          <Text className='mol-store-meta'>商家主账号</Text>
        </View>
      </View>
      <View className='mol-header-right'>
        {statusLabel ? <View className={statusResting ? 'mol-pill mol-pill-resting' : 'mol-pill'}>
          <View className='mol-pill-dot' /><Text>{statusLabel}</Text>
        </View> : null}
        <Button className='mol-bell' ariaLabel='消息通知' onClick={openMessages}><Image src={bell} mode='aspectFit' /></Button>
      </View>
    </View>
    <View className='mol-tabs'>
      {TABS.map(tab => tab.key === 'REFUND'
        ? <Button key={tab.label} className='mol-tab' onClick={() => choose(tab.key)}><Text>{tab.label}</Text></Button>
        : <Button key={tab.label} id={`mol-tab-${tab.key ?? 'all'}`}
          className={state.tab === tab.key ? 'mol-tab mol-tab-active' : 'mol-tab'}
          disabled={state.status === 'loading'}
          onClick={() => choose(tab.key)}><Text>{tab.label}</Text></Button>)}
    </View>
    <ScrollView className='mol-body' scrollY enhanced showScrollbar={false}>
      {(state.status === 'idle' || state.status === 'loading') && <View className='mol-state' role='status'><Text id='mol-loading'>正在读取订单…</Text></View>}
      {state.status === 'entry' && <View className='mol-state' role='status'>
        <Text id='mol-entry-hint'>{state.notice || notice || '请从商家工作台进入订单处理。'}</Text>
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
        <Text id='mol-empty'>{state.tab === null ? '本店暂无订单。' : state.tab === 'PENDING_CONFIRM' ? '暂无待接单订单；顾客支付后 30 分钟内未处理将由系统自动接单。' : '该状态暂无订单。'}</Text>
      </View>}
      {state.status === 'ready' && state.items.map(item => <Button key={item.orderId} id={`mol-card-${item.orderId}`}
        className='mol-card'
        ariaLabel={`订单 ${item.orderNo}，${merchantDisplayStatusLabels[item.displayStatus]}，${merchantAppointmentWindow(item)}，${item.payAmount} 元`}
        onClick={() => openProcess(item.orderId)}>
        <View className='mol-card-head'>
          <View className='mol-card-avatar'><Text>单</Text></View>
          <View className='mol-card-who'>
            <Text className='mol-card-no'>订单 {item.orderNo}</Text>
            <Text className='mol-card-service'>{merchantAppointmentWindow(item)}</Text>
          </View>
          <Text className='mol-card-amount'>¥{item.payAmount}</Text>
        </View>
        <View className='mol-card-meta'>
          <Text className={`mol-badge mol-badge-${item.displayStatus}`}>{merchantDisplayStatusLabels[item.displayStatus]}</Text>
          <Text className='mol-card-paid'>支付 {item.paidAt ? merchantOrderTimeText(item.paidAt) : '—'}</Text>
        </View>
        {item.displayStatus === 'PENDING_CONFIRM' && <View className='mol-card-actions'>
          <View className='mol-action-pill mol-action-reject'><Text>拒绝</Text></View>
          <View className='mol-action-pill mol-action-accept'><Text>接单</Text></View>
        </View>}
      </Button>)}
      {state.status === 'ready' && state.items.length < state.total
        && <Button id='mol-load-more' className='mol-more' disabled={state.loadingMore} onClick={() => void controller.loadMore()}>
          {state.loadingMore ? '正在加载…' : '加载更多'}
        </Button>}
      {state.status === 'ready' && <Text className='mol-count'>本店 · 共 {state.total} 笔订单</Text>}
      {state.notice && state.status !== 'load-error' && <Text id='mol-notice' className='mol-notice'>{state.notice}</Text>}
      {notice && <Text className='mol-notice'>{notice}</Text>}
      <View className='mol-tab-space' />
    </ScrollView>
    <MerchantTabBar active='orders' unit={unit} />
  </View>
}
