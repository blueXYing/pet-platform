import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { MerchantAdmissionRepository } from '../../../shared/merchant-repositories'
import { StaffWorkbenchController } from '../../staff/workbench'
import { StaffInvitationListController } from '../../staff/controller'
import { previewStaffInvitationListDeps } from '../../staff/preview'
import { RealStaffInvitationRepository } from '../../staff/repository'
import { invitationStatusText, staffActionText, type InvitationStatus } from '../../staff/model'
import navBack from '../members/assets/nav-back@2x.png'
import './page.css'

// 员工工作台（2026-10-06 用户裁决：员工确认入口定在商家端小程序的员工工作台；区别于 OWNER
// 工作台）。设计源：登记表 §3/§4 复核无「员工工作台」原稿帧，按登记表规范登记缺稿，沿 M 端
// 现行规范（members 页 12:6591 的 measures/tokens）实现。按 54/48 号契约里员工可见的能力组织：
// 核销入口（48 K2 v0.3 核销 HTTP 面已交付，目标页为 OWNER/STAFF 共用真交互提交页，身份由服务
// 端按 K1 链解析）与我的邀请记录（54 §7 员工侧列表：登录手机号服务端匹配，待确认邀请高亮进
// 确认页，终局态只读；此前 #111 的编号查询入口已被该真列表替换）。

const admissionText: Record<string, string> = {
  ALLOWED: '可正常使用',
  LIMITED: '受限访问',
  DENIED: '暂不可用',
}
const reasonText: Record<string, string> = {
  STAFF_DISABLED: '员工账号已被停用，请联系商家恢复',
  MERCHANT_OFFLINE: '商家已下线，暂停新经营业务',
  STORE_OFFLINE: '门店已下线，暂停新经营业务',
  MERCHANT_FROZEN: '商家已冻结，受限访问',
  STORE_FROZEN: '门店已冻结，受限访问',
  SIGNING_REQUIRED: '商家协议未签署，暂不可用',
}

