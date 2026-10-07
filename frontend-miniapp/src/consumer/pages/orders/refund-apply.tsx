import { Button, Input, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, useDidHide, useRouter } from '@tarojs/taro'
import { useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { ConsumerPageLayout } from '../../components/page-layout'
// ARCH-005 分工：本页不触碰订单原始事实字段，事实→展示的推导（回执口径文案/徽标变体/入口门控/
// 错误面映射）全部委托 src/consumer/orders/refund.ts（receiptHeadline/receiptBody/
// receiptStatusBadge/canApplyRefundEntry/refundApplyMessage）；页面只消费现成展示值。
import { appointmentWindow, orderStatusBadge, formatOrderInstant, type OrderDetailView } from '../../orders/model'
import {
  PreviewRefundApplyRepository, RefundApplyController, emptyRefundDraft, isOrderId,
  isRefundApplyScenario, receiptBody, receiptHeadline, receiptStatusBadge, validateRefundDraft,
  type RefundApplyScenario, type RefundDraft, type RefundDraftErrors,
} from '../../orders/refund'
import { RealRefundApplyRepository } from '../../orders/refund-repository'
import './orders.css'

// C-005 切片：退款申请页（订单详情 actions.canApplyRefund 门控进入，10号 §3.9 + 49号内核）。
// 申请理由按契约必填项：reasonCode（服务端配置字典，未封板不内置选项）+ 可选说明 reasonText；
// 成功回执两窗口如实展示（服务前自动全额 / 服务后商家 24 小时），退款单号由耐久任务异步生成，
// 回执阶段如实为空；失败错误面按 49号（窗口外/在途/REJECTED 后可再申请等）映射现成文案。
// preview=1 走本地夹具（pre/post/conflict 三场景），真实模式走 §3.9 接口（后端同批交付，
// 默认关闭，联调留 E2E 轮，如实披露）。幂等槽：未确认申请保留原 X-Request-Id 原载荷，仅显式重试重发。
export default function RefundApplyPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario: RefundApplyScenario = isRefundApplyScenario(route.params.scenario) ? route.params.scenario : 'pre'
  const orderId = route.params.orderId || ''
  const { revision } = useWorkspace(preview ? 'preview' : 'real')
  return <RefundApplyScreen key={`${revision}:${orderId}:${scenario}`} preview={preview} scenario={scenario} orderId={orderId} />
}

