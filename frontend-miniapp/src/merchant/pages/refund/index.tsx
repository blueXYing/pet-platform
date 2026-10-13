import { Button, ScrollView, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { dateText, refundStatusTagClass } from '../../refund/model'
import { MerchantRefundShell, MerchantRefundState, useMerchantRefund } from './page'

/** 商家退款待处理列表（57号/HTTP10 §4.4）：仅本店 PENDING_MERCHANT，固定排序，按单进入处理。 */
export default function MerchantRefundList() {
  const { controller, state, style } = useMerchantRefund()
  return <MerchantRefundShell title='退款处理' style={style}>
    {state.status !== 'ready' ? <MerchantRefundState controller={controller} status={state.status} text={state.notice} /> : <>
      <ScrollView className='mrf-list' scrollY enhanced showScrollbar={false}>
        {state.frozen && <View className='mrf-alert'>商家或门店已冻结，当前只能查看退款申请。</View>}
        {state.pending && <View className='mrf-alert'>有上次的{state.pending.action === 'approve' ? '同意' : '拒绝'}提交结果未确认，进入对应申请按原内容重试。</View>}
        <Text className='mrf-count'>当前门店 · 共 {state.total} 笔待处理退款</Text>
        {state.items.length === 0 && <View className='mrf-empty'>暂无待处理退款申请</View>}
        {state.items.map(item => <View key={item.applicationId} className='mrf-list-card'>
          <View className='mrf-line'><Text className='mrf-case-number'>申请 {item.applicationId}</Text>
            <Text className='mrf-requested'>¥{item.refundAmount}</Text></View>
          <View className='mrf-line mrf-subline'><Text className='mrf-type'>订单 {item.orderId}</Text><Text className='mrf-date'>{dateText(item.createdAt)}</Text></View>
          <View className='mrf-line mrf-list-actions'><Text className={refundStatusTagClass(item.status)}>待商家处理</Text>
            <Button id={`mrf-case-${item.applicationId}`} className='mrf-card-action' onClick={() => Taro.navigateTo({ url: `/merchant/pages/refund/detail?applicationId=${encodeURIComponent(item.applicationId)}` }).catch(() => controller.showNotice('页面跳转失败，请重试。'))}>处理退款</Button></View>
          <Text className='mrf-help'>处理期限 {dateText(item.merchantDeadline)}（北京时间，超时系统自动全额退款）</Text>
        </View>)}
        {state.items.length < state.total && <Button id='mrf-more' className='mrf-more' disabled={state.loadingMore} onClick={() => void controller.loadMore()}>{state.loadingMore ? '正在加载…' : '加载更多'}</Button>}
        {state.notice && <View className='mrf-notice' role='status'>{state.notice}</View>}
        <View className='mrf-bottom-space' />
      </ScrollView>
    </>}
  </MerchantRefundShell>
}