export default function StaffWorkbenchPage() {
  const { scope, revision, context } = useWorkspace('real')
  const [controller] = useState(() => new StaffWorkbenchController({
    admission: (merchantId, storeId) => new MerchantAdmissionRepository(consumerApi).admission(merchantId, storeId),
  }))
  const route = useRouter()
  const preview = route.params.preview === '1'
  const [invitations] = useState(() => new StaffInvitationListController(
    preview ? previewStaffInvitationListDeps() : new RealStaffInvitationRepository(consumerApi)))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const listState = useSyncExternalStore(invitations.subscribe, invitations.getSnapshot)
  const [notice, setNotice] = useState('')
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--msw-status-top': `${platformInfo.statusBarHeight || 0}px`, '--msw-unit': `${unit}px` } as CSSProperties
  const previousRevision = useRef(revision)

  const load = useCallback(() => {
    setNotice('')
    controller.load(scope.current)
    // 54 §7 list is session-scoped, not admission-scoped: pre-binding employees (unbound)
    // need it most — the list is how they find their pending invitation without a number.
    if (scope.current) void invitations.load()
  }, [controller, invitations, scope])
  useDidShow(load)
  useEffect(() => () => { controller.dispose(); invitations.dispose() }, [controller, invitations])
  // Coordinate/account switches invalidate the admission projection and the phone-scoped list.
  useEffect(() => {
    if (previousRevision.current !== revision) {
      previousRevision.current = revision
      controller.load(scope.current)
      if (scope.current) void invitations.load()
    }
  }, [revision, scope, controller, invitations])

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack().catch(() => setNotice('返回失败，请重试'))
    else if (scope.current?.workspace === 'merchant') await Taro.redirectTo({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('返回失败，请重试'))
    else await Taro.reLaunch({ url: '/consumer/pages/shell/index' }).catch(() => setNotice('返回失败，请重试'))
  }

  // WXSS 状态样式一律显式 className 变体（页面级 msw- 前缀，沿 members/msi 页先例）。
  function tagClass(status: InvitationStatus): string {
    if (status === 'INVITED') return 'msw-inv-tag msw-inv-tag-invited'
    if (status === 'CANCELED') return 'msw-inv-tag msw-inv-tag-canceled'
    if (status === 'CONFIRMED') return 'msw-inv-tag msw-inv-tag-confirmed'
    return 'msw-inv-tag'
  }

  function openInvitation(invitationId: string) {
    setNotice('')
    Taro.navigateTo({ url: `/merchant/pages/staff-invitation/index?invitationId=${invitationId}` })
      .catch(() => setNotice('页面跳转失败，请重试'))
  }

  function openVerify() {
    Taro.navigateTo({ url: '/merchant/pages/staff-verify/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }

  function goWorkspace() {
    Taro.redirectTo({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function goShell() {
    Taro.reLaunch({ url: '/consumer/pages/shell/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }

  // 54号 §7「我的邀请记录」区块：同一控制器渲染于未绑定（待确认引导）与已准入（历史记录）
  // 两种形态；待确认邀请高亮可进确认页，终局态只读；失败关闭（开关关 404 / 依赖 503 同形）。
  function invitationListCard() {
    return <View className='msw-card' id='msw-invitation-card'>
      <Text className='msw-card-title'>我的邀请记录</Text>
      <Text className='msw-card-hint'>发给当前登录手机号的商家邀请（含已确认/已撤销历史）。</Text>
      {preview && <Text className='msw-inv-preview-note'>预览模式：本地样例数据，仅用于设计验收，不发起真实请求。</Text>}
      {listState.status === 'loading' && <View className='msw-inv-state' role='status'><Text>正在读取邀请记录…</Text></View>}
      {listState.status === 'idle' && <View className='msw-inv-state' role='status'><Text>登录后可查看发给您的邀请。</Text></View>}
      {listState.status === 'error' && <View className='msw-inv-state' role={listState.closed ? 'status' : 'alert'}>
        <Text>{listState.closed ? '功能未开放或暂不可见，邀请记录无法加载。' : listState.notice || '邀请记录加载失败。'}</Text>
        {!listState.closed && <Button id='msw-inv-retry' className='msw-state-action' onClick={() => void invitations.load()}>重新加载</Button>}
      </View>}
      {listState.status === 'ready' && listState.items.length === 0 && <View className='msw-inv-state' role='status'><Text>暂无邀请记录；商家发出邀请后会显示在这里。</Text></View>}
      {listState.status === 'ready' && listState.items.map(item => {
        const pending = item.status === 'INVITED'
        return <View key={item.invitationId}
          className={pending ? 'msw-inv-row msw-inv-row-pending' : 'msw-inv-row'}
          onClick={() => pending && openInvitation(item.invitationId)}>
          <View className='msw-inv-line'>
            <Text className='msw-inv-store'>{item.storeName}</Text>
            <Text className={tagClass(item.status)}>{invitationStatusText[item.status]}</Text>
          </View>
          <Text className='msw-inv-merchant'>{item.merchantName} · {item.memberName} · 邀请于 {item.invitedAt.slice(0, 10)}</Text>
          <View className='msw-inv-line'>
            <Text className='msw-inv-actions'>{item.grantedActions.map(action => staffActionText[action] || action).join('、')}</Text>
            {pending && <Text className='msw-inv-go'>去确认</Text>}
          </View>
        </View>
      })}
      {listState.status === 'ready' && listState.items.length < listState.total
        && <Button id='msw-inv-more' className='msw-secondary'
          onClick={() => void invitations.loadMore()} disabled={listState.loadingMore}>
          {listState.loadingMore ? '正在加载…' : `加载更多（${listState.items.length}/${listState.total}）`}
        </Button>}
    </View>
  }

  const view = state.view
  const admitted = state.status === 'ready'
  return <View className='msw-page' style={style}>
    <View className='msw-status-area' />
    <View className='msw-header'>
      <Button id='msw-back' ariaLabel='返回' className='msw-back' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='msw-title'>员工工作台</Text>
    </View>
    <ScrollView className='msw-body' scrollY enhanced showScrollbar={false}>
      {state.status === 'entry' && <View className='msw-state' role='status'>
        <Text>{state.notice || '请先登录后进入员工工作台。'}</Text>
        <Button id='msw-go-shell' className='msw-state-action' onClick={goShell}>去登录</Button>
      </View>}
      {(state.status === 'idle' || state.status === 'checking') && <View className='msw-state' role='status'><Text>正在校验员工准入…</Text></View>}
      {state.status === 'error' && <View className='msw-state' role='alert'>
        <Text>{state.notice || '员工准入查询失败，功能未开放。'}</Text>
        <Button id='msw-retry' className='msw-state-action' onClick={load}>重新加载</Button>
        <Button className='msw-state-action' onClick={goWorkspace}>回商家工作台</Button>
      </View>}
      {state.status === 'owner' && <View className='msw-state' role='status'>
        <Text>{state.notice || '当前账号是商家主账号，请使用商家工作台。'}</Text>
        <Button id='msw-go-owner-workbench' className='msw-state-action' onClick={goWorkspace}>去商家工作台</Button>
      </View>}
      {state.status === 'unbound' && <View>
        {invitationListCard()}
        <View className='msw-card'>
          <View className='msw-entry msw-entry-disabled'>
            <Text className='msw-entry-title'>订单核销</Text>
            <Text className='msw-entry-hint'>完成员工绑定并经商家工作区进入后可用。</Text>
          </View>
          <Button id='msw-go-workspace' className='msw-secondary' onClick={goWorkspace}>已是店员？经商家工作区进入</Button>
        </View>
      </View>}
      {admitted && view && <View>
        <View className='msw-card'>
          <View className='msw-card-line'>
            <Text className='msw-card-title'>门店 {view.storeId}</Text>
            <Text className={view.admission === 'ALLOWED' ? 'msw-admission msw-admission-allowed' : 'msw-admission'}>{admissionText[view.admission] || view.admission}</Text>
          </View>
          <Text className='msw-card-hint'>商家 {view.merchantId} · 核销员身份 {view.facts.staffEnabled === true ? '启用中' : '未启用'}</Text>
          {view.reasonCodes.length > 0 && <View className='msw-reasons' role='status'>
            {view.reasonCodes.map(code => <Text key={code}>· {reasonText[code] || code}</Text>)}
          </View>}
          <Text className='msw-card-meta'>校验时间 {view.checkedAt} · authzVersion {view.authzVersion}</Text>
        </View>
        {/* 核销入口（48 K2 v0.3）：准入判定在此，命令通道在目标页（真交互提交，默认开关关时
            目标页按 403/503 整页失败关闭）。 */}
        <Button id='msw-open-verify' className='msw-entry-button' onClick={openVerify}>
          <Text className='msw-entry-title'>订单核销</Text>
          <Text className='msw-entry-hint'>为到店顾客核销服务订单；核销人所属门店须与订单门店一致。</Text>
        </Button>
        {invitationListCard()}
      </View>}
      {notice && <Text id='msw-notice' className='msw-notice'>{notice}</Text>}
      <View className='msw-tail'><Text>当前工作区：{context?.workspace ?? '未登录'}{context?.storeId ? ` · ${context.storeId}` : ''}；页面数据：真实接口（contract 52/54）</Text></View>
    </ScrollView>
  </View>
}
