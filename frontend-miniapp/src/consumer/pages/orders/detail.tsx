import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { ConsumerPageLayout } from '../../components/page-layout'
// ARCH-005 分工：本页不触碰订单原始事实字段（orderStage/paymentStatus/verificationStatus/
// refund*/afterSaleStatus），事实→展示的推导（文案映射/空值占位/按钮可见性/缺核销码说明）
// 全部委托 src/consumer/orders/model.ts（orderStatusBadge/orderFactRows/enabledActionLabels/
// verifyAbsenceNotice）；页面只消费现成展示值与非事实字段（订单号/金额/预约时间窗）。
import {
  appointmentWindow, canShowVerifyBlock, orderFactRows, orderReadMessage, orderStatusBadge,
  verifyAbsenceNotice, PreviewOrderReadRepository, type OrderDetailView,
} from '../../orders/model'
import { canApplyRefundEntry } from '../../orders/refund'
import { canRescheduleEntry } from '../../orders/reschedule'
import { RealOrderReadRepository, isOrderReadUnauthorized } from '../../orders/repository'
// 支付发起入口（booking 切片）：仅凭服务端 OrderActions.canPay（§3.7 #123）挂载，不推导业务真相。
import { canInitiatePayment } from '../../booking/model'
import {
  OrderVerifyController, PreviewOrderVerifyRepository, actionFor, formatCode,
  formatInstant, isOrderId, isOrderVerifyScenario, remainingLabel, statusHints, statusLabels,
  type OrderVerifyScenario,
} from '../../order-verify/model'
import { RealOrderVerifyRepository } from '../../order-verify/repository'
import './orders.css'

// C-004 切片：订单详情（只读 + 核销码主入口）。契约 10号 §3.7 + 11号 OrderDetailData：字段
// 全量只读呈现（UI 按服务端返回的 actions 决定入口，不推导业务真相）；核销码区块仅在
// actions.canShowVerificationCode=true 时呈现，复用 #116 的取码/刷新/五状态/429 交互
//（OrderVerifyController + Real/PreviewOrderVerifyRepository，47号 §4）。已核销信息按契约字段
//（verificationStatus/verifiedAt）展示；核销操作人不在契约内，不显示（PR 登记）。
// #116 的 order-verify 页保留为直连通道。preview=1 走本地夹具（orderId 取夹具单；scenario
// 透传核销码本地模拟）；真实模式走 §3.7 接口（后端同批交付，联调留 E2E 轮，如实披露）。
type Phase = 'loading' | 'ready' | 'invalid' | 'expired' | 'load-error'

export default function OrderDetailPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario: OrderVerifyScenario = isOrderVerifyScenario(route.params.scenario) ? route.params.scenario : 'active'
  const orderId = route.params.orderId || ''
  const { revision } = useWorkspace(preview ? 'preview' : 'real')
  return <DetailScreen key={revision} preview={preview} scenario={scenario} orderId={orderId} />
}

