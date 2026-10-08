import { Button, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, useDidHide, useRouter } from '@tarojs/taro'
import { useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { ConsumerPageLayout } from '../../components/page-layout'
// ARCH-005 分工：本页不触碰订单原始事实字段，事实→展示的推导（资格文案/综合分预览/权重口径/
// 错误面映射/入口门控）全部委托 src/consumer/orders/review.ts（eligibilityHeadline/
// compositePreview/reviewDimensions/canReviewEntry/reviewCreateMessage）；页面只消费现成展示值。
import { appointmentWindow, orderStatusBadge, type OrderDetailView } from '../../orders/model'
import {
  ReviewController, compositePreview, eligibilityHeadline, emptyReviewDraft, isOrderId,
  isReviewScenario, receiptBody as receiptBodyText, receiptHeadline, reviewDimensions,
  validateReviewDraft, PreviewReviewRepository,
  type ReviewDraft, type ReviewDraftErrors, type ReviewScenario,
} from '../../orders/review'
import { RealReviewRepository } from '../../orders/review-repository'
import './orders.css'

// REV-001 切片：评价页（订单详情 actions.canReview 门控进入，10号 §3.14 + SSOT §11）。
// 三维评分按 40/40/20 权重口径呈现（门店 40% / 服务 40% / 人员 20%），综合分预览仅是本地
// 展示推导，真实综合分由服务端内核计算；文字评价选填（≤2000 码点）；媒体能力未开放，本页
// 不提供图片上传（契约 mediaFileIds 字段保留，服务端对非空数组 400 如实拒绝）。
// preview=1 走本地夹具（fresh/done/ineligible 三场景），真实模式走 §3.14 接口（后端同批
// 交付，默认关闭，联调留 E2E 轮，如实披露）。幂等槽：未确认提交保留原 X-Request-Id 原载荷，
// 仅显式重试重发。
export default function ReviewPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario: ReviewScenario = isReviewScenario(route.params.scenario) ? route.params.scenario : 'fresh'
  const orderId = route.params.orderId || ''
  const { revision } = useWorkspace(preview ? 'preview' : 'real')
  return <ReviewScreen key={`${revision}:${orderId}:${scenario}`} preview={preview} scenario={scenario} orderId={orderId} />
}

