import { Button, Image, Input, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealStaffInvitationRepository } from '../../staff/repository'
import { StaffInvitationController } from '../../staff/controller'
import {
  invitationStatusText, invitationTagClass, isInvitationId, memberStateClass, memberStatusText,
  staffActionText,
} from '../../staff/model'
import navBack from '../members/assets/nav-back@2x.png'
import './page.css'

// 员工邀请确认页（contract 54 §4 员工侧通道）：员工本人（登录态）按邀请编号读取自己收到的
// 邀请并确认。设计源：登记表 §3/§4 复核无「员工确认」原稿帧，按登记表规范登记缺稿，页面沿
// M 端现行规范（members 页 12:6591 的 measures/tokens）实现，非一比一还原。服务端在读取与
// 确认内核里比对登录账号手机号与邀请手机号，不一致一律 404 防枚举——前端同一文案覆盖，不区分
// 原因；开关默认关（路由 404）与依赖故障（503）都整页失败关闭为不可交互面板，不渲染可编辑表单。

export default function StaffInvitationPage() {
  const route = useRouter()
  const { scope, revision } = useWorkspace('real')
  const [controller] = useState(() => new StaffInvitationController(new RealStaffInvitationRepository(consumerApi)))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [notice, setNotice] = useState('')
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--msi-status-top': `${platformInfo.statusBarHeight || 0}px`, '--msi-unit': `${unit}px` } as CSSProperties
  const [queryId, setQueryId] = useState(() => route.params.invitationId && isInvitationId(route.params.invitationId)
    ? route.params.invitationId : '')
  const previousRevision = useRef(revision)

  const load = useCallback((invitationId: string) => {
    setNotice('')
    const current = scope.current
    if (!current) { controller.entry('请先登录后查看邀请。'); return }
    if (!isInvitationId(invitationId)) { controller.entry('请输入商家提供的邀请编号（数字）。'); return }
    void controller.load(invitationId.trim())
  }, [controller, scope])
  useDidShow(() => { load(queryId) })
  useEffect(() => () => controller.dispose(), [controller])
  // Account switch invalidates the loaded invitation (phone comparison is session-bound).
  useEffect(() => {
    if (previousRevision.current !== revision) {
      previousRevision.current = revision
      if (!scope.current) controller.entry('请先登录后查看邀请。')
    }
  }, [revision, scope, controller])

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack().catch(() => setNotice('返回失败，请重试'))
    else await Taro.redirectTo({ url: '/merchant/pages/staff-workbench/index' }).catch(() => setNotice('返回失败，请重试'))
  }

  async function confirmBinding() {
    const confirmed = await Taro.showModal({
      title: '确认加入门店', content: '确认后您将以核销员身份加入该门店，获得本店订单核销权限；确认须由被邀请手机号本人完成，且一经确认不可自行解除。',
      confirmText: '确认绑定', cancelText: '再想想',
    })
    if (!confirmed.confirm) return
    await controller.confirm(`staff-invitation:${state.invitationId || queryId.trim()}:confirm`)
  }

  function goWorkbench() {
    Taro.redirectTo({ url: '/merchant/pages/staff-workbench/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function goShell() {
    Taro.reLaunch({ url: '/consumer/pages/shell/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }

  const ready = state.status === 'ready'
  const detail = state.detail
  return <View className='msi-page' style={style}>
    <View className='msi-status-area' />
    <View className='msi-header'>
      <Button id='msi-back' ariaLabel='返回' className='msi-back' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='msi-title'>员工邀请确认</Text>
    </View>
    {!ready && <View className='msi-state' role='status'>
      {(state.status === 'loading' || state.status === 'idle') && <Text>正在读取邀请…</Text>}
      {state.status === 'entry' && <View>
        <Text>{state.notice || notice || '请输入商家提供的邀请编号查看邀请。'}</Text>
        <View className='msi-query'>
          <Input className='msi-query-input' type='number' placeholder='邀请编号（商家成员管理页可查）'
            value={queryId} onInput={event => setQueryId(event.detail.value)} />
          <Button id='msi-query-submit' className='msi-query-submit' onClick={() => load(queryId)}>查看邀请</Button>
        </View>
        {!scope.current && <Button className='msi-state-action' onClick={goShell}>去登录</Button>}
      </View>}
      {state.status === 'load-error' && <View>
        {/* Switch off (route 404) / dependency fault (503) / invisible invitation all read the
            same here: the whole page fails closed — no editable form, only retry or re-entry. */}
        <Text>{state.closed ? '功能未开放或邀请不可见，暂时无法确认。' : '邀请读取失败。'}</Text>
        <Text>{state.notice || notice || '加载失败，请重试。'}</Text>
        <Button id='msi-retry' className='msi-state-action' onClick={() => load(queryId)}>重新加载</Button>
        <Button id='msi-reenter' className='msi-state-action' onClick={() => controller.entry('请输入商家提供的邀请编号（数字）。')}>修改邀请编号</Button>
      </View>}
    </View>}
    {ready && detail && <ScrollView className='msi-body' scrollY enhanced showScrollbar={false}>
      {notice && <Text id='msi-notice' className='msi-notice'>{notice}</Text>}
      {state.notice && <Text id='msi-controller-notice' className='msi-notice'>{state.notice}</Text>}
      <View className='msi-card'>
        <View className='msi-card-line'>
          <Text className='msi-store-name'>{detail.storeName}</Text>
          <Text className={invitationTagClass(detail.status)}>{invitationStatusText[detail.status]}</Text>
        </View>
        <Text className='msi-card-sub'>{detail.merchantName}</Text>
        <View className='msi-card-row'>
          <Text className='msi-card-label'>被邀姓名</Text>
          <Text className='msi-card-value'>{detail.memberName}</Text>
        </View>
        <View className='msi-card-row'>
          <Text className='msi-card-label'>获得权限</Text>
          <View className='msi-card-actions'>
            {detail.grantedActions.length === 0 && <Text className='msi-card-value'>无</Text>}
            {detail.grantedActions.map(action => <Text key={action} className='msi-action-chip'>{staffActionText[action] || action}</Text>)}
          </View>
        </View>
        <Text className='msi-card-hint'>
          {detail.status === 'INVITED' ? '等待您本人在微信内确认；确认后以核销员身份加入该门店，权限仅授予本店。'
            : detail.status === 'CONFIRMED' ? '该邀请已完成确认，绑定关系已生效。'
              : '邀请已被商家撤销，无法确认；如需加入请商家重新发起邀请。'}
        </Text>
      </View>
      {state.receipt && <View className='msi-success' role='status'>
        <Text className='msi-success-title'>确认成功</Text>
        <View className='msi-card-row'><Text className='msi-card-label'>成员状态</Text>
          <View className={memberStateClass(state.receipt.memberStatus)}><Text className='msi-state-dot' /><Text className='msi-state-text'>{memberStatusText[state.receipt.memberStatus]}</Text></View>
        </View>
        <Text className='msi-card-hint'>请从商家工作区进入员工工作台使用核销入口。</Text>
        <Button id='msi-go-workbench' className='msi-primary' onClick={goWorkbench}>去员工工作台</Button>
      </View>}
      {detail.status === 'INVITED' && !state.receipt && <Button
        id='msi-confirm' className='msi-primary' disabled={state.busy}
        onClick={() => void confirmBinding()}>
        {state.busy ? '确认中…' : '确认绑定'}
      </Button>}
      {detail.status === 'CONFIRMED' && !state.receipt && <Button id='msi-go-workbench-confirmed' className='msi-primary' onClick={goWorkbench}>去员工工作台</Button>}
      <View className='msi-tail'><Text>页面数据：真实接口（contract 54 员工侧，默认关闭时接口失败关闭）</Text></View>
    </ScrollView>}
  </View>
}
