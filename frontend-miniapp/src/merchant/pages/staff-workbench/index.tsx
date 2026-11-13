import { Button, Image, Input, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi, consumerSession } from '../../../shared/consumer-runtime'
import { phoneGuard } from '../../../shared/phone-guard-runtime'
import { MerchantAdmissionRepository } from '../../../shared/merchant-repositories'
import { StaffWorkbenchController } from '../../staff/workbench'
import { StaffInvitationListController } from '../../staff/controller'
import { previewStaffInvitationListDeps } from '../../staff/preview'
import { RealStaffInvitationRepository } from '../../staff/repository'
import { invitationStatusText, staffActionText, type InvitationStatus } from '../../staff/model'
import { RealVerificationRepository } from '../../verify/repository'
import { VerificationController } from '../../verify/controller'
import { verifyResultHint, verifyResultText, verifyTimeText } from '../../verify/model'
import { MerchantTabBar } from '../../components/tab-bar'
import navBack from '../members/assets/nav-back@2x.png'
import './page.css'

// 员工工作台（2026-10-06 用户裁决：员工确认入口定在商家端小程序的员工工作台；区别于 OWNER
// 工作台）。设计源：登记表 §3/§4 复核无「员工工作台」原稿帧，按登记表规范登记缺稿，沿 M 端
// 现行规范（members 页 12:6591 的 measures/tokens）实现。按 54/48 号契约里员工可见的能力组织：
// 订单核销执行面板（P-13 方案 B：原 staff-verify 页的核销执行表单迁入本页常显卡区，48 号 K2
// v0.3 核销 HTTP 面不变，OWNER/STAFF 共用同一提交界面、身份由服务端按 K1 链解析；staff-verify
// 页已改为 OWNER 专属核销记录列表，CCR-M-VERIFY-RECORD-001）与我的邀请记录（54 §7 员工侧
// 列表：登录手机号服务端匹配，待确认邀请高亮进确认页，终局态只读；此前 #111 的编号查询入口
// 已被该真列表替换）。

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
  // 48号 K2 核销执行（P-13 方案 B 迁入）：常驻面板控制器，load 随工作台 did-show 重读。
  const [verification] = useState(() => new VerificationController(new RealVerificationRepository(consumerApi)))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const listState = useSyncExternalStore(invitations.subscribe, invitations.getSnapshot)
  const verifyState = useSyncExternalStore(verification.subscribe, verification.getSnapshot)
  const [notice, setNotice] = useState('')
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--msw-status-top': `${platformInfo.statusBarHeight || 0}px`, '--msw-unit': `${unit}px` } as CSSProperties
  const previousRevision = useRef(revision)

  const load = useCallback(() => {
    void (async () => {
      setNotice('')
      // The employee side of contracts 52/54 needs a C session before the admission and
      // phone-scoped invitation reads: a deep link can land here before the session exists.
      try { await consumerSession.launch() } catch { /* the controllers render the error state */ }
      controller.load(scope.current)
      // 48号 K2 核销面板：did-show 重读工作区坐标与未知结果日志（核销完成后返回即复位表单）。
      verification.load(scope.current)
      // 54 §7 list is session-scoped, not admission-scoped: pre-binding employees (unbound)
      // need it most — the list is how they find their pending invitation without a number.
      if (scope.current) void invitations.load()
    })()
  }, [controller, invitations, verification, scope])
  useDidShow(load)
  useEffect(() => () => { controller.dispose(); invitations.dispose(); verification.dispose() }, [controller, invitations, verification])
  // Coordinate/account switches invalidate the admission projection and the phone-scoped list.
  useEffect(() => {
    if (previousRevision.current !== revision) {
      previousRevision.current = revision
      controller.load(scope.current)
      verification.load(scope.current)
      if (scope.current) void invitations.load()
    }
  }, [revision, scope, controller, invitations, verification])

  async function back() {
    // P-03 追缉（2026-11-12）：仅当正下方是 consumer 页时才 navigateBack（consumer 树安全）；
    // 落到工作台一律 reLaunch 重建（navigateBack 会落在被 dispose 的旧工作台死页上）。
    const pages = Taro.getCurrentPages()
    const below = pages.length > 1 ? pages[pages.length - 2].route : null
    if (below && !below.startsWith('merchant/')) await Taro.navigateBack().catch(() => setNotice('返回失败，请重试'))
    else if (scope.current?.workspace === 'merchant') await Taro.reLaunch({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('返回失败，请重试'))
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
    // 手机号前置守卫（用户 2026-10-08 裁决的接入点）：员工邀请确认须由被邀请手机号本人完成，
    // 无已验证会话（即无绑定手机号）时不进确认页，引导去「我的」页授权；守卫内部会先做一次
    // 静默恢复，仅会话真正缺失才拦截。
    void phoneGuard(() => {
      Taro.navigateTo({ url: `/merchant/pages/staff-invitation/index?invitationId=${invitationId}` })
        .catch(() => setNotice('页面跳转失败，请重试'))
    })
  }

  function goWorkspace() {
    // P-03：进工作台一律 reLaunch 重建（redirectTo 会残留已 dispose 的旧工作台实例，白屏）。
    Taro.reLaunch({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function goShell() {
    Taro.switchTab({ url: '/consumer/pages/mine/index' }).catch(() => setNotice('页面跳转失败，请重试'))
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

  // 48号 K2 v0.3 订单核销执行面板（P-13 方案 B：自 staff-verify 页原样迁入的提交表单与回执，
  // CCR-M-VERIFY-RECORD-001；常显卡片区，随页面节奏不再跳页）：OWNER 与 STAFF 共用同一提交
  // 界面，身份由服务端按 K1 v0.2 链在 guard 事务内解析；提交经 ConsumerApi.write 按订单槽位记
  // X-Request-Id（23号 §5 幂等重放）；200 回执六字段展示，VERIFICATION_ALREADY_DONE 走已核销
  // 面板，403（动作未授予/开关未开）与 503 依赖故障整块失败关闭。
  function verifyPanelCard() {
    const receipt = verifyState.receipt
    const verified = receipt?.resultCode === 'VERIFIED'
    return <View className='msw-card' id='msw-verify-panel'>
      <Text className='msw-card-title'>订单核销</Text>
      <Text className='msw-card-hint'>为到店顾客核销服务订单；核销人所属门店须与订单门店一致。</Text>
      {verifyState.status === 'entry' && <View className='mswv-state' role='status'>
        <Text>{verifyState.notice || '请经商家工作区进入员工工作台后核销。'}</Text>
      </View>}
      {verifyState.status === 'closed' && <View className='mswv-panel mswv-panel-closed' role='alert'>
        <Text className='mswv-panel-title'>核销暂不可用</Text>
        <Text className='mswv-panel-line'>{verifyState.notice}</Text>
        <Text className='mswv-panel-line'>开关默认关闭，由平台开启核销通道；依赖故障与未获授权同样失败关闭，不提供可编辑表单。</Text>
        <Button id='mswv-closed-back-form' className='mswv-back-form' onClick={() => verification.reset()}>返回表单</Button>
      </View>}
      {verifyState.status === 'already' && <View className='mswv-panel mswv-panel-info' role='status'>
        <Text className='mswv-panel-title'>订单已核销</Text>
        <Text className='mswv-panel-line'>订单 {verifyState.orderId.trim()} 已完成核销，不可重复核销；重复提交不会产生新的核销记录。</Text>
        <Text className='mswv-panel-line'>如对核销结果有疑问，请联系平台核对核销记录（含核销人与门店归属）。</Text>
        <Button id='mswv-already-back-form' className='mswv-back-form' onClick={() => verification.reset()}>返回继续核销其他订单</Button>
      </View>}
      {verifyState.status === 'receipt' && receipt && (verified
        ? <View className='mswv-receipt mswv-receipt-verified' role='status'>
            <Text className='mswv-receipt-title'>核销成功</Text>
            <View className='mswv-row'><Text className='mswv-row-label'>订单编号</Text><Text className='mswv-row-value'>{receipt.orderId}</Text></View>
            <View className='mswv-row'><Text className='mswv-row-label'>尝试编号</Text><Text className='mswv-row-value'>{receipt.attemptId}</Text></View>
            <View className='mswv-row'><Text className='mswv-row-label'>核销编号</Text><Text className='mswv-row-value'>{receipt.verificationId}</Text></View>
            <View className='mswv-row'><Text className='mswv-row-label'>核销时间</Text><Text className='mswv-row-value'>{verifyTimeText(receipt.verifiedAt)}</Text></View>
            <View className='mswv-row'><Text className='mswv-row-label'>订单版本</Text><Text className='mswv-row-value'>{receipt.orderVersion}</Text></View>
            <Text className='mswv-receipt-hint'>
              {verifyState.replayed
                ? '本次为同一请求编号的重试回执：以上为首次成功回执的原始核销时间与订单版本（23 号幂等重放语义），不会产生第二次核销。'
                : '核销完成；核销记录含操作身份与门店归属，由平台留痕可追溯。同一订单不可重复核销。'}
            </Text>
            <Button id='mswv-verify-next' className='mswv-back-form' onClick={() => verification.reset()}>继续核销下一单</Button>
          </View>
        : <View className='mswv-receipt mswv-receipt-rejected' role='status'>
            <Text className='mswv-receipt-title'>{verifyResultText[receipt.resultCode]}</Text>
            <View className='mswv-row'><Text className='mswv-row-label'>订单编号</Text><Text className='mswv-row-value'>{receipt.orderId}</Text></View>
            <View className='mswv-row'><Text className='mswv-row-label'>尝试编号</Text><Text className='mswv-row-value'>{receipt.attemptId}</Text></View>
            <Text className='mswv-receipt-hint'>{verifyResultHint[receipt.resultCode]}</Text>
            <Text className='mswv-receipt-hint'>本次结果已作为已提交业务结果记录（回执后三字段为空）；订单未核销，请顾客重新出示有效核销码后再提交。</Text>
            <Button id='mswv-rejected-back-form' className='mswv-back-form' onClick={() => verification.reset()}>返回重新输入核销码</Button>
          </View>)}
      {(verifyState.status === 'form' || verifyState.status === 'idle') && <View>
        {verifyState.pending && <View className='mswv-pending' role='status'>
          <Text className='mswv-pending-title'>有未确认的核销提交</Text>
          <Text className='mswv-panel-line'>订单 {verifyState.pending.orderId} 的上次提交结果尚未确认。请保持原订单与核销码（{verifyState.pending.verificationCode}）重新提交：将按原请求编号重试并返回首次回执，不会重复核销。</Text>
        </View>}
        <View className='mswv-form'>
          <Text className='mswv-form-title'>输入核销信息</Text>
          <View className='mswv-field'>
            <Text className='mswv-field-label'>订单编号</Text>
            <Input id='mswv-order-id' className='mswv-field-input' type='number' maxlength={19}
              placeholder='顾客订单编号（数字）' value={verifyState.orderId}
              onInput={event => verification.setOrderId(event.detail.value)} />
          </View>
          <View className='mswv-field'>
            <Text className='mswv-field-label'>核销码</Text>
            <Input id='mswv-code' className='mswv-field-input' type='text' maxlength={128}
              placeholder='顾客订单详情页出示的核销码' value={verifyState.code}
              onInput={event => verification.setCode(event.detail.value)} />
            <Text className='mswv-field-hint'>1至128位大写字母或数字；输入自动按大写提交。</Text>
          </View>
          <Button id='mswv-submit' className='mswv-submit' disabled={verifyState.busy} onClick={() => void verification.submit()}>
            {verifyState.busy ? '核销中…' : '提交核销'}
          </Button>
          <Text className='mswv-form-note'>核销前请与顾客当面确认服务已完成；核销完成后可在商家端「核销」tab 的核销记录中查看。</Text>
        </View>
      </View>}
      {(verifyState.status === 'form' || verifyState.status === 'idle') && verifyState.notice && <Text id='mswv-notice' className='mswv-notice'>{verifyState.notice}</Text>}
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
        {/* 订单核销执行面板（48号 K2 v0.3，P-13 方案 B 迁入页内常显，不再跳转 staff-verify——
            该页已改为 OWNER 核销记录列表，CCR-M-VERIFY-RECORD-001）：准入判定在此，命令通道
            在本面板（真交互提交，默认开关关时按 403/503 整块失败关闭）。 */}
        {verifyPanelCard()}
        {invitationListCard()}
      </View>}
      {notice && <Text id='msw-notice' className='msw-notice'>{notice}</Text>}
      {preview && <View className='msw-tail'><Text>页面数据：契约 Mock（preview=1，不联调）</Text></View>}
      <View className='msw-tab-space' />
    </ScrollView>
    <MerchantTabBar active='verify' unit={unit} />
  </View>
}
