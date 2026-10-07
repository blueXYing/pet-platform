import { Button, Input, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { ConsumerPageLayout } from '../../components/page-layout'
import {
  OrderVerifyController, actionFor, formatCode, formatInstant, isOrderVerifyScenario, isOrderId,
  remainingLabel, statusHints, statusLabels, PreviewOrderVerifyRepository, type OrderVerifyScenario,
} from '../../order-verify/model'
import { RealOrderVerifyRepository } from '../../order-verify/repository'
import './order-verify.css'

// 核销码页（47号 §4 v0.2 C 端凭证路由的最小用户侧闭环）。入口现状（C-004 已交付）：核销码
// 主入口在订单详情页（consumer/pages/orders/detail，按 OrderActions.canShowVerificationCode
// 呈现同源核销码区块）；本页按 aftersale 先例保留为显式输入订单号的直连通道，只展示核销码
// 视图本身，不虚构任何订单字段。preview=1 走本地夹具（设计验收通道，scenario=active|none|
// expired|invalidated|locked）；真实模式走 GET/POST /api/v1/c/orders/{orderId}/verification-code
// （开关 pet.verification.credential.http.enabled 默认关闭，未开放期间失败关闭）。码仅在 ACTIVE
// 显示，页面不缓存、不写日志；刷新沿 api.write 幂等槽，未确认结果只能重试原操作。
export default function OrderVerifyPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario: OrderVerifyScenario = preview && isOrderVerifyScenario(route.params.scenario) ? route.params.scenario : 'active'
  const { revision } = useWorkspace(preview ? 'preview' : 'real')
  return <VerifyScreen key={`${revision}:${scenario}`} preview={preview} scenario={scenario} initialOrderId={route.params.orderId || ''} />
}

