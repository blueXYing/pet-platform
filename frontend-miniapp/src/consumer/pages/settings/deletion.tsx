import { Button, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealSettingsRepository } from '../../settings/repository'
import { SubpageHeader } from '../../components/navigation/subpage-header'
import {
  blockerFallbackLabels,
  type CancelReceipt, type DeletionPrecheck, type DeletionReceipt, type DeletionStatusView,
} from '../../settings/model'
import './deletion.css'

// 注销账号页（契约 66 §3/§4 v0.2 冷静期 / PRD 5.1.29；CCR-USR-DELETION-001 v0.2：
// 默认 7 天可配冷静期）。流程：进入并行拉前置校验+冷静期状态 → 申请中（PENDING）渲染
// 冷静期横幅（到期时刻+撤销按钮，账号期间可正常使用，撤销=请求单 CAS 回 CANCELED）；
// 无申请时按前置校验渲染阻塞明细或影响确认 → 勾选确认 → 提交（幂等槽 settings:deletion）
// → 回执展示冷静期信息。到期由平台 durable 任务执行注销（账号届时才置 CANCELED、登录失效）。
// 开关关闭期（默认）请求 404/503 失败关闭，不降级不伪造成功。
type Phase = 'loading' | 'ready' | 'pending' | 'load-error' | 'submitting' | 'canceling' | 'done'
const MINE_TAB_URL = '/consumer/pages/mine/index'

const deletionImpacts = [
  '提交后进入 7 天冷静期：期间账号可正常登录使用，可随时回到本页撤销。',
  '冷静期到期时平台自动执行注销：所有登录会话失效，无法再使用本微信登录该账号。',
  '冷静期内再次出现未完结订单、处理中售后或名下商家未完结业务时，到期注销会自动作废。',
  '注销后账号数据按平台保留策略处理；冷静期内数据不做任何删除或改动。',
] as const

function formatIso(iso: string): string {
  const parsed = new Date(iso)
  if (Number.isNaN(parsed.getTime())) return iso
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
}

