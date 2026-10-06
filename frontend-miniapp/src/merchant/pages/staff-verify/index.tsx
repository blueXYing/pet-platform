import { Button, Image, Input, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealVerificationRepository } from '../../verify/repository'
import { VerificationController } from '../../verify/controller'
import { isOrderId, verifyResultHint, verifyResultText, verifyTimeText } from '../../verify/model'
import navBack from '../members/assets/nav-back@2x.png'
import './page.css'

// 订单核销页（48 号 K2 v0.3 核销 HTTP 面，PR#114 合入）：OWNER 与 STAFF 共用同一提交界面，
// 身份由服务端按 K1 v0.2 链在 guard 事务内解析（OWNER 常规；双开关下 STAFF 走 52 号动作门），
// merchantId/storeId 由订单 locate，页面不声明门店/店员坐标。提交经 ConsumerApi.write 按
// 订单槽位记 X-Request-Id（23 号 §5：未知结果后原参数重试按原 requestId 重放，返回首回执的
// 原始时间与版本）；200 回执按契约六字段展示（resultCode=VERIFIED 携带后三字段，无效码/过期
// 码/风险锁为已提交业务结果、后三字段 null）；403（动作未授予/开关未开）与 503 依赖故障整页
// 失败关闭；404 防枚举同文案。设计源：登记表 §4.2 —— 原稿帧 23:18830/23:19210 为第三方平台
// 聚合核销语义（美团/大众点评/抖音、手机号查询、底部 tab），与 47/48 号契约冲突，登记为不
// 作为还原依据；本页沿 M 端现行规范（members 页 measures/tokens）实现，非一比一还原。