function DetailScreen({ preview, scenario, orderId }: { preview: boolean; scenario: OrderVerifyScenario; orderId: string }) {
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState(() => preview ? new PreviewOrderReadRepository('normal') : new RealOrderReadRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>(isOrderId(orderId) ? 'loading' : 'invalid')
  const [detail, setDetail] = useState<OrderDetailView | null>(null)
  const [notice, setNotice] = useState('')
  const mounted = useRef(true)
  const sequence = useRef(0)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--ord-status-top': `${platformInfo.statusBarHeight || 0}px`, '--ord-unit': `${unit}px` } as CSSProperties

  async function load(target = orderId) {
    if (!isOrderId(target)) { setPhase('invalid'); return }
    const currentRevision = scope.revision
    const current = ++sequence.current
    setNotice('')
    if (preview) {
      setPhase('loading')
      try {
        const result = await scope.run(undefined, () => repository.detail(target))
        if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
        setDetail(result); setPhase('ready')
      } catch (error) {
        if (mounted.current && current === sequence.current && currentRevision === scope.revision) { setNotice(orderReadMessage(error)); setPhase('load-error') }
      }
      return
    }
    if (!scope.current) { try { await consumerApi.restore() } catch { /* 未登录，走下方登录引导 */ } }
    if (current !== sequence.current || currentRevision !== scope.revision) return
    if (!scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    setPhase('loading')
    try {
      const result = await scope.run(undefined, () => repository.detail(target))
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setDetail(result); setPhase('ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setNotice(orderReadMessage(error))
      setPhase(isOrderReadUnauthorized(error) ? 'expired' : 'load-error')
    }
  }
  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false } }, [])
  useDidShow(() => { if (phase === 'ready') void load() })
  function goLogin() { void Taro.redirectTo({ url: '/consumer/pages/shell/index' }) }
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: `/consumer/pages/orders/list${preview ? '?preview=1' : ''}` })
  }
  function goOrders() { void Taro.redirectTo({ url: `/consumer/pages/orders/list${preview ? '?preview=1' : ''}` }) }
  const ready = phase === 'ready' && detail !== null
  const badge = detail === null ? null : orderStatusBadge(detail)
  return <ConsumerPageLayout page='orderDetail' unit={unit} className='ord-page' style={style}
    navigation={{ idPrefix: 'ord', disabled: phase === 'loading', onSelect: key => { void Taro.showToast({ title: navigationUnavailableMessage(key), icon: 'none' }) } }}>
    <View className='ord-design'>
      <View className='ord-status-area' />
      <View className='ord-nav'>
        <Button id='ord-back' ariaLabel='返回' className='ord-nav-back' onClick={() => void goBack()}>
          <Text className='ord-nav-back-icon'>‹</Text>
        </Button>
        <Text className='ord-nav-title'>订单详情</Text>
      </View>
      {phase === 'loading' && <View className='ord-state' role='status'><Text id='ord-loading'>正在读取订单…</Text></View>}
      {phase === 'invalid' && <View className='ord-state' role='status'>
        <Text id='ord-invalid'>订单链接无效，请从我的订单重新进入。</Text>
        <Button id='ord-go-orders' className='ord-state-action' onClick={goOrders}>返回我的订单</Button>
      </View>}
      {phase === 'expired' && <View className='ord-state' role='status'>
        <Text id='ord-login-hint'>登录后可查看订单详情。</Text>
        <Button id='ord-login' className='ord-state-action' onClick={goLogin}>去登录</Button>
      </View>}
      {phase === 'load-error' && <View className='ord-state' role='status'>
        <Text id='ord-error'>{notice || '订单读取失败，请稍后重试。'}</Text>
        <Button id='ord-retry' className='ord-state-action' onClick={() => void load()}>重新加载</Button>
      </View>}
      {ready && <View className='ord-detail-body'>
        <View className='ord-summary'>
          <View className='ord-card-head'>
            <Text className='ord-card-no'>订单号 {detail.orderNo}</Text>
            {badge && <Text className={`ord-badge ${badge.className}`}>{badge.label}</Text>}
          </View>
          <Text className='ord-card-time'>服务时间 {appointmentWindow(detail)}</Text>
          <Text className='ord-card-amount ord-summary-amount'>¥{detail.payAmount}</Text>
        </View>
        {/* 未支付单支付入口：仅凭服务端 actions.canPay（§3.7），进入 booking/pay 发起页。 */}
        {canInitiatePayment(detail) && <Button id='ord-go-pay' className='ord-pay-action'
          onClick={() => { void Taro.navigateTo({ url: `/consumer/pages/booking/pay?${preview ? 'preview=1&' : ''}orderId=${encodeURIComponent(detail.orderId)}` }).catch(() => setNotice('页面跳转失败，请重试')) }}>
          去支付 ¥{detail.payAmount}
        </Button>}
        {/* §3.8 改期入口：仅凭服务端 actions.canReschedule（46号准入投影，ARCH-005 不在页面推导），
            进入改期页（新时段选择 + expectedOrderVersion CAS + 46号错误面）。 */}
        {canRescheduleEntry(detail) && <Button id='ord-go-reschedule' className='ord-pay-action'
          onClick={() => { void Taro.navigateTo({ url: `/consumer/pages/orders/reschedule?orderId=${encodeURIComponent(detail.orderId)}${preview ? '&preview=1' : ''}` }).catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) }) }}>
          订单改期
        </Button>}
        <View className='ord-facts'>
          {orderFactRows(detail).map(row => <View key={row.id} className='ord-fact-row'>
            <Text className='ord-fact-label'>{row.label}</Text>
            <Text className='ord-fact-value'>{row.value}</Text>
          </View>)}
        </View>
        {canShowVerifyBlock(detail)
          ? <VerifyCodeBlock key={detail.orderId} preview={preview} scenario={scenario} orderId={detail.orderId} />
          : <View className='ord-verify-note'><Text id='ord-verify-absent'>{verifyAbsenceNotice(detail)}</Text></View>}
        {/* C-005 退款入口：仅凭服务端 actions.canApplyRefund（§3.7 事实字段，ARCH-005 不在页面推导），
            两窗口口径文案与页面归 src/consumer/orders/refund.ts。 */}
        {canApplyRefundEntry(detail) && <View className='ord-refund-entry'>
          <View className='ord-refund-entry-head'>
            <Text className='ord-refund-entry-title'>退款</Text>
          </View>
          <Text className='ord-verify-hint'>服务开始前申请将自动全额原路退回；服务开始后由商家在 24 小时内处理。</Text>
          <Button id='ord-apply-refund' className='ord-refund-entry-link' onClick={() => { void Taro.navigateTo({ url: `/consumer/pages/orders/refund-apply?orderId=${encodeURIComponent(detail.orderId)}${preview ? '&preview=1' : ''}` }).catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) }) }}>申请退款</Button>
        </View>}
        <View className='ord-preview-note'><Text>{preview ? '只读预览：本地样例数据，仅用于设计验收，不发起真实请求。' : '页面数据：真实接口（订单只读 + 核销码 no-store）。'}</Text></View>
      </View>}
    </View>
  </ConsumerPageLayout>
}

