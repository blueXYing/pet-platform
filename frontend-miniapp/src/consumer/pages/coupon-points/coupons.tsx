import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { ConsumerPageLayout } from '../../components/page-layout'
import {
  couponsByStatus, isCouponPointsScenario, statusTabLabels, thresholdLabel,
  PreviewCouponPointsRepository, type CouponStatus, type CouponView,
} from '../../coupon-points/model'
import { RealCouponPointsRepository, isCouponPointsUnauthorized } from '../../coupon-points/repository'
import back from '../../assets/profile/back.png'
import './coupon-points.css'

// C-006 切片：我的优惠券（只读）。设计源 128:2078（登记表 §3 用户端）。券查询契约已裁决并实现
// （PR#112，10 号 §3.15.1 + 11 号 OpenAPI）：真实模式按 tab 的 status 参数服务端分桶分页
// （可用/已使用/已过期对应 AVAILABLE/USED/EXPIRED，默认 AVAILABLE；FROZEN/RISK_FROZEN 任何桶
// 永不返回，D2）；preview=1 仍走本地夹具（设计验收通道）。只读：无领取、使用、跳转下单等写路径。
// 失败关闭：路由未挂载（pet.auth.c.enabled 关闭时 404）、503、网络故障一律不可交互提示面板，
// 不渲染任何虚构数据；401/未登录引导去登录（profile/messages 先例）。
type Phase = 'loading' | 'ready' | 'expired' | 'load-error'
const PAGE_SIZE = 20