function ReviewScreen({ preview, scenario, orderId }: { preview: boolean; scenario: ReviewScenario; orderId: string }) {
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [controller] = useState(() => {
    if (preview) {
      const repository = new PreviewReviewRepository(scenario)
      return new ReviewController({
        detail: target => repository.detail(target),
        eligibility: target => repository.eligibility(target),
        create: (target, input) => repository.create(target, input),
        pendingReview: () => repository.pendingReview(),
        retireConflict: () => repository.retireConflict(),
      }, scope)
    }
    const repository = new RealReviewRepository(consumerApi)
    return new ReviewController({
      detail: target => repository.detail(target),
      eligibility: target => repository.eligibility(target),
      create: (target, input) => repository.create(target, input),
      pendingReview: target => repository.pendingReview(target),
      retireConflict: (target, error) => repository.retireConflict(target, error),
    }, scope)
  })
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [draft, setDraft] = useState<ReviewDraft>(emptyReviewDraft)
  const [errors, setErrors] = useState<ReviewDraftErrors>({})
  const [invalidOrderId] = useState(!isOrderId(orderId))
  const mounted = useRef(true)
  const initialized = useRef(false)
  const visible = useRef(true)
  const loadGeneration = useRef(0)
  const revision = consumerApi.scope.revision
  const live = () => mounted.current && visible.current && revision === consumerApi.scope.revision
  const slot = `review:draft:${orderId}`
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
          const saved = consumerApi.intent(slot) as Partial<ReviewDraft> | undefined
          if (saved && [saved.storeScore, saved.serviceScore, saved.staffScore].every(score => Number.isInteger(score) && (score as number) >= 0 && (score as number) <= 5)
            && typeof saved.content === 'string' && Array.from(saved.content).length <= 2000)
            setDraft({ storeScore: saved.storeScore as number, serviceScore: saved.serviceScore as number, staffScore: saved.staffScore as number, content: saved.content })
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

  function rate(key: 'storeScore' | 'serviceScore' | 'staffScore', value: number) {
    if (locked) return
    const next = { ...draft, [key]: value }
    setDraft(next)
    setErrors(previous => ({ ...previous, [key]: undefined }))
    if (!preview) { try { consumerApi.saveIntent(slot, next) } catch { /* 未登录时不持久化草稿 */ } }
  }
  function editContent(value: string) {
    if (locked) return
    const next = { ...draft, content: value }
    setDraft(next)
    setErrors(previous => ({ ...previous, content: undefined }))
    if (!preview) { try { consumerApi.saveIntent(slot, next) } catch { /* 未登录时不持久化草稿 */ } }
  }
  function submitForm() {
    if (locked || invalidOrderId) return
    const next = validateReviewDraft(draft)
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
  const composite = compositePreview(draft)
  return <ConsumerPageLayout page='orderDetail' unit={unit} className='ord-page' style={style}
    navigation={{ idPrefix: 'rvw', disabled: state.phase === 'loading', onSelect: key => { void Taro.showToast({ title: navigationUnavailableMessage(key), icon: 'none' }) } }}>
    <View className='ord-design'>
      <View className='ord-status-area' />
      <View className='ord-nav'>
        <Button id='rvw-back' ariaLabel='返回' className='ord-nav-back' onClick={() => void goBack()}>
          <Text className='ord-nav-back-icon'>‹</Text>
        </Button>
        <Text className='ord-nav-title'>评价服务</Text>
      </View>
      {invalidOrderId && <View className='ord-state' role='status'><Text id='rvw-invalid'>订单链接无效，请从订单详情重新进入。</Text></View>}
      {state.phase === 'loading' && <View className='ord-state' role='status'><Text id='rvw-loading'>正在读取订单…</Text></View>}
      {state.phase === 'unauthorized' && <View className='ord-state' role='status'>
        <Text id='rvw-login-hint'>登录后可评价本单服务。</Text>
        <Button id='rvw-login' className='ord-state-action' onClick={goLogin}>去登录</Button>
      </View>}
      {state.phase === 'load-error' && <View className='ord-state' role='status'>
        <Text id='rvw-error'>{state.notice || '订单读取失败，请稍后重试。'}</Text>
        <Button id='rvw-retry-read' className='ord-state-action' onClick={() => void load()}>重新加载</Button>
      </View>}
      {state.phase === 'ineligible' && state.receipt === null && <View className='ord-state' role='status'>
        <Text id='rvw-ineligible'>{state.eligibility !== null ? eligibilityHeadline(state.eligibility) : '当前订单不可评价（以订单实时状态为准）。'}</Text>
        <Button id='rvw-back-detail' className='ord-state-action' onClick={() => void goBack()}>返回订单详情</Button>
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
        {state.receipt !== null && <View className='ord-receipt' id='rvw-receipt'>
          <View className='ord-verify-head'>
            <Text className='ord-verify-title'>{receiptHeadline(state.receipt)}</Text>
          </View>
          <Text className='ord-verify-hint'>{receiptBodyText()}</Text>
          <View className='ord-fact-row'><Text className='ord-fact-label'>评价编号</Text><Text className='ord-fact-value'>{state.receipt.reviewId}</Text></View>
          <View className='ord-fact-row'><Text className='ord-fact-label'>综合分构成</Text><Text className='ord-fact-value'>门店 40% + 服务 40% + 人员 20%</Text></View>
          <Button id='rvw-back-after' className='ord-verify-action' onClick={() => void goBack()}>返回订单详情</Button>
        </View>}
        {state.receipt === null && state.eligibility !== null && <View className='ord-review'>
          <View className='ord-verify-head'><Text className='ord-verify-title'>本单评分</Text></View>
          <Text className='ord-verify-hint'>按平台规则：单条综合分 = 门店评分×40% + 服务评分×40% + 人员评分×20%；核销后 30 天内可评价，一单一次。</Text>
          {reviewDimensions.map(dimension => <View className='ord-rate' key={dimension.key}>
            <View className='ord-rate-head'>
              <Text className='ord-rate-label'>{dimension.label}</Text>
              <Text className='ord-rate-weight'>权重 {dimension.weight}</Text>
            </View>
            <View className='ord-rate-stars' role='radiogroup' ariaLabel={dimension.label}>
              {[1, 2, 3, 4, 5].map(star => <Button key={star} id={`rvw-${dimension.key}-${star}`} ariaLabel={`${star} 星`}
                className={`ord-star${draft[dimension.key] >= star ? ' is-on' : ''}`} disabled={locked} onClick={() => rate(dimension.key, star)}>
                <Text className='ord-star-icon'>★</Text>
              </Button>)}
            </View>
            {errors[dimension.key] && <Text className='ord-error'>{errors[dimension.key]}</Text>}
          </View>)}
          <View className='ord-rate-summary'>
            <Text className='ord-rate-label'>综合分预览</Text>
            <Text className='ord-rate-composite'>{composite === null ? '评满三维后显示' : composite}</Text>
          </View>
          <View className='ord-field'>
            <Text className='ord-label'>文字评价（选填）</Text>
            <Textarea id='rvw-content' className='ord-textarea' disabled={locked} maxlength={2000} value={draft.content}
              placeholder='分享本次服务的体验（选填，最多 2000 字）' onInput={event => editContent(event.detail.value)} />
            <Text className='ord-count'>{Array.from(draft.content).length}/2000</Text>
            {errors.content && <Text className='ord-error'>{errors.content}</Text>}
          </View>
          {state.pending !== null
            ? <Button id='rvw-retry' className='ord-verify-action' disabled={state.busy} onClick={() => void controller.retry(orderId)}>重试原提交</Button>
            : <Button id='rvw-submit' className='ord-verify-action' disabled={state.busy} onClick={submitForm}>提交评价</Button>}
          {state.busy && <Text className='ord-verify-hint'>正在提交评价…</Text>}
          {state.notice && <Text id='rvw-notice' className='ord-error'>{state.notice}</Text>}
          {state.pending !== null && state.notice === '' && <Text className='ord-verify-hint'>已恢复上次未确认的评价，重试将沿用原请求编号与内容，不会重复提交。</Text>}
        </View>}
        <View className='ord-preview-note'><Text>{preview ? '只读预览：本地样例数据，仅用于设计验收，不发起真实请求。' : '页面数据：真实接口（订单只读 + 评价资格 no-store 读 + 评价幂等写）。'}</Text></View>
      </View>}
    </View>
  </ConsumerPageLayout>
}
