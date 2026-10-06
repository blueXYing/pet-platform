import { Button, Image, Input, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealMembersRepository } from '../../members/repository'
import { MembersController, type MembersTab } from '../../members/controller'
import {
  invitationStatusText, invitationTagClass, isValidInviteName, isValidInvitePhone,
  memberStatusClass, memberStatusText,
  type InvitationRow, type MemberRow,
} from '../../members/model'
import navBack from './assets/nav-back@2x.png'
import './page.css'

// M 端成员管理（contract 54 D1-a 邀请确认绑定；design source: merchant frame 12:6591
// 「首页-员工管理」, 402x898 — header band 86 #FFF6E5, body #F0FBFF, cards 371x88 white with
// #C0ECFF accents, status dot #22C55E/#D1D5DB with #16A34A/#9CA3AF text). V1 cuts: role tag
// reads 核销员 (the only approved staff role), 店长/排期 rows and the design tab bar are not
// part of this workbench child page. Only the approved verify action is granted — the
// invite form exposes no action picker (D2: server-pinned catalog).

export default function MerchantMembersPage() {
  const { scope, revision } = useWorkspace('real')
  const [repository] = useState(() => new RealMembersRepository(consumerApi,
    () => consumerApi.scope.current?.merchantId || '',
    () => consumerApi.scope.current?.storeId || ''))
  const [controller] = useState(() => new MembersController(repository))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [notice, setNotice] = useState('')
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--mmb-status-top': `${platformInfo.statusBarHeight || 0}px`, '--mmb-unit': `${unit}px` } as CSSProperties
  const previousRevision = useRef(revision)
  // Invite form draft state is page-local; submission goes through the controller.
  const [formOpen, setFormOpen] = useState(false)
  const [phone, setPhone] = useState('')
  const [memberName, setMemberName] = useState('')

  const load = useCallback(() => {
    setNotice('')
    const current = scope.current
    if (!current) { controller.entry(); setNotice('请先登录后从商家工作台进入。'); return }
    if (current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      controller.entry(); setNotice('请从商家工作台进入成员管理。')
      return
    }
    void controller.load()
  }, [controller, scope])
  useDidShow(load)
  useEffect(() => () => controller.dispose(), [controller])
  // Coordinates switched away (workbench leave / account switch) invalidate the listing.
  useEffect(() => {
    if (previousRevision.current !== revision) {
      previousRevision.current = revision
      const current = scope.current
      if (!current || current.workspace !== 'merchant') controller.entry()
    }
  }, [revision, scope, controller])

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack().catch(() => setNotice('返回失败，请重试'))
    else await Taro.redirectTo({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('返回失败，请重试'))
  }

  async function submitInvite() {
    const trimmedPhone = phone.trim()
    const trimmedName = memberName.trim()
    if (!isValidInvitePhone(trimmedPhone)) { setNotice('请输入 11 位员工手机号。'); return }
    if (!isValidInviteName(trimmedName)) { setNotice('请输入员工姓名（2-20 字）。'); return }
    const slot = `staff-member:invite:${trimmedPhone}`
    const ok = await controller.invite(slot, trimmedPhone, trimmedName)
    if (ok) { setPhone(''); setMemberName(''); setFormOpen(false) }
  }

  async function cancelInvitation(row: InvitationRow) {
    const confirmed = await Taro.showModal({
      title: '撤销邀请', content: `撤销后「${row.phoneMasked}」将无法确认绑定；如需重新邀请，可再次发起。`,
      confirmText: '撤销邀请', cancelText: '取消',
    })
    if (!confirmed.confirm) return
    await controller.cancelInvitation(`staff-member:${row.invitationId}:cancel`, row)
  }

  async function toggleMember(row: MemberRow) {
    const disabling = row.memberStatus === 'ENABLED'
    if (disabling) {
      const confirmed = await Taro.showModal({
        title: '停用成员', content: `停用后「${row.memberName}」在本店的核销权限立即失效，历史操作记录保留；可随时恢复。`,
        confirmText: '确认停用', cancelText: '取消',
      })
      if (!confirmed.confirm) return
    }
    await controller.toggleMember(`staff-member:${row.memberId}:${disabling ? 'disable' : 'enable'}`, row)
  }

  const ready = state.status === 'ready'
  return <View className='mmb-page' style={style}>
    <View className='mmb-status-area' />
    <View className='mmb-header'>
      <Button id='mmb-back' ariaLabel='返回' className='mmb-back' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mmb-title'>成员管理</Text>
      <Button id='mmb-invite' ariaLabel='邀请成员' className='mmb-invite' onClick={() => setFormOpen(open => !open)}>
        {formOpen ? '收起' : '邀请成员'}
      </Button>
    </View>
    {formOpen && <View className='mmb-form'>
      <Text className='mmb-form-title'>邀请店员（核销）</Text>
      <Text className='mmb-form-hint'>登记员工本人手机号与姓名；员工以本人微信确认后绑定生效，核销权限仅授予本店。</Text>
      <View className='mmb-form-row'>
        <Text className='mmb-form-label'>手机号</Text>
        <Input className='mmb-form-input' type='number' maxlength={11} placeholder='员工本人手机号'
          value={phone} onInput={event => setPhone(event.detail.value)} />
      </View>
      <View className='mmb-form-row'>
        <Text className='mmb-form-label'>姓名</Text>
        <Input className='mmb-form-input' maxlength={20} placeholder='员工真实姓名'
          value={memberName} onInput={event => setMemberName(event.detail.value)} />
      </View>
      <Button id='mmb-form-submit' className='mmb-form-submit' disabled={state.busy} onClick={() => void submitInvite()}>
        {state.busy ? '提交中…' : '发出邀请'}
      </Button>
    </View>}
    {!ready && <View className='mmb-state' role='status'>
      <Text>{state.status === 'loading' || state.status === 'idle' ? '正在加载成员列表…' : state.notice || notice || '加载失败，请重试。'}</Text>
      {state.status === 'load-error' && <Button id='mmb-retry' className='mmb-state-action' onClick={load}>重新加载</Button>}
      {state.status === 'entry' && <Button className='mmb-state-action' onClick={() => Taro.redirectTo({ url: '/merchant/pages/workspace/index' })}>去商家工作台</Button>}
    </View>}
    {ready && <ScrollView className='mmb-body' scrollY enhanced showScrollbar={false}>
      <View className='mmb-tabs' role='tablist'>
        <Button id='mmb-tab-members' className={state.tab === 'members' ? 'mmb-tab mmb-tab-active' : 'mmb-tab'}
          onClick={() => controller.switchTab('members' as MembersTab)}>成员（{state.memberTotal}）</Button>
        <Button id='mmb-tab-invitations' className={state.tab === 'invitations' ? 'mmb-tab mmb-tab-active' : 'mmb-tab'}
          onClick={() => controller.switchTab('invitations' as MembersTab)}>邀请记录（{state.invitationTotal}）</Button>
      </View>
      {notice && <Text id='mmb-notice' className='mmb-notice'>{notice}</Text>}
      {state.notice && <Text id='mmb-controller-notice' className='mmb-notice'>{state.notice}</Text>}
      {state.tab === 'members' && <View>
        {state.members.length === 0 && <View className='mmb-empty'><Text>还没有已绑定的店员。点击右上角「邀请成员」，员工本人微信确认后即可核销。</Text></View>}
        {state.members.map(row => <View key={row.memberId} className='mmb-card'>
          <View className='mmb-avatar'><Text className='mmb-avatar-text'>{row.memberName.slice(0, 1)}</Text></View>
          <View className='mmb-card-main'>
            <View className='mmb-card-line'>
              <Text className='mmb-card-name'>{row.memberName}</Text>
              <Text className='mmb-role-tag'>核销员</Text>
            </View>
            <Text className='mmb-card-phone'>{row.phoneMasked}</Text>
            <View className='mmb-card-line'>
              <View className={memberStatusClass(row.memberStatus)}><Text className='mmb-state-dot' /><Text className='mmb-state-text'>{memberStatusText[row.memberStatus]}</Text></View>
              {row.grantStatus === 'REVOKED' && <Text className='mmb-grant-revoked'>门店授权已撤回</Text>}
            </View>
          </View>
          {row.grantStatus === 'ENABLED' && <Button
            id={`mmb-toggle-${row.memberId}`}
            ariaLabel={row.memberStatus === 'ENABLED' ? `停用${row.memberName}` : `恢复${row.memberName}`}
            className={row.memberStatus === 'ENABLED' ? 'mmb-action mmb-action-danger' : 'mmb-action'}
            disabled={state.busy} onClick={() => void toggleMember(row)}>
            {row.memberStatus === 'ENABLED' ? '停用' : '恢复'}
          </Button>}
        </View>)}
      </View>}
      {state.tab === 'invitations' && <View>
        {state.invitations.length === 0 && <View className='mmb-empty'><Text>还没有邀请记录。</Text></View>}
        {state.invitations.map(row => <View key={row.invitationId} className='mmb-card'>
          <View className='mmb-avatar'><Text className='mmb-avatar-text'>{row.memberName.slice(0, 1)}</Text></View>
          <View className='mmb-card-main'>
            <View className='mmb-card-line'>
              <Text className='mmb-card-name'>{row.memberName}</Text>
              <Text className={invitationTagClass(row.status)}>{invitationStatusText[row.status]}</Text>
            </View>
            <Text className='mmb-card-phone'>{row.phoneMasked}</Text>
            <Text className='mmb-card-hint'>{row.status === 'INVITED' ? '等待员工本人在微信内确认' : row.status === 'CONFIRMED' ? '该员工已完成绑定' : '邀请已撤销，可重新发起'}</Text>
          </View>
          {row.status === 'INVITED' && <Button
            id={`mmb-cancel-${row.invitationId}`} ariaLabel={`撤销对${row.phoneMasked}的邀请`}
            className='mmb-action mmb-action-danger' disabled={state.busy}
            onClick={() => void cancelInvitation(row)}>撤销</Button>}
        </View>)}
      </View>}
      <View className='mmb-tail'><Text>页面数据：真实接口（contract 54，默认关闭时接口失败关闭）</Text></View>
    </ScrollView>}
  </View>
}
