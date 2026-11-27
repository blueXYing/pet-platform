import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { ApiError } from '../../../shared/request'
import { switchConsumerTab } from '../../components/navigation/switch'
import { ConsumerPageLayout } from '../../components/page-layout'
import { SubpageHeader, SubpageHeaderSpacer } from '../../components/navigation/subpage-header'
import { appointmentWindow, orderReadMessage, orderStatusBadge, PreviewOrderReadRepository, type OrderDetailView } from '../../orders/model'
import { RealOrderReadRepository } from '../../orders/repository'
import { canInitiatePayment, isBookingScenario, paymentGateNotice, PREVIEW_PAY_ORDER } from '../../booking/model'
import { usePayLaunch } from '../../booking/use-pay-launch'
import './booking.css'

// 支付发起兜底页（10号 §3.6 / 40 号裁决 §1 / CCR-PAY-CENTER-001 阶段 7；43 号裁决 2026-11-27）。
// 「去支付」已在预约回执与订单详情里直接拉起微信支付（booking/use-pay-launch），本页只保留为
// 直连/深链接兜底：读取订单 → 单击「去支付」→ 建支付单并调起微信原生支付 → 支付窗口结束后转订单详情。
// - V1 小程序不展示支付方式选择（channel 客户端固定 WECHAT_MINI_PROGRAM）；
// - 无论调起成功与否绝不本地虚构“已支付”态，支付终态一律以服务端订单详情为准；
// - preview=1 只读预览不调起微信支付；
// - ARCH-005 分工：页面不触碰订单事实字段，展示状态经 orders/model.orderStatusBadge 现成展示值。
type Phase = 'loading' | 'ready' | 'invalid' | 'missing' | 'expired' | 'load-error'

export default function BookingPayPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isBookingScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const orderId = route.params.orderId || (preview ? PREVIEW_PAY_ORDER : '')
  const { revision } = useWorkspace(preview ? 'preview' : 'real')
  return <PayLaunchScreen key={revision} preview={preview} scenario={scenario} orderId={orderId} />
}

function PayLaunchScreen({ preview, scenario, orderId }: { preview: boolean; scenario: string; orderId: string }) {
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [readRepository] = useState(() => preview ? new PreviewOrderReadRepository('normal') : new RealOrderReadRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>(/^[1-9][0-9]{0,18}$/.test(orderId) ? 'loading' : 'invalid')
  const [detail, setDetail] = useState<OrderDetailView | null>(null)
  const [notice, setNotice] = useState('')
  const { launching, launch } = usePayLaunch(preview, scenario)
  const mounted = useRef(true)
  const sequence = useRef(0)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--bkg-status-top': `${platformInfo.statusBarHeight || 0}px`, '--bkg-unit': `${unit}px` } as CSSProperties

  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setNotice('')
    if (!/^[1-9][0-9]{0,18}$/.test(orderId)) { setPhase('invalid'); return }
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

  const detailUrl = `/consumer/pages/orders/detail?${preview ? 'preview=1&' : ''}orderId=${encodeURIComponent(orderId)}`
  const listUrl = `/consumer/pages/orders/list${preview ? '?preview=1' : ''}`
  // 43 号：支付窗口结束后直接去订单详情（本页是兜底页，替换自身）。
  function goOrderDetail() {
    void Taro.redirectTo({ url: detailUrl }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function goOrderList() {
    void Taro.redirectTo({ url: listUrl }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function goLogin() { void Taro.switchTab({ url: '/consumer/pages/mine/index' }) }
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: detailUrl }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function pay() {
    if (detail === null) return
    void launch(detail.orderId, { onSubmitted: goOrderDetail, onLoginRequired: goLogin })
  }

  const ready = phase === 'ready' && detail !== null
  const badge = detail === null ? null : orderStatusBadge(detail)
  const payable = detail !== null && canInitiatePayment(detail)
  return <ConsumerPageLayout page='bookingPay' unit={unit} className='bkg-page' style={style}
    navigation={preview ? { idPrefix: 'bkp', disabled: phase === 'loading', onSelect: key => { void switchConsumerTab(key) } } : undefined}>
    <View className='bkg-design'>
      <SubpageHeader title='去支付' backId='bkp-back' height={44} onBack={() => void goBack()} />
      <SubpageHeaderSpacer height={44} />
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
        {/* 去支付：单击即建支付单并调起微信原生支付（V1 小程序不展示支付方式选择，无确认步骤）。 */}
        {payable
          ? <Button id='bkp-pay' className='bkg-footer-action bkg-pay-action' disabled={launching} onClick={pay}>
              {launching ? '正在发起支付…' : `去支付 ¥${detail.payAmount}`}
            </Button>
          : <View className='bkg-slot-error' role='status'><Text id='bkp-gate'>{paymentGateNotice(payable)}</Text></View>}
        {notice && <Text id='bkp-notice' className='bkg-notice'>{notice}</Text>}
        <Button id='bkp-go-order' className='bkg-secondary' onClick={goOrderDetail}>查看订单详情</Button>
      </View>}
    </View>
  </ConsumerPageLayout>
}
