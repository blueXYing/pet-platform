import { Button, Image, Input, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi, consumerSession } from '../../../shared/consumer-runtime'
import { RealMembersRepository } from '../../members/repository'
import {
  MemberDetailController, availableMemberActions, type MemberDetailDeps,
} from '../../members/detail-controller'
import {
  isValidPosition, memberStatusClass, memberStatusText, type MemberRow,
} from '../../members/model'
import { previewMembersDeps } from '../../workbench/preview'
import { MerchantTabBar } from '../../components/tab-bar'
import navBack from './assets/nav-back@2x.png'
import './page.css'

// M 端成员详情（P-12；contract 54 read side + owner lifecycle commands; design source:
// merchant frame 12:7842「首页-员工管理」详情态, 402x1673 — identity card with 54 #C0ECFF avatar,
// 基本信息 value rows #F9FAFB/#E5E7EB, filled/danger action buttons #5BAAE8 /
// #FECACA+#EF4444). 54号 has no single-member read endpoint (§4 lists only), so the route
// carries the Snowflake memberId (String, encodeURIComponent'd) and the page re-reads the
// owner member list for fresh member/grant versions. CCR-M-STAFF-POS-001 (2026-11-16): 岗位
// becomes an owner-editable free-text label (PUT position, member version pinned); the
// 操作权限 card is gone — verify is granted by DEFAULT at confirm (D2 single-action catalog),
// so the remaining owner operations are 停用/启用 + 撤门店权（终局；撤权后不可核销但岗位保留）。
// V1 cuts per contract D3: 姓名/手机号编辑、重邀/换绑/恢复 REVOKED 成员均不做；年龄/性别/
// 资质证照/服务单量 carry no contract-54 data and are not rendered (no invented fields).

const MEMBER_ID_PATTERN = /^[1-9][0-9]{0,18}$/

/** ?preview=1 keeps the real path untouched: mock writes ride the same deps interface. */
function previewMemberDetailDeps(): MemberDetailDeps {
  const base = previewMembersDeps()
  const rows = new Map<string, MemberRow>()
  const rowOf = (memberId: string): MemberRow => rows.get(memberId) ?? {
    merchantId: '9007199254740995', storeId: '9007199254740996', memberId,
    memberName: '王小美', position: '美容师', phoneMasked: '138****0001',
    memberStatus: 'ENABLED', grantStatus: 'ENABLED',
    grantedActions: ['merchant.order.verify'], memberVersion: '1', grantVersion: '1',
  }
  const bump = (row: MemberRow, patch: Partial<MemberRow>): MemberRow => ({
    ...row, ...patch, memberVersion: String(Number(row.memberVersion) + 1),
    grantVersion: String(Number(row.grantVersion) + 1),
  })
  return {
    listMembers: async (page, pageSize) => {
      const result = await base.listMembers(page, pageSize)
      result.items.forEach(row => rows.set(row.memberId, row))
      return result
    },
    disableMember: async (_slot, memberId, _version) => bump(rowOf(memberId), { memberStatus: 'DISABLED' }),
    enableMember: async (_slot, memberId, _version) => bump(rowOf(memberId), { memberStatus: 'ENABLED' }),
    revokeStoreGrant: async (_slot, memberId, _version) =>
      bump(rowOf(memberId), { grantStatus: 'REVOKED', grantedActions: [] }),
    updatePosition: async (_slot, memberId, position, _version) =>
      bump(rowOf(memberId), { position }),
  }
}

