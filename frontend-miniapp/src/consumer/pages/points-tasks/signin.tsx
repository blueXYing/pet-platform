import { Button, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { ConsumerPageLayout } from '../../components/page-layout'
import { weekCells, type SignInStatusView } from '../../points-tasks/model'
import { RealPointsTaskRepository, isPointsTaskUnauthorized } from '../../points-tasks/repository'
import './points-tasks.css'

// 签到日历页（58 号切片，设计源 690:11773，登记表 §4.12）。周条为服务端近 7 自然日真实
// 签到事实（S1/S2 保守口径）；连签数与面额接 GET sign-in/status；立即签到走 POST sign-in
// （api.write 幂等槽，X-Request-Id 由 ConsumerApi 分配）。当日已签按钮置灰；签到成功弹出
// 原稿 151x30 胶囊 toast。月历区无契约事实不虚构（登记表偏差 2）。三快捷入口：拉新任务/
// 奖励领取进本分包两页，积分明细沿既有积分页。读取失败失败关闭；401 引导去登录。
// V1 积分只赚取/扣回不消费（AGENTS 硬规则），本页无任何兑换动作。
type Phase = 'loading' | 'ready' | 'expired' | 'load-error'

export default function SignInPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const repository = useRef(preview ? undefined : new RealPointsTaskRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  const [status, setStatus] = useState<SignInStatusView | null>(null)
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [toast, setToast] = useState('')
  const mounted = useRef(true)
  const sequence = useRef(0)
  const previousRevision = useRef(revision)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--ptk-status-top': `${platformInfo.statusBarHeight || 0}px`, '--ptk-unit': `${unit}px` } as CSSProperties

  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setNotice('')
    if (preview) {
      setPhase('ready')
      return
    }
    if (!scope.current) { try { await consumerApi.restore() } catch { /* 未登录走下方引导 */ } }
    if (current !== sequence.current || currentRevision !== scope.revision) return
    if (!scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    setPhase('loading')
    try {
      const value = await scope.run(undefined, () => repository.current!.signInStatus())
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setStatus(value); setPhase('ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setPhase(isPointsTaskUnauthorized(error) ? 'expired' : 'load-error')
    }
  }, [preview, scope])
  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false } }, [load])
  useEffect(() => {
    if (revision === previousRevision.current) return
    previousRevision.current = revision
    setStatus(null); setPhase('loading'); void load()
  }, [revision, load])

  async function signIn() {
    if (busy || phase !== 'ready' || status?.signedToday) return
    setBusy(true); setNotice(''); setToast('')
    try {
      await scope.run(undefined, () => repository.current!.signIn())
      if (!mounted.current) return
      setToast('签到成功')
      setTimeout(() => { if (mounted.current) setToast('') }, 2200)
      await load()
    } catch (error) {
      if (!mounted.current) return
      setNotice(error instanceof Error && error.message === 'PENDING_WRITE_CHANGED'
        ? '签到请求处理中，请稍候' : '签到失败，请稍后重试。')
    } finally {
      if (mounted.current) setBusy(false)
    }
  }
  async function open(url: string) {
    if (url) await Taro.navigateTo({ url })
    else await Taro.showToast({ title: '功能暂未开放', icon: 'none' })
  }
  const ready = phase === 'ready' && status !== null
  const streak = ready ? status.streak : 0
  const dailyPoints = ready ? status.dailyPoints : '5'
  const signedToday = ready && status.signedToday
  return <ConsumerPageLayout page='pointsPage' unit={unit} className='ptk-page'
    navigation={preview ? { idPrefix: 'ptk', disabled: !ready, onSelect: () => {} } : undefined} style={style}>
    <View className='ptk-design'>
      <View className='ptk-status-area' />
      {phase === 'loading' && <View className='ptk-state' role='status'><Text>正在读取签到状态…</Text></View>}
      {phase === 'expired' && <View className='ptk-state' role='status'>
        <Text id='ptk-login-hint'>登录后可以每日签到赚积分。</Text>
        <Button id='ptk-login' className='ptk-state-action' onClick={() => { void Taro.switchTab({ url: '/consumer/pages/mine/index' }) }}>去登录</Button>
      </View>}
      {phase === 'load-error' && <View className='ptk-state' role='status'>
        <Text id='ptk-error'>签到状态读取失败，请稍后重试。</Text>
        <Button id='ptk-retry' className='ptk-state-action' onClick={() => void load()}>重新加载</Button>
      </View>}
      {ready && <View className='ptk-signin-card' id='ptk-card'>
        <View className='ptk-signin-head'>
          <View className='ptk-signin-title'><View className='ptk-signin-cal' /><Text>每日签到</Text></View>
          <View className='ptk-signin-streak'>
            <Text>连续签到 </Text>
            <Text className='ptk-signin-streak-num'>{streak}</Text>
            <Text> 天</Text>
          </View>
        </View>
        <View className='ptk-signin-week'>
          {weekCells(status.week).map(cell => <View key={cell.day + cell.label}
            className={`ptk-signin-day${cell.signed ? ' is-signed' : ''}`}>
            <View className='ptk-signin-day-circle'><Text>{cell.label}</Text></View>
            <Text className='ptk-signin-day-date'>{cell.day}</Text>
          </View>)}
        </View>
        <Button id='ptk-sign-in' className={`ptk-signin-action${signedToday ? ' is-done' : ''}`}
          disabled={busy || signedToday || preview} onClick={() => void signIn()}>
          <Text>{signedToday ? '今日已签到' : `立即签到 · 得 ${dailyPoints} 积分`}</Text>
        </Button>
      </View>}
      {ready && <View className='ptk-shortcuts'>
        <Button id='ptk-open-tasks' className='ptk-shortcut' hoverClass='none'
          onClick={() => { void open('/consumer/pages/points-tasks/tasks') }}>
          <View className='ptk-shortcut-ico'><View className='ptk-shortcut-ico-dot' /></View>
          <Text className='ptk-shortcut-label'>拉新任务</Text>
        </Button>
        <Button id='ptk-open-rewards' className='ptk-shortcut' hoverClass='none'
          onClick={() => { void open('/consumer/pages/points-tasks/rewards') }}>
          <View className='ptk-shortcut-ico'><View className='ptk-shortcut-ico-dot' /></View>
          <Text className='ptk-shortcut-label'>奖励领取</Text>
        </Button>
        <Button id='ptk-open-ledger' className='ptk-shortcut' hoverClass='none'
          onClick={() => { void Taro.navigateTo({ url: '/consumer/pages/coupon-points/points' }) }}>
          <View className='ptk-shortcut-ico'><View className='ptk-shortcut-ico-dot' /></View>
          <Text className='ptk-shortcut-label'>积分明细</Text>
        </Button>
      </View>}
      {notice && <View className='ptk-notice' role='status'><Text id='ptk-notice'>{notice}</Text></View>}
      {toast && <View className='ptk-toast' role='status'><Text>{toast}</Text></View>}
    </View>
  </ConsumerPageLayout>
}