export default function StaffVerifyPage() {
  const route = useRouter()
  const { scope } = useWorkspace('real')
  const [controller] = useState(() => new VerificationController(new RealVerificationRepository(consumerApi)))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [notice, setNotice] = useState('')
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--msv-status-top': `${platformInfo.statusBarHeight || 0}px`, '--msv-unit': `${unit}px` } as CSSProperties
  // Deep-linkable order id (an M-side order list may hand it over later); still editable.
  const [presetOrderId] = useState(() => route.params.orderId && isOrderId(route.params.orderId) ? route.params.orderId : '')

  const load = useCallback(() => {
    setNotice('')
    // Seed a deep-linked order id once; the field stays editable.
    if (presetOrderId && !controller.getSnapshot().orderId) controller.setOrderId(presetOrderId)
    controller.load(scope.current)
  }, [controller, scope, presetOrderId])
  useDidShow(load)
  useEffect(() => () => controller.dispose(), [controller])

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack().catch(() => setNotice('返回失败，请重试'))
    else await Taro.redirectTo({ url: '/merchant/pages/staff-workbench/index' }).catch(() => setNotice('返回失败，请重试'))
  }
  function goStaffWorkbench() {
    Taro.redirectTo({ url: '/merchant/pages/staff-workbench/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function goWorkspace() {
    Taro.redirectTo({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function submit() {
    void controller.submit()
  }

  const receipt = state.receipt
  const verified = receipt?.resultCode === 'VERIFIED'
  return <View className='msv-page' style={style}>
    <View className='msv-status-area' />
    <View className='msv-header'>
      <Button id='msv-back' ariaLabel='返回' className='msv-back' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='msv-title'>订单核销</Text>
    </View>
    <ScrollView className='msv-body' scrollY enhanced showScrollbar={false}>
      {state.status === 'entry' && <View className='msv-state' role='status'>
        <Text>{state.notice || notice || '请从商家工作台或员工工作台进入核销。'}</Text>
        <Button id='msv-go-workspace' className='msv-state-action' onClick={goWorkspace}>去商家工作台</Button>
        <Button id='msv-go-workbench' className='msv-state-action' onClick={goStaffWorkbench}>去员工工作台</Button>
      </View>}
      {state.status === 'closed' && <View className='msv-panel msv-panel-closed' role='alert'>
        <Text className='msv-panel-title'>核销暂不可用</Text>
        <Text className='msv-panel-line'>{state.notice}</Text>
        <Text className='msv-panel-line'>开关默认关闭，由平台开启核销通道；依赖故障与未获授权同样整页失败关闭，不提供可编辑表单。</Text>
        <Button id='msv-closed-back-form' className='msv-back-workbench' onClick={() => controller.reset()}>返回表单</Button>
      </View>}
      {state.status === 'already' && <View className='msv-panel msv-panel-info' role='status'>
        <Text className='msv-panel-title'>订单已核销</Text>
        <Text className='msv-panel-line'>订单 {state.orderId.trim()} 已完成核销，不可重复核销；重复提交不会产生新的核销记录。</Text>
        <Text className='msv-panel-line'>如对核销结果有疑问，请联系平台核对核销记录（含核销人与门店归属）。</Text>
        <Button id='msv-already-back-form' className='msv-back-workbench' onClick={() => controller.reset()}>返回继续核销其他订单</Button>
      </View>}
      {state.status === 'receipt' && receipt && (verified
        ? <View className='msv-receipt msv-receipt-verified' role='status'>
            <Text className='msv-receipt-title'>核销成功</Text>
            <View className='msv-row'><Text className='msv-row-label'>订单编号</Text><Text className='msv-row-value'>{receipt.orderId}</Text></View>
            <View className='msv-row'><Text className='msv-row-label'>尝试编号</Text><Text className='msv-row-value'>{receipt.attemptId}</Text></View>
            <View className='msv-row'><Text className='msv-row-label'>核销编号</Text><Text className='msv-row-value'>{receipt.verificationId}</Text></View>
            <View className='msv-row'><Text className='msv-row-label'>核销时间</Text><Text className='msv-row-value'>{verifyTimeText(receipt.verifiedAt)}</Text></View>
            <View className='msv-row'><Text className='msv-row-label'>订单版本</Text><Text className='msv-row-value'>{receipt.orderVersion}</Text></View>
            <Text className='msv-receipt-hint'>
              {state.replayed
                ? '本次为同一请求编号的重试回执：以上为首次成功回执的原始核销时间与订单版本（23 号幂等重放语义），不会产生第二次核销。'
                : '核销完成；核销记录含操作身份与门店归属，由平台留痕可追溯。同一订单不可重复核销。'}
            </Text>
            <Button id='msv-verify-next' className='msv-back-workbench' onClick={() => controller.reset()}>继续核销下一单</Button>
          </View>
        : <View className='msv-receipt msv-receipt-rejected' role='status'>
            <Text className='msv-receipt-title'>{verifyResultText[receipt.resultCode]}</Text>
            <View className='msv-row'><Text className='msv-row-label'>订单编号</Text><Text className='msv-row-value'>{receipt.orderId}</Text></View>
            <View className='msv-row'><Text className='msv-row-label'>尝试编号</Text><Text className='msv-row-value'>{receipt.attemptId}</Text></View>
            <Text className='msv-receipt-hint'>{verifyResultHint[receipt.resultCode]}</Text>
            <Text className='msv-receipt-hint'>本次结果已作为已提交业务结果记录（回执后三字段为空）；订单未核销，请顾客重新出示有效核销码后再提交。</Text>
            <Button id='msv-rejected-back-form' className='msv-back-workbench' onClick={() => controller.reset()}>返回重新输入核销码</Button>
          </View>)}
      {(state.status === 'form' || state.status === 'idle') && <View>
        {state.pending && <View className='msv-pending' role='status'>
          <Text className='msv-pending-title'>有未确认的核销提交</Text>
          <Text className='msv-panel-line'>订单 {state.pending.orderId} 的上次提交结果尚未确认。请保持原订单与核销码（{state.pending.verificationCode}）重新提交：将按原请求编号重试并返回首次回执，不会重复核销。</Text>
        </View>}
        <View className='msv-form'>
          <Text className='msv-form-title'>输入核销信息</Text>
          <View className='msv-field'>
            <Text className='msv-field-label'>订单编号</Text>
            <Input id='msv-order-id' className='msv-field-input' type='number' maxlength={19}
              placeholder='顾客订单编号（数字）' value={state.orderId}
              onInput={event => controller.setOrderId(event.detail.value)} />
          </View>
          <View className='msv-field'>
            <Text className='msv-field-label'>核销码</Text>
            <Input id='msv-code' className='msv-field-input' type='text' maxlength={128}
              placeholder='顾客订单详情页出示的核销码' value={state.code}
              onInput={event => controller.setCode(event.detail.value)} />
            <Text className='msv-field-hint'>1至128位大写字母或数字；输入自动按大写提交。</Text>
          </View>
          <Button id='msv-submit' className='msv-submit' disabled={state.busy} onClick={submit}>
            {state.busy ? '核销中…' : '提交核销'}
          </Button>
          <Text className='msv-form-note'>核销人所属门店须与订单门店一致；核销前请与顾客当面确认服务已完成。</Text>
        </View>
      </View>}
      {(state.status === 'form' || state.status === 'idle') && state.notice && <Text id='msv-notice' className='msv-notice'>{state.notice}</Text>}
      {notice && <Text className='msv-notice'>{notice}</Text>}
      <View className='msv-tail'><Text>页面数据：真实接口（contract 48 K2 核销 HTTP 面，平台开关默认关闭时接口失败关闭）</Text></View>
    </ScrollView>
  </View>
}