function VerifyScreen({ preview, scenario, initialOrderId }: { preview: boolean; scenario: OrderVerifyScenario; initialOrderId: string }) {
  // real 模式下 useWorkspace('real').scope 即 consumerApi.scope；preview 用本地夹具 scope。
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState(() => preview ? new PreviewOrderVerifyRepository(scenario) : new RealOrderVerifyRepository(consumerApi))
  const [controller] = useState(() => new OrderVerifyController(repository, scope))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [orderId, setOrderId] = useState(isOrderId(initialOrderId) ? initialOrderId : '')
  const [inputError, setInputError] = useState('')
  const [now, setNow] = useState(() => Date.now())
  const mounted = useRef(true)
  const submitted = useRef<string | null>(null)
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = { '--ovv-unit': `${unit}px`, '--ovv-top': `${info.statusBarHeight || 0}px` } as CSSProperties

  useEffect(() => {
    mounted.current = true
    const active = state.view?.status === 'ACTIVE'
    const timer = active ? setInterval(() => { if (mounted.current) setNow(Date.now()) }, 1000) : null
    return () => { mounted.current = false; if (timer) clearInterval(timer) }
  }, [state.view?.status, state.view?.code])
  useEffect(() => () => { controller.dispose() }, [controller])
  // 首次进入带合法 orderId 参数：先补会话校验再读取（coupons 先例，避免 restore 在途误报未登录）。
  useEffect(() => {
    if (!initialOrderId || !isOrderId(initialOrderId) || submitted.current === initialOrderId) return
    void enter(initialOrderId)
  }, [initialOrderId])
  useDidShow(() => { if (state.orderId) void controller.load(state.orderId) })

  async function enter(target: string) {
    submitted.current = target
    if (!preview && !consumerApi.scope.current) { try { await consumerApi.restore() } catch { /* 未登录由 401 路径承接 */ } }
    if (!mounted.current) return
    void controller.load(target)
    controller.restore(target)
  }
  function submit() {
    setInputError('')
    if (!isOrderId(orderId)) { setInputError('请输入真实订单号（纯数字，最多19位）'); return }
    void enter(orderId)
  }
  function goLogin() { void Taro.redirectTo({ url: '/consumer/pages/shell/index' }) }
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/consumer/pages/shell/index' })
  }
  const view = state.view
  const action = view ? actionFor(view.status) : null
  const remaining = view?.status === 'ACTIVE' ? remainingLabel(now, view.expiresAt) : ''
  return <ConsumerPageLayout page='orderVerify' unit={unit} className='ovv-page' style={style}
    navigation={{ idPrefix: 'ovv', disabled: state.phase === 'loading', onSelect: key => { void Taro.showToast({ title: navigationUnavailableMessage(key), icon: 'none' }) } }}>
    <View className='ovv-design'>
      <View className='ovv-status-area' />
      <View className='ovv-nav'>
        <Button id='ovv-back' ariaLabel='返回' className='ovv-nav-back' onClick={() => void goBack()}>
          <Text className='ovv-nav-back-icon'>‹</Text>
        </Button>
        <Text className='ovv-nav-title'>订单核销码</Text>
      </View>
      <View className='ovv-body'>
        <View className='ovv-card'>
          <Text className='ovv-heading'>查看核销码</Text>
          <Text className='ovv-hint'>输入本人的订单号，核销码以实时读取为准。核销码主入口在「我的订单」订单详情内（C-004 已交付），本页保留为直连通道。</Text>
          <Input id='ovv-order-id' className='ovv-input' type='text' maxlength={19} value={orderId} placeholder='订单号'
            disabled={state.busy || state.phase === 'loading'} onInput={event => { setOrderId(event.detail.value); setInputError('') }} />
          {inputError && <Text className='ovv-error'>{inputError}</Text>}
          <View className='ovv-actions ovv-field'>
            <Button id='ovv-submit-order' className='ovv-secondary' disabled={state.busy || state.phase === 'loading'} onClick={submit}>
              {state.orderId ? '重新读取核销码' : '查询核销码'}
            </Button>
          </View>
        </View>
        {state.phase === 'loading' && <View className='ovv-state' role='status'><Text id='ovv-loading'>正在读取核销码…</Text></View>}
        {state.phase === 'unauthorized' && <View className='ovv-state' role='status'>
          <Text id='ovv-login-hint'>登录后可查看订单核销码。</Text>
          <Button id='ovv-login' className='ovv-state-action' onClick={goLogin}>去登录</Button>
        </View>}
        {state.phase === 'load-error' && <View className='ovv-state' role='status'>
          <Text id='ovv-error'>{state.notice || '核销码读取失败，请稍后重试。'}</Text>
          <Button id='ovv-retry-read' className='ovv-state-action' disabled={!state.orderId} onClick={() => state.orderId && void controller.load(state.orderId)}>重新读取</Button>
        </View>}
        {state.phase === 'ready' && view && <View className='ovv-card'>
          <View className='ovv-row'><Text className='ovv-heading'>订单 {view.orderId}</Text><Text className={`ovv-status is-${view.status.toLowerCase()}`}>{statusLabels[view.status]}</Text></View>
          <Text className='ovv-hint'>{statusHints[view.status]}</Text>
          {view.status === 'ACTIVE' && view.code !== null && <View className='ovv-code-panel'>
            <Text id='ovv-code' className='ovv-code'>{formatCode(view.code)}</Text>
            <Text id='ovv-code-remaining' className='ovv-hint'>有效期至 {formatInstant(view.expiresAt)}{remaining ? `，剩余 ${remaining}` : ''}</Text>
            <Text className='ovv-hint'>到店出示该码由商家核销；刷新成功后旧码立即作废。</Text>
          </View>}
          {view.status === 'LOCKED' && <Text id='ovv-locked-until' className='ovv-hint'>锁定解除时间：{formatInstant(view.lockedUntil)}</Text>}
          {view.status === 'EXPIRED' && <Text className='ovv-hint'>原截止时间：{formatInstant(view.expiresAt)}</Text>}
          {state.pending
            ? <Button id='ovv-retry-issue' className='ovv-primary' disabled={state.busy} onClick={() => void controller.retry()}>重试原{state.pending.refreshKind === 'INITIAL' ? '取码' : '刷新'}</Button>
            : action && <Button id='ovv-issue' className='ovv-primary' disabled={state.busy} onClick={() => void controller.issue()}>{action.label}</Button>}
          {state.busy && <View className='ovv-state' role='status'><Text>正在提交取码/刷新…</Text></View>}
        </View>}
        {state.notice && state.phase !== 'load-error' && <View className='ovv-notice' role='status'><Text id='ovv-notice'>{state.notice}</Text></View>}
        <View className='ovv-preview-note'><Text>{preview ? '只读预览：本地样例数据，仅用于设计验收，不发起真实请求。' : '页面数据：真实接口（核销码读取/刷新，no-store）。'}</Text></View>
      </View>
    </View>
  </ConsumerPageLayout>
}
