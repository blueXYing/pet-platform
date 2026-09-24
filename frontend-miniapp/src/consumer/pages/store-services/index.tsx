import { Button, Text, View } from '@tarojs/components'
import Taro, { useRouter, useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { realServiceRepository } from '../../api/page-repository'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { ConsumerPageLayout } from '../../components/page-layout'
import {
  PreviewServiceRepository, designSamples, fixtureStoreId, isServiceScenario,
  type ServiceCatalogDeps, type ServiceItemView, type ServicePage,
} from '../../service/model'
import { PreviewStoreRepository, type StoreDetailView, type StoreDirectoryDeps } from '../../store/model'
import { RealStoreRepository, isStoreNotFound, runCatalogRead } from '../../store/repository'
import { consumerApi } from '../../../shared/consumer-runtime'
import { MerchantApplicationRepository } from '../../../shared/merchant-repositories'
import { detailActionGate, detailReadAllowed } from './browse-gate'
import { ServiceRow, StoreServicesDesign, reviewCardHeight, serviceListCardHeight } from './view'

// Node 690:6660 (服务-商家详情页), 402x1306. The 团购套餐 region binds the frozen
// GET /api/v1/c/stores/{storeId}/services contract; since the /c/stores slice (2026-09-22)
// the store header binds the frozen nine-field store projection (storeName/address/
// phoneMasked; masked phone registered as a VIS-004 difference) and a 404 store renders the
// PRD §5.1.14 不可访问 state. Rating/sold/distance/reviews/promo remain registered design
// samples until their own contracts freeze. Viewing this page is anonymous (STR-D8, same
// adjudication as the directory); 预约/拨打电话 stay login-gated with a login guide, and
// booking itself belongs to a later C-003/C-004 slice and stays an explicit no-op when
// logged in.
type Phase = 'loading' | 'ready' | 'load-error' | 'expired' | 'invalid' | 'missing'

export default function StoreServicesPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isServiceScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision, context } = useWorkspace(preview ? 'preview' : 'real')
  const [phase, setPhase] = useState<Phase>('loading')
  const [items, setItems] = useState<ServiceItemView[]>([])
  const [store, setStore] = useState<StoreDetailView | null>(null)
  const [notice, setNotice] = useState('')
  const mounted = useRef(true)
  const sequence = useRef(0)
  const previousRevision = useRef(revision)
  const [platformInfo, setPlatformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--svc-status-top': `${platformInfo.statusBarHeight || 0}px`, '--svc-unit': `${unit}px` } as CSSProperties
  const storeId = preview ? fixtureStoreId : (route.params.storeId || '')

  const repository = useRef<ServiceCatalogDeps>(preview ? new PreviewServiceRepository(undefined, scenario) : realServiceRepository())
  const storeRepository = useRef<StoreDirectoryDeps>(preview
    ? new PreviewStoreRepository()
    : new RealStoreRepository(consumerApi, () => new MerchantApplicationRepository(consumerApi).cities()))
  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setPhase('loading'); setNotice('')
    if (!isContractId(storeId)) { setPhase('invalid'); return }
    if (!detailReadAllowed(preview, scenario)) { setPhase('expired'); return }
    try {
      // Anonymous-browsable per STR-D8 (same pattern as the directory page PR#75): with a
      // consumer context runCatalogRead still guards stale context switches; logged out the
      // reads run bare. The store detail stays the accessibility judge (STR-D5): 404 means
      // unknown, invisible or ineligible — the page then renders the PRD 不可访问 state.
      const storeView = await runCatalogRead(scope, () => storeRepository.current.detail(storeId))
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setStore(storeView)
      const page: ServicePage = await runCatalogRead(scope, () => repository.current.list(storeId, 1, 20))
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setItems(page.items); setPhase('ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      if (isStoreNotFound(error)) { setStore(null); setPhase('missing'); return }
      setPhase('load-error')
    }
  }, [preview, scenario, scope, storeId])
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
  function canInteract() { return ready }
  function notWired(label: string) {
    if (!canInteract()) return
    // Actions (not viewing) stay login-gated per the final PRD; anonymous taps get the guide.
    if (detailActionGate(context) === 'login-required') {
      void Taro.showModal({ title: '请先登录', content: `${label}需要先登录，是否前往登录？`, confirmText: '去登录', cancelText: '暂不' })
        .then(answer => { if (answer.confirm) void Taro.redirectTo({ url: '/consumer/pages/shell/index' }).catch(() => setNotice('页面跳转失败，请重试')) })
        .catch(() => setNotice(''))
      return
    }
    setNotice(preview ? `“${label}”尚未接入本次预览` : `“${label}”功能尚未接通`)
  }
  function openDetail(service: ServiceItemView) {
    if (!canInteract()) return
    Taro.navigateTo({ url: `/consumer/pages/store-services/service-detail?preview=${preview ? '1' : '0'}&serviceId=${encodeURIComponent(service.serviceId)}` }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  const count = items.length
  const listTop = 711.5
  const reviewTop = listTop + serviceListCardHeight(count) + 15.5
  return <ConsumerPageLayout page='storeServices' unit={unit} navigation={{ idPrefix: 'svc', disabled: !ready, onSelect: key => { setNotice(navigationUnavailableMessage(key)) }, referencePlacement: undefined }} className='svc-page' style={style}>
    <View className='svc-status-area' />
    {!ready && <View className='svc-state' role='status'>
      <Text>{phase === 'loading' ? '正在加载门店服务…' : phase === 'expired' ? '登录已失效，请重新登录' : phase === 'invalid' ? '门店参数无效' : phase === 'missing' ? '门店不存在或不可访问' : '加载失败，请重试'}</Text>
      {phase === 'load-error' && <Button id='svc-retry-load' className='svc-state-action' onClick={() => void load()}>重新加载</Button>}
      {(phase === 'missing' || phase === 'invalid') && <Button id='svc-back-directory' className='svc-state-action' onClick={() => Taro.redirectTo({ url: '/consumer/pages/store-services/stores' }).catch(() => setNotice('返回失败'))}>返回门店列表</Button>}
    </View>}
    {ready && <StoreServicesDesign store={store} listTop={listTop} reviewTop={reviewTop} onBack={() => Taro.navigateBack().catch(() => setNotice('返回失败'))}
      onCall={() => notWired('拨打电话')} onBookNow={() => notWired('立即预约')} bookEnabled={canInteract()}
      footer={<Text>页面数据：{preview ? '契约 Mock（preview=1，不联调）' : '真实接口（后端交付前失败关闭，可匿名浏览）'}</Text>}
      notice={notice ? <Text id='svc-notice' className='svc-notice' style={{ left: `calc(var(--svc-unit) * 29)`, right: `calc(var(--svc-unit) * 29)`, top: `calc(var(--svc-unit) * ${reviewTop + reviewCardHeight() + 24})` }}>{notice}</Text> : undefined}
      servicesNode={count === 0
        ? <View className='svc-list-empty'><Text>暂无服务</Text></View>
        : items.map(service => <ServiceRow key={service.serviceId} idPrefix='svc-row' line={{ service, description: preview ? designSamples.listDescription[service.serviceId] : undefined }}
            onOpen={() => openDetail(service)} onBook={() => notWired('预约')} />)} />}
  </ConsumerPageLayout>
}
function isContractId(value: string): boolean {
  return /^[1-9][0-9]{0,18}$/.test(value) && (value.length < 19 || BigInt(value) <= 9223372036854775807n)
}
