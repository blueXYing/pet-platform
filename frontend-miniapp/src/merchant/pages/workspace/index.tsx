import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { MerchantWorkspace } from '../../workspace'
import { realDeps } from '../../admission-runtime'
import navBack from '../aftersale/assets/nav-back@3x.png'
import './index.css'

const reasonText: Record<string, string> = {
  NO_APPLICATION: '尚未提交入驻申请',
  APPLICATION_DRAFT: '入驻申请尚未提交审核',
  APPLICATION_PENDING: '入驻申请审核中，请耐心等待',
  APPLICATION_REJECTED: '入驻申请未通过，请查看审核意见',
  SIGNING_REQUIRED: '入驻申请已通过，请先签署商家协议',
  MERCHANT_OFFLINE: '商家已下线，暂停新经营业务',
  STORE_OFFLINE: '门店已下线，暂停新经营业务',
  MERCHANT_FROZEN: '商家已冻结，受限访问',
  STORE_FROZEN: '门店已冻结，受限访问',
}
const stepText: Record<string, string> = {
  APPLY: '去申请入驻',
  VIEW_APPLICATION: '查看入驻申请',
  COMPLETE_SIGNING: '去签署商家协议',
  VIEW_EXISTING_ORDERS: '查看存量订单',
  VIEW_AFTERSALES: '查看售后',
  APPEAL: '查看处罚与申诉',
  CONTACT_SUPPORT: '联系平台支持',
}
// Whitelist routing only; admission nextSteps never carry arbitrary redirect URLs.
function routeForStep(step: string, merchantId: string): string | null {
  if (step === 'COMPLETE_SIGNING') return `/consumer/pages/merchant-application/signing?merchantId=${merchantId}`
  if (step === 'VIEW_APPLICATION' || step === 'APPLY') return '/consumer/pages/merchant-application/index'
  return null
}

