import { Button, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { ConsumerPageLayout } from '../../components/page-layout'
import {
  appointmentWindow, displayOrderStatuses, displayStatusLabels, isOrdersScenario, orderReadMessage,
  statusVariant, PreviewOrderReadRepository, type DisplayOrderStatus, type OrderDetailView,
} from '../../orders/model'
import { RealOrderReadRepository, isOrderReadUnauthorized } from '../../orders/repository'
import './orders.css'

// C-004 切片：我的订单列表（只读）。契约 10号 §3.7 + 11号 listMyOrders：真实模式按 tab 的
// displayStatus 参数服务端过滤分页（“全部”不发送该参数；displayStatus 由服务端统一计算，前端
// 不按底层字段重算），preview=1 走本地夹具本地分桶。卡面仅渲染 OrderDetailData 字段（订单号/
// 展示状态/预约时间窗/支付金额）：设计原稿 129:9946 的服务名/类目/门店名不在契约内，不显示
// （PR 登记差异）。失败关闭：路由未挂载 404、503、网络故障一律不可交互面板，不渲染虚构数据；
// 401/未登录引导去登录（coupons 先例）。
type Phase = 'loading' | 'ready' | 'expired' | 'load-error'
const PAGE_SIZE = 20

export default function OrdersListPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isOrdersScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { revision } = useWorkspace(preview ? 'preview' : 'real')
  return <ListScreen key={revision} preview={preview} scenario={scenario} />
}

