import { Button, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { ConsumerPageLayout } from '../../components/page-layout'
import { inviteShareText, taskRowStatusLabels, type TasksView } from '../../points-tasks/model'
import { RealPointsTaskRepository, isPointsTaskUnauthorized } from '../../points-tasks/repository'
import './points-tasks.css'

// 拉新任务面板页（58 号切片，设计源 129:5854，登记表 §4.12）。面板式：暗化背景 + 底部白
// 面板（标题+关闭）；任务行四条来自 GET tasks 服务端投影——COMMUNITY_POST 恒 NOT_OPEN，
// 文案"暂未开放"（V1 无社区积分规则，M2）。本人邀请码确定性可逆（R1），V1 无邀请落地页，
// 分享动作落地为复制口令文本（偏差 4：不切原稿分享卡片素材）。读取失败失败关闭。
// 积分只赚取/扣回不消费；领取动作统一走奖励页（A2：除签到外显式领取）。
type Phase = 'loading' | 'ready' | 'expired' | 'load-error'

export default function PointsTasksPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const repository = useRef(preview ? undefined : new RealPointsTaskRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  const [tasks, setTasks] = useState<TasksView | null>(null)
  const [notice, setNotice] = useState('')
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
  async function copyInvite() {
    if (!tasks) return
    const text = inviteShareText(tasks.inviteCode, tasks.invite.perInviteePoints)
    try {
      await Taro.setClipboardData({ data: text })
      setNotice('邀请口令已复制，去发给好友吧。')
    } catch {
      setNotice(`复制失败，请手动记录邀请码 ${tasks.inviteCode}`)
    }
  }
  const ready = phase === 'ready' && tasks !== null
  return <ConsumerPageLayout page='pointsPage' unit={unit} className='ptk-page'
    navigation={{ idPrefix: 'ptt', disabled: !ready, onSelect: () => {} }} style={style}>
    <View className='ptk-design'>
      <View className='ptk-status-area' />
      {phase === 'loading' && <View className='ptk-state' role='status'><Text>正在读取任务…</Text></View>}
      {phase === 'expired' && <View className='ptk-state' role='status'>
        <Text id='ptt-login-hint'>登录后可以查看拉新任务。</Text>
        <Button id='ptt-login' className='ptk-state-action' onClick={() => { void Taro.switchTab({ url: '/consumer/pages/mine/index' }) }}>去登录</Button>
      </View>}
      {phase === 'load-error' && <View className='ptk-state' role='status'>
        <Text id='ptt-error'>任务读取失败，请稍后重试。</Text>
        <Button id='ptt-retry' className='ptk-state-action' onClick={() => void load()}>重新加载</Button>
      </View>}
      {ready && <View className='ptk-veil'>
        <View className='ptk-panel'>
          <View className='ptk-panel-head'>
            <Text className='ptk-panel-title'>拉新任务</Text>
            <Button id='ptt-close' ariaLabel='关闭' className='ptk-panel-close' onClick={() => void back()}>
              <View className='ptk-close-glyph' />
            </Button>
          </View>
          <View className='ptk-rows'>
            {tasks.tasks.map(row => <View key={row.taskCode} id={`ptt-task-${row.taskCode}`} className='ptk-row'>
              <View className='ptk-row-ico'><View className='ptk-row-ico-dot' /></View>
              <View className='ptk-row-main'>
                <Text className='ptk-row-title'>{row.title}</Text>
                <Text className='ptk-row-desc'>{row.description}</Text>
                <Text className='ptk-row-points'>{row.points === '0' ? '暂未开放' : `${row.points} 积分`}</Text>
              </View>
              <View className={`ptk-badge${row.status === 'DONE_TODAY' || row.status === 'CLAIMED' ? '' : ' is-progress'}`}>
                <Text>{taskRowStatusLabels[row.status]}</Text>
              </View>
            </View>)}
          </View>
          <View className='ptk-invite' id='ptt-invite'>
            <Text className='ptk-invite-label'>我的邀请码（好友注册后输入，完成首单你我均得积分）</Text>
            <View className='ptk-invite-line'>
              <Text className='ptk-invite-code'>{tasks.inviteCode}</Text>
              <Button id='ptt-copy' className='ptk-invite-copy' disabled={preview} onClick={() => void copyInvite()}>
                <Text>复制邀请口令</Text>
              </Button>
            </View>
          </View>
        </View>
      </View>}
      {notice && <View className='ptk-notice' role='status'><Text id='ptt-notice'>{notice}</Text></View>}
    </View>
  </ConsumerPageLayout>
}
