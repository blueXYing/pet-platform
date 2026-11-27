import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { ApiError } from '../../../shared/request'
import { appointmentWindow, orderReadMessage, PreviewOrderReadRepository, type OrderDetailView } from '../../orders/model'
import { RealOrderReadRepository } from '../../orders/repository'
import {
  bookingPaymentMessage, canInitiatePayment, isBookingScenario, paymentGateNotice, paymentSandboxNotice,
  PREVIEW_PAY_ORDER, PreviewBookingRepository, type PaymentReceipt,
} from '../../booking/model'
import { paymentSlot, RealBookingRepository } from '../../booking/repository'
import { PAY_METHOD_WECHAT, paySummaryLabels } from './labels'
import wechatPayIcon from './assets/wechat-pay.svg'
import './pay-sheet.css'

// 确认支付半屏弹层（Figma 690:4837「确认支付」；2026-11-27 用户裁决 43 号）。支付发起逻辑自原
// booking/pay 整页平移（10号 §3.6 / 40 号裁决 §1 / CCR-PAY-CENTER-001 阶段 7），状态自包含：
// - V1 小程序不展示支付方式选择（channel 客户端固定 WECHAT_MINI_PROGRAM）：弹层只渲染固定的
//   微信支付一行并呈选中态，不渲染支付宝行（登记差异见 pay-sheet.css）；
// - 真实模式调用 POST /api/v1/c/orders/{orderId}/payments 获取渠道支付参数后，调用微信原生
//   Taro.requestPayment(...) 调起收银台；无论调起成功与否绝不本地虚构“已支付”态，支付终态一律以
//   服务端订单详情异步回调/查单结果为准；
// - preview=1 只读预览模式不调用 Taro.requestPayment，仅呈现样例回执；
// - ARCH-005 分工：弹层不触碰订单事实字段，展示状态经 orders/model 现成展示值。
// 宿主：预约弹层回执态「去支付」、订单详情「去支付」在当前页面弹出；booking/pay 兜底页同用本组件。
type Phase = 'loading' | 'ready' | 'invalid' | 'missing' | 'expired' | 'load-error' | 'receipt'

const REAL_PAYMENT_SUBMITTED_NOTICE = '微信支付已提交，正在确认订单支付结果，请前往订单详情查看最终状态。'

export type PaySheetOutcome = Readonly<{
  /** 本次弹层里是否已向微信提交过支付（进入回执态）：宿主据此决定关闭后是否刷新订单。 */
  submitted: boolean
}>

export type PaySheetProps = Readonly<{
  /** 订单 ID（契约十进制串）；无效时呈现「订单链接无效」。 */
  orderId: string
  /** preview=1 本地夹具通道（设计验收，不发起真实请求、不调起支付）。 */
  preview: boolean
  /** preview 场景（normal/pickup 等；仅 preview 生效）。 */
  scenario?: string
  /** 关闭弹层（遮罩/×）。 */
  onClose: (outcome: PaySheetOutcome) => void
  /** 打开订单详情：宿主决定导航方式（弹层宿主保留当前页，兜底页替换自身）。 */
  onOpenOrderDetail: () => void
  /** 返回我的订单：宿主决定导航方式。 */
  onOpenOrderList: () => void
  /** aria/id 前缀：宿主内唯一即可，booking/pay 兜底页传 'bkp' 保留原 id 族。 */
  idPrefix?: string
}>