function ListScreen({ preview, scenario }: { preview: boolean; scenario: 'normal' | 'empty' }) {
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState(() => preview ? new PreviewOrderReadRepository(scenario) : new RealOrderReadRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  const [orders, setOrders] = useState<readonly OrderDetailView[]>([])
  const [tab, setTab] = useState<DisplayOrderStatus | null>(null)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loadingMore, setLoadingMore] = useState(false)
  const [notice, setNotice] = useState('')
  const mounted = useRef(true)
  const sequence = useRef(0)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--ord-status-top': `${platformInfo.statusBarHeight || 0}px`, '--ord-unit': `${unit}px` } as CSSProperties

  const load = useCallback(async (nextTab: DisplayOrderStatus | null) => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setNotice('')
    if (preview) {
      setPhase('loading')
      try {
        const result = await scope.run(undefined, () => repository.list(nextTab, 1, PAGE_SIZE))
        if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
        setOrders(result.items); setTotal(result.total); setPage(1); setPhase('ready')
      } catch {
        if (mounted.current && current === sequence.current && currentRevision === scope.revision) setPhase('ready')
      }
      return
    }
    // 真实模式：先补一次会话校验，避免 restore 在途时误报未登录（aftersale/coupons 先例）。
    if (!scope.current) { try { await consumerApi.restore() } catch { /* 未登录，走下方登录引导 */ } }
    if (current !== sequence.current || currentRevision !== scope.revision) return
    if (!scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    setPhase('loading')
    try {
      const result = await scope.run(undefined, () => repository.list(nextTab, 1, PAGE_SIZE))
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setOrders(result.items); setTotal(result.total); setPage(1); setPhase('ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setNotice(orderReadMessage(error))
      setPhase(isOrderReadUnauthorized(error) ? 'expired' : 'load-error')
    }
  }, [preview, scope, repository])
  useEffect(() => { mounted.current = true; void load(tab); return () => { mounted.current = false } }, [load])
  function chooseTab(key: DisplayOrderStatus | null) {
    if (key === tab) return
    setTab(key)
    void load(key) // preview 与真实均按当前桶重读第一页（夹具本地分桶 / 服务端 displayStatus 过滤）。
  }
  async function loadMore() {
    if (loadingMore || phase !== 'ready' || orders.length >= total) return
    setLoadingMore(true)
    const current = sequence.current
    const currentRevision = scope.revision
    try {
      const result = await scope.run(undefined, () => repository.list(tab, page + 1, PAGE_SIZE))
      if (current !== sequence.current || currentRevision !== scope.revision) return
      const known = new Set(orders.map(order => order.orderId))
      setOrders([...orders, ...result.items.filter(order => !known.has(order.orderId))])
      setPage(result.page); setTotal(result.total)
    } catch { setNotice('加载更多失败，请重试。') } finally { setLoadingMore(false) }
  }
  function goLogin() { void Taro.redirectTo({ url: '/consumer/pages/shell/index' }) }
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/consumer/pages/shell/index' })
  }
  const ready = phase === 'ready'
  return <ConsumerPageLayout page='orderList' unit={unit} className='ord-page' style={style}
    navigation={{ idPrefix: 'ord', disabled: phase === 'loading', onSelect: key => { void Taro.showToast({ title: navigationUnavailableMessage(key), icon: 'none' }) } }}>
    <View className='ord-design'>
      <View className='ord-status-area' />
      <View className='ord-nav'>
        <Button id='ord-back' ariaLabel='返回' className='ord-nav-back' onClick={() => void goBack()}>
          <Text className='ord-nav-back-icon'>‹</Text>
        </Button>
        <Text className='ord-nav-title'>我的订单</Text>
      </View>
      {/* 契约桶=displayStatus 单值查询（§3.7）+“全部”（不发送参数）。设计原稿 5 桶中的“进行中”
          无法用单一 displayStatus 表达，不并入任何桶（PR 登记差异），横向滚动承载十状态。 */}
      <ScrollView scrollX className='ord-tabs'>
        <Button className={`ord-tab${tab === null ? ' is-active' : ''}`} ariaLabel='全部' onClick={() => chooseTab(null)}><Text>全部</Text></Button>
        {displayOrderStatuses.map(status => <Button key={status} className={`ord-tab${tab === status ? ' is-active' : ''}`}
          ariaLabel={displayStatusLabels[status]} onClick={() => chooseTab(status)}><Text>{displayStatusLabels[status]}</Text></Button>)}
      </ScrollView>
      {phase === 'loading' && <View className='ord-state' role='status'><Text id='ord-loading'>正在读取订单…</Text></View>}
      {phase === 'expired' && <View className='ord-state' role='status'>
        <Text id='ord-login-hint'>登录后可查看我的订单。</Text>
        <Button id='ord-login' className='ord-state-action' onClick={goLogin}>去登录</Button>
      </View>}
      {phase === 'load-error' && <View className='ord-state' role='status'>
        <Text id='ord-error'>{notice || '订单读取失败，请稍后重试。'}</Text>
        <Button id='ord-retry' className='ord-state-action' onClick={() => void load(tab)}>重新加载</Button>
      </View>}
      {ready && orders.length === 0 && <View className='ord-state'><Text id='ord-empty'>{tab === null ? '暂无订单。' : `暂无${displayStatusLabels[tab]}订单。`}</Text></View>}
      {ready && orders.map(order => <Button key={order.orderId} id={`ord-card-${order.orderId}`}
        className='ord-card'
        ariaLabel={`订单 ${order.orderNo}，${displayStatusLabels[order.displayStatus]}，${appointmentWindow(order)}，${order.payAmount} 元`}
        onClick={() => { void Taro.navigateTo({ url: `/consumer/pages/orders/detail?${preview ? 'preview=1&' : ''}orderId=${encodeURIComponent(order.orderId)}` }).catch(() => setNotice('页面跳转失败，请重试')) }}>
        <View className='ord-card-head'>
          <Text className='ord-card-no'>订单号 {order.orderNo}</Text>
          <Text className={`ord-badge ${statusVariant(order.displayStatus)}`}>{displayStatusLabels[order.displayStatus]}</Text>
        </View>
        <Text className='ord-card-time'>服务时间 {appointmentWindow(order)}</Text>
        <View className='ord-card-foot'>
          <Text className='ord-card-id'>ID {order.orderId}</Text>
          <Text className='ord-card-amount'>¥{order.payAmount}</Text>
        </View>
      </Button>)}
      {ready && orders.length < total && <Button id='ord-load-more' className='ord-more' disabled={loadingMore} onClick={() => void loadMore()}>
        {loadingMore ? '正在加载…' : '加载更多'}
      </Button>}
      {ready && <View className='ord-preview-note'><Text>{preview ? '只读预览：本地样例数据，仅用于设计验收，不发起真实请求。' : '页面数据：真实接口（只读查询）。'}</Text></View>}
      {notice && phase !== 'load-error' && <Text id='ord-notice' className='ord-notice'>{notice}</Text>}
    </View>
  </ConsumerPageLayout>
}
