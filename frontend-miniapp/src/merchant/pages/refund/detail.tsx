import { Button, ScrollView, Text, Textarea, View } from '@tarojs/components'
import { useRouter } from '@tarojs/taro'
import { useEffect, useState } from 'react'
import { dateText, deadlinePassed, refundStatusTagClass, refundStatusText } from '../../refund/model'
import { MerchantRefundShell, MerchantRefundState, useMerchantRefund } from './page'

/** 商家退款申请详情（57号/HTTP10 §4.5/§4.6）：同意全额或拒绝（必填理由），回执按 49号七字段呈现。 */
export default function MerchantRefundDetail() {
  const route = useRouter()
  const applicationId = typeof route.params.applicationId === 'string' && /^[1-9][0-9]{0,18}$/.test(route.params.applicationId)
    && BigInt(route.params.applicationId) <= 9223372036854775807n ? route.params.applicationId : undefined
  const { controller, state, style } = useMerchantRefund(applicationId, !!applicationId)
  const [sheet, setSheet] = useState<'reject' | null>(null)
  // A remounted unknown-outcome write reopens its original action with the immutable payload.
  useEffect(() => { if (state.pending) setSheet(state.pending.action === 'reject' ? 'reject' : null) }, [state.pending])
  useEffect(() => { if (state.status === 'entry' || state.status === 'denied' || state.status === 'idle') setSheet(null) }, [state.status])
  useEffect(() => { if (!state.busy && !state.pending && state.receipt) setSheet(null) }, [state.busy, state.pending, state.receipt])
  const detail = state.detail
  const decideAllowed = controller.canDecide(Date.now())
  const expired = !!detail && deadlinePassed(detail, Date.now())
  const locked = state.busy || !!state.pending
  if (!applicationId) return <MerchantRefundShell title='退款详情' style={style}><View className='mrf-state'>申请编号无效，请从退款处理列表进入。</View></MerchantRefundShell>
  return <MerchantRefundShell title='退款详情' style={style}>
    {state.status !== 'ready' || !detail ? <MerchantRefundState controller={controller} status={state.status} text={state.notice} /> : <>
      <ScrollView className='mrf-detail' scrollY enhanced showScrollbar={false}>
        {state.frozen && <View className='mrf-alert'>商家或门店已冻结，当前只能查看退款申请。</View>}
        <View className='mrf-detail-card'>
          <View className='mrf-line'><Text className={refundStatusTagClass(detail.status)}>{refundStatusText[detail.status]}</Text><Text className='mrf-date'>申请 {detail.applicationId}</Text></View>
          <Text className='mrf-heading'>订单 {detail.orderId}</Text>
          <Text className='mrf-type'>申请于 {dateText(detail.createdAt)}</Text>
          <View className='mrf-amount-row'><Text>退款金额（全额）</Text><Text className='mrf-amount'>¥{detail.refundAmount}</Text></View>
        </View>
        <View className='mrf-detail-card'>
          <Text className='mrf-section-title'>申请信息</Text>
          <View className='mrf-field'><Text>申请编号</Text><Text>{detail.applicationNo}</Text></View>
          <View className='mrf-field'><Text>申请原因编码</Text><Text>{detail.reasonCode}</Text></View>
          <View className='mrf-field'><Text>商家处理期限</Text><Text>{dateText(detail.merchantDeadline)}</Text></View>
          <Text className='mrf-help'>同一订单被拒绝后买家可再次申请，每轮重新计算24小时处理期限。</Text>
          {expired && <Text className='mrf-help mrf-readonly'>已过24小时处理期限，系统将自动全额退款，商家不再处理。</Text>}
        </View>
        {detail.status !== 'PENDING_MERCHANT' && <View className='mrf-detail-card'>
          <Text className='mrf-section-title'>处理结果</Text>
          <View className='mrf-field'><Text>结果</Text><Text>{refundStatusText[detail.status]}</Text></View>
          <View className='mrf-field'><Text>处理时间</Text><Text>{dateText(detail.decidedAt)}</Text></View>
          <View className='mrf-field'><Text>决定编号</Text><Text>{detail.decisionId ?? '—'}</Text></View>
          {detail.refundOrderId && <View className='mrf-field'><Text>退款单</Text><Text>{detail.refundOrderId}</Text></View>}
          <Text className='mrf-help'>同意退款后系统创建退款单并原路退回，此处不表示渠道退款已完成。</Text>
        </View>}
        {!decideAllowed && !state.pending && detail.status === 'PENDING_MERCHANT' && <Text className='mrf-help mrf-readonly'>当前申请只读：冻结状态或处理期限已到。</Text>}
        {state.notice && <View className='mrf-notice' role='status'>{state.notice}</View>}
        <View className='mrf-refresh-row'>
          <Button id='mrf-refresh' className='mrf-more' disabled={state.busy} onClick={() => void controller.reloadFromScope()}>刷新详情</Button>
        </View>
        <View className='mrf-bottom-space' />
      </ScrollView>
      {detail.status === 'PENDING_MERCHANT' && <View className='mrf-detail-footer'>
        <Button id='mrf-open-reject' className='mrf-secondary' disabled={!decideAllowed || locked} onClick={() => setSheet('reject')}>拒绝退款</Button>
        <Button id='mrf-approve' className='mrf-primary' disabled={!decideAllowed || locked} onClick={() => void controller.submit('approve')}>{state.pending?.action === 'approve' || state.busy ? '正在处理…' : '同意全额退款'}</Button>
      </View>}
      {sheet === 'reject' && <View className='mrf-overlay'>
        <Button className='mrf-sheet-dismiss' ariaLabel='关闭填写面板' disabled={state.busy} onClick={() => setSheet(null)} />
        <View className='mrf-sheet' role='dialog' ariaLabel='拒绝退款'>
          <View className='mrf-sheet-handle' />
          <Text className='mrf-sheet-title'>拒绝退款</Text>
          <Text className='mrf-sheet-description'>{state.pending ? '上次提交结果待确认，按原内容重试。' : '拒绝原因将展示给买家；买家仍可再次申请退款。'}</Text>
          <Textarea id='mrf-reject-text' className='mrf-textarea' value={state.draft.reasonText} maxlength={500} disabled={locked} autoHeight={false}
            placeholder='请填写拒绝原因（1至500字）' onInput={event => controller.setDraft({ reasonText: event.detail.value })} />
          <Text className='mrf-text-count'>{state.draft.reasonText.length}/500</Text>
          {(state.notice) && <View className='mrf-notice' role='status'>{state.notice}</View>}
          <Button id='mrf-submit-reject' className='mrf-primary mrf-submit' disabled={state.busy}
            onClick={() => void controller.submit('reject')}>{state.busy ? '正在处理…' : state.pending ? '重试原提交' : '确认拒绝'}</Button>
          <Button className='mrf-close-sheet' disabled={state.busy} onClick={() => setSheet(null)}>关闭</Button>
        </View>
      </View>}
    </>}
  </MerchantRefundShell>
}