// M-002 workbench visual pass (2026-10-13): merchant frame 95:1899「首页」family measures —
// header band #FFF6E5 + centered title + back arrow, blue hero card #5BAAE8 with the light
// wave zone, quick-entry grid on #D7F1FD r16 with white r12 icon tiles. Visual only: every
// button id / mount condition / route / admission gate below is byte-identical to the prior
// implementation. Design blocks with no admission-view fields (今日营收/较昨日/营业中状态/
// 待办预约/统计卡/底部tab) are not rendered — registry §4.10 deviations.
export default function MerchantWorkbenchPage() {
  const { scope, context } = useWorkspace('real')
  const [controller] = useState(() => new MerchantWorkspace(scope))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const enter = () => { void controller.enter(realDeps()) }
  useDidShow(enter)
  useDidHide(() => controller.leave())
  useEffect(() => () => controller.dispose(), [controller])
  // Custom navigation reserves the real status-bar height, same mechanism as the services and
  // application pages; the unit scale follows the 402-wide design frame.
  const [windowInfo] = useState(() => Taro.getWindowInfo())
  const style = {
    '--mwb-status-top': `${windowInfo.statusBarHeight || 0}px`,
    '--mwb-unit': `${windowInfo.windowWidth / 402}px`,
  } as CSSProperties
  async function back() {
    controller.leave()
    await Taro.switchTab({ url: '/consumer/pages/home/index' })
  }
  function takeStep(step: string, merchantId: string) {
    if (step === 'VIEW_AFTERSALES') { openAftersales(); return }
    const url = routeForStep(step, merchantId)
    if (url) void Taro.navigateTo({ url })
    else void Taro.showToast({ title: '该入口将在后续切片提供', icon: 'none' })
  }
  // F1 fix: navigateTo hides this page first, and the service pages gate on the merchant
  // coordinates this workbench holds — hand them to the child instead of revoking on hide
  // (leave() used to reset them, deadlocking the child at its entry state in real mode).
  function openServices() {
    controller.handoffToChild()
    Taro.navigateTo({ url: '/merchant/pages/services/index' }).catch(() => controller.handoffCancelled())
  }
  function openAftersales() {
    controller.handoffToChild()
    Taro.navigateTo({ url: '/merchant/pages/aftersale/index' }).catch(() => controller.handoffCancelled())
  }

  // Review management (56号 / REV-002): same handoff discipline as aftersale — the child page
  // gates on the merchant coordinates this workbench holds.
  function openReviews() {
    controller.handoffToChild()
    Taro.navigateTo({ url: '/merchant/pages/reviews/index' }).catch(() => controller.handoffCancelled())
  }

  function openSchedule() {
    controller.handoffToChild()
    Taro.navigateTo({ url: '/merchant/pages/schedule/index' }).catch(() => controller.handoffCancelled())
  }

  // Contract 54 staff binding entry (member management: list/invite/cancel/disable). Same
  // handoff discipline as the service pages: the child gates on the coordinates this page holds.
  function openMembers() {
    controller.handoffToChild()
    Taro.navigateTo({ url: '/merchant/pages/members/index' }).catch(() => controller.handoffCancelled())
  }
  // Staff landing (52/54): the staff workbench re-checks admission itself and organizes the
  // verify entry + invitation records for the STAFF projection.
  function openStaffWorkbench() {
    controller.handoffToChild()
    Taro.navigateTo({ url: '/merchant/pages/staff-workbench/index' }).catch(() => controller.handoffCancelled())
  }
  // Order verification (48 K2 v0.3 HTTP face): OWNER and STAFF share the same submit page —
  // the operator identity is resolved server-side by the K1 chain. The OWNER path keeps
  // ACTIVE/OFFLINE existing-order fulfillment, so a LIMITED store keeps the entry too
  // (FROZEN fails closed server-side).
  function openVerify() {
    controller.handoffToChild()
    Taro.navigateTo({ url: '/merchant/pages/staff-verify/index' }).catch(() => controller.handoffCancelled())
  }
  // Merchant order processing (45号 via 10号 §4.2/§4.3 + §4.1 list read): the entry now lands
  // on the store order list (待接单/全部 tabs); the #129 confirm/reject page is entered from a
  // list card. Existing-order family like verify — OFFLINE/LIMITED keeps handling paid orders,
  // FROZEN fails closed server-side.
  function openOrderConfirm() {
    controller.handoffToChild()
    Taro.navigateTo({ url: '/merchant/pages/order-list/index' }).catch(() => controller.handoffCancelled())
  }
  // Merchant refund processing (57号 via 10号 §4.4-§4.6): pending refund list + OWNER
  // decisions. Existing-material family — OFFLINE keeps handling pending refunds (49号),
  // FROZEN stays read-only and new decisions fail closed server-side.
  function openRefunds() {
    controller.handoffToChild()
    Taro.navigateTo({ url: '/merchant/pages/refund/index' }).catch(() => controller.handoffCancelled())
  }
  const view = state.view
  const heroTitle = state.status === 'idle' ? '正在进入工作台…'
    : state.status === 'checking' ? '正在校验工作台准入…'
    : state.status === 'error' ? (state.notice || '准入查询失败，未放行。')
    : state.status === 'no-stores' ? '还没有可进入的商家门店'
    : state.status === 'choose-store' ? '请选择要进入的门店'
    : state.status === 'allowed' ? '工作台已就绪'
    : state.status === 'limited' ? '门店受限访问'
    : '暂不能进入工作台'
  return <View className='mwb-page' style={style}>
    <View className='mwb-status-area' />
    <View className='mwb-header'>
      <Button className='mwb-back' ariaLabel='返回' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mwb-title'>商家工作台</Text>
    </View>
    <View className='mwb-body'>
      <View className='mwb-hero'>
        <View className='mwb-hero-wave' />
        <View className='mwb-hero-pill'><Text>商家工作台</Text></View>
        <Text className='mwb-hero-title'>{heroTitle}</Text>
        {view && <Text className='mwb-hero-sub'>校验时间 {view.checkedAt} · authzVersion {view.authzVersion}</Text>}
      </View>
      {state.status === 'checking' && <View className='mwb-line' role='status'>正在校验工作台准入…</View>}
      {state.status === 'error' && <View className='mwb-card mwb-error' role='alert'>
        <Text>{state.notice || '准入查询失败，未放行。'}</Text>
        <Button className='mwb-primary' onClick={enter}>重试</Button>
      </View>}
      {state.status === 'no-stores' && <View className='mwb-card'>
        <Text>当前账号还没有可进入的商家门店。</Text>
        <Text className='mwb-hint'>完成入驻申请并审核通过、签署商家协议后即可进入。</Text>
        <Button className='mwb-primary' onClick={() => void Taro.navigateTo({ url: '/consumer/pages/merchant-application/index' })}>去申请入驻</Button>
        {/* Staff binding (contract 54): the pre-binding employee has no membership yet, so the
            staff workbench is the discoverable confirm entry (2026-10-06 user adjudication). */}
        <Button id='workbench-staff-entry' className='mwb-hint' onClick={() => void Taro.navigateTo({ url: '/merchant/pages/staff-workbench/index' })}>收到店员邀请？去员工工作台确认</Button>
      </View>}
      {state.status === 'choose-store' && <View className='mwb-card'>
        <Text>请选择要进入的门店</Text>
        {state.stores?.map(store => <Button key={store.storeId} className='mwb-store'
          onClick={() => void controller.select(store.merchantId, store.storeId, realDeps())}>
          {store.storeName}<Text className='mwb-hint'>{store.merchantName}</Text>
        </Button>)}
      </View>}
      {view && (state.status === 'allowed' || state.status === 'limited' || state.status === 'denied') && <View className='mwb-card'>
        {view.reasonCodes.length > 0 && <View className='mwb-reasons' role='status'>
          {view.reasonCodes.map(code => <Text key={code}>· {reasonText[code] || code}</Text>)}
        </View>}
        {state.status === 'limited' && <Text className='mwb-hint'>存量订单与售后读取不受影响；新经营业务暂停。处理中的订单请继续履约。</Text>}
        {state.status === 'allowed' && view.allowedActions.length > 0 && <View className='mwb-actions'>
          {view.allowedActions.map(action => <Text key={action} className='mwb-chip'>{action}</Text>)}
          <Text className='mwb-hint'>以上为入口提示；完整工作台功能（订单）在后续切片交付。</Text>
        </View>}
        <View className='mwb-grid'>
          {view.membershipKind === 'STAFF' && (state.status === 'allowed' || state.status === 'limited') && <>
            {/* Staff landing is the staff workbench (2026-10-06 adjudication), distinct from the
                OWNER entries below: a STAFF projection never shows owner management entries. */}
            <Button id='workbench-staff-workspace' className='mwb-cell' onClick={openStaffWorkbench}>
              <View className='mwb-cell-icon mwb-icon-staff' /><Text className='mwb-cell-label'>员工工作台</Text>
            </Button>
          </>}
          {view.membershipKind === 'OWNER' && state.status === 'allowed' && <>
            {/* M-002 service management entry (NAVIGATION-BASIS: workbench is the approved hub;
                the entry stays hidden for LIMITED stores — new-business writes are suspended). */}
            <Button id='workbench-services' className='mwb-cell' onClick={openServices}>
              <View className='mwb-cell-icon mwb-icon-services' /><Text className='mwb-cell-label'>服务管理</Text>
            </Button>
            {/* Schedule maintenance entry gates on the approved action (53号 写侧消费切片). */}
            {view.allowedActions.includes('merchant.schedule.manage') && <Button id='workbench-schedule' className='mwb-cell' onClick={openSchedule}>
              <View className='mwb-cell-icon mwb-icon-schedule' /><Text className='mwb-cell-label'>排期管理</Text>
            </Button>}
            <Button id='workbench-members' className='mwb-cell' onClick={openMembers}>
              <View className='mwb-cell-icon mwb-icon-staff' /><Text className='mwb-cell-label'>成员管理</Text>
            </Button>
          </>}
          {view.membershipKind === 'OWNER' && (state.status === 'allowed' || state.status === 'limited') && <>
            {/* Order verification entry (48 K2 v0.3): 存量履约 family — ACTIVE/OFFLINE keeps
                verifying existing orders, so the entry stays for LIMITED like aftersale. */}
            <Button id='workbench-verify' className='mwb-cell' onClick={openVerify}>
              <View className='mwb-cell-icon mwb-icon-verify' /><Text className='mwb-cell-label'>订单核销</Text>
            </Button>
            {/* Merchant order processing (45号 + §4.1 list read): 本店订单列表(重点待接单),同样属存量履约 family。 */}
            <Button id='workbench-order-confirm' className='mwb-cell' onClick={openOrderConfirm}>
              <View className='mwb-cell-icon mwb-icon-orders' /><Text className='mwb-cell-label'>订单处理</Text>
            </Button>
            {/* Merchant refund processing (57号): 待处理退款列表入口，同存量履约 family（OFFLINE 可处理）。 */}
            <Button id='workbench-refunds' className='mwb-cell' onClick={openRefunds}>
              <View className='mwb-cell-icon mwb-icon-refunds' /><Text className='mwb-cell-label'>退款处理</Text>
            </Button>
          </>}
          {view.membershipKind === 'OWNER' && (state.status === 'allowed' || state.status === 'limited') && view.allowedActions.includes('merchant.aftersale.read') && <>
            <Button id='workbench-aftersales' className='mwb-cell' onClick={openAftersales}>
              <View className='mwb-cell-icon mwb-icon-aftersales' /><Text className='mwb-cell-label'>售后管理</Text>
            </Button>
          </>}
          {/* Review management entry (56号 / REV-002): 存量读取 family — the review list stays
              readable for LIMITED/FROZEN stores; the one-appeal write fails closed server-side. */}
          {view.membershipKind === 'OWNER' && (state.status === 'allowed' || state.status === 'limited') && view.allowedActions.includes('merchant.review.read') && <>
            <Button id='workbench-reviews' className='mwb-cell' onClick={openReviews}>
              <View className='mwb-cell-icon mwb-icon-reviews' /><Text className='mwb-cell-label'>评价管理</Text>
            </Button>
          </>}
        </View>
        {view.nextSteps.length > 0 && <View className='mwb-steps'>
          {view.nextSteps.map(step => <Button key={step.type} onClick={() => takeStep(step.type, view.merchantId)}>{stepText[step.type] || step.type}</Button>)}
        </View>}
      </View>}
      <Text className='mwb-meta'>当前工作区：{context?.workspace ?? '未登录'}{context?.merchantId ? ` · ${context.merchantId}` : ''}</Text>
    </View>
  </View>
}
