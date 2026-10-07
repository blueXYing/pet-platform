import { Button, Image, Input, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealMerchantOrderRepository } from '../../order/repository'
import { OrderDecisionController } from '../../order/controller'
import { REJECT_REASON_CODES, decisionTimeText, isOrderId, rejectReasonText } from '../../order/model'
import navBack from '../members/assets/nav-back@2x.png'
import './page.css'

// 商家订单处理页（45 号手动接单/拒单 HTTP 面，本切片）：OWNER 主账号按单号进入处理，
// 身份由服务端在命令 guard 事务内每次执行与重放重验（页面不声明门店坐标）。M 端暂无
// 商家订单列表读侧，按单处理并在页面披露；待接单订单为支付后 30 分钟内（逾期系统自动
// 接单，409 截止错误面整页提示）。接单可附 0～200 码点店内备注（不对 C 端公开）；拒单
// 必填五类编码之一 + 5～200 码点原因文本（不修剪改写，敏感词审核服务端执行）。提交经
// ConsumerApi.write 按 merchant-confirm/reject:{merchantId}:{orderId} 槽位记 X-Request-Id
// （23 号 §5：未知结果后原参数重试按原 requestId 重放，返回首回执原始决定时间）；200 回执
// 按契约七字段展示（拒单携带同事务全额退款 refundOrderId）；403（开关未开/无权限/订单不
// 属于本商家）与 503 依赖故障整页失败关闭；订单不存在与非本商家同文案防枚举。
// 设计源：登记表 §4.3 —— 原稿帧 23:14926/23:18010/23:18149/23:18284/23:18440 为第三方
// 平台聚合门店后台语义（无五类拒单原因、含商家取消按钮、客户手机号、底部 tab），与
// 45 号契约冲突，登记为不作为还原依据；本页沿 M 端现行规范（members/staff-verify 页
// measures/tokens）实现，非一比一还原。

