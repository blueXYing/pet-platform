import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { ConsumerPageLayout } from '../../components/page-layout'
import {
  deltaLabel, formatLedgerTime, isCouponPointsScenario, pointsBizTypeLabels,
  PreviewCouponPointsRepository, type PointsLedgerView,
} from '../../coupon-points/model'
import back from '../../assets/profile/back.png'
import './coupon-points.css'

// C-006 切片：我的积分（只读）。设计源 129:8174 的"积分明细"区块（余额样例 1280、
// 流水行"名称 +delta 时间"）；签到日历/立即签到/拉新任务/奖励领取/收藏足迹/积分商城
// 均不在本切片（写路径或 V1 范围外，见 INVENTORY §3）。V1 积分只有赚取与退款扣回，
// 无消费、兑换、抵现（AGENTS 硬规则），页面只呈现余额与流水两类既有事实。无可用 C 端
// 查询契约（pet-points-api 为包骨架），仅 preview=1 本地夹具，非预览 fail-closed。
type Phase = 'loading' | 'ready' | 'blocked'

export default function PointsPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isCouponPointsScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const [phase, setPhase] = useState<Phase>('loading')
  const [balance, setBalance] = useState('0')
  const [ledger, setLedger] = useState<readonly PointsLedgerView[]>([])
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
      setBalance(data.balance.balance); setLedger(data.ledger); setPhase('ready')
    } catch {
      if (mounted.current && current === sequence.current && currentRevision === scope.revision) setPhase('ready')
    }
  }, [preview, scenario, scope])
  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false } }, [load])
  useEffect(() => {
    if (revision === previousRevision.current) return
    previousRevision.current = revision
    setLedger([]); setPhase('loading'); void load()
  }, [revision, load])
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/consumer/pages/shell/index' })
  }
  const ready = phase === 'ready'
  return <ConsumerPageLayout page='pointsPage' unit={unit} className='cpn-page'
    navigation={{ idPrefix: 'cpt', disabled: !ready, onSelect: key => setNotice(navigationUnavailableMessage(key)) }}
    style={style}>
    <View className='cpn-design'>
      <View className='cpn-status-area' />
      <View className='cpn-nav'>
        <Button id='cpt-back' ariaLabel='返回' className='cpn-nav-back' onClick={() => void goBack()}>
          <Image src={back} className='cpn-nav-back-icon' />
        </Button>
        <Text className='cpn-nav-title'>我的积分</Text>
      </View>
      {phase === 'loading' && <View className='cpn-state' role='status'><Text>正在读取积分…</Text></View>}
      {phase === 'blocked' && <View className='cpn-state' role='status'>
        <Text id='cpt-blocked'>积分查询契约尚未裁决接入（见 CCR-C006-COUPON-POINTS-READ-001），当前仅提供只读预览。</Text>
      </View>}
      {ready && <View className='cpn-balance-card' id='cpt-balance'>
        <Text className='cpn-balance-label'>积分余额</Text>
        <Text className='cpn-balance-value'>{balance}</Text>
        <Text className='cpn-balance-note'>积分仅通过赚取获得，退款时按规则扣回；V1 不支持积分消费、兑换或抵现。</Text>
      </View>}
      {ready && <View className='cpn-ledger-head'><Text className='cpn-ledger-title'>积分明细</Text></View>}
      {ready && ledger.length === 0 && <View className='cpn-state'><Text id='cpt-empty'>暂无积分明细。</Text></View>}
      {ready && ledger.map(entry => <View key={entry.ledgerId} id={`cpt-ledger-${entry.ledgerId}`}
        className='cpn-ledger-row' ariaLabel={`${pointsBizTypeLabels[entry.bizType]}，${deltaLabel(entry)}积分`}>
        <View className='cpn-ledger-main'>
          <Text className='cpn-ledger-name'>{pointsBizTypeLabels[entry.bizType]}</Text>
          <Text className='cpn-ledger-time'>{formatLedgerTime(new Date(), entry.createdAt)}</Text>
        </View>
        <Text className={`cpn-ledger-delta${entry.delta.startsWith('-') ? ' is-negative' : ''}`}>{deltaLabel(entry)}</Text>
      </View>)}
      {ready && <View className='cpn-preview-note'><Text>只读预览：本地样例数据，积分查询契约待裁决；签到/邀请/任务等赚取行为不在本页提供。</Text></View>}
      {notice && <Text id='cpt-notice' className='cpn-notice'>{notice}</Text>}
    </View>
  </ConsumerPageLayout>
}
