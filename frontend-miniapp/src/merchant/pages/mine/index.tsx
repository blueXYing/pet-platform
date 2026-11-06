import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useState, type CSSProperties } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { MerchantAdmissionRepository, type MerchantAdmission } from '../../../shared/merchant-repositories'
import { StoreProfileClient, type StoreProfileView } from '../../../shared/merchant-storefront-api'
import { fixtureDeps } from '../../admission'
import { previewStoreProfile } from '../../workbench/preview'
import { businessStatusLabel } from '../../storefront/model'
import { MerchantTabBar } from '../../components/tab-bar'
import './index.css'

// 商家端「我的」tab 落地页（2026-11-04 用户批准底部五 tab）。Figma 25:20291「我的」族
// 原为 M 端 PRD 范围外（登记表 §4.10 偏差5 无「我的」tab），本次按用户裁决新增最小枢纽：
// 只聚合既有已批准入口，不引入账户资金/安全设置/通知设置/客服中心等 C2 项。
// 店名/营业状态读 27号 §6.4；OWNER 的受限态只读展示，写入入口在 ALLOWED 才可点。
type MenuItem = Readonly<{ id: string; label: string; hint?: string; url?: string; disabled?: boolean }>

type State = Readonly<{
  phase: 'loading' | 'entry' | 'ready' | 'error'
  notice: string
  view: MerchantAdmission | null
  profile: StoreProfileView | null
}>

