import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { switchConsumerTab } from '../../components/navigation/switch'
import { ConsumerPageLayout } from '../../components/page-layout'
import {
  deltaLabel, formatLedgerTime, isCouponPointsScenario, pointsBizTypeLabels,
  PreviewCouponPointsRepository, type PointsLedgerView,
} from '../../coupon-points/model'
import { RealCouponPointsRepository, isCouponPointsUnauthorized } from '../../coupon-points/repository'
import back from '../../assets/profile/back.png'
import './coupon-points.css'

// C-006 切片：我的积分（只读）。设计源 129:8174 的"积分明细"区块（余额样例 1280、
// 流水行"名称 +delta 时间"）；签到日历/立即签到/拉新任务/奖励领取/收藏足迹/积分商城
// 均不在本切片（写路径或 V1 范围外，见 INVENTORY §3）。积分查询契约已裁决并实现（PR#112，
// 10 号 §3.15.2 + 11 号 OpenAPI）：真实模式并读余额与流水分页（固定 created_at DESC, id DESC），
// 两读任一失败即整页失败关闭（不渲染半份真实数据）；preview=1 仍走本地夹具。
// V1 积分只有赚取与退款扣回，无消费、兑换、抵现（AGENTS 硬规则）。401/未登录引导去登录。
type Phase = 'loading' | 'ready' | 'expired' | 'load-error'
const PAGE_SIZE = 20

export default function PointsPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario = preview && isCouponPointsScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const repository = useRef(preview ? undefined : new RealCouponPointsRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  const [balance, setBalance] = useState('0')
  const [ledger, setLedger] = useState<readonly PointsLedgerView[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loadingMore, setLoadingMore] = useState(false)
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
    if (preview) {
      setPhase('loading')
      try {
        const data = await scope.run(undefined, () => new PreviewCouponPointsRepository(scenario).load())
        if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
        setBalance(data.balance.balance); setLedger(data.ledger); setPhase('ready')
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
      // 余额与明细两读要么都成就，要么整页失败关闭（不渲染半份真实数据）。
      const { balanceValue, ledgerValue } = await scope.run(undefined, async () => {
        const [balanceRead, ledgerRead] = await Promise.all([
          repository.current!.balance(), repository.current!.ledger(1, PAGE_SIZE),
        ])
        return { balanceValue: balanceRead, ledgerValue: ledgerRead }
      })
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setBalance(balanceValue.balance); setLedger(ledgerValue.items)
      setTotal(ledgerValue.total); setPage(1); setPhase('ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setPhase(isCouponPointsUnauthorized(error) ? 'expired' : 'load-error')
    }
  }, [preview, scenario, scope])
  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false } }, [load])
  useEffect(() => {
    if (revision === previousRevision.current) return
    previousRevision.current = revision
    setLedger([]); setTotal(0); setPage(1); setPhase('loading'); void load()
  }, [revision, load])
  async function loadMore() {
    if (loadingMore || preview || phase !== 'ready' || ledger.length >= total) return
    setLoadingMore(true)
    const current = sequence.current
    const currentRevision = scope.revision
    try {
      const result = await scope.run(undefined, () => repository.current!.ledger(page + 1, PAGE_SIZE))
      if (current !== sequence.current || currentRevision !== scope.revision) return
      const known = new Set(ledger.map(entry => entry.ledgerId))
      setLedger([...ledger, ...result.items.filter(entry => !known.has(entry.ledgerId))])
      setPage(result.page); setTotal(result.total)
    } catch { setNotice('加载更多失败，请重试。') } finally { setLoadingMore(false) }
  }
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: '/consumer/pages/mine/index' })
  }
  const ready = phase === 'ready'
  return <ConsumerPageLayout page='pointsPage' unit={unit} className='cpn-page'
    navigation={{ idPrefix: 'cpt', disabled: !ready, onSelect: key => void switchConsumerTab(key) }}
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
      {phase === 'expired' && <View className='cpn-state' role='status'>
        <Text id='cpt-login-hint'>登录后可查看我的积分。</Text>
        <Button id='cpt-login' className='cpn-state-action' onClick={() => { void Taro.switchTab({ url: '/consumer/pages/mine/index' }) }}>去登录</Button>
      </View>}
      {phase === 'load-error' && <View className='cpn-state' role='status'>
        <Text id='cpt-error'>积分读取失败，请稍后重试。</Text>
        <Button id='cpt-retry' className='cpn-state-action' onClick={() => void load()}>重新加载</Button>
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
      {ready && !preview && ledger.length < total && <Button id='cpt-load-more' className='cpn-more' disabled={loadingMore} onClick={() => void loadMore()}>
        {loadingMore ? '正在加载…' : '加载更多'}
      </Button>}
      {ready && <View className='cpn-preview-note'><Text>{preview ? '只读预览：本地样例数据，仅用于设计验收，不发起真实请求；签到/邀请/任务等赚取行为不在本页提供。' : '页面数据：真实接口（只读查询）；签到/邀请/任务等赚取行为不在本页提供。'}</Text></View>}
      {notice && <Text id='cpt-notice' className='cpn-notice'>{notice}</Text>}
    </View>
  </ConsumerPageLayout>
}