export default function AccountDeletionPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = {
    '--st-top': `${platformInfo.statusBarHeight || 0}px`,
    '--st-unit': `${unit}px`,
  } as CSSProperties
  const [repository] = useState(() => new RealSettingsRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  const [precheck, setPrecheck] = useState<DeletionPrecheck | null>(null)
  const [pending, setPending] = useState<DeletionStatusView | null>(null)
  const [acknowledged, setAcknowledged] = useState(false)
  const [receipt, setReceipt] = useState<DeletionReceipt | null>(null)
  const [cancelReceipt, setCancelReceipt] = useState<CancelReceipt | null>(null)
  const [notice, setNotice] = useState('')
  const mounted = useRef(true)
  useEffect(() => () => { mounted.current = false }, [])
  useDidShow(() => { void load() })

  async function load() {
    setPhase('loading')
    setNotice('')
    try {
      if (preview) {
        const scenario = route.params.scenario
        const demo: DeletionPrecheck = scenario === 'blocked'
          ? { blocked: true, blockers: [{ code: 'USER_ORDERS_UNSETTLED', label: blockerFallbackLabels.USER_ORDERS_UNSETTLED, detail: '您有待支付、待确认、待服务、退款确认中或退款中的订单，完结后才能注销' }] }
          : { blocked: false, blockers: [] }
        if (!mounted.current) return
        setPrecheck(demo)
        setPending(scenario === 'pending'
          ? { status: 'PENDING', requestedAt: new Date().toISOString(), effectiveAt: new Date(Date.now() + 7 * 86400000).toISOString(), cooldownDays: 7 }
          : { status: 'NONE' })
        setPhase(scenario === 'pending' ? 'pending' : 'ready')
        return
      }
      const [precheckResult, statusResult] = await Promise.all([
        repository.precheck(), repository.deletionStatus(),
      ])
      if (!mounted.current) return
      setPrecheck(precheckResult)
      setPending(statusResult)
      setPhase(statusResult.status === 'PENDING' ? 'pending' : 'ready')
    } catch {
      if (!mounted.current) return
      setPhase('load-error')
    }
  }

  async function submit() {
    if (phase !== 'ready' || precheck === null || precheck.blocked || !acknowledged) return
    setPhase('submitting')
    setNotice('')
    try {
      if (preview) {
        if (!mounted.current) return
        setReceipt({
          userId: 'preview', status: 'PENDING', requestedAt: new Date().toISOString(),
          effectiveAt: new Date(Date.now() + 7 * 86400000).toISOString(), cooldownDays: 7,
        })
        setPhase('done')
        return
      }
      const result = await repository.deleteAccount()
      if (!mounted.current) return
      setReceipt(result)
      setPhase('done')
    } catch {
      if (!mounted.current) return
      setPhase('ready')
      setNotice('注销申请未完成，请稍后重试；若提示存在未完结业务，请先完结后再来。')
    }
  }

  async function cancelPending() {
    if (phase !== 'pending') return
    setPhase('canceling')
    setNotice('')
    try {
      if (preview) {
        if (!mounted.current) return
        setCancelReceipt({ userId: 'preview', status: 'CANCELED', canceledAt: new Date().toISOString() })
        setPhase('done')
        return
      }
      const result = await repository.cancelDeletion()
      if (!mounted.current) return
      setCancelReceipt(result)
      setPhase('done')
    } catch {
      if (!mounted.current) return
      setPhase('pending')
      setNotice('撤销未完成，请稍后重试；若注销已到期执行，本页会显示账号已注销。')
    }
  }

  async function finish() {
    try { await Taro.switchTab({ url: MINE_TAB_URL }) } catch { /* 已在 tab 时忽略 */ }
  }

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: MINE_TAB_URL })
  }

  return <View className='cpn-page st-page st-subpage' style={style}>
    <SubpageHeader title='注销账号' backId='del-back' fixed={false} height={46} onBack={() => void back()} />
    <ScrollView className='del-body' scrollY enhanced showScrollbar={false}>
      {phase === 'loading' && <View className='del-state' role='status'><Text>正在检查注销条件…</Text></View>}
      {phase === 'load-error' && <View className='del-state'>
        <Text>注销条件检查失败，请稍后重试。</Text>
        <Button id='del-retry' className='del-state-btn' hoverClass='none' onClick={() => void load()}>重试</Button>
      </View>}
      {phase === 'done' && receipt !== null && <View className='del-card'>
        <Text className='del-card-title'>注销申请已提交</Text>
        <Text className='del-impact'>冷静期 {receipt.cooldownDays} 天，到期时间：{formatIso(receipt.effectiveAt)}。</Text>
        <Text className='del-impact'>冷静期内账号可正常使用；如改变主意，请回到本页撤销。</Text>
        <Button id='del-finish' className='del-primary' hoverClass='none' onClick={() => void finish()}>完成</Button>
      </View>}
      {phase === 'done' && receipt === null && cancelReceipt !== null && <View className='del-card'>
        <Text className='del-card-title'>已撤销注销申请</Text>
        <Text className='del-impact'>你的账号继续保持正常使用，本次注销申请已作废。</Text>
        <Button id='del-finish-cancel' className='del-primary' hoverClass='none' onClick={() => void finish()}>完成</Button>
      </View>}
      {(phase === 'pending' || phase === 'canceling') && pending !== null
        && pending.status === 'PENDING' && <View className='del-card del-card-pending'>
        <Text className='del-card-title'>注销申请处理中（冷静期 {pending.cooldownDays} 天）</Text>
        <Text className='del-impact'>申请时间：{formatIso(pending.requestedAt ?? '')}</Text>
        <Text className='del-impact'>到期执行时间：{formatIso(pending.effectiveAt ?? '')}</Text>
        <Text className='del-impact'>冷静期内你的账号可正常登录与使用；到期平台将自动执行注销并使登录失效。改变主意请在到期前撤销。</Text>
        <Button id='del-cancel' className='del-primary' hoverClass='none'
          disabled={phase === 'canceling'} onClick={() => void cancelPending()}>
          {phase === 'canceling' ? '正在撤销…' : '撤销注销申请'}
        </Button>
      </View>}
      {(phase === 'ready' || phase === 'submitting') && precheck !== null
        && pending !== null && pending.status !== 'PENDING' && <>
        {precheck.blocked && <View className='del-card del-card-blocked'>
          <Text className='del-card-title'>暂时无法注销</Text>
          {precheck.blockers.map(blocker => <View key={blocker.code} className='del-blocker' id={`del-blocker-${blocker.code}`}>
            <Text className='del-blocker-label'>{blocker.label}</Text>
            <Text className='del-blocker-detail'>{blocker.detail}</Text>
          </View>)}
          <Text className='del-impact'>请先完结上述业务，再回到本页发起注销。</Text>
        </View>}
        {!precheck.blocked && <>
          <View className='del-card'>
            <Text className='del-card-title'>注销前请知晓</Text>
            {deletionImpacts.map((impact, index) => (
              <Text key={index} className='del-impact' id={`del-impact-${index}`}>{impact}</Text>
            ))}
          </View>
          <Button id='del-ack' className={`del-ack${acknowledged ? ' del-ack-on' : ''}`} hoverClass='none'
            ariaLabel='我已知晓并确认上述影响' onClick={() => setAcknowledged(value => !value)}>
            <View className='del-ack-box'>{acknowledged ? <View className='del-ack-tick' /> : null}</View>
            <Text className='del-ack-text'>我已阅读并确认上述影响，申请注销账号</Text>
          </Button>
          <Button id='del-submit' className='del-primary' hoverClass='none' disabled={!acknowledged || phase === 'submitting'}
            onClick={() => void submit()}>
            {phase === 'submitting' ? '正在提交…' : '确认注销'}
          </Button>
        </>}
        {notice !== '' && <Text id='del-notice' className='st-notice'>{notice}</Text>}
      </>}
      {(phase === 'pending' || phase === 'canceling') && notice !== ''
        && <Text id='del-notice-pending' className='st-notice'>{notice}</Text>}
      {preview && <Text className='del-preview-note'>预览模式：本地样例数据，仅用于设计验收，不发起真实请求。</Text>}
    </ScrollView>
  </View>
}
