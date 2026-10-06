import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { ConsumerPageLayout } from '../../components/page-layout'
import {
  isCouponPointsScenario, statusTabLabels, thresholdLabel,
  PreviewCouponPointsRepository, type CouponView,
} from '../../coupon-points/model'
import { RealCouponPointsRepository, isCouponNotFound, isCouponPointsUnauthorized } from '../../coupon-points/repository'
import back from '../../assets/profile/back.png'
import './coupon-points.css'

// C-006 切片：优惠券详情（只读）。登记表内无"优惠券详情"原稿（仅 128:2078 列表页），
// 按 20 号登记表 §4 口径沿 C 端现行规范实现并登记设计缺稿；字段与列表页同一投影
// （10 号 §3.15.1：单券同投影）。真实模式按编号调 GET /c/coupons/{couponId}：非本人券、
// 不存在券与冻结态券为同一 404（防枚举，D2），页面呈现"未找到该优惠券"，不区分成因；
// 401/未登录引导去登录；其余失败（含路由未挂载）失败关闭，不渲染任何数据。只读，无使用/
// 转赠/立即下单等任何动作入口。
type Phase = 'loading' | 'ready' | 'missing' | 'expired' | 'load-error'

function formatUsedAt(usedAt: string): string {
  return usedAt.replace(/-/g, '.').replace('T', ' ').slice(0, 16)
}

export default function CouponDetailPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isCouponPointsScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const repository = useRef(preview ? undefined : new RealCouponPointsRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  const [coupon, setCoupon] = useState<CouponView | null>(null)
  const mounted = useRef(true)
  const previousRevision = useRef(revision)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--cpn-status-top': `${platformInfo.statusBarHeight || 0}px`, '--cpn-unit': `${unit}px` } as CSSProperties

  const load = useCallback(async () => {
    setPhase('loading')
    if (preview) {
      try {
        const data = await scope.run(undefined, () => new PreviewCouponPointsRepository(scenario).load())
        if (!mounted.current) return
        const found = data.coupons.find(entry => entry.couponId === (route.params.couponId || '')) || null
        setCoupon(found); setPhase(found ? 'ready' : 'missing')
      } catch {
        if (mounted.current) setPhase('missing')
      }
      return
    }
    // 真实模式：先补一次会话校验，避免 restore 在途时误报未登录（aftersale 先例）。
    if (!scope.current) { try { await consumerApi.restore() } catch { /* 未登录，走下方登录引导 */ } }
    if (!mounted.current) return
    if (!scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    try {
      const found = await scope.run(undefined, () => repository.current!.coupon(route.params.couponId || ''))
      if (!mounted.current) return
      setCoupon(found); setPhase('ready')
    } catch (error) {
      if (!mounted.current) return
      if (isCouponPointsUnauthorized(error)) setPhase('expired')
      else if (isCouponNotFound(error)) { setCoupon(null); setPhase('missing') }
      else setPhase('load-error')
    }
  }, [preview, route.params.couponId, scenario, scope])
  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false } }, [load])
  useEffect(() => {
    if (revision === previousRevision.current) return
    previousRevision.current = revision
    setCoupon(null); void load()
  }, [revision, load])
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/consumer/pages/shell/index' })
  }
  return <ConsumerPageLayout page='couponDetail' unit={unit} className='cpn-page' style={style}>
    <View className='cpn-design'>
      <View className='cpn-status-area' />
      <View className='cpn-nav'>
        <Button id='cpnd-back' ariaLabel='返回' className='cpn-nav-back' onClick={() => void goBack()}>
          <Image src={back} className='cpn-nav-back-icon' />
        </Button>
        <Text className='cpn-nav-title'>优惠券详情</Text>
      </View>
      {phase === 'loading' && <View className='cpn-state' role='status'><Text>正在读取优惠券…</Text></View>}
      {phase === 'expired' && <View className='cpn-state' role='status'>
        <Text id='cpnd-login-hint'>登录后可查看我的优惠券。</Text>
        <Button id='cpnd-login' className='cpn-state-action' onClick={() => { void Taro.redirectTo({ url: '/consumer/pages/shell/index' }) }}>去登录</Button>
      </View>}
      {phase === 'load-error' && <View className='cpn-state' role='status'>
        <Text id='cpnd-error'>优惠券读取失败，请稍后重试。</Text>
        <Button id='cpnd-retry' className='cpn-state-action' onClick={() => void load()}>重新加载</Button>
      </View>}
      {phase === 'missing' && <View className='cpn-state'><Text id='cpnd-missing'>未找到该优惠券。</Text></View>}
      {phase === 'ready' && coupon && <View className='cpn-detail-body'>
        <View className={`cpn-card is-detail${coupon.status !== 'AVAILABLE' ? ` is-${coupon.status.toLowerCase()}` : ''}`}>
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
        </View>
        <View className='cpn-detail-facts' id='cpnd-facts'>
          <View className='cpn-fact-row'><Text className='cpn-fact-label'>状态</Text><Text className='cpn-fact-value'>{statusTabLabels[coupon.status]}</Text></View>
          <View className='cpn-fact-row'><Text className='cpn-fact-label'>适用范围</Text><Text className='cpn-fact-value'>{coupon.scopeSummary === null ? '—' : coupon.scopeSummary}</Text></View>
          <View className='cpn-fact-row'><Text className='cpn-fact-label'>有效期至</Text><Text className='cpn-fact-value'>{coupon.validTo.replace(/-/g, '.')}</Text></View>
          {coupon.usedAt !== null && <View className='cpn-fact-row'><Text className='cpn-fact-label'>使用时间</Text><Text className='cpn-fact-value'>{formatUsedAt(coupon.usedAt)}</Text></View>}
        </View>
        <View className='cpn-preview-note'><Text>{preview ? '只读预览：本地样例数据，仅用于设计验收，不发起真实请求。' : '只读详情：无使用规则与动作入口；券状态与使用行为由订单/核销域呈现。'}</Text></View>
      </View>}
    </View>
  </ConsumerPageLayout>
}
