import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useRouter } from '@tarojs/taro'
import { useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi, consumerSession } from '../../../shared/consumer-runtime'
import { consumerFixture } from '../../../shared/fixture'
import { MerchantStatsClient, StoreProfileClient } from '../../../shared/merchant-storefront-api'
import { MerchantRefundClient } from '../../../shared/merchant-refund-api'
import { MerchantWorkspace } from '../../workspace'
import { clearTabHandoff, consumeTabHandoff } from '../../tab-handoff'
import { realDeps } from '../../admission-runtime'
import { fixtureDeps } from '../../admission'
import { MerchantWorkbenchDashboard } from '../../workbench/dashboard'
import { previewDashboardDeps } from '../../workbench/preview'
import { createWorkbenchCardOpeners } from '../../workbench/links'
import { RealMerchantOrderListRepository } from '../../order/repository'
import {
  merchantCardSubtitle, merchantCustomerInitial, merchantCustomerName, merchantOrderTimeText,
  type MerchantOrderSummary,
} from '../../order/model'
import { businessStatusLabel, deltaLabel } from '../../storefront/model'
import { MerchantTabBar } from '../../components/tab-bar'
import navBack from '../aftersale/assets/nav-back@3x.png'
import bell from './assets/bell.svg'
import paper from './assets/paper-grid.jpg'
import tape from './assets/tape.png'
import heroTicket from './assets/hero-ticket.svg'
import heroPanel from './assets/hero-panel.svg'
import chevron from './assets/chevron.svg'
import gridServices from './assets/grid-services.svg'
import gridSchedule from './assets/grid-schedule.svg'
import gridMembers from './assets/grid-members.svg'
import gridVerify from './assets/grid-verify.svg'
import gridMarketing from './assets/grid-marketing.svg'
import gridStats from './assets/grid-stats.svg'
import gridCustomers from './assets/grid-customers.svg'
import gridReviews from './assets/grid-reviews.svg'
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

