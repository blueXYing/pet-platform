import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi, consumerSession, consumerStorage } from '../../../shared/consumer-runtime'
import { integrationMessage } from '../../../shared/consumer-api'
import { RealProfileRepository } from '../../api/repositories'
import { RealCouponPointsRepository } from '../../coupon-points/repository'
import { deltaLabel, formatLedgerTime, pointsBizTypeLabels, type PointsLedgerView } from '../../coupon-points/model'
import avatar from '../../assets/profile/avatar.png'
import {
  MINE_DEVELOPER_SHELL_URL, MINE_MERCHANT_WORKSPACE_URL, mineCard, mineHubEntries, phoneGuideCopy,
  type MineCard, type MineStats,
} from './model'
import './mine.css'

// 「我的」tab（用户 2026-10-08 裁决的登录体验层落点）：静默登录状态投影 + 首次进入的手机号
// 授权引导（可跳过）+ 功能枢纽。复用既有机制：会话存取/登录链全在 ConsumerApi
// （wechat-login → VERIFY_PHONE → phone-binding attempt 通道），本页不新造第二套会话；
// 设计源：我的 126:675 为原稿，本页还原用户卡片/订单入口/服务网格骨架，签到/拉新/收藏/
// 足迹/会员等级等无 V1 后端字段登记偏差不实现。
const GUIDE_KEY = 'pet.c.phone-guide.v1'

