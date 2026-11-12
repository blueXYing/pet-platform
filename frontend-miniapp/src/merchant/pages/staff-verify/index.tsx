import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi, consumerSession } from '../../../shared/consumer-runtime'
import { RealVerificationRecordRepository } from '../../verify-records/repository'
import { VerificationRecordListController } from '../../verify-records/controller'
import {
  verifiedAtLabel, verificationAmountText, verificationCustomerInitial, verificationCustomerName,
  verificationOperatorClass, verificationOperatorText, verificationOrderNo, verificationServiceName,
  type VerificationRecordItem,
} from '../../verify-records/model'
import { MerchantTabBar } from '../../components/tab-bar'
import navBack from '../members/assets/nav-back@2x.png'
import './page.css'

// 核销记录页（P-13 方案 B，CCR-M-VERIFY-RECORD-001 §3/§9，2026-11-12 批准）：商家端「核销」
// tab 由核销执行表单改读本店核销记录列表（核销执行已迁员工工作台页内面板，48 号 K2 端点
// 不变）。可见范围仅商家 OWNER（§9.3）——员工共用本 tab，落此页时按 entry 态引导回工作台
// 而非失败关闭；卡面客户块沿 CCR-M-ORDER-CARD-001 口径（昵称+头像、空值降级、无手机号），
// 核销人按 §9.1：STAFF=成员真名、OWNER=「店长」固定标签（胶囊配色区分）。
// 设计源 Figma 12:7426（头带 86 #fff6e5 + 白卡 #c0ecff 描边 1 + 圆角 15.39 + 紧凑时间文案）；
// 原稿帧内的「全部/已核销/待核销」筛选 tab 与「美团/抖音/大众点评」渠道标签为第三方平台
// 聚合核销语义（同 23:18830 登记纪律），契约无对应过滤/字段，不作为还原依据，不渲染。
// 分页沿服务端固定排序 verified_at DESC, id DESC 稳定页，触底加载按 verificationId 去重。

/** CCR-M-ORDER-CARD-001 客户头像：URL 为空或加载失败降级为首字占位圆，不伪造图片。 */
function CustomerAvatar({ url, initial }: { url: string | null; initial: string }) {
  const [failed, setFailed] = useState(false)
  if (url !== null && !failed) {
    return <Image className='msvr-avatar msvr-avatar-image' src={url} mode='aspectFill' onError={() => setFailed(true)} />
  }
  return <View className='msvr-avatar'><Text>{initial}</Text></View>
}

/** 核销记录卡（只读事实卡，无页内操作）：订单短号+核销人胶囊 / 服务名 / 客户块+金额 / 时间。 */
function RecordCard({ item }: { item: VerificationRecordItem }) {
  return <View className='msvr-card' id={`msvr-card-${item.verificationId}`}>
    <View className='msvr-card-head'>
      <Text className='msvr-card-no'>{verificationOrderNo(item)}</Text>
      <Text className={verificationOperatorClass(item)}>{verificationOperatorText(item)}</Text>
    </View>
    <Text className='msvr-card-service'>{verificationServiceName(item)}</Text>
    <View className='msvr-card-mid'>
      <View className='msvr-customer'>
        <CustomerAvatar url={item.customerAvatarUrl} initial={verificationCustomerInitial(item)} />
        <Text className='msvr-card-name'>{verificationCustomerName(item)}</Text>
      </View>
      <Text className='msvr-card-amount'>¥{verificationAmountText(item)}</Text>
    </View>
    <Text className='msvr-card-time'>{verifiedAtLabel(item.verifiedAt)}</Text>
  </View>
}

export default function StaffVerifyPage() {
  const { scope } = useWorkspace('real')
  const [controller] = useState(() => new VerificationRecordListController({
    scope,
    list: query => new RealVerificationRecordRepository(consumerApi).list(query),
  }))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [notice, setNotice] = useState('')
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--msvr-status-top': `${platformInfo.statusBarHeight || 0}px`, '--msvr-unit': `${unit}px` } as CSSProperties

  const load = useCallback(() => {
    setNotice('')
    void controller.load()
  }, [controller])
  useDidShow(() => {
    // Resolve the session first so the first read carries a live credential (order-list race).
    void (async () => {
      try { await consumerSession.launch() } catch { /* load() renders the error state */ }
      load()
    })()
  })
  useEffect(() => () => controller.dispose(), [controller])

  async function back() {
    // Same disposed-workbench issue as order-confirm: rebuild the merchant workbench so the
    // dashboard re-enters admission instead of showing stale placeholders.
    await Taro.reLaunch({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('返回失败，请重试'))
  }
  function goWorkspace() {
    // P-03：进工作台一律 reLaunch 重建（redirectTo 会残留已 dispose 的旧工作台实例，白屏）。
    Taro.reLaunch({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }

  return <View className='msvr-page' style={style}>
    <View className='msvr-status-area' />
    <View className='msvr-header'>
      <Button id='msvr-back' ariaLabel='返回' className='msvr-back' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='msvr-title'>核销记录</Text>
    </View>
    <ScrollView className='msvr-body' scrollY enhanced showScrollbar={false}
      onScrollToLower={() => void controller.loadMore()}>
      {(state.status === 'idle' || state.status === 'loading') && <View className='msvr-state' role='status'><Text id='msvr-loading'>正在读取核销记录…</Text></View>}
      {state.status === 'entry' && <View className='msvr-state' role='status'>
        <Text id='msvr-entry-hint'>{state.notice || notice || '仅商家店长可查看核销记录。'}</Text>
        <Button id='msvr-go-workspace' className='msvr-state-action' onClick={goWorkspace}>去商家工作台</Button>
      </View>}
      {state.status === 'closed' && <View className='msvr-panel msvr-panel-closed' role='alert'>
        <Text className='msvr-panel-title'>核销记录暂不可用</Text>
        <Text className='msvr-panel-line'>{state.notice}</Text>
        <Text className='msvr-panel-line'>读取通道默认由平台开启；依赖故障时整页失败关闭，不渲染任何核销记录。</Text>
      </View>}
      {state.status === 'load-error' && <View className='msvr-state' role='status'>
        <Text id='msvr-error'>{state.notice || '核销记录读取失败，请稍后重试。'}</Text>
        <Button id='msvr-retry' className='msvr-state-action' onClick={() => void controller.load()}>重新加载</Button>
      </View>}
      {state.status === 'ready' && state.items.length === 0 && <View className='msvr-state'>
        <Text id='msvr-empty'>本店暂无核销记录；在员工工作台或店长本人完成核销后，记录会显示在这里。</Text>
      </View>}
      {state.status === 'ready' && state.items.map(item => <RecordCard key={item.verificationId} item={item} />)}
      {state.status === 'ready' && state.items.length < state.total
        && <Text id='msvr-load-more' className='msvr-more-hint'>
          {state.loadingMore ? '正在加载更多…' : `上拉加载更多（${state.items.length}/${state.total}）`}
        </Text>}
      {state.notice && state.status === 'ready' && <Text id='msvr-notice' className='msvr-notice'>{state.notice}</Text>}
      {notice && <Text className='msvr-notice'>{notice}</Text>}
      <View className='msvr-tab-space' />
    </ScrollView>
    <MerchantTabBar active='verify' unit={unit} />
  </View>
}
