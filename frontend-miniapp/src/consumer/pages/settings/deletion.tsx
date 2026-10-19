import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealSettingsRepository } from '../../settings/repository'
import { blockerFallbackLabels, type DeletionPrecheck, type DeletionReceipt } from '../../settings/model'
import backIcon from '../../assets/profile/back.png'
import './deletion.css'

// 注销账号页（契约 66 §3/§4 / PRD 5.1.29；CCR-USR-DELETION-001 最小实现：同步注销、
// 无冷静期/申请中态）。流程：进入即拉前置校验 → 有阻塞则渲染阻塞明细且不可提交；
// 无阻塞展示影响确认 → 勾选确认 → 提交（ConsumerApi.write 幂等槽 settings:deletion）→
// 成功后本地会话即死（服务端 CANCELED），清本地态回「我的」tab。开关关闭期（默认）请求
// 404/503 失败关闭，不降级不伪造成功。
type Phase = 'loading' | 'ready' | 'load-error' | 'submitting' | 'done'
const MINE_TAB_URL = '/consumer/pages/mine/index'

const deletionImpacts = [
  '账号立即注销：所有登录会话失效，无法再使用本微信登录该账号。',
  '账号置为注销状态后无法自行恢复；如有异议需联系客服人工处理。',
  '你名下的商家资料、宠物档案、订单与售后记录将按平台保留策略处理。',
  '存在未完结订单、处理中售后或名下商家未完结业务时，注销会被拦截。',
] as const

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
  const [acknowledged, setAcknowledged] = useState(false)
  const [receipt, setReceipt] = useState<DeletionReceipt | null>(null)
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
        setPhase('ready')
        return
      }
      const result = await repository.precheck()
      if (!mounted.current) return
      setPrecheck(result)
      setPhase('ready')
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
        setReceipt({ userId: 'preview', status: 'CANCELED', deletedAt: new Date().toISOString() })
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
      setNotice('注销未完成，请稍后重试；若提示存在未完结业务，请先完结后再来。')
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
    <View className='st-status-area' />
    <View className='st-header'>
      <Button id='del-back' className='st-back' hoverClass='none' ariaLabel='返回' onClick={() => void back()}>
        <Image src={backIcon} className='st-back-icon' />
      </Button>
      <Text className='st-title'>注销账号</Text>
    </View>
    <ScrollView className='del-body' scrollY enhanced showScrollbar={false}>
      {phase === 'loading' && <View className='del-state' role='status'><Text>正在检查注销条件…</Text></View>}
      {phase === 'load-error' && <View className='del-state'>
        <Text>注销条件检查失败，请稍后重试。</Text>
        <Button id='del-retry' className='del-state-btn' hoverClass='none' onClick={() => void load()}>重试</Button>
      </View>}
      {phase === 'done' && receipt !== null && <View className='del-card'>
        <Text className='del-card-title'>账号已注销</Text>
        <Text className='del-impact'>注销时间：{receipt.deletedAt}</Text>
        <Text className='del-impact'>本机的登录状态已退出。</Text>
        <Button id='del-finish' className='del-primary' hoverClass='none' onClick={() => void finish()}>完成</Button>
      </View>}
      {(phase === 'ready' || phase === 'submitting') && precheck !== null && <>
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
      {preview && <Text className='del-preview-note'>预览模式：本地样例数据，仅用于设计验收，不发起真实请求。</Text>}
    </ScrollView>
  </View>
}