export default function MerchantMinePage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = {
    '--mmine-status-top': `${info.statusBarHeight || 0}px`,
    '--mmine-unit': `${unit}px`,
  } as CSSProperties
  const [state, setState] = useState<State>({ phase: 'loading', notice: '', view: null, profile: null })

  const load = useCallback(() => {
    if (preview) {
      setState({ phase: 'loading', notice: '', view: null, profile: null })
      void fixtureDeps('allowed').admission(
        { userId: 'preview', workspace: 'merchant', merchantId: '9007199254740995', storeId: '9007199254740996' },
        '9007199254740995', '9007199254740996')
        .then(view => setState({ phase: 'ready', notice: '', view, profile: previewStoreProfile() }))
        .catch(() => setState({ phase: 'error', notice: '预览数据读取失败。', view: null, profile: null }))
      return
    }
    const context = consumerApi.scope.capture().context
    if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      setState({ phase: 'entry', notice: '请从商家工作台进入。', view: null, profile: null })
      return
    }
    setState(previous => ({ ...previous, phase: 'loading', notice: '' }))
    new MerchantAdmissionRepository(consumerApi).admission(context.merchantId, context.storeId)
      .then(async view => {
        let profile: StoreProfileView | null = null
        if (view.membershipKind === 'OWNER' && (view.admission === 'ALLOWED' || view.admission === 'LIMITED')) {
          profile = await new StoreProfileClient(consumerApi).get().catch(() => null)
        }
        setState({ phase: 'ready', notice: '', view, profile })
      })
      .catch(() => setState({ phase: 'error', notice: '商家身份读取失败，请返回工作台重试。', view: null, profile: null }))
  }, [preview])
  useDidShow(load)

  function open(url: string) {
    Taro.navigateTo({ url }).catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) })
  }
  function backToConsumer() {
    // The tab handoff keeps merchant coordinates for the merchant tabs; returning to the
    // consumer shell is an explicit exit and must hand the consumer pages consumer coordinates.
    const current = consumerApi.scope.current
    if (current) consumerApi.scope.replace({
      userId: current.userId, workspace: 'consumer', merchantId: null, storeId: null,
    })
    Taro.switchTab({ url: '/consumer/pages/mine/index' }).catch(() => {
      void Taro.showToast({ title: '切换失败，请重试', icon: 'none' })
    })
  }

  const view = state.view
  const owner = view?.membershipKind === 'OWNER'
  const allowed = view?.admission === 'ALLOWED'
  const limited = view?.admission === 'LIMITED'
  const can = (action: string) => Boolean(view?.allowedActions.includes(action))
  const profile = state.profile
  const storeName = profile?.readonlyFacts.storeName ?? '商家工作台'
  const initial = Array.from(storeName)[0] ?? '商'
  const statusLabel = profile ? businessStatusLabel[profile.profile.businessStatus] ?? '营业中' : ''
  const ownerMenu: readonly MenuItem[] = owner ? [
    { id: 'mine-shop-info', label: '店铺信息', hint: '名称/地址/电话/营业状态/照片', url: '/merchant/pages/shop-info/index', disabled: !allowed },
    { id: 'mine-marketing', label: '营销活动', hint: '平台活动报名与商家活动', url: '/merchant/pages/marketing/index', disabled: !allowed },
    { id: 'mine-members', label: '成员管理', hint: '邀请员工与成员状态', url: '/merchant/pages/members/index', disabled: !allowed },
  ] : []
  const operationMenu: readonly MenuItem[] = owner ? [
    { id: 'mine-orders', label: '订单处理', hint: '待接单与全部订单', url: '/merchant/pages/order-list/index', disabled: !can('merchant.order.read') },
    { id: 'mine-refunds', label: '退款处理', hint: '待处理退款申请', url: '/merchant/pages/refund/index', disabled: !can('merchant.refund.read') },
    { id: 'mine-aftersales', label: '售后管理', hint: '售后单与凭证', url: '/merchant/pages/aftersale/index', disabled: !can('merchant.aftersale.read') },
  ] : []
  const moreMenu: readonly MenuItem[] = owner ? [
    { id: 'mine-reviews', label: '店铺评价', url: '/merchant/pages/reviews/index', disabled: !can('merchant.review.read') },
    { id: 'mine-customers', label: '客户管理', url: '/merchant/pages/customers/index', disabled: !can('merchant.order.read') },
    { id: 'mine-stats', label: '数据统计', url: '/merchant/pages/stats/index', disabled: !can('merchant.order.read') },
    { id: 'mine-staff-workbench', label: '员工工作台', url: '/merchant/pages/staff-workbench/index' },
  ] : [
    { id: 'mine-staff-workbench', label: '员工工作台', hint: '邀请确认与核销入口', url: '/merchant/pages/staff-workbench/index' },
    { id: 'mine-staff-verify', label: '订单核销', url: '/merchant/pages/staff-verify/index' },
    { id: 'mine-staff-messages', label: '消息通知', url: '/merchant/pages/messages/index' },
  ]

  function renderMenu(items: readonly MenuItem[]) {
    return <View className='mmine-card'>
      {items.map(item => <Button key={item.id} id={item.id}
        className={item.disabled ? 'mmine-row mmine-row-disabled' : 'mmine-row'}
        disabled={item.disabled}
        onClick={() => { if (item.url && !item.disabled) open(item.url) }}>
        <View className='mmine-row-main'>
          <Text className='mmine-row-label'>{item.label}</Text>
          {item.hint && <Text className='mmine-row-hint'>{item.hint}</Text>}
        </View>
        <Text className='mmine-row-chevron'>›</Text>
      </Button>)}
    </View>
  }

  return <View className='mmine-page' style={style}>
    <View className='mmine-status-area' />
    <View className='mmine-header'><Text className='mmine-title'>我的</Text></View>
    <View className='mmine-body'>
      {state.phase === 'loading' && <View className='mmine-state' role='status'><Text>正在读取商家身份…</Text></View>}
      {state.phase === 'entry' && <View className='mmine-state' role='status'>
        <Text>{state.notice}</Text>
        <Button className='mmine-primary' onClick={backToConsumer}>返回用户端</Button>
      </View>}
      {state.phase === 'error' && <View className='mmine-state' role='status'>
        <Text>{state.notice}</Text>
        <Button className='mmine-primary' onClick={load}>重新加载</Button>
        <Button className='mmine-ghost' onClick={backToConsumer}>返回用户端</Button>
      </View>}
      {state.phase === 'ready' && view && <>
        <View className='mmine-store'>
          <View className='mmine-avatar'><Text>{initial}</Text></View>
          <View className='mmine-store-main'>
            <Text className='mmine-store-name'>{storeName}</Text>
            <Text className='mmine-store-meta'>{owner ? '商家主账号' : '员工账号'}{limited ? ' · 门店受限' : ''}</Text>
          </View>
          {statusLabel ? <View className={profile?.profile.businessStatus === 'RESTING' ? 'mmine-pill mmine-pill-resting' : 'mmine-pill'}>
            <View className='mmine-pill-dot' /><Text>{statusLabel}</Text>
          </View> : null}
        </View>
        {owner && <Text className='mmine-section'>店铺管理</Text>}
        {owner && renderMenu(ownerMenu)}
        {owner && <Text className='mmine-section'>经营处理</Text>}
        {owner && renderMenu(operationMenu)}
        <Text className='mmine-section'>{owner ? '更多' : '员工入口'}</Text>
        {renderMenu(moreMenu)}
        <Button id='mine-back-consumer' className='mmine-back' onClick={backToConsumer}>返回用户端</Button>
        <Text className='mmine-tail'>仅展示 V1 已批准入口；账户资金、安全设置、通知设置等不在本期范围。</Text>
      </>}
      <View className='mmine-tab-space' />
    </View>
    <MerchantTabBar active='mine' unit={unit} />
  </View>
}
