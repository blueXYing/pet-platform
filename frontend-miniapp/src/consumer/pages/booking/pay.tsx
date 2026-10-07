import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { ApiError } from '../../../shared/request'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { ConsumerPageLayout } from '../../components/page-layout'
import { appointmentWindow, orderReadMessage, orderStatusBadge, PreviewOrderReadRepository, type OrderDetailView } from '../../orders/model'
import { RealOrderReadRepository } from '../../orders/repository'
import {
  bookingPaymentMessage, canInitiatePayment, isBookingScenario, paymentGateNotice, paymentSandboxNotice,
  PREVIEW_PAY_ORDER, PreviewBookingRepository, type PaymentReceipt,
} from '../../booking/model'
import { paymentSlot, RealBookingRepository } from '../../booking/repository'
import './booking.css'

// 支付发起页（10号 §3.6）。设计源登记表 §4.3：原稿 690:4837「确认支付」含支付方式选择
// （微信支付/支付宝）与 690:5424「完成支付-支付成功」，与契约冲突——§3.6 裁决 V1 小程序不
// 展示支付方式选择（channel 客户端固定 WECHAT_MINI_PROGRAM），且支付渠道为无正式渠道参数的
// 沙箱态（40号）：本页如实呈现 POST payments 契约回执（paymentId/paymentNo/channel/
// wechatPayParameters），不虚构支付成功、不调起收银台，发起后引导回订单详情/列表查看状态。
// 入口：下单成功回执（booking/create）或订单详情（未支付单 actions.canPay，§3.7 #123）。
// 金额/订单信息来自 §3.7 订单详情只读投影；发起走幂等槽 order-pay:{orderId}（23号重放）。
// ARCH-005 分工：页面不触碰订单事实字段，展示状态经 orders/model.orderStatusBadge 现成展示值。
type Phase = 'loading' | 'ready' | 'invalid' | 'missing' | 'expired' | 'load-error' | 'receipt'

export default function BookingPayPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isBookingScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const orderId = route.params.orderId || (preview ? PREVIEW_PAY_ORDER : '')
  const { revision } = useWorkspace(preview ? 'preview' : 'real')
  return <PayScreen key={revision} preview={preview} scenario={scenario} orderId={orderId} />
}

