import { Button, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { ConsumerPageLayout } from '../../components/page-layout'
import { rewardPointsLabel, rewardStatusLabels, type RewardRowView, type TasksView } from '../../points-tasks/model'
import { RealPointsTaskRepository, isPointsTaskUnauthorized } from '../../points-tasks/repository'
import './points-tasks.css'

// 奖励领取面板页（58 号切片，设计源 129:7677，登记表 §4.12；原稿标题栏"拉新任务"系笔误，
// 实现按"奖励领取"）。三里程碑行（连签7天/邀请3位/完善资料）来自 GET tasks 服务端投影，
// CLAIMABLE 才可点领取（POST claims，api.write 幂等槽按 claimType 分槽）；CLAIMED 显已领取、
// NOT_ACHIEVED 显进度徽章（progress 如 "5/7"）。领取成功后刷新投影。读取失败失败关闭。
// V1 积分只赚取/扣回不消费；一次性里程碑领取后再点为服务端 409（页面提示后刷新）。
type Phase = 'loading' | 'ready' | 'expired' | 'load-error'

export default function PointsRewardsPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const repository = useRef(preview ? undefined : new RealPointsTaskRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  const [tasks, setTasks] = useState<TasksView | null>(null)
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState('')
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
    if (preview) { setPhase('ready'); return }
    if (!scope.current) { try { await consumerApi.restore() } catch { /* 未登录走下方引导 */ } }
    if (current !== sequence.current || currentRevision !== scope.revision) return
    if (!scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    setPhase('loading')
    try {
      const value = await scope.run(undefined, () => repository.current!.tasks())
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setTasks(value); setPhase('ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setPhase(isPointsTaskUnauthorized(error) ? 'expired' : 'load-error')
    }
  }, [preview, scope])
  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false } }, [load])
  useEffect(() => {
    if (revision === previousRevision.current) return
    previousRevision.current = revision
    setTasks(null); setPhase('loading'); void load()
  }, [revision, load])

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: '/consumer/pages/mine/index' })
  }
  async function claim(row: RewardRowView) {
    if (busy || row.status !== 'CLAIMABLE') return
    setBusy(row.rewardCode); setNotice('')
    try {
      const receipt = await scope.run(undefined, () => repository.current!.claimReward(row.rewardCode))
      if (!mounted.current) return
      setNotice(`领取成功，+${receipt.pointsGranted} 积分已入账。`)
      await load()
    } catch (error) {
      if (!mounted.current) return
      setNotice(error instanceof Error && error.message === 'PENDING_WRITE_CHANGED'
        ? '领取请求处理中，请稍候' : '当前不可领取，已刷新状态。')
      await load()
    } finally {
      if (mounted.current) setBusy('')
    }
  }
  const ready = phase === 'ready' && tasks !== null
  return <ConsumerPageLayout page='pointsPage' unit={unit} className='ptk-page'
    navigation={{ idPrefix: 'ptr', disabled: !ready, onSelect: () => {} }} style={style}>
    <View className='ptk-design'>
      <View className='ptk-status-area' />
      {phase === 'loading' && <View className='ptk-state' role='status'><Text>正在读取奖励…</Text></View>}
      {phase === 'expired' && <View className='ptk-state' role='status'>
        <Text id='ptr-login-hint'>登录后可以领取奖励。</Text>
        <Button id='ptr-login' className='ptk-state-action' onClick={() => { void Taro.switchTab({ url: '/consumer/pages/mine/index' }) }}>去登录</Button>
      </View>}
      {phase === 'load-error' && <View className='ptk-state' role='status'>
        <Text id='ptr-error'>奖励读取失败，请稍后重试。</Text>
        <Button id='ptr-retry' className='ptk-state-action' onClick={() => void load()}>重新加载</Button>
      </View>}
      {ready && <View className='ptk-veil'>
        <View className='ptk-panel'>
          <View className='ptk-panel-head'>
            <Text className='ptk-panel-title'>奖励领取</Text>
            <Button id='ptr-close' ariaLabel='关闭' className='ptk-panel-close' onClick={() => void back()}>
              <View className='ptk-close-glyph' />
            </Button>
          </View>
          <View className='ptk-rows'>
            {tasks.rewards.map(row => <View key={row.rewardCode} id={`ptr-reward-${row.rewardCode}`} className='ptk-row'>
              <View className='ptk-row-ico'><View className='ptk-row-ico-dot' /></View>
              <View className='ptk-row-main'>
                <Text className='ptk-row-title'>{row.title}</Text>
                <Text className='ptk-row-desc'>{row.description}</Text>
                <Text className='ptk-row-points'>{rewardPointsLabel(row.points)}</Text>
              </View>
              {row.status === 'CLAIMABLE'
                ? <Button className='ptk-claim' disabled={busy === row.rewardCode || preview}
                  id={`ptr-claim-${row.rewardCode}`} onClick={() => void claim(row)}>
                    <Text>{busy === row.rewardCode ? '领取中…' : rewardStatusLabels.CLAIMABLE}</Text>
                  </Button>
                : row.status === 'CLAIMED'
                  ? <View className='ptk-badge'><Text>{rewardStatusLabels.CLAIMED}</Text></View>
                  : <View className='ptk-badge is-progress'><Text>{row.progress}</Text></View>}
            </View>)}
          </View>
        </View>
      </View>}
      {notice && <View className='ptk-notice' role='status'><Text id='ptr-notice'>{notice}</Text></View>}
    </View>
  </ConsumerPageLayout>
}