export default function MerchantOrderConfirmPage() {
  const route = useRouter()
  const { scope } = useWorkspace('real')
  const [controller] = useState(() => new OrderDecisionController(new RealMerchantOrderRepository(consumerApi)))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [notice, setNotice] = useState('')
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--moc-status-top': `${platformInfo.statusBarHeight || 0}px`, '--moc-unit': `${unit}px` } as CSSProperties
  // Deep-linkable order id (a future M-side order list may hand it over); still editable.
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
    else await Taro.redirectTo({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('返回失败，请重试'))
  }
  function goWorkspace() {
    Taro.redirectTo({ url: '/merchant/pages/workspace/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }
  function submit() {
    void controller.submit()
  }

  const receipt = state.receipt
  const confirmed = receipt?.action === 'CONFIRM'
  return <View className='moc-page' style={style}>
    <View className='moc-status-area' />
    <View className='moc-header'>
      <Button id='moc-back' ariaLabel='返回' className='moc-back' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='moc-title'>订单处理</Text>
    </View>
    <ScrollView className='moc-body' scrollY enhanced showScrollbar={false}>
      {state.status === 'entry' && <View className='moc-state' role='status'>
        <Text>{state.notice || notice || '请从商家工作台进入订单处理。'}</Text>
        <Button id='moc-go-workspace' className='moc-state-action' onClick={goWorkspace}>去商家工作台</Button>
      </View>}
      {state.status === 'closed' && <View className='moc-panel moc-panel-closed' role='alert'>
        <Text className='moc-panel-title'>订单处理暂不可用</Text>
        <Text className='moc-panel-line'>{state.notice}</Text>
        <Text className='moc-panel-line'>通道开关默认由平台开启；依赖故障与无处理权限同样整页失败关闭，不提供可编辑表单。超过支付后30分钟的订单由系统自动接单。</Text>
        <Button id='moc-closed-back-form' className='moc-back-workbench' onClick={() => controller.reset()}>返回表单</Button>
      </View>}
      {state.status === 'receipt' && receipt && (confirmed
        ? <View className='moc-receipt moc-receipt-confirmed' role='status'>
            <Text className='moc-receipt-title'>接单成功</Text>
            <View className='moc-row'><Text className='moc-row-label'>订单编号</Text><Text className='moc-row-value'>{receipt.orderId}</Text></View>
            <View className='moc-row'><Text className='moc-row-label'>决定编号</Text><Text className='moc-row-value'>{receipt.decisionId}</Text></View>
            <View className='moc-row'><Text className='moc-row-label'>确认轮次</Text><Text className='moc-row-value'>{receipt.confirmRound}</Text></View>
            <View className='moc-row'><Text className='moc-row-label'>决定时间</Text><Text className='moc-row-value'>{decisionTimeText(receipt.decidedAt)}</Text></View>
            <View className='moc-row'><Text className='moc-row-label'>订单阶段</Text><Text className='moc-row-value'>待服务</Text></View>
            <Text className='moc-receipt-hint'>
              {state.replayed
                ? '本次为同一请求编号的重试回执：以上为首次成功回执的原始决定时间（23 号幂等重放语义），不会产生第二次决定。'
                : '接单完成，订单进入待服务；请按预约时间履约，服务完成后核销。'}
            </Text>
            <Button id='moc-confirm-next' className='moc-back-workbench' onClick={() => controller.reset()}>继续处理下一单</Button>
          </View>
        : <View className='moc-receipt moc-receipt-rejected' role='status'>
            <Text className='moc-receipt-title'>已拒单，全额退款已创建</Text>
            <View className='moc-row'><Text className='moc-row-label'>订单编号</Text><Text className='moc-row-value'>{receipt.orderId}</Text></View>
            <View className='moc-row'><Text className='moc-row-label'>决定编号</Text><Text className='moc-row-value'>{receipt.decisionId}</Text></View>
            <View className='moc-row'><Text className='moc-row-label'>确认轮次</Text><Text className='moc-row-value'>{receipt.confirmRound}</Text></View>
            <View className='moc-row'><Text className='moc-row-label'>决定时间</Text><Text className='moc-row-value'>{decisionTimeText(receipt.decidedAt)}</Text></View>
            <View className='moc-row'><Text className='moc-row-label'>退款单编号</Text><Text className='moc-row-value'>{receipt.refundOrderId}</Text></View>
            <Text className='moc-receipt-hint'>订单已关闭并同事务创建全额退款，退款创建后该订单禁止核销；渠道退款以退款单进度为准，最终成功后预约自动释放。</Text>
            <Button id='moc-rejected-next' className='moc-back-workbench' onClick={() => controller.reset()}>继续处理下一单</Button>
          </View>)}
      {(state.status === 'form' || state.status === 'idle') && <View>
        {state.pending && <View className='moc-pending' role='status'>
          <Text className='moc-pending-title'>有未确认的{state.pending.action === 'confirm' ? '接单' : '拒单'}提交</Text>
          <Text className='moc-panel-line'>订单 {state.pending.orderId} 的上次{state.pending.action === 'confirm' ? '接单' : '拒单'}结果尚未确认。请保持原订单与原填写内容重新提交：将按原请求编号重试并返回首次回执，不会重复处理。</Text>
        </View>}
        <View className='moc-form'>
          <Text className='moc-form-title'>处理待接单订单</Text>
          <View className='moc-field'>
            <Text className='moc-field-label'>订单编号</Text>
            <Input id='moc-order-id' className='moc-field-input' type='number' maxlength={19}
              placeholder='待接单订单编号（数字）' value={state.orderId}
              onInput={event => controller.setOrderId(event.detail.value)} />
            <Text className='moc-field-hint'>顾客订单详情页的订单编号；商家订单列表入口将在后续切片提供。</Text>
          </View>
          <View className='moc-actions'>
            <Button id='moc-action-confirm' className={state.action === 'confirm' ? 'moc-action moc-action-active' : 'moc-action'}
              onClick={() => controller.setAction('confirm')}>接单</Button>
            <Button id='moc-action-reject' className={state.action === 'reject' ? 'moc-action moc-action-active moc-action-danger' : 'moc-action'}
              onClick={() => controller.setAction('reject')}>拒单</Button>
          </View>
          {state.action === 'confirm' && <View className='moc-field'>
            <Text className='moc-field-label'>店内备注（可省略）</Text>
            <Input id='moc-internal-note' className='moc-field-input' type='text' maxlength={200}
              placeholder='仅店内可见，不对顾客展示' value={state.internalNote}
              onInput={event => controller.setInternalNote(event.detail.value)} />
            <Text className='moc-field-hint'>0至200个字符；留空与填写空串在幂等上视为不同内容。</Text>
          </View>}
          {state.action === 'reject' && <View className='moc-field'>
            <Text className='moc-field-label'>拒单原因分类（必填）</Text>
            <View className='moc-reasons'>
              {REJECT_REASON_CODES.map(code => (
                <Button key={code} id={`moc-reason-${code}`}
                  className={state.reasonCode === code ? 'moc-reason moc-reason-active' : 'moc-reason'}
                  onClick={() => controller.setReasonCode(code)}>{rejectReasonText[code]}</Button>
              ))}
            </View>
          </View>}
          {state.action === 'reject' && <View className='moc-field'>
            <Text className='moc-field-label'>拒单原因说明（必填）</Text>
            <Input id='moc-reason-text' className='moc-field-input' type='text' maxlength={200}
              placeholder='5至200个字符，如实填写拒单原因' value={state.reasonText}
              onInput={event => controller.setReasonText(event.detail.value)} />
            <Text className='moc-field-hint'>原因将按原文提交敏感词审核；不修剪改写。</Text>
          </View>}
          <Button id='moc-submit' className={state.action === 'reject' ? 'moc-submit moc-submit-danger' : 'moc-submit'} disabled={state.busy} onClick={submit}>
            {state.busy ? '提交中…' : state.action === 'confirm' ? '确认接单' : '确认拒单'}
          </Button>
          <Text className='moc-form-note'>待接单订单须在顾客支付后30分钟内处理，逾期系统自动接单；拒单将自动创建全额退款。</Text>
        </View>
      </View>}
      {(state.status === 'form' || state.status === 'idle') && state.notice && <Text id='moc-notice' className='moc-notice'>{state.notice}</Text>}
      {notice && <Text className='moc-notice'>{notice}</Text>}
      <View className='moc-tail'><Text>页面数据：真实接口（contract 45 手动接单/拒单 HTTP 面，平台开关默认关闭时接口失败关闭）</Text></View>
    </ScrollView>
  </View>
}