export default function CouponsPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isCouponPointsScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const repository = useRef(preview ? undefined : new RealCouponPointsRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  // preview：全量夹具本地分桶；real：当前 tab 的服务端页。
  const [coupons, setCoupons] = useState<readonly CouponView[]>([])
  const [tab, setTab] = useState<CouponStatus>('AVAILABLE')
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loadingMore, setLoadingMore] = useState(false)
  const [tabsShown, setTabsShown] = useState(false)
  const [notice, setNotice] = useState('')
  const mounted = useRef(true)
  const sequence = useRef(0)
  const previousRevision = useRef(revision)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--cpn-status-top': `${platformInfo.statusBarHeight || 0}px`, '--cpn-unit': `${unit}px` } as CSSProperties

  const load = useCallback(async (nextTab: CouponStatus) => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setNotice('')
    if (preview) {
      setPhase('loading')
      try {
        const data = await scope.run(undefined, () => new PreviewCouponPointsRepository(scenario).load())
        if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
        setCoupons(data.coupons); setPhase('ready')
      } catch {
        if (mounted.current && current === sequence.current && currentRevision === scope.revision) setPhase('ready')
      }
      return
    }
    // 真实模式：先补一次会话校验，避免 restore 在途时误报未登录（aftersale 先例）。
    if (!scope.current) { try { await consumerApi.restore() } catch { /* 未登录，走下方登录引导 */ } }
    if (current !== sequence.current || currentRevision !== scope.revision) return
    if (!scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    setPhase('loading')
    try {
      const result = await scope.run(undefined, () => repository.current!.listCoupons(nextTab, 1, PAGE_SIZE))
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setCoupons(result.items); setTotal(result.total); setPage(1)
      setTabsShown(true); setPhase('ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setPhase(isCouponPointsUnauthorized(error) ? 'expired' : 'load-error')
    }
  }, [preview, scenario, scope])
  useEffect(() => { mounted.current = true; void load(tab); return () => { mounted.current = false } }, [load])
  useEffect(() => {
    if (revision === previousRevision.current) return
    previousRevision.current = revision
    setCoupons([]); setTotal(0); setPage(1); setTabsShown(false); setPhase('loading'); void load(tab)
  }, [revision, load, tab])
  function chooseTab(key: CouponStatus) {
    if (key === tab) return
    setTab(key)
    if (preview) return // 夹具全量在本地，切 tab 只做本地分桶。
    void load(key) // 真实模式：status 参数服务端过滤。
  }
  async function loadMore() {
    if (loadingMore || preview || phase !== 'ready' || coupons.length >= total) return
    setLoadingMore(true)
    const current = sequence.current
    const currentRevision = scope.revision
    try {
      const result = await scope.run(undefined, () => repository.current!.listCoupons(tab, page + 1, PAGE_SIZE))
      if (current !== sequence.current || currentRevision !== scope.revision) return
      const known = new Set(coupons.map(coupon => coupon.couponId))
      setCoupons([...coupons, ...result.items.filter(coupon => !known.has(coupon.couponId))])
      setPage(result.page); setTotal(result.total)
    } catch { setNotice('加载更多失败，请重试。') } finally { setLoadingMore(false) }
  }
  function goLogin() { void Taro.redirectTo({ url: '/consumer/pages/shell/index' }) }
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/consumer/pages/shell/index' })
  }
  const ready = phase === 'ready'
  // preview：ready 才出 tab（PR#105 行为不变）；real：首个桶成功后 tab 常驻，切换即服务端查询。
  const visible = preview ? couponsByStatus(coupons, tab) : coupons
  return <ConsumerPageLayout page='couponList' unit={unit} className='cpn-page'
    navigation={{ idPrefix: 'cpn', disabled: !ready, onSelect: key => setNotice(navigationUnavailableMessage(key)) }}
    style={style}>
    <View className='cpn-design'>
      <View className='cpn-status-area' />
      <View className='cpn-nav'>
        <Button id='cpn-back' ariaLabel='返回' className='cpn-nav-back' onClick={() => void goBack()}>
          <Image src={back} className='cpn-nav-back-icon' />
        </Button>
        <Text className='cpn-nav-title'>优惠券</Text>
      </View>
      {(preview ? ready : tabsShown) && <View className='cpn-tabs' role='tablist'>
        {(Object.keys(statusTabLabels) as CouponStatus[]).map(key => <Button key={key}
          id={`cpn-tab-${key}`} ariaLabel={statusTabLabels[key]}
          className={`cpn-tab${tab === key ? ' is-active' : ''}`}
          onClick={() => chooseTab(key)}><Text>{statusTabLabels[key]}</Text></Button>)}
      </View>}
      {phase === 'loading' && <View className='cpn-state' role='status'><Text>正在读取优惠券…</Text></View>}
      {phase === 'expired' && <View className='cpn-state' role='status'>
        <Text id='cpn-login-hint'>登录后可查看我的优惠券。</Text>
        <Button id='cpn-login' className='cpn-state-action' onClick={goLogin}>去登录</Button>
      </View>}
      {phase === 'load-error' && <View className='cpn-state' role='status'>
        <Text id='cpn-error'>优惠券读取失败，请稍后重试。</Text>
        <Button id='cpn-retry' className='cpn-state-action' onClick={() => void load(tab)}>重新加载</Button>
      </View>}
      {ready && visible.length === 0 && <View className='cpn-state'><Text id='cpn-empty'>暂无{statusTabLabels[tab]}优惠券。</Text></View>}
      {ready && visible.map(coupon => <Button key={coupon.couponId} id={`cpn-coupon-${coupon.couponId}`}
        className={`cpn-card${coupon.status !== 'AVAILABLE' ? ` is-${coupon.status.toLowerCase()}` : ''}`}
        ariaLabel={`${coupon.name}，${thresholdLabel(coupon)}，${statusTabLabels[coupon.status]}`}
        onClick={() => { void Taro.navigateTo({ url: `/consumer/pages/coupon-points/coupon-detail?${preview ? 'preview=1&' : ''}couponId=${encodeURIComponent(coupon.couponId)}` }).catch(() => setNotice('页面跳转失败，请重试')) }}>
        <View className='cpn-card-amount'>
          <Text className='cpn-card-amount-value'>{coupon.amountOff === null ? '--' : `¥${coupon.amountOff}`}</Text>
          <Text className='cpn-card-amount-threshold'>{thresholdLabel(coupon)}</Text>
        </View>
        <View className='cpn-card-main'>
          <View className='cpn-card-head'>
            <Text className='cpn-card-name'>{coupon.name}</Text>
            {coupon.typeLabel !== null && <Text className='cpn-card-type'>{coupon.typeLabel}</Text>}
          </View>
          {coupon.scopeSummary !== null && <Text className='cpn-card-scope'>{coupon.scopeSummary}</Text>}
          <Text className='cpn-card-validity'>有效期至 {coupon.validTo.replace(/-/g, '.')}</Text>
        </View>
      </Button>)}
      {ready && !preview && coupons.length < total && <Button id='cpn-load-more' className='cpn-more' disabled={loadingMore} onClick={() => void loadMore()}>
        {loadingMore ? '正在加载…' : '加载更多'}
      </Button>}
      {ready && <View className='cpn-preview-note'><Text>{preview ? '只读预览：本地样例数据，仅用于设计验收，不发起真实请求。' : '页面数据：真实接口（只读查询）。'}</Text></View>}
      {notice && <Text id='cpn-notice' className='cpn-notice'>{notice}</Text>}
    </View>
  </ConsumerPageLayout>
}