export function PaySheet({
  orderId, preview, scenario = 'normal', onClose, onOpenOrderDetail, onOpenOrderList, idPrefix = 'bps',
}: PaySheetProps) {
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const previewScenario = preview && isBookingScenario(scenario) ? scenario : 'normal'
  const [readRepository] = useState(() => preview ? new PreviewOrderReadRepository('normal') : new RealOrderReadRepository(consumerApi))
  const [bookingRepository] = useState(() => preview
    ? new PreviewBookingRepository(isBookingScenario(previewScenario) ? previewScenario : 'normal')
    : new RealBookingRepository(consumerApi))
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
  const style = { '--bps-unit': `${unit}px` } as CSSProperties

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
  // 宿主页面重新显示（如从其他页面返回）时重读订单，支付窗口与状态以服务端为准。
  useDidShow(() => { if (phase === 'ready') void load() })

  async function initiate() {
    if (busy || phase !== 'ready' || detail === null) return
    setBusy(true); setNotice('')
    try {
      const paid = await scope.run(undefined, () => bookingRepository.pay(orderId))
      if (!mounted.current) return
      if (preview) {
        receiptRef.current = true
        setReceipt(paid); setPhase('receipt')
        return
      }
      try {
        await Taro.requestPayment({
          timeStamp: paid.wechatPayParameters.timeStamp,
          nonceStr: paid.wechatPayParameters.nonceStr,
          package: paid.wechatPayParameters.package,
          signType: paid.wechatPayParameters.signType as 'RSA' | 'MD5',
          paySign: paid.wechatPayParameters.paySign,
        })
        if (!mounted.current) return
        receiptRef.current = true
        setReceipt(paid); setPhase('receipt')
      } catch (payError) {
        if (!mounted.current) return
        const errMsg = extractRequestPaymentErrMsg(payError)
        if (/cancel/i.test(errMsg)) {
          setNotice('已取消微信支付，可点击按钮重新发起。')
        } else {
          setNotice(errMsg
            ? `微信支付未完成（${errMsg}），请前往订单详情查看或重试。`
            : '微信支付未完成，请前往订单详情查看或重试。')
        }
      }
    } catch (error) {
      if (mounted.current) setNotice(bookingPaymentMessage(error))
    } finally {
      if (mounted.current) setBusy(false)
    }
  }
  function close() {
    if (busy) return
    onClose({ submitted: receiptRef.current })
  }
  function goLogin() { void Taro.switchTab({ url: '/consumer/pages/mine/index' }) }

  const ready = phase === 'ready' && detail !== null
  const payable = detail !== null && canInitiatePayment(detail)
  // 幂等恢复提示：真实模式存在未确认的发起命令时，按钮文案改为重试原发起（同一 X-Request-Id）。
  const pendingLaunch = !preview && consumerApi.pendingCommand(paymentSlot(orderId)) !== undefined
  const labels = ready ? paySummaryLabels(detail) : null
  return <View className='bps-mask' style={style} onClick={close} catchMove>
    <View className='bps-sheet' onClick={event => event.stopPropagation()} catchMove>
      <View className='bps-head'>
        <Text className='bps-title'>确认支付</Text>
        <Button id={`${idPrefix}-close`} ariaLabel='关闭' className='bps-close' hoverClass='none' onClick={close}>
          <Text>×</Text>
        </Button>
      </View>
      {phase === 'loading' && <View className='bps-state' role='status'><Text id={`${idPrefix}-loading`}>正在读取订单…</Text></View>}
      {phase === 'invalid' && <View className='bps-state' role='status'>
        <Text id={`${idPrefix}-invalid`}>订单链接无效，请从我的订单重新进入。</Text>
        <View className='bps-state-wrap'><Button id={`${idPrefix}-go-orders`} className='bps-state-action' hoverClass='none' onClick={onOpenOrderList}>返回我的订单</Button></View>
      </View>}
      {phase === 'missing' && <View className='bps-state' role='status'>
        <Text id={`${idPrefix}-missing`}>订单不存在或支付服务未开放。</Text>
        <View className='bps-state-wrap'><Button id={`${idPrefix}-go-orders-missing`} className='bps-state-action' hoverClass='none' onClick={onOpenOrderList}>返回我的订单</Button></View>
      </View>}
      {phase === 'expired' && <View className='bps-state' role='status'>
        <Text id={`${idPrefix}-login-hint`}>登录后可发起支付。</Text>
        <View className='bps-state-wrap'><Button id={`${idPrefix}-login`} className='bps-state-action' hoverClass='none' onClick={goLogin}>去登录</Button></View>
      </View>}
      {phase === 'load-error' && <View className='bps-state' role='status'>
        <Text id={`${idPrefix}-error`}>{notice || '订单读取失败，请稍后重试。'}</Text>
        <View className='bps-state-wrap'><Button id={`${idPrefix}-retry-load`} className='bps-state-action' hoverClass='none' onClick={() => void load()}>重新加载</Button></View>
      </View>}
      {ready && detail && labels && <>
        <View className='bps-summary' ariaLabel={`${labels.store} ${labels.service}`}>
          <View className='bps-summary-head'>
            <Text className='bps-store'>{labels.store}</Text>
            <Text className='bps-service'>{labels.service}</Text>
          </View>
          <Text className='bps-time'>服务时间 {appointmentWindow(detail)}</Text>
          <View className='bps-amount-row'>
            <Text className='bps-amount-label'>实付金额</Text>
            <Text className='bps-amount'>¥{detail.payAmount}</Text>
          </View>
        </View>
        <Text className='bps-label'>支付方式</Text>
        <View className='bps-method' ariaLabel={`支付方式 ${PAY_METHOD_WECHAT}（已选中）`}>
          <View className='bps-method-icon'><Image className='bps-method-img' src={wechatPayIcon} mode='aspectFit' /></View>
          <Text className='bps-method-name'>{PAY_METHOD_WECHAT}</Text>
          <View className='bps-radio' ariaLabel='已选中'><View className='bps-radio-dot' /></View>
        </View>
        {notice && <Text id={`${idPrefix}-notice`} className='bps-notice'>{notice}</Text>}
        {pendingLaunch && payable && <Text className='bps-hint' id={`${idPrefix}-pending-hint`}>检测到上次支付发起结果尚未确认，重试将沿用原请求编号（不会重复发起）。</Text>}
        {payable
          ? <View className='bps-action-wrap'>
              <Button id={`${idPrefix}-pay`} className='bps-action' disabled={busy} hoverClass='none' onClick={() => void initiate()}>
                {busy ? '正在发起…' : pendingLaunch ? '重试原发起支付' : `确认支付 ¥${detail.payAmount}`}
              </Button>
            </View>
          : <View className='bps-gate' role='status'><Text id={`${idPrefix}-gate`}>{paymentGateNotice(payable)}</Text></View>}
        <View className='bps-secondary-wrap'><Button id={`${idPrefix}-go-order`} className='bps-secondary' hoverClass='none' onClick={onOpenOrderDetail}>查看订单详情</Button></View>
        {preview && <Text className='bps-note'>只读预览：本地样例数据，仅用于设计验收，不发起真实请求。</Text>}
      </>}
      {phase === 'receipt' && receipt && <>
        <View className='bps-summary'>
          <View className='bps-receipt-head'>
            <Text className='bps-receipt-no'>支付单号 {receipt.paymentNo}</Text>
            <Text className='bps-badge'>{preview ? '已发起' : '确认中'}</Text>
          </View>
          <Text className='bps-hint'>渠道支付参数已按契约返回（签名方式 {receipt.wechatPayParameters.signType}）。</Text>
        </View>
        <View className='bps-gate' role='status'><Text id={`${idPrefix}-sandbox`}>{preview ? paymentSandboxNotice : REAL_PAYMENT_SUBMITTED_NOTICE}</Text></View>
        <View className='bps-action-wrap'><Button id={`${idPrefix}-receipt-order`} className='bps-action' hoverClass='none' onClick={onOpenOrderDetail}>查看订单支付状态</Button></View>
        <View className='bps-secondary-wrap'><Button id={`${idPrefix}-receipt-list`} className='bps-secondary' hoverClass='none' onClick={onOpenOrderList}>返回我的订单</Button></View>
      </>}
    </View>
  </View>
}

function extractRequestPaymentErrMsg(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'errMsg' in error) {
    const raw = (error as { errMsg?: unknown }).errMsg
    if (typeof raw === 'string') return raw.trim()
  }
  return error instanceof Error ? error.message.trim() : ''
}

function isContractId(value: string): boolean {
  return /^[1-9][0-9]{0,18}$/.test(value) && (value.length < 19 || BigInt(value) <= 9223372036854775807n)
}
