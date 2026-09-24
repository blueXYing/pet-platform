import { Button, Text, View } from '@tarojs/components'
import Taro, { useRouter, useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { realServiceRepository } from '../../api/page-repository'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { ConsumerPageLayout } from '../../components/page-layout'
import {
  PreviewServiceRepository, isServiceScenario,
  type ServiceCatalogDeps, type ServiceDetailView,
} from '../../service/model'
import { runCatalogRead } from '../../store/repository'
import { detailActionGate, detailReadAllowed } from './browse-gate'
import { ServiceRow, StoreServicesDesign, serviceListCardHeight } from './view'

// Node 690:2025 / 690:4205 (热门服务-宠物美容-详情页) — byte-identical copies of the
// 690:6660 layout, so the service detail reuses that design one-to-one. The 团购套餐 region
// renders the ONE service returned by the frozen GET /api/v1/c/services/{serviceId} contract
// (description included); the store header stays design-sample copy here (this page targets a
// service, and the 商家详情页 remains the store-bound view). Viewing is anonymous (STR-D8,
// same adjudication as the directory and store detail); 预约/拨打电话 stay login-gated with
// a login guide, booking itself stays an explicit no-op when logged in.
// VIS note: the design frames contain three sample cards; this page renders the queried
// service only — recorded as a data-driven difference pending VIS-003 overlay review.
type Phase = 'loading' | 'ready' | 'missing' | 'load-error' | 'expired' | 'invalid'

export default function ServiceDetailPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isServiceScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision, context } = useWorkspace(preview ? 'preview' : 'real')
  const [phase, setPhase] = useState<Phase>('loading')
  const [detail, setDetail] = useState<ServiceDetailView | null>(null)
  const [notice, setNotice] = useState('')
  const mounted = useRef(true)
  const sequence = useRef(0)
  const previousRevision = useRef(revision)
  const [platformInfo, setPlatformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--svc-status-top': `${platformInfo.statusBarHeight || 0}px`, '--svc-unit': `${unit}px` } as CSSProperties
  const serviceId = route.params.serviceId || ''

  const repository = useRef<ServiceCatalogDeps>(preview ? new PreviewServiceRepository(undefined, scenario) : realServiceRepository())
  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setPhase('loading'); setNotice('')
    if (!isContractId(serviceId)) { setPhase('invalid'); return }
    if (!detailReadAllowed(preview, scenario)) { setPhase('expired'); return }
    try {
      // Anonymous-browsable per STR-D8 (same pattern as the directory page PR#75): with a
      // consumer context runCatalogRead still guards stale context switches; logged out the
      // read runs bare.
      const value = await runCatalogRead(scope, () => repository.current.detail(serviceId))
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setDetail(value); setPhase('ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      // Frozen error split (SVC-D1b): 404 SERVICE_NOT_FOUND is the indistinguishable
      // missing-or-ineligible answer; anything else fails closed as a load error.
      if (isServiceNotFound(error)) setPhase('missing')
      else setPhase('load-error')
    }
  }, [preview, scenario, scope, serviceId])
  useDidShow(() => { void load() })
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; sequence.current++ }
  }, [load])
  useEffect(() => {
    // Browsing is anonymous, so a login/logout (revision change) no longer expires the page;
    // the public catalog is simply re-read under the new context for a clean state.
    if (previousRevision.current === revision) return
    previousRevision.current = revision
    void load()
  }, [revision, load])
  useEffect(() => {
    const handler = () => setPlatformInfo(Taro.getWindowInfo())
    Taro.onWindowResize(handler)
    return () => Taro.offWindowResize(handler)
  }, [])

  const ready = phase === 'ready'
  function notWired(label: string) {
    if (!ready) return
    // Actions (not viewing) stay login-gated per the final PRD; anonymous taps get the guide.
    if (detailActionGate(context) === 'login-required') {
      void Taro.showModal({ title: '请先登录', content: `${label}需要先登录，是否前往登录？`, confirmText: '去登录', cancelText: '暂不' })
        .then(answer => { if (answer.confirm) void Taro.redirectTo({ url: '/consumer/pages/shell/index' }).catch(() => setNotice('页面跳转失败，请重试')) })
        .catch(() => setNotice(''))
      return
    }
    setNotice(preview ? `“${label}”尚未接入本次预览` : `“${label}”功能尚未接通`)
  }
  const listTop = 711.5
  const reviewTop = listTop + serviceListCardHeight(1) + 15.5
  return <ConsumerPageLayout page='serviceDetail' unit={unit} navigation={{ idPrefix: 'svcd', disabled: !ready, onSelect: key => { setNotice(navigationUnavailableMessage(key)) }, referencePlacement: undefined }} className='svc-page svc-detail-page' style={style}>
    <View className='svc-status-area' />
    {!ready && <View className='svc-state' role='status'>
      <Text>{phase === 'loading' ? '正在加载服务详情…' : phase === 'missing' ? '服务不存在或已下架' : phase === 'expired' ? '登录已失效，请重新登录' : phase === 'invalid' ? '服务参数无效' : '加载失败，请重试'}</Text>
      {phase === 'load-error' && <Button id='svcd-retry-load' className='svc-state-action' onClick={() => void load()}>重新加载</Button>}
      {phase === 'missing' && <Button id='svcd-back-list' className='svc-state-action' onClick={() => Taro.navigateBack().catch(() => setNotice('返回失败'))}>返回上一页</Button>}
    </View>}
    {ready && detail && <StoreServicesDesign store={null} listTop={listTop} reviewTop={reviewTop} onBack={() => Taro.navigateBack().catch(() => setNotice('返回失败'))}
      serviceCover={preview ? undefined : detail.cover} onRefreshCover={() => void load()}
      onCall={() => notWired('拨打电话')} onBookNow={() => notWired('立即预约')} bookEnabled
      footer={<Text>页面数据：{preview ? '契约 Mock（preview=1，不联调）' : '真实接口（后端交付前失败关闭，可匿名浏览）'}</Text>}
      notice={notice ? <Text id='svcd-notice' className='svc-notice' style={{ left: `calc(var(--svc-unit) * 29)`, right: `calc(var(--svc-unit) * 29)`, top: `calc(var(--svc-unit) * ${reviewTop + 246 + 24})` }}>{notice}</Text> : undefined}
      servicesNode={<ServiceRow idPrefix='svcd-row' line={{ service: detail, description: detail.description }} onBook={() => notWired('预约')} />} />}
  </ConsumerPageLayout>
}
function isContractId(value: string): boolean {
  return /^[1-9][0-9]{0,18}$/.test(value) && (value.length < 19 || BigInt(value) <= 9223372036854775807n)
}
function isServiceNotFound(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'statusCode' in error && (error as { statusCode: unknown }).statusCode === 404
}