export default function MerchantMemberDetailPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const memberId = typeof route.params.memberId === 'string'
    ? decodeURIComponent(route.params.memberId) : ''
  const idValid = MEMBER_ID_PATTERN.test(memberId)
  const { scope, revision } = useWorkspace('real')
  // ?preview=1：先注入商户坐标，再挂本地成员样本；真实路径不变。
  useState(() => {
    if (!preview) return false
    const current = scope.current
    if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      scope.replace({
        userId: '9007199254740993', workspace: 'merchant',
        merchantId: '9007199254740995', storeId: '9007199254740996',
      })
    }
    return true
  })
  const [repository] = useState(() => preview
    ? previewMemberDetailDeps()
    : new RealMembersRepository(consumerApi,
      () => consumerApi.scope.current?.merchantId || '',
      () => consumerApi.scope.current?.storeId || ''))
  const [controller] = useState(() => new MemberDetailController(repository))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [notice, setNotice] = useState('')
  const [positionDraft, setPositionDraft] = useState('')
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--mmb-status-top': `${platformInfo.statusBarHeight || 0}px`, '--mmb-unit': `${unit}px` } as CSSProperties
  const previousRevision = useRef(revision)

  const load = useCallback(() => {
    setNotice('')
    if (!idValid) { controller.entry(); setNotice('成员编号无效，请从成员管理列表进入。'); return }
    const current = scope.current
    if (!current) { controller.entry(); setNotice('请先登录后从商家工作台进入。'); return }
    if (current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      controller.entry(); setNotice('请从商家工作台进入成员管理。')
      return
    }
    void controller.load(memberId)
  }, [controller, idValid, memberId, scope])
  useDidShow(() => {
    // The provider's session restore and this page's did-show can race on a fresh entry.
    void (async () => {
      try { await consumerSession.launch() } catch { /* load() renders the error state */ }
      load()
    })()
  })
  useEffect(() => () => controller.dispose(), [controller])
  // Coordinates switched away (workbench leave / account switch) invalidate the detail view.
  useEffect(() => {
    if (previousRevision.current !== revision) {
      previousRevision.current = revision
      const current = scope.current
      if (!current || current.workspace !== 'merchant') controller.entry()
    }
  }, [revision, scope, controller])
  // 岗位草稿跟随成员行（加载/写回执都会替换整行）；打字只改草稿不触发重置。
  useEffect(() => {
    setPositionDraft(state.member?.position ?? '')
  }, [state.member?.memberId, state.member?.position])

  async function back() {
    // P-03 追缉（2026-11-12）：商家域返回不用 navigateBack——隐藏页树可能已被 dispose，
    // 回落是死页；reLaunch 到成员列表（列表 back 再 reLaunch 工作台）。
    try {
      await Taro.reLaunch({ url: '/merchant/pages/members/index' })
    } catch { setNotice('返回失败，请重试') }
  }

  async function confirmModal(title: string, content: string, confirmText: string): Promise<boolean> {
    const confirmed = await Taro.showModal({ title, content, confirmText, cancelText: '取消' })
    return confirmed.confirm
  }

  async function toggleMember() {
    const row = state.member
    if (!row) return
    const disabling = row.memberStatus === 'ENABLED'
    if (disabling) {
      const ok = await confirmModal('停用成员',
        `停用后「${row.memberName}」在本店的核销权限立即失效，历史操作记录保留；可随时恢复。`, '确认停用')
      if (!ok) return
    } else {
      const ok = await confirmModal('启用成员',
        `启用后「${row.memberName}」可在本店继续执行已授权的核销操作。`, '确认启用')
      if (!ok) return
    }
    await controller.toggle(`staff-member:${row.memberId}:${disabling ? 'disable' : 'enable'}`)
  }

  async function revokeStore() {
    const row = state.member
    if (!row) return
    const ok = await confirmModal('撤销门店授权',
      `撤销后「${row.memberName}」在本店的核销权限立即失效且不可恢复（54号终局）；历史操作记录与岗位标签保留。`,
      '确认撤销')
    if (!ok) return
    await controller.revokeStore(`staff-member:${row.memberId}:revoke-store`)
  }

  async function savePosition() {
    const row = state.member
    if (!row) return
    const next = positionDraft.trim()
    if (!isValidPosition(next)) { setNotice('岗位需在 64 个字符以内。'); return }
    // 槽位携带目标值：失败后改文重试换新 requestId，同值重试安全重放（23号幂等）。
    await controller.updatePosition(`staff-member:${row.memberId}:position:${next}`, next)
  }

  const member = state.member
  const actions = availableMemberActions(member)
  const ready = state.status === 'ready' && member
  return <View className='mmb-page' style={style}>
    <View className='mmb-status-area' />
    <View className='mmb-header'>
      <Button id='mmd-back' ariaLabel='返回' className='mmb-back' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mmb-title'>成员详情</Text>
    </View>
    {!ready && <View className='mmb-state' role='status'>
      <Text>{state.status === 'loading' || state.status === 'idle' ? '正在读取成员信息…'
        : state.status === 'not-found' ? '该成员不存在或已不在本店成员列表中。'
        : state.notice || notice || '加载失败，请重试。'}</Text>
      {(state.status === 'load-error' || state.status === 'not-found') && idValid
        && <Button id='mmd-retry' className='mmb-state-action' onClick={load}>重新加载</Button>}
      {state.status === 'entry' && <Button className='mmb-state-action'
        onClick={() => Taro.reLaunch({ url: '/merchant/pages/workspace/index' })}>去商家工作台</Button>}
      <Button className='mmb-state-action' onClick={() => void back()}>返回成员列表</Button>
    </View>}
    {ready && member && <ScrollView className='mmd-body' scrollY enhanced showScrollbar={false}>
      <View className='mmd-identity'>
        <View className='mmd-avatar'><Text className='mmd-avatar-text'>{member.memberName.slice(0, 1)}</Text></View>
        <View className='mmd-identity-main'>
          <Text className='mmd-identity-name'>{member.memberName}</Text>
          <View className={memberStatusClass(member.memberStatus)}><Text className='mmb-state-dot' /><Text className='mmb-state-text'>{memberStatusText[member.memberStatus]}</Text></View>
          <Text className='mmd-identity-phone'>{member.phoneMasked}（脱敏）</Text>
        </View>
      </View>
      <View className='mmd-card'>
        <Text className='mmd-card-title'>基本信息</Text>
        <View className='mmd-field'>
          <Text className='mmd-field-label'>姓名</Text>
          <Text id='mmd-name' className='mmd-field-value'>{member.memberName}</Text>
        </View>
        <View className='mmd-field'>
          <Text className='mmd-field-label'>手机号</Text>
          <Text id='mmd-phone' className='mmd-field-value'>{member.phoneMasked}</Text>
        </View>
        <View className='mmd-field'>
          <Text className='mmd-field-label'>岗位</Text>
          <View className='mmd-position-row'>
            <Input id='mmd-position-input' className='mmd-position-input' maxlength={64}
              placeholder='如：美容师 / 前台（选填）' value={positionDraft}
              onInput={event => setPositionDraft(event.detail.value)} />
            <Button id='mmd-position-save' ariaLabel={`保存${member.memberName}的岗位`}
              className='mmd-position-save' disabled={state.busy || positionDraft.trim() === member.position}
              onClick={() => void savePosition()}>保存</Button>
          </View>
          <Text className='mmd-hint'>岗位为老板可编辑的自由文本标签，仅用于展示，不关联操作权限。</Text>
        </View>
        <View className='mmd-field'>
          <Text className='mmd-field-label'>成员状态</Text>
          <Text id='mmd-status' className='mmd-field-value'>{memberStatusText[member.memberStatus]}</Text>
        </View>
        <Text className='mmd-hint'>姓名与手机号来自邀请绑定时登记的信息，V1 不支持修改（54号 D3 保留）。</Text>
      </View>
      <View className='mmd-ops'>
        {notice && <Text id='mmd-notice' className='mmb-notice'>{notice}</Text>}
        {state.notice && <Text id='mmd-controller-notice' className='mmb-notice'>{state.notice}</Text>}
        {actions.includes('disable') && <Button
          id='mmd-disable' ariaLabel={`停用${member.memberName}`}
          className='mmd-op mmd-op-danger' disabled={state.busy}
          onClick={() => void toggleMember()}>停用成员</Button>}
        {actions.includes('enable') && <Button
          id='mmd-enable' ariaLabel={`启用${member.memberName}`}
          className='mmd-op mmd-op-primary' disabled={state.busy}
          onClick={() => void toggleMember()}>启用成员</Button>}
        {actions.includes('revoke-store') && <Button
          id='mmd-revoke' ariaLabel={`撤销${member.memberName}的门店授权`}
          className='mmd-op mmd-op-danger' disabled={state.busy}
          onClick={() => void revokeStore()}>撤销门店授权</Button>}
        {actions.length === 0 && <Text className='mmd-terminal'>门店授权已撤回（终局）：本店无可执行操作，岗位标签仍可编辑。</Text>}
      </View>
      <Text className='mmd-tail'>仅展示 54号契约数据 · 手机号脱敏展示</Text>
      {preview && <Text className='mmd-tail'>页面数据：契约 Mock（preview=1，不联调）</Text>}
      <View className='mmd-bottom-space' />
      <View className='mmb-tab-space' />
    </ScrollView>}
    <MerchantTabBar active='home' unit={unit} />
  </View>
}
