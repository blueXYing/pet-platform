import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { ConsumerPageLayout } from '../../components/page-layout'
import {
  couponsByStatus, isCouponPointsScenario, statusTabLabels, thresholdLabel,
  PreviewCouponPointsRepository, type CouponStatus, type CouponView,
} from '../../coupon-points/model'
import back from '../../assets/profile/back.png'
import './coupon-points.css'

// C-006 切片：我的优惠券（只读）。设计源 128:2078（登记表 §3 用户端）；无可用 C 端查询契约
// （pet-coupon-api 无 CouponQueryApi 实现），本页仅 preview=1 本地夹具；非预览进入按
// fail-closed 展示契约待接提示，不构造任何数据。可用/已使用/已过期三个 tab 对应
// coupon_instance.status 的 AVAILABLE/USED/EXPIRED（schema 事实）；FROZEN/RISK_FROZEN
// 呈现待裁决（CCR-C006-COUPON-POINTS-READ-001 D2），不在本页展示。只读：无领取、
// 无使用、无跳转下单等任何写路径或规则行为。
type Phase = 'loading' | 'ready' | 'blocked'

export default function CouponsPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isCouponPointsScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const [phase, setPhase] = useState<Phase>('loading')
  const [coupons, setCoupons] = useState<readonly CouponView[]>([])
  const [tab, setTab] = useState<CouponStatus>('AVAILABLE')
  const [notice, setNotice] = useState('')
  const mounted = useRef(true)
  const sequence = useRef(0)
  const previousRevision = useRef(revision)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--cpn-status-top': `${platformInfo.statusBarHeight || 0}px`, '--cpn-unit': `${unit}px` } as CSSProperties

  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setNotice('')
    if (!preview) { setPhase('blocked'); return }
    setPhase('loading')
    try {
      const data = await scope.run(undefined, () => new PreviewCouponPointsRepository(scenario).load())
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setCoupons(data.coupons); setPhase('ready')
    } catch {
      if (mounted.current && current === sequence.current && currentRevision === scope.revision) setPhase('ready')
    }
  }, [preview, scenario, scope])
  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false } }, [load])
  useEffect(() => {
    if (revision === previousRevision.current) return
    previousRevision.current = revision
    setCoupons([]); setPhase('loading'); void load()
  }, [revision, load])
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/consumer/pages/shell/index' })
  }
  const ready = phase === 'ready'
  const visible = couponsByStatus(coupons, tab)
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
      {ready && <View className='cpn-tabs' role='tablist'>
        {(Object.keys(statusTabLabels) as CouponStatus[]).map(key => <Button key={key}
          id={`cpn-tab-${key}`} ariaLabel={statusTabLabels[key]}
          className={`cpn-tab${tab === key ? ' is-active' : ''}`}
          onClick={() => setTab(key)}><Text>{statusTabLabels[key]}</Text></Button>)}
      </View>}
      {phase === 'loading' && <View className='cpn-state' role='status'><Text>正在读取优惠券…</Text></View>}
      {phase === 'blocked' && <View className='cpn-state' role='status'>
        <Text id='cpn-blocked'>优惠券查询契约尚未裁决接入（见 CCR-C006-COUPON-POINTS-READ-001），当前仅提供只读预览。</Text>
      </View>}
      {ready && visible.length === 0 && <View className='cpn-state'><Text id='cpn-empty'>暂无{statusTabLabels[tab]}优惠券。</Text></View>}
      {ready && visible.map(coupon => <Button key={coupon.couponId} id={`cpn-coupon-${coupon.couponId}`}
        className={`cpn-card${coupon.status !== 'AVAILABLE' ? ` is-${coupon.status.toLowerCase()}` : ''}`}
        ariaLabel={`${coupon.name}，${thresholdLabel(coupon)}，${statusTabLabels[coupon.status]}`}
        onClick={() => { void Taro.navigateTo({ url: `/consumer/pages/coupon-points/coupon-detail?${preview ? 'preview=1&' : ''}couponId=${encodeURIComponent(coupon.couponId)}` }).catch(() => setNotice('页面跳转失败，请重试')) }}>
        <View className='cpn-card-amount'>
          <Text className='cpn-card-amount-value'>¥{coupon.amountOff}</Text>
          <Text className='cpn-card-amount-threshold'>{thresholdLabel(coupon)}</Text>
        </View>
        <View className='cpn-card-main'>
          <View className='cpn-card-head'>
            <Text className='cpn-card-name'>{coupon.name}</Text>
            <Text className='cpn-card-type'>{coupon.typeLabel}</Text>
          </View>
          <Text className='cpn-card-scope'>{coupon.scopeSummary}</Text>
          <Text className='cpn-card-validity'>有效期至 {coupon.validTo.replace(/-/g, '.')}</Text>
        </View>
      </Button>)}
      {ready && <View className='cpn-preview-note'><Text>只读预览：本地样例数据，券/积分查询契约待裁决，暂无真实接口。</Text></View>}
      {notice && <Text id='cpn-notice' className='cpn-notice'>{notice}</Text>}
    </View>
  </ConsumerPageLayout>
}