/** 金额千分位（契约金额是两位小数字符串；null 显示 —）。 */
function moneyText(value: string | null): string {
  if (value === null) return '—'
  const [whole, fraction] = value.split('.')
  return `${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}${fraction ? `.${fraction}` : ''}`
}
/** 订单卡时间：只取 UTC+8 的 HH:mm；无窗口显示「待定」。 */
function orderClock(order: MerchantOrderSummary): string {
  const text = merchantOrderTimeText(order.appointmentStart)
  return text ? text.slice(11, 16) : '待定'
}
/** 待办倒计时（设计稿语言）；只做展示，不改变 30 分钟确认窗口的服务端权威。 */
function countdownText(order: MerchantOrderSummary): string {
  if (!order.appointmentStart) return '时间待定'
  const start = new Date(order.appointmentStart).getTime()
  if (!Number.isFinite(start)) return '时间待定'
  const minutes = Math.round((start - Date.now()) / 60_000)
  if (minutes <= 0) return '已到时间'
  if (minutes < 60) return `${minutes}分钟后`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours}小时后`
  return `${Math.round(hours / 24)}天后`
}
function countdownClass(order: MerchantOrderSummary): string {
  if (!order.appointmentStart) return 'mwb-todo-count mwb-todo-count-muted'
  const start = new Date(order.appointmentStart).getTime()
  if (!Number.isFinite(start)) return 'mwb-todo-count mwb-todo-count-muted'
  const minutes = Math.round((start - Date.now()) / 60_000)
  if (minutes <= 0) return 'mwb-todo-count mwb-todo-count-muted'
  return minutes < 60 ? 'mwb-todo-count mwb-todo-count-soon' : 'mwb-todo-count mwb-todo-count-later'
}
function deltaClass(value: string | null): string {
  const number = value === null ? 0 : Number(value)
  if (!Number.isFinite(number) || number === 0) return 'mwb-delta-value mwb-delta-flat'
  return number > 0 ? 'mwb-delta-value mwb-delta-up' : 'mwb-delta-value mwb-delta-down'
}

/** CCR-M-ORDER-CARD-001 客户头像：URL 为空或加载失败降级为首字占位圆，不伪造图片。 */
function CustomerAvatar({ url, initial }: { url: string | null; initial: string }) {
  const [failed, setFailed] = useState(false)
  if (url !== null && !failed) {
    return <Image className='mwb-todo-avatar mwb-todo-avatar-image' src={url} mode='aspectFill' onError={() => setFailed(true)} />
  }
  return <View className='mwb-todo-avatar'><Text>{initial}</Text></View>
}

/** 待办卡服务行：服务名·宠物（可空）；快照全缺时回落到既有订单尾号事实，不伪造。 */
function todoLine(order: MerchantOrderSummary): string {
  return merchantCardSubtitle(order) ?? `订单 ${order.orderNo.slice(-6)}`
}

// 商家工作台首页，按 Figma 95:1899（402x898）还原：
// - 头带 86 #fff6e5：店招 40 圆、店名/账号、营业中/休息中胶囊、铃铛（店铺资料 27号 §6.4）
// - 营收票券：paper-grid 纸纹 + hero-ticket/hero-panel 原稿矢量 + tape；今日营收/较昨日（70号 TODAY）；
//   票券可点（P-01，#workbench-hero-stats → openStats，与八宫格数据统计同目标）
// - 四 KPI：待确认/待服务（10号 §4.1 列表 total）、今日订单（70号）、退款（57号待处理 total）；
//   待确认/待服务深链 order-list 对应 tab、退款跳售后列表（P-07a-c）；今日订单本轮不跳转（P-07d 待口径）
// - 待办预约：PENDING_CONFIRM 订单横滑卡（CCR-M-ORDER-CARD-001 批准后补客户昵称/头像、
//   服务名、宠物名·品种；空值降级首字/订单尾号，评分仍不在读侧不渲染）
// - 8 格入口：完全按原稿 商品管理/排期管理/员工管理/核销记录/营销工具/数据统计/客户管理/店铺评价
// - 底部五 tab：首页/订单/核销/消息/我的（2026-11-04 用户批准）
// 所有既有入口 Button id、挂载条件、准入 handoff、请求语义保持；视觉块均为新增只读展示。
export default function MerchantWorkbenchPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const { scope, context, revision } = useWorkspace('real')
  const [controller] = useState(() => new MerchantWorkspace(scope))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [admissionDeps] = useState(() => preview ? fixtureDeps('allowed') : realDeps())
  const caps = useRef({ profile: false, stats: false, orders: false, refunds: false })
  const [dashboard] = useState(() => {
    if (preview) return new MerchantWorkbenchDashboard(previewDashboardDeps())
    const profileClient = new StoreProfileClient(consumerApi)
    const statsClient = new MerchantStatsClient(consumerApi)
    const orderRepository = new RealMerchantOrderListRepository(consumerApi)
    const refundClient = new MerchantRefundClient(consumerApi)
    const coordinates = () => {
      const current = consumerApi.scope.capture().context
      if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
        throw new Error('MERCHANT_ENTRY_REQUIRED')
      }
      return { merchantId: current.merchantId, storeId: current.storeId }
    }
    const skipped = () => Promise.reject(new Error('DASHBOARD_SKIPPED'))
    return new MerchantWorkbenchDashboard({
      profile: () => caps.current.profile ? profileClient.get() : skipped(),
      stats: () => caps.current.stats ? statsClient.overview('TODAY') : skipped(),
      orders: displayStatus => caps.current.orders
        ? orderRepository.list({ ...coordinates(), displayStatus, page: 1, pageSize: 20 })
        : skipped(),
      refunds: () => caps.current.refunds
        ? refundClient.list({ ...coordinates(), page: 1, pageSize: 20 })
        : skipped(),
    })
  })
  const dash = useSyncExternalStore(dashboard.subscribe, dashboard.getSnapshot)
  const enter = () => {
    clearTabHandoff()
    // ?preview=1 是开发者工具视觉验收夹具：先注入消费者样本上下文，再由 fixtureDeps 放行；
    // 真实路径仍要求已登录会话，0 改动。
    if (preview && !scope.current) scope.replace(consumerFixture)
    if (!preview) {
      // The provider launch and did-show race on first mount, and a reused page may return
      // with an expired grant while the merchant scope is still in memory. Always restore the
      // session before the admission read; launch() is single-flight at the auth layer.
      void consumerSession.launch()
        .then(() => controller.enter(admissionDeps))
        .catch(() => controller.enter(admissionDeps))
      return
    }
    void controller.enter(admissionDeps)
  }
  useDidShow(() => {
    // A disposed-but-reused page object must re-arm before enter() can publish again.
    controller.reopen()
    dashboard.show()
    enter()
  })
  useDidHide(() => {
    // Tab siblings are not workbench children, but they still need the merchant coordinates;
    // the tab bar leaves a one-shot marker so this hide is a handoff, not a leave-to-consumer.
    if (consumeTabHandoff()) controller.handoffToMerchantPage()
    controller.leave()
    dashboard.hide()
  })
  useEffect(() => () => { controller.dispose(); dashboard.dispose() }, [controller, dashboard])
  const [windowInfo] = useState(() => Taro.getWindowInfo())
  const unit = windowInfo.windowWidth / 402
  const style = {
    '--mwb-status-top': `${windowInfo.statusBarHeight || 0}px`,
    '--mwb-unit': `${unit}px`,
  } as CSSProperties

  const view = state.view
  const ready = state.status === 'allowed' || state.status === 'limited'
  const owner = view?.membershipKind === 'OWNER'
  const staff = view?.membershipKind === 'STAFF'
  const canOrders = Boolean(ready && owner && view?.allowedActions.includes('merchant.order.read'))
  const canRefunds = Boolean(canOrders && view?.allowedActions.includes('merchant.refund.read'))
  const canProfile = Boolean(ready && owner)
  const dashboardKey = `${revision}|${state.revision}|${state.status}|${view?.merchantId ?? ''}|${view?.storeId ?? ''}|${canProfile}|${canOrders}|${canRefunds}`
  const lastDashboardKey = useRef('')
  useEffect(() => {
    if (!ready || !owner) return
    if (lastDashboardKey.current === dashboardKey) return
    lastDashboardKey.current = dashboardKey
    caps.current = { profile: canProfile, stats: canOrders, orders: canOrders, refunds: canRefunds }
    dashboard.show()
    void dashboard.load()
  }, [dashboardKey, ready, owner, canProfile, canOrders, canRefunds, dashboard])

  async function back() {
    controller.leave()
    await Taro.switchTab({ url: '/consumer/pages/home/index' })
  }
  function takeStep(step: string, merchantId: string) {
    if (step === 'VIEW_AFTERSALES') { openAftersales(); return }
    const url = routeForStep(step, merchantId)
    if (url) void Taro.navigateTo({ url })
    else void Taro.showToast({ title: '该入口将在后续切片提供', icon: 'none' })
  }
  function handoff(url: string) {
    // Taro disposes the hidden workbench React tree for merchant child pages too; their
    // controllers must keep the merchant coordinates, so this is the same handoff contract
    // as a bottom-tab switch (survives dispose), not the consumer-leave path.
    controller.handoffToMerchantPage()
    Taro.navigateTo({ url }).catch(() => controller.handoffCancelled())
  }
  // P-01/P-07a-c：营收票券与 KPI 卡的可点出口全部收口在上面的 handoff 单点。
  const cardOpeners = createWorkbenchCardOpeners(handoff)
  function openServices() { handoff('/merchant/pages/services/index') }
  function openAftersales() { handoff('/merchant/pages/aftersale/index') }
  function openReviews() { handoff('/merchant/pages/reviews/index') }
  function openSchedule() { handoff('/merchant/pages/schedule/index') }
  function openMembers() { handoff('/merchant/pages/members/index') }
  function openStaffWorkbench() { handoff('/merchant/pages/staff-workbench/index') }
  function openVerify() { handoff('/merchant/pages/staff-verify/index') }
  function openOrders() { handoff('/merchant/pages/order-list/index') }
  function openCustomers() { handoff('/merchant/pages/customers/index') }
  function openStats() { cardOpeners.heroStats() }
  function openMarketing() { handoff('/merchant/pages/marketing/index') }
  function openOrder(order: MerchantOrderSummary) {
    handoff(`/merchant/pages/order-confirm/index?orderId=${encodeURIComponent(order.orderId)}`)
  }
  function openMessages() {
    controller.handoffToMerchantPage()
    const pages = Taro.getCurrentPages()
    const index = pages.findIndex(page => page.route === 'merchant/pages/messages/index')
    if (index >= 0) {
      const delta = pages.length - 1 - index
      if (delta > 0) Taro.navigateBack({ delta }).catch(() => controller.handoffCancelled())
      return
    }
    Taro.navigateTo({ url: '/merchant/pages/messages/index' }).catch(() => controller.handoffCancelled())
  }

  const storeName = dash.storeName ?? (owner ? '商家工作台' : staff ? '员工工作台' : '商家工作台')
  const initial = dash.storeInitial
  const statusLabel = dash.businessStatus ? businessStatusLabel[dash.businessStatus] ?? '' : ''
  const statusResting = dash.businessStatus === 'RESTING'
  const cells = [
    { id: 'workbench-services', label: '商品管理', icon: gridServices, open: openServices,
      enabled: Boolean(owner && state.status === 'allowed') },
    { id: 'workbench-schedule', label: '排期管理', icon: gridSchedule, open: openSchedule,
      enabled: Boolean(owner && state.status === 'allowed' && view?.allowedActions.includes('merchant.schedule.manage')) },
    { id: 'workbench-members', label: '员工管理', icon: gridMembers, open: openMembers,
      enabled: Boolean(owner && state.status === 'allowed') },
    { id: 'workbench-verify', label: '核销记录', icon: gridVerify, open: openVerify,
      enabled: Boolean(owner && ready) },
    { id: 'workbench-marketing', label: '营销工具', icon: gridMarketing, open: openMarketing,
      enabled: Boolean(owner && state.status === 'allowed') },
    { id: 'workbench-stats', label: '数据统计', icon: gridStats, open: openStats,
      enabled: Boolean(owner && ready && view?.allowedActions.includes('merchant.order.read')) },
    { id: 'workbench-customers', label: '客户管理', icon: gridCustomers, open: openCustomers,
      enabled: Boolean(owner && ready && view?.allowedActions.includes('merchant.order.read')) },
    { id: 'workbench-reviews', label: '店铺评价', icon: gridReviews, open: openReviews,
      enabled: Boolean(owner && ready && view?.allowedActions.includes('merchant.review.read')) },
  ]

  function renderAdmission() {
    if (state.status === 'checking' || state.status === 'idle') {
      return <View className='mwb-card' role='status'><Text>正在校验工作台准入…</Text></View>
    }
    if (state.status === 'error') {
      return <View className='mwb-card mwb-error' role='alert'>
        <Text>{state.notice || '准入查询失败，未放行。'}</Text>
        <View className='mwb-row'>
          <Button className='mwb-primary' onClick={enter}>重试</Button>
          <Button className='mwb-secondary' onClick={() => void back()}>返回用户端</Button>
        </View>
      </View>
    }
    if (state.status === 'no-stores') {
      return <View className='mwb-card'>
        <Text>当前账号还没有可进入的商家门店。</Text>
        <Text className='mwb-hint'>完成入驻申请并审核通过、签署商家协议后即可进入。</Text>
        <Button className='mwb-primary' onClick={() => void Taro.navigateTo({ url: '/consumer/pages/merchant-application/index' })}>去申请入驻</Button>
        {/* Staff binding (contract 54): pre-binding employees have no membership yet. */}
        <Button id='workbench-staff-entry' className='mwb-secondary' onClick={() => void Taro.navigateTo({ url: '/merchant/pages/staff-workbench/index' })}>收到店员邀请？去员工工作台确认</Button>
        <Button className='mwb-link' onClick={() => void back()}>返回用户端</Button>
      </View>
    }
    if (state.status === 'choose-store') {
      return <View className='mwb-card'>
        <Text>请选择要进入的门店</Text>
        {state.stores?.map(store => <Button key={store.storeId} className='mwb-store'
          onClick={() => void controller.select(store.merchantId, store.storeId, admissionDeps)}>
          {store.storeName}<Text className='mwb-hint'>{store.merchantName}</Text>
        </Button>)}
        <Button className='mwb-link' onClick={() => void back()}>返回用户端</Button>
      </View>
    }
    if (state.status === 'denied' && view) {
      return <View className='mwb-card'>
        {view.reasonCodes.length > 0 && <View className='mwb-reasons' role='status'>
          {view.reasonCodes.map(code => <Text key={code}>· {reasonText[code] || code}</Text>)}
        </View>}
        {view.nextSteps.length > 0 && <View className='mwb-steps'>
          {view.nextSteps.map(step => <Button key={step.type} onClick={() => takeStep(step.type, view.merchantId)}>{stepText[step.type] || step.type}</Button>)}
        </View>}
        <Button className='mwb-link' onClick={() => void back()}>返回用户端</Button>
      </View>
    }
    return null
  }

  function renderDashboard() {
    return <>
      {state.status === 'limited' && <View className='mwb-limited'>门店受限：存量订单与售后可查看，新经营业务暂停。</View>}
      {/* P-01：营收票券可点（同八宫格「数据统计」目标）；仅绑事件，视觉零改动。 */}
      <View id='workbench-hero-stats' className='mwb-hero-zone' onClick={openStats}>
        <View className='mwb-paper'><Image className='mwb-paper-image' src={paper} mode='widthFix' /></View>
        <Image className='mwb-ticket' src={heroTicket} mode='scaleToFill' />
        <View className='mwb-panel'><Image className='mwb-panel-image' src={heroPanel} mode='scaleToFill' /></View>
        <Image className='mwb-tape' src={tape} mode='scaleToFill' />
        <View className='mwb-hero-pill'><Text>今日营收</Text></View>
        <View className='mwb-amount'>
          <Text className='mwb-currency'>¥</Text>
          <Text className='mwb-number'>{moneyText(dash.revenue)}</Text>
        </View>
        <View className='mwb-delta'>
          <Text className='mwb-delta-label'>较昨日</Text>
          <Text className={deltaClass(dash.revenueDelta)}>{deltaLabel(dash.revenueDelta)}</Text>
        </View>
      </View>
      <View className='mwb-kpis'>
        <View id='workbench-kpi-pending-confirm' className='mwb-kpi' onClick={cardOpeners.pendingConfirm}>
          <View className='mwb-kpi-value'><Text>{dash.pendingConfirm ?? '—'}</Text>{dash.pendingConfirm ? <View className='mwb-kpi-dot' /> : null}</View>
          <Text className='mwb-kpi-label'>待确认</Text>
        </View>
        <View id='workbench-kpi-pending-service' className='mwb-kpi' onClick={cardOpeners.pendingService}>
          <View className='mwb-kpi-value'><Text>{dash.pendingService ?? '—'}</Text></View>
          <Text className='mwb-kpi-label'>待服务</Text>
        </View>
        {/* P-07d（待定）：今日订单二级页口径待产品确认，本轮保持不可跳转。 */}
        <View id='workbench-kpi-today-orders' className='mwb-kpi'>
          <View className='mwb-kpi-value'><Text>{dash.todayOrders ?? '—'}</Text></View>
          <Text className='mwb-kpi-label'>今日订单</Text>
        </View>
        <View id='workbench-kpi-refund' className='mwb-kpi' onClick={cardOpeners.refunds}>
          <View className='mwb-kpi-value'><Text>{dash.refunds ?? '—'}</Text></View>
          <Text className='mwb-kpi-label'>退款</Text>
        </View>
      </View>
      <View className='mwb-section-head'>
        <Text className='mwb-section-title'>待办预约</Text>
        <Button className='mwb-all' onClick={openOrders}><Text>全部</Text><Image className='mwb-chevron' src={chevron} mode='aspectFit' /></Button>
      </View>
      <ScrollView className='mwb-todo' scrollX enhanced showScrollbar={false}>
        <View className='mwb-todo-row'>
          {dash.pending.length === 0
            ? <View className='mwb-todo-empty'><Text>{dash.ordersReady ? '暂无待办预约' : '待办预约暂不可用'}</Text></View>
            : dash.pending.map(order => <View className='mwb-todo-card' key={order.orderId}>
              <View className='mwb-todo-head'>
                <CustomerAvatar url={order.customerAvatarUrl} initial={merchantCustomerInitial(order)} />
                <View className='mwb-todo-who'>
                  <Text className='mwb-todo-name'>{merchantCustomerName(order)}</Text>
                  <Text className='mwb-todo-time'>{orderClock(order)}</Text>
                </View>
              </View>
              <View className='mwb-todo-line'>
                <Text className='mwb-todo-service'>{todoLine(order)}</Text>
                <Text className='mwb-todo-amount'>¥{order.payAmount}</Text>
              </View>
              <Text className={countdownClass(order)}>{countdownText(order)}</Text>
              <View className='mwb-todo-actions'>
                <Button className='mwb-todo-reject' onClick={() => openOrder(order)}>拒绝</Button>
                <Button className='mwb-todo-accept' onClick={() => openOrder(order)}>接单</Button>
              </View>
            </View>)}
        </View>
      </ScrollView>
      <View className='mwb-grid-card'>
        <View className='mwb-grid'>
          {cells.map(cell => cell.enabled
            ? <Button key={cell.id} id={cell.id} className='mwb-grid-cell' onClick={cell.open}>
              <View className='mwb-grid-tile'><Image className='mwb-grid-icon' src={cell.icon} mode='aspectFit' /></View>
              <Text className='mwb-grid-label'>{cell.label}</Text>
            </Button>
            : <View key={cell.id} id={cell.id} className='mwb-grid-cell mwb-grid-disabled'
              onClick={() => { void Taro.showToast({ title: '当前状态不可用', icon: 'none' }) }}>
              <View className='mwb-grid-tile'><Image className='mwb-grid-icon' src={cell.icon} mode='aspectFit' /></View>
              <Text className='mwb-grid-label'>{cell.label}</Text>
            </View>)}
        </View>
      </View>
      {dash.notice && <Text className='mwb-dash-notice'>{dash.notice}</Text>}
      <View className='mwb-tab-space' />
    </>
  }

  function renderStaff() {
    return <>
      <View className='mwb-card'>
        <Text className='mwb-card-title'>员工账号</Text>
        <Text className='mwb-hint'>当前为员工身份；员工工作台与订单核销按服务端权限放行。</Text>
        <Button id='workbench-staff-workspace' className='mwb-primary' onClick={openStaffWorkbench}>进入员工工作台</Button>
        {/* P-13 方案 B：核销执行表单随员工工作台常驻；员工态的「订单核销」直达执行面板，
            staff-verify 已改为 OWNER 专属的核销记录列表（CCR-M-VERIFY-RECORD-001 §9.3）。 */}
        <Button id='workbench-verify' className='mwb-secondary' onClick={openStaffWorkbench}>订单核销</Button>
      </View>
      {view && view.reasonCodes.length > 0 && <View className='mwb-card'>
        {view.reasonCodes.map(code => <Text key={code}>· {reasonText[code] || code}</Text>)}
      </View>}
      {view && view.nextSteps.length > 0 && <View className='mwb-card mwb-steps'>
        {view.nextSteps.map(step => <Button key={step.type} onClick={() => takeStep(step.type, view.merchantId)}>{stepText[step.type] || step.type}</Button>)}
      </View>}
      <View className='mwb-tab-space' />
    </>
  }

  return <View className='mwb-page' style={style}>
    <View className='mwb-status-area' />
    <View className={ready ? 'mwb-header mwb-header-ready' : 'mwb-header'}>
      {!ready && <Button className='mwb-back' ariaLabel='返回' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>}
      <View className='mwb-store-head'>
        <View className='mwb-avatar'><Text>{initial}</Text></View>
        <View className='mwb-store-main'>
          <Text className='mwb-store-name'>{storeName}</Text>
          {owner
            ? <View className='mwb-store-meta-row'>
                <Text className='mwb-rating-star'>★ {dash.reviewAvg ?? '--'}</Text>
                <Text className='mwb-rating-label'>评分</Text>
              </View>
            : <Text className='mwb-store-meta'>{staff ? '员工账号' : '商家工作台'}</Text>}
        </View>
      </View>
      {ready && <View className='mwb-header-right'>
        {statusLabel ? <View className={statusResting ? 'mwb-pill mwb-pill-resting' : 'mwb-pill'}>
          <View className='mwb-pill-dot' /><Text>{statusLabel}</Text>
        </View> : null}
        <Button className='mwb-bell' ariaLabel='消息通知' onClick={openMessages}><Image src={bell} mode='aspectFit' /></Button>
      </View>}
    </View>
    <View className='mwb-body'>
      {!ready && renderAdmission()}
      {ready && owner && renderDashboard()}
      {ready && staff && renderStaff()}
    </View>
    <MerchantTabBar active='home' unit={unit}
      onBeforeSwitch={() => controller.handoffToMerchantPage()}
      onError={() => controller.handoffCancelled()} />
  </View>
}