export default function MinePage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = { '--mine-unit': `${unit}px`, '--mine-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  const { revision } = useWorkspace('real')
  const [profileRepository] = useState(() => new RealProfileRepository(consumerApi))
  const [couponPointsRepository] = useState(() => new RealCouponPointsRepository(consumerApi))
  const [card, setCard] = useState<MineCard>(() => mineCard({
    outcome: consumerSession.outcome(),
    nickname: '',
    phoneMasked: consumerApi.currentSession?.phoneMasked ?? null,
    guideDismissed: readDismissed(),
    stats: { coupons: null, points: null },
  }))
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  // 积分明细半屏弹层(129:8174 原稿形态):统计条积分入口弹出,不再整页跳转。
  const [sheetPhase, setSheetPhase] = useState<'closed' | 'loading' | 'ready' | 'error'>('closed')
  const [ledger, setLedger] = useState<readonly PointsLedgerView[]>([])
  const busyRef = useRef(false)
  const runningRef = useRef(false)
  const mounted = useRef(true)
  // 显式退出后本进程内不再自动静默重登（退出是用户决定）；重启后由 app 启动静默登录接管。
  const explicitLogout = useRef(false)

  async function refresh() {
    if (runningRef.current) return
    runningRef.current = true
    try {
      let outcome = consumerSession.outcome()
      // 启动静默登录失败（如网络）时的兜底重试；显式退出后不自动复活。
      if (outcome === 'unavailable' && !explicitLogout.current) outcome = await consumerSession.ensure()
      let nickname = ''
      let phoneMasked: string | null = consumerApi.currentSession?.phoneMasked ?? null
      let stats: MineStats = { coupons: null, points: null }
      if (outcome === 'authenticated') {
        // 三路读取失败均软降级（卡片仍可用）；会话过期由 ConsumerApi 401 自动恢复兜底。
        const [profile, coupons, points] = await Promise.all([
          profileRepository.load().catch(() => null),
          couponPointsRepository.listCoupons('AVAILABLE', 1, 1).then(page => page.total).catch(() => null),
          couponPointsRepository.balance().then(value => value.balance).catch(() => null),
        ])
        nickname = profile?.nickname || ''
        if (profile?.phoneMasked) phoneMasked = profile.phoneMasked
        stats = { coupons, points }
      }
      if (!mounted.current) return
      setCard(mineCard({ outcome, nickname, phoneMasked, guideDismissed: readDismissed(), stats }))
    } finally {
      runningRef.current = false
    }
  }
  useDidShow(() => { void refresh() })
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  // 会话坐标变化（登录完成/退出）时同步投影。
  useEffect(() => { void refresh() }, [revision])

  async function authorize(detail: { code?: string; errMsg?: string }) {
    if (busyRef.current) return
    busyRef.current = true; setBusy(true); setNotice('')
    try {
      if (!detail.code || detail.errMsg !== 'getPhoneNumber:ok') throw new Error('PHONE_AUTH_DENIED')
      try {
        await consumerApi.bindPhone(detail)
      } catch (error) {
        // 重启后登录 attempt 不在内存：静默重建登录链（新 attempt、原微信身份）再原样提交。
        if (!(error instanceof Error && error.message === 'LOGIN_REQUIRED')) throw error
        const outcome = await consumerSession.ensure()
        if (outcome !== 'phone-required') throw error
        await consumerApi.bindPhone(detail)
      }
      consumerStorage.remove(GUIDE_KEY)
      await refresh()
    } catch (error) {
      setNotice(integrationMessage(error))
    } finally {
      busyRef.current = false; setBusy(false)
    }
  }
  function skipGuide() {
    consumerStorage.set(GUIDE_KEY, true)
    setCard(current => current.state === 'phone-required' ? { ...current, guideOpen: false } : current)
  }
  function reopenGuide() {
    consumerStorage.remove(GUIDE_KEY)
    setCard(current => current.state === 'phone-required' ? { ...current, guideOpen: true } : current)
  }
  async function loginRetry() {
    if (busyRef.current) return
    busyRef.current = true; setBusy(true); setNotice('')
    try {
      explicitLogout.current = false
      await consumerSession.ensure()
      await refresh()
    } catch (error) {
      setNotice(integrationMessage(error))
    } finally {
      busyRef.current = false; setBusy(false)
    }
  }
  async function logoutUser() {
    if (busyRef.current) return
    busyRef.current = true; setBusy(true); setNotice('')
    try {
      explicitLogout.current = true
      await consumerApi.logout()
      await refresh()
    } catch (error) {
      // 退出回执丢失沿既有语义：本地会话已失效，重试退出走同一入口。
      setNotice(integrationMessage(error))
      await refresh()
    } finally {
      busyRef.current = false; setBusy(false)
    }
  }
  function open(url: string) {
    Taro.navigateTo({ url }).catch(() => setNotice('页面打开失败，请重试'))
  }
  function openPointsSheet() {
    if (card.state !== 'authenticated') { setNotice('登录后可查看积分明细。'); return }
    setSheetPhase('loading')
    void couponPointsRepository.ledger(1, 20)
      .then(page => { setLedger(page.items); setSheetPhase('ready') })
      .catch(() => setSheetPhase('error'))
  }
  function closePointsSheet() { setSheetPhase('closed') }

  return <View className='mine-page' style={style}>
    <View className='mine-status-area' />
    <View className='mine-header'>
      <Text className='mine-title'>我的</Text>
    </View>
    <ScrollView className='mine-body' scrollY enhanced showScrollbar={false}>
      {card.state === 'pending' && <View className='mine-card mine-card-pending' role='status'>
        <Text>正在同步登录状态…</Text>
      </View>}
      {card.state === 'authenticated' && <View className='mine-card mine-card-authenticated'>
        <View className='mine-user-row'>
          <Image className='mine-avatar' src={avatar} mode='scaleToFill' />
          <View className='mine-user-main'>
            <Text className='mine-nickname'>{card.nickname}</Text>
            <Text id='mine-phone' className='mine-phone'>{card.phoneMasked || '已登录'}</Text>
          </View>
          <Button id='mine-profile' className='mine-link' hoverClass='none' onClick={() => open('/consumer/pages/profile-edit/index')}>
            <Text className='mine-link-text'>编辑资料 ›</Text>
          </Button>
        </View>
        <View className='mine-stats'>
          <Button id='mine-stat-coupons' className='mine-stat' hoverClass='none' onClick={() => open('/consumer/pages/coupon-points/coupons')}>
            <Text className='mine-stat-value'>{card.stats.coupons === null ? '—' : card.stats.coupons}</Text>
            <Text className='mine-stat-label'>优惠券</Text>
          </Button>
          <Button id='mine-stat-points' className='mine-stat' hoverClass='none' onClick={openPointsSheet}>
            <Text className='mine-stat-value'>{card.stats.points === null ? '—' : card.stats.points}</Text>
            <Text className='mine-stat-label'>积分</Text>
          </Button>
          <Button id='mine-stat-pets' className='mine-stat' hoverClass='none' onClick={() => open('/consumer/pages/pet-archive/index')}>
            <Text className='mine-stat-value'>›</Text>
            <Text className='mine-stat-label'>宠物档案</Text>
          </Button>
        </View>
      </View>}
      {card.state === 'phone-required' && <View className={`mine-card mine-card-phone${card.guideOpen ? ' mine-card-phone-open' : ' mine-card-phone-closed'}`}>
        {card.guideOpen ? <View className='mine-guide'>
          <Text className='mine-guide-title'>{phoneGuideCopy.title}</Text>
          <Text className='mine-guide-body'>{phoneGuideCopy.body}</Text>
          <Button id='mine-phone-authorize' className='mine-guide-primary' openType='getPhoneNumber' disabled={busy}
            onGetPhoneNumber={event => void authorize(event.detail)}>{phoneGuideCopy.authorize}</Button>
          <Button id='mine-phone-skip' className='mine-guide-secondary' disabled={busy} onClick={skipGuide}>{phoneGuideCopy.skip}</Button>
        </View> : <Button id='mine-phone-reopen' className='mine-guide-collapsed' hoverClass='none' onClick={reopenGuide}>
          <Text className='mine-guide-collapsed-text'>{phoneGuideCopy.collapsed} ›</Text>
        </Button>}
      </View>}
      {card.state === 'unavailable' && <View className='mine-card mine-card-unauthenticated'>
        <View className='mine-guide'>
          <Text className='mine-guide-title'>未登录</Text>
          <Text className='mine-guide-body'>可先匿名浏览门店与服务；下单、订单与消息需要登录。</Text>
          <Button id='mine-login' className='mine-guide-primary' disabled={busy} onClick={() => void loginRetry()}>微信一键登录</Button>
        </View>
      </View>}
      {notice && <View className='mine-notice' role='status'><Text id='mine-notice'>{notice}</Text></View>}
      <View className='mine-hub'>
        {mineHubEntries.map(entry => <Button key={entry.key} id={`mine-hub-${entry.key}`} className='mine-hub-cell' hoverClass='none' onClick={() => open(entry.url)}>
          <Text className='mine-hub-label'>{entry.label}</Text>
          <Text className='mine-hub-go'>›</Text>
        </Button>)}
      </View>
      <Button id='mine-merchant' className='mine-wide' hoverClass='none' onClick={() => open(MINE_MERCHANT_WORKSPACE_URL)}>
        <Text className='mine-wide-title'>商家工作台</Text>
        <Text className='mine-wide-sub'>商家与员工沿现有工作台路径进入</Text>
        <Text className='mine-hub-go'>›</Text>
      </Button>
      <View className='mine-footer'>
        <Button id='mine-logout' className={`mine-logout${card.state === 'authenticated' ? '' : ' mine-logout-hidden'}`}
          disabled={busy || card.state !== 'authenticated'} onClick={() => void logoutUser()}>退出登录</Button>
        <Button id='mine-developer' className='mine-developer' hoverClass='none' onClick={() => open(MINE_DEVELOPER_SHELL_URL)}>
          <Text className='mine-developer-text'>开发者工具 · 工程验证</Text>
        </Button>
      </View>
    </ScrollView>
    {sheetPhase !== 'closed' && <View className='pts-mask' onClick={closePointsSheet} catchMove>
      <View className='pts-sheet' onClick={event => event.stopPropagation()} catchMove>
        <View className='pts-head'>
          <Text className='pts-title'>积分明细</Text>
          <Button id='mine-pts-close' className='pts-close' hoverClass='none' onClick={closePointsSheet}>
            <Text>×</Text>
          </Button>
        </View>
        <ScrollView className='pts-list' scrollY enhanced showScrollbar={false}>
          {sheetPhase === 'loading' && <View className='pts-state' role='status'><Text>正在读取积分明细…</Text></View>}
          {sheetPhase === 'error' && <View className='pts-state' role='status'><Text>积分明细读取失败，请稍后重试。</Text></View>}
          {sheetPhase === 'ready' && ledger.length === 0 && <View className='pts-state'><Text>暂无积分明细。</Text></View>}
          {sheetPhase === 'ready' && ledger.map(entry => <View key={entry.ledgerId} id={`mine-pts-row-${entry.ledgerId}`}
            className='pts-row' ariaLabel={`${pointsBizTypeLabels[entry.bizType]}，${deltaLabel(entry)}积分`}>
            <View className='pts-row-left'>
              <View className={`pts-icon${entry.delta.startsWith('-') ? ' is-negative' : ''}`}>
                <Text>{entry.delta.startsWith('-') ? '−' : '+'}</Text>
              </View>
              <Text className='pts-name'>{pointsBizTypeLabels[entry.bizType]}</Text>
            </View>
            <View className='pts-row-right'>
              <Text className={`pts-delta${entry.delta.startsWith('-') ? ' is-negative' : ''}`}>{deltaLabel(entry)}</Text>
              <Text className='pts-time'>{formatLedgerTime(new Date(), entry.createdAt)}</Text>
            </View>
          </View>)}
        </ScrollView>
      </View>
    </View>}
  </View>
}

function readDismissed(): boolean {
  return consumerStorage.get(GUIDE_KEY) === true
}
