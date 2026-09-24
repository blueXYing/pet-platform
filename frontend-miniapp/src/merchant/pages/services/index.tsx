import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealServiceManageRepository, serviceManageMessage } from '../../services/repository'
import { ServiceListController } from '../../services/controller'
import {
  PreviewServiceManageRepository, formatPrice, isServiceManageScenario, manageStatusText,
  type ManagedServiceItem, type ServiceManageDeps,
} from '../../services/model'
import navBack from './assets/nav-back@2x.png'
import iconPlus from './assets/icon-plus@2x.png'
import './page.css'

// M-002 服务管理列表（design source: merchant frame 10:5255「首页-商品管理」, 402x898,
// adjudicated 2026-09-22 as the M-002 list design source with V1 cuts: title reads 服务管理,
// direct-sale/package notion removed, five contract statuses shown as design-language tags).
// Data flows through the contract mock (preview=1) or the real repository (role A backend).
const PAGE_SIZE = 20

export default function MerchantServicesPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isServiceManageScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision, context } = useWorkspace(preview ? 'preview' : 'real')
  const [repository] = useState<ServiceManageDeps>(() => preview ? new PreviewServiceManageRepository(undefined, scenario) : realRepository())
  const [controller] = useState(() => new ServiceListController(repository))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--msvc-status-top': `${platformInfo.statusBarHeight || 0}px`, '--msvc-unit': `${unit}px` } as CSSProperties
  const previousRevision = useRef(revision)

  const load = useCallback(() => {
    setNotice('')
    if (preview) {
      if (scenario === 'expired') { controller.entry(); return }
      void controller.load()
      return
    }
    const current = scope.current
    if (!current) { controller.entry(); setNotice('请先登录后从商家工作台进入。'); return }
    if (current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      controller.entry(); setNotice('请从商家工作台进入服务管理。')
      return
    }
    void controller.load()
  }, [controller, preview, scenario, scope])
  useDidShow(load)
  useEffect(() => () => controller.dispose(), [controller])
  // Coordinates switched away (workbench leave / account switch) invalidates the listing.
  useEffect(() => {
    if (previousRevision.current !== revision) {
      previousRevision.current = revision
      const current = scope.current
      if (!preview && (!current || current.workspace !== 'merchant')) controller.entry()
    }
  }, [revision, scope, preview, controller])

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack().catch(() => setNotice('返回失败，请重试'))
    else await Taro.redirectTo({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('返回失败，请重试'))
  }
  function openEditor(item?: ManagedServiceItem) {
    const query = `preview=${preview ? '1' : '0'}${item ? `&serviceId=${encodeURIComponent(item.serviceId)}` : ''}`
    Taro.navigateTo({ url: `/merchant/pages/services/edit?${query}` }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  /** Design toggle = online/offline affordance; V1 semantics: ON submits for review, OFF takes offline. */
  async function toggle(item: ManagedServiceItem) {
    if (state.status !== 'ready' || busy) return
    setNotice('')
    if (item.status === 'ACTIVE') {
      const confirmed = await Taro.showModal({ title: '确认下架', content: '下架后前台不再展示该服务；对历史订单无影响，仍按原快照履约。', confirmText: '确认下架', cancelText: '取消' })
      if (!confirmed.confirm) return
      setBusy(true)
      try {
        await repository.takeOffline(`merchant-service:${item.serviceId}:offline`, item.serviceId, item.version)
        await controller.load()
      } catch (error) { setNotice(serviceManageMessage(error)) } finally { setBusy(false) }
      return
    }
    if (item.status === 'REVIEWING') { setNotice('审核中的服务暂不能操作，请等待审核结果。'); return }
    const confirmed = await Taro.showModal({ title: '提交审核', content: `重新上架「${item.serviceName}」需要重新通过平台审核，确认提交？`, confirmText: '提交审核', cancelText: '取消' })
    if (!confirmed.confirm) return
    setBusy(true)
    try {
      await repository.submitOnline(`merchant-service:${item.serviceId}:online`, item.serviceId, item.version)
      await controller.load()
    } catch (error) { setNotice(serviceManageMessage(error)) } finally { setBusy(false) }
  }

  const ready = state.status === 'ready'
  return <View className='msvc-page' style={style}>
    <View className='msvc-status-area' />
    <View className='msvc-header'>
      <Button id='msvc-back' ariaLabel='返回' className='msvc-back' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='msvc-title'>服务管理</Text>
    </View>
    {!ready && <View className='msvc-state' role='status'>
      <Text>{state.status === 'loading' || state.status === 'idle' ? '正在加载服务列表…' : state.notice || '加载失败，请重试。'}</Text>
      {state.status === 'load-error' && <Button id='msvc-retry' className='msvc-state-action' onClick={load}>重新加载</Button>}
      {state.status === 'entry' && !preview && <Button className='msvc-state-action' onClick={() => Taro.redirectTo({ url: '/merchant/pages/workspace/index' })}>去商家工作台</Button>}
    </View>}
    {ready && <ScrollView className='msvc-body' scrollY enhanced showScrollbar={false}>
      <View className='msvc-count-row'>
        <Text className='msvc-count'>共 {state.total} 个服务</Text>
        <Button id='msvc-add' className='msvc-add' onClick={() => openEditor()}>
          <Image className='msvc-add-icon' src={iconPlus} mode='scaleToFill' /><Text>添加服务</Text>
        </Button>
      </View>
      {state.items.length === 0 && <View className='msvc-empty'><Text>还没有服务，点击右上角“添加服务”创建第一个单次预约服务。</Text></View>}
      {state.items.map(item => <View key={item.serviceId} className='msvc-card' data-status={item.status}>
        <Button id={`msvc-card-${item.serviceId}`} className='msvc-card-main' ariaLabel={`${item.serviceName || '未命名草稿'}，${manageStatusText[item.status]}${item.price === null ? '' : `，价格${formatPrice(item.price)}元`}`} onClick={() => openEditor(item)}>
          <View className='msvc-card-line'>
            <Text className='msvc-card-name'>{item.serviceName || '未命名草稿'}</Text>
            <Text className='msvc-card-status' data-status={item.status}>{manageStatusText[item.status]}</Text>
          </View>
          <Text className='msvc-card-price'>{item.price === null ? '未定价' : `¥${formatPrice(item.price)}`}</Text>
        </Button>
        <Button
          id={`msvc-toggle-${item.serviceId}`} ariaLabel={item.status === 'ACTIVE' ? `下架${item.serviceName}` : `提交${item.serviceName}审核`}
          className='msvc-toggle' data-on={item.status === 'ACTIVE' ? 'true' : 'false'} disabled={busy || item.status === 'REVIEWING'}
          onClick={() => void toggle(item)}>
          <View className='msvc-toggle-knob' />
        </Button>
      </View>)}
      {state.items.length < state.total && <Button id='msvc-load-more' className='msvc-load-more' disabled={state.loadingMore} onClick={() => void controller.loadMore()}>
        {state.loadingMore ? '正在加载…' : '加载更多'}
      </Button>}
      {notice && <Text id='msvc-notice' className='msvc-notice'>{notice}</Text>}
      <View className='msvc-tail'><Text>页面数据：{preview ? '契约 Mock（preview=1，不联调）' : '真实接口（后端交付前失败关闭）'}</Text></View>
    </ScrollView>}
  </View>
}

function realRepository(): ServiceManageDeps {
  return new RealServiceManageRepository(consumerApi,
    () => consumerApi.scope.current?.merchantId || '',
    () => consumerApi.scope.current?.storeId || '')
}