/** 核销码区块：复用 #116 的控制器/仓库与五状态交互（47号 §4），仅随 canShowVerificationCode 挂载。 */
function VerifyCodeBlock({ preview, scenario, orderId }: { preview: boolean; scenario: OrderVerifyScenario; orderId: string }) {
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState(() => preview ? new PreviewOrderVerifyRepository(scenario) : new RealOrderVerifyRepository(consumerApi))
  const [controller] = useState(() => new OrderVerifyController(repository, scope))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [now, setNow] = useState(() => Date.now())
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    const active = state.view?.status === 'ACTIVE'
    const timer = active ? setInterval(() => { if (mounted.current) setNow(Date.now()) }, 1000) : null
    return () => { mounted.current = false; if (timer) clearInterval(timer) }
  }, [state.view?.status, state.view?.code])
  useEffect(() => () => { controller.dispose() }, [controller])
  useEffect(() => { void controller.load(orderId); controller.restore(orderId) }, [orderId])
  useDidShow(() => { void controller.load(orderId) })
  const view = state.view
  const action = view ? actionFor(view.status) : null
  const remaining = view?.status === 'ACTIVE' ? remainingLabel(now, view.expiresAt) : ''
  return <View className='ord-verify'>
    <View className='ord-verify-head'>
      <Text className='ord-verify-title'>核销码</Text>
      {view && <Text className={`ord-badge ${credentialVariant(view.status)}`}>{statusLabels[view.status]}</Text>}
    </View>
    {state.phase === 'loading' && <Text className='ord-verify-hint' id='ord-verify-loading'>正在读取核销码…</Text>}
    {state.phase === 'unauthorized' && <View className='ord-verify-state' role='status'>
      <Text id='ord-verify-login-hint'>登录已失效，核销码不可用。</Text>
      <Button id='ord-verify-login' className='ord-state-action' onClick={() => void Taro.redirectTo({ url: '/consumer/pages/shell/index' })}>去登录</Button>
    </View>}
    {state.phase === 'load-error' && <View className='ord-verify-state' role='status'>
      <Text id='ord-verify-error'>{state.notice || '核销码读取失败，请稍后重试。'}</Text>
      <Button id='ord-verify-retry-read' className='ord-state-action' disabled={state.busy} onClick={() => void controller.load(orderId)}>重新读取</Button>
    </View>}
    {state.phase === 'ready' && view && <View className='ord-verify-body'>
      <Text className='ord-verify-hint'>{statusHints[view.status]}</Text>
      {view.status === 'ACTIVE' && view.code !== null && <View className='ord-code-panel'>
        <Text id='ord-code' className='ord-code'>{formatCode(view.code)}</Text>
        <Text id='ord-code-remaining' className='ord-verify-hint'>有效期至 {formatInstant(view.expiresAt)}{remaining ? `，剩余 ${remaining}` : ''}</Text>
        <Text className='ord-verify-hint'>到店出示该码由商家核销；刷新成功后旧码立即作废。</Text>
      </View>}
      {view.status === 'LOCKED' && <Text id='ord-locked-until' className='ord-verify-hint'>锁定解除时间：{formatInstant(view.lockedUntil)}</Text>}
      {view.status === 'EXPIRED' && <Text className='ord-verify-hint'>原截止时间：{formatInstant(view.expiresAt)}</Text>}
      {state.pending
        ? <Button id='ord-retry-issue' className='ord-verify-action' disabled={state.busy} onClick={() => void controller.retry()}>重试原{state.pending.refreshKind === 'INITIAL' ? '取码' : '刷新'}</Button>
        : action && <Button id='ord-issue' className='ord-verify-action' disabled={state.busy} onClick={() => void controller.issue()}>{action.label}</Button>}
      {state.busy && <Text className='ord-verify-hint'>正在提交取码/刷新…</Text>}
    </View>}
    <Button id='ord-verify-direct' className='ord-verify-link' onClick={() => { void Taro.navigateTo({ url: `/consumer/pages/order-verify/index?${preview ? 'preview=1&scenario=' + scenario + '&' : ''}orderId=${encodeURIComponent(orderId)}` }).catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) }) }}>核销码直连通道（输入订单号）</Button>
    {state.notice && state.phase !== 'load-error' && <Text id='ord-verify-notice' className='ord-verify-hint'>{state.notice}</Text>}
  </View>
}

/** 核销码五状态徽标（复用 #116 文案，样式并入本页 ord-badge 变体）。 */
function credentialVariant(status: string): string {
  return `is-cred-${status.toLowerCase()}`
}