function PayScreen({ preview, scenario, orderId }: { preview: boolean; scenario: string; orderId: string }) {
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [readRepository] = useState(() => preview ? new PreviewOrderReadRepository('normal') : new RealOrderReadRepository(consumerApi))
  const [bookingRepository] = useState(() => preview ? new PreviewBookingRepository(isBookingScenario(scenario) ? scenario : 'normal') : new RealBookingRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>(isContractId(orderId) ? 'loading' : 'invalid')
  const [detail, setDetail] = useState<OrderDetailView | null>(null)
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [receipt, setReceipt] = useState<PaymentReceipt | null>(null)
  const receiptRef = useRef(false)
  const mounted = useRef(true)
  const sequence = useRef(0)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--bkg-status-top': `${platformInfo.statusBarHeight || 0}px`, '--bkg-unit': `${unit}px` } as CSSProperties

  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setNotice('')
    if (!isContractId(orderId)) { setPhase('invalid'); return }
    if (receiptRef.current) return
    if (preview) {
      setPhase('loading')
      try {
        const result = await scope.run(undefined, () => readRepository.detail(orderId))
        if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
        setDetail(result); setPhase('ready')
      } catch (error) {
        if (mounted.current && current === sequence.current && currentRevision === scope.revision) {
          setNotice(orderReadMessage(error)); setPhase(error instanceof ApiError && error.statusCode === 404 ? 'missing' : 'load-error')
        }
      }
      return
    }
    if (!scope.current) { try { await consumerApi.restore() } catch { /* 未登录，走下方登录引导 */ } }
    if (current !== sequence.current || currentRevision !== scope.revision) return
    if (!scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    setPhase('loading')
    try {
      const result = await scope.run(undefined, () => readRepository.detail(orderId))
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setDetail(result); setPhase('ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setNotice(orderReadMessage(error))
      if (error instanceof ApiError && error.statusCode === 401) setPhase('expired')
      else if (error instanceof ApiError && error.statusCode === 404) setPhase('missing')
      else setPhase('load-error')
    }
  }, [preview, scope, orderId, readRepository])

  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false; sequence.current++ } }, [load])
  useDidShow(() => { if (phase === 'ready') void load() })

  async function initiate() {
    if (busy || phase !== 'ready' || detail === null) return
    setBusy(true); setNotice('')
    try {
      const paid = await scope.run(undefined, () => bookingRepository.pay(orderId))
      if (!mounted.current) return
      receiptRef.current = true
      setReceipt(paid); setPhase('receipt')
    } catch (error) {
      if (mounted.current) setNotice(bookingPaymentMessage(error))
    } finally {
      if (mounted.current) setBusy(false)
    }
  }
  function goOrderDetail() {
    void Taro.redirectTo({ url: `/consumer/pages/orders/detail?${preview ? 'preview=1&' : ''}orderId=${encodeURIComponent(orderId)}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }
  function goOrderList() {
    void Taro.redirectTo({ url: `/consumer/pages/orders/list${preview ? '?preview=1' : ''}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }
  function goLogin() { void Taro.redirectTo({ url: '/consumer/pages/shell/index' }) }
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: `/consumer/pages/orders/detail?${preview ? 'preview=1&' : ''}orderId=${encodeURIComponent(orderId)}` })
  }

  const ready = phase === 'ready' && detail !== null
  const badge = detail === null ? null : orderStatusBadge(detail)
  const payable = detail !== null && canInitiatePayment(detail)
  // 幂等恢复提示：真实模式存在未确认的发起命令时，按钮文案改为重试原发起（同一 X-Request-Id）。
  const pendingLaunch = !preview && consumerApi.pendingCommand(paymentSlot(orderId)) !== undefined
  return <ConsumerPageLayout page='bookingPay' unit={unit} className='bkg-page' style={style}
    navigation={{ idPrefix: 'bkp', disabled: phase === 'loading', onSelect: key => { void Taro.showToast({ title: navigationUnavailableMessage(key), icon: 'none' }) } }}>
    <View className='bkg-design'>
      <View className='bkg-status-area' />
      <View className='bkg-nav'>
        <Button id='bkp-back' ariaLabel='返回' className='bkg-nav-back' onClick={() => void goBack()}><Text className='bkg-nav-back-icon'>‹</Text></Button>
        <Text className='bkg-nav-title'>确认支付</Text>
      </View>
      {phase === 'loading' && <View className='bkg-state' role='status'><Text id='bkp-loading'>正在读取订单…</Text></View>}
      {phase === 'invalid' && <View className='bkg-state' role='status'>
        <Text id='bkp-invalid'>订单链接无效，请从我的订单重新进入。</Text>
        <Button id='bkp-go-orders' className='bkg-state-action' onClick={goOrderList}>返回我的订单</Button>
      </View>}
      {phase === 'missing' && <View className='bkg-state' role='status'>
        <Text id='bkp-missing'>订单不存在或支付服务未开放。</Text>
        <Button id='bkp-go-orders-missing' className='bkg-state-action' onClick={goOrderList}>返回我的订单</Button>
      </View>}
      {phase === 'expired' && <View className='bkg-state' role='status'>
        <Text id='bkp-login-hint'>登录后可发起支付。</Text>
        <Button id='bkp-login' className='bkg-state-action' onClick={goLogin}>去登录</Button>
      </View>}
      {phase === 'load-error' && <View className='bkg-state' role='status'>
        <Text id='bkp-error'>{notice || '订单读取失败，请稍后重试。'}</Text>
        <Button id='bkp-retry-load' className='bkg-state-action' onClick={() => void load()}>重新加载</Button>
      </View>}
      {ready && detail && badge && <View className='bkg-body'>
        <View className='bkg-service-card'>
          <View className='bkg-card-head'>
            <Text className='bkg-card-no'>订单号 {detail.orderNo}</Text>
            <Text className={`bkg-badge ${badge.className}`}>{badge.label}</Text>
          </View>
          <Text className='bkg-hint'>服务时间 {appointmentWindow(detail)}</Text>
          <Text className='bkg-summary-amount'>¥{detail.payAmount}</Text>
        </View>
        <View className='bkg-section bkg-confirm-card'>
          <Text className='bkg-section-title'>支付方式</Text>
          <View className='bkg-fact-row'>
            <Text className='bkg-fact-label'>渠道</Text>
            <Text className='bkg-fact-value'>微信支付（小程序）</Text>
          </View>
          <Text className='bkg-hint'>V1 小程序暂不提供支付方式选择，统一通过微信支付发起。</Text>
        </View>
        {payable
          ? <Button id='bkp-pay' className='bkg-footer-action bkg-pay-action' disabled={busy} onClick={() => void initiate()}>
              {busy ? '正在发起…' : pendingLaunch ? '重试原发起支付' : `确认支付 ¥${detail.payAmount}`}
            </Button>
          : <View className='bkg-slot-error' role='status'><Text id='bkp-gate'>{paymentGateNotice(payable)}</Text></View>}
        {pendingLaunch && payable && <Text className='bkg-hint' id='bkp-pending-hint'>检测到上次支付发起结果尚未确认，重试将沿用原请求编号（不会重复发起）。</Text>}
        {notice && <Text id='bkp-notice' className='bkg-notice'>{notice}</Text>}
        <Button id='bkp-go-order' className='bkg-secondary' onClick={goOrderDetail}>查看订单详情</Button>
        <View className='bkg-preview-note'><Text>{preview ? '只读预览：本地样例数据，仅用于设计验收，不发起真实请求。' : '页面数据：真实接口（订单只读 + 发起支付）。'}</Text></View>
      </View>}
      {phase === 'receipt' && receipt && <View className='bkg-body'>
        <View className='bkg-service-card'>
          <View className='bkg-card-head'>
            <Text className='bkg-card-no'>支付单号 {receipt.paymentNo}</Text>
            <Text className='bkg-badge is-pending-payment'>已发起</Text>
          </View>
          <View className='bkg-fact-row'><Text className='bkg-fact-label'>支付单ID</Text><Text className='bkg-fact-value'>{receipt.paymentId}</Text></View>
          <View className='bkg-fact-row'><Text className='bkg-fact-label'>支付渠道</Text><Text className='bkg-fact-value'>{receipt.channel}</Text></View>
          <Text className='bkg-hint'>渠道支付参数已按契约返回（签名方式 {receipt.wechatPayParameters.signType}）。</Text>
        </View>
        <View className='bkg-slot-error' role='status'><Text id='bkp-sandbox'>{paymentSandboxNotice}</Text></View>
        <Button id='bkp-receipt-order' className='bkg-primary' onClick={goOrderDetail}>查看订单支付状态</Button>
        <Button id='bkp-receipt-list' className='bkg-secondary' onClick={goOrderList}>返回我的订单</Button>
        <View className='bkg-preview-note'><Text>{preview ? '只读预览：本地样例回执，不发起真实请求。' : '页面数据：真实接口（发起支付回执）。'}</Text></View>
      </View>}
    </View>
  </ConsumerPageLayout>
}

function isContractId(value: string): boolean {
  return /^[1-9][0-9]{0,18}$/.test(value) && (value.length < 19 || BigInt(value) <= 9223372036854775807n)
}