function RefundApplyScreen({ preview, scenario, orderId }: { preview: boolean; scenario: RefundApplyScenario; orderId: string }) {
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [controller] = useState(() => {
    if (preview) {
      const repository = new PreviewRefundApplyRepository(scenario)
      return new RefundApplyController({
        detail: target => repository.detail(target),
        apply: (target, input) => repository.apply(target, input),
        pendingApply: () => repository.pendingApply(),
        retireConflict: () => repository.retireConflict(),
      }, scope)
    }
    const repository = new RealRefundApplyRepository(consumerApi)
    return new RefundApplyController({
      detail: target => repository.detail(target),
      apply: (target, input) => repository.apply(target, input),
      pendingApply: target => repository.pendingApply(target),
      retireConflict: (target, error) => repository.retireConflict(target, error),
    }, scope)
  })
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [draft, setDraft] = useState<RefundDraft>(emptyRefundDraft)
  const [errors, setErrors] = useState<RefundDraftErrors>({})
  const [invalidOrderId] = useState(!isOrderId(orderId))
  const mounted = useRef(true)
  const initialized = useRef(false)
  const visible = useRef(true)
  const loadGeneration = useRef(0)
  const revision = consumerApi.scope.revision
  const live = () => mounted.current && visible.current && revision === consumerApi.scope.revision
  const slot = `refund-apply:draft:${orderId}`
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--ord-status-top': `${platformInfo.statusBarHeight || 0}px`, '--ord-unit': `${unit}px` } as CSSProperties
  const locked = state.busy || state.pending !== null || state.receipt !== null

  async function load() {
    const generation = ++loadGeneration.current
    if (!isOrderId(orderId)) return
    if (!preview) {
      if (!scope.current) { try { await consumerApi.restore() } catch { /* 未登录，走下方登录引导 */ } }
      if (!live() || generation !== loadGeneration.current) return
      if (!scope.current || scope.current.workspace !== 'consumer') return
    }
    if (!initialized.current) {
      initialized.current = true
      if (!preview) {
        try {
          const saved = consumerApi.intent(slot) as { reasonCode?: string; reasonText?: string } | undefined
          if (saved && typeof saved.reasonCode === 'string' && typeof saved.reasonText === 'string'
            && saved.reasonCode.length <= 64 && saved.reasonText.length <= 500) setDraft({ reasonCode: saved.reasonCode, reasonText: saved.reasonText })
        } catch { /* 未登录或读取失败时按空草稿处理 */ }
      }
      controller.restore(orderId)
    }
    await controller.load(orderId)
  }
  useDidShow(() => { visible.current = true; void load() })
  useDidHide(() => { visible.current = false; loadGeneration.current++ })
  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false; controller.dispose() } }, [])
  useEffect(() => {
    if (state.receipt === null || !live()) return
    if (!preview) { try { consumerApi.saveIntent(slot, null) } catch { /* 回执已持有，草稿清理失败不影响 */ } }
  }, [state.receipt])

  function edit(key: keyof RefundDraft, value: string) {
    if (locked) return
    const next = { ...draft, [key]: value }
    setDraft(next)
    setErrors(previous => ({ ...previous, [key]: undefined }))
    if (!preview) { try { consumerApi.saveIntent(slot, next) } catch { /* 未登录时不持久化草稿 */ } }
  }
  function submitForm() {
    if (locked || invalidOrderId) return
    const next = validateRefundDraft(draft)
    setErrors(next)
    if (Object.keys(next).length > 0) return
    void controller.submit(orderId, draft)
  }
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: `/consumer/pages/orders/detail?orderId=${encodeURIComponent(orderId)}${preview ? '&preview=1' : ''}` })
  }
  function goLogin() { void Taro.redirectTo({ url: '/consumer/pages/shell/index' }) }

  const detail: OrderDetailView | null = state.detail
  const badge = detail === null ? null : orderStatusBadge(detail)
  const receiptBadge = state.receipt === null ? null : receiptStatusBadge(state.receipt)
  return <ConsumerPageLayout page='orderDetail' unit={unit} className='ord-page' style={style}
    navigation={{ idPrefix: 'rfd', disabled: state.phase === 'loading', onSelect: key => { void Taro.showToast({ title: navigationUnavailableMessage(key), icon: 'none' }) } }}>
    <View className='ord-design'>
      <View className='ord-status-area' />
      <View className='ord-nav'>
        <Button id='rfd-back' ariaLabel='返回' className='ord-nav-back' onClick={() => void goBack()}>
          <Text className='ord-nav-back-icon'>‹</Text>
        </Button>
        <Text className='ord-nav-title'>申请退款</Text>
      </View>
      {invalidOrderId && <View className='ord-state' role='status'><Text id='rfd-invalid'>订单链接无效，请从订单详情重新进入。</Text></View>}
      {state.phase === 'loading' && <View className='ord-state' role='status'><Text id='rfd-loading'>正在读取订单…</Text></View>}
      {state.phase === 'unauthorized' && <View className='ord-state' role='status'>
        <Text id='rfd-login-hint'>登录后可申请退款。</Text>
        <Button id='rfd-login' className='ord-state-action' onClick={goLogin}>去登录</Button>
      </View>}
      {state.phase === 'load-error' && <View className='ord-state' role='status'>
        <Text id='rfd-error'>{state.notice || '订单读取失败，请稍后重试。'}</Text>
        <Button id='rfd-retry-read' className='ord-state-action' onClick={() => void load()}>重新加载</Button>
      </View>}
      {state.phase === 'ineligible' && state.receipt === null && <View className='ord-state' role='status'>
        <Text id='rfd-ineligible'>当前订单不支持发起退款申请（以订单实时状态为准；被拒绝的历史申请处理后再试）。</Text>
        <Button id='rfd-back-detail' className='ord-state-action' onClick={() => void goBack()}>返回订单详情</Button>
      </View>}
      {(state.phase === 'ready' || state.receipt !== null) && detail !== null && <View className='ord-detail-body'>
        <View className='ord-summary'>
          <View className='ord-card-head'>
            <Text className='ord-card-no'>订单号 {detail.orderNo}</Text>
            {badge && <Text className={`ord-badge ${badge.className}`}>{badge.label}</Text>}
          </View>
          <Text className='ord-card-time'>服务时间 {appointmentWindow(detail)}</Text>
          <Text className='ord-card-amount ord-summary-amount'>¥{detail.payAmount}</Text>
        </View>
        {state.receipt !== null && <View className='ord-receipt' id='rfd-receipt'>
          <View className='ord-verify-head'>
            <Text className='ord-verify-title'>{receiptHeadline(state.receipt)}</Text>
            {receiptBadge && <Text className={`ord-badge ${receiptBadge.className}`}>{receiptBadge.label}</Text>}
          </View>
          <Text className='ord-verify-hint'>{receiptBody(state.receipt, formatOrderInstant)}</Text>
          <View className='ord-fact-row'><Text className='ord-fact-label'>申请编号</Text><Text className='ord-fact-value'>{state.receipt.applicationId}</Text></View>
          <View className='ord-fact-row'><Text className='ord-fact-label'>处理路径</Text><Text className='ord-fact-value'>{receiptRouteLabel(state.receipt.applicationStatus, state.receipt.route)}</Text></View>
          <View className='ord-fact-row'><Text className='ord-fact-label'>退款单号</Text><Text className='ord-fact-value'>{state.receipt.refundOrderId ?? '生成中（提交后自动创建）'}</Text></View>
          <Button id='rfd-back-after' className='ord-verify-action' onClick={() => void goBack()}>返回订单详情</Button>
        </View>}
        {state.receipt === null && <View className='ord-refund'>
          <View className='ord-verify-head'><Text className='ord-verify-title'>退款原因</Text></View>
          <Text className='ord-verify-hint'>按平台规则：服务开始前申请自动全额原路退回；服务开始后由商家在 24 小时内处理。提交时以订单实时状态判定适用窗口。</Text>
          <View className='ord-field'>
            <Text className='ord-label'>原因代码（必填）</Text>
            <Input id='rfd-reason-code' className='ord-input' type='text' disabled={locked} maxlength={64} value={draft.reasonCode}
              placeholder='请输入渠道公示的退款原因代码' onInput={event => edit('reasonCode', event.detail.value)} />
            {errors.reasonCode && <Text className='ord-error'>{errors.reasonCode}</Text>}
          </View>
          <View className='ord-field'>
            <Text className='ord-label'>补充说明（选填）</Text>
            <Textarea id='rfd-reason-text' className='ord-textarea' disabled={locked} maxlength={500} value={draft.reasonText}
              placeholder='补充说明退款原因（选填，最多 500 字）' onInput={event => edit('reasonText', event.detail.value)} />
            <Text className='ord-count'>{Array.from(draft.reasonText).length}/500</Text>
            {errors.reasonText && <Text className='ord-error'>{errors.reasonText}</Text>}
          </View>
          {state.pending !== null
            ? <Button id='rfd-retry' className='ord-verify-action' disabled={state.busy} onClick={() => void controller.retry(orderId)}>重试原申请</Button>
            : <Button id='rfd-submit' className='ord-verify-action' disabled={state.busy} onClick={submitForm}>提交退款申请</Button>}
          {state.busy && <Text className='ord-verify-hint'>正在提交退款申请…</Text>}
          {state.notice && <Text id='rfd-notice' className='ord-error'>{state.notice}</Text>}
          {state.pending !== null && state.notice === '' && <Text className='ord-verify-hint'>已恢复上次未确认的退款申请，重试将沿用原请求编号与内容，不会重复创建。</Text>}
        </View>}
        <View className='ord-preview-note'><Text>{preview ? '只读预览：本地样例数据，仅用于设计验收，不发起真实请求。' : '页面数据：真实接口（订单只读 + 退款申请 no-store 幂等写）。'}</Text></View>
      </View>}
    </View>
  </ConsumerPageLayout>
}

/** 处理路径展示（服务前自动全额 / 服务后商家确认两窗口；AFTERSALE_DECISION 不在本入口出现）。 */
function receiptRouteLabel(status: string, route: string): string {
  if (route === 'AUTO_FULL_BEFORE_SERVICE') return '服务前自动全额'
  if (route === 'MERCHANT_CONFIRM_AFTER_SERVICE') return '服务后商家 24 小时处理'
  return status
}
