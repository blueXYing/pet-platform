import { Button, ScrollView, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import type { AfterSaleStatus } from '../../../shared/aftersale-api'
import { dateText, statusText } from '../../aftersale/model'
import { MerchantAfterSaleShell, MerchantAfterSaleState, useMerchantAfterSale } from './page'

const filters: readonly (AfterSaleStatus | undefined)[] = [undefined, 'PENDING', 'PROCESSING', 'WAITING_SUPPLEMENT', 'RESOLVED', 'INVALIDATED', 'WITHDRAWN', 'CLOSED']

/** Figma 40:1061 card geometry; Contract51 case data replaces unavailable order/customer data. */
export default function MerchantAfterSaleList() {
  const { controller, state, style } = useMerchantAfterSale()
  return <MerchantAfterSaleShell title='售后工单' style={style}>
    {state.status !== 'ready' ? <MerchantAfterSaleState controller={controller} status={state.status} text={state.notice} /> : <>
      <ScrollView className='mas-filter-scroll' scrollX showScrollbar={false}>
        <View className='mas-filters'>{filters.map(filter => <Button key={filter || 'all'} id={`mas-filter-${filter || 'all'}`} className='mas-filter'
          data-selected={state.filter === filter ? 'true' : 'false'} onClick={() => void controller.load(filter ?? null)}>{filter ? statusText[filter] : '全部'}</Button>)}</View>
      </ScrollView>
      <ScrollView className='mas-list' scrollY enhanced showScrollbar={false}>
        {state.frozen && <View className='mas-alert'>商家或门店已冻结，当前只能查看工单和证据。</View>}
        <Text className='mas-count'>当前门店 · 共 {state.total} 笔售后工单</Text>
        {state.items.length === 0 && <View className='mas-empty'>暂无符合条件的售后工单</View>}
        {state.items.map(item => <View key={item.afterSaleId} className='mas-list-card'>
          <View className='mas-line'><Text className='mas-case-number'>工单 {item.afterSaleId}</Text>
            <Text className='mas-requested'>{item.requestedAmount === null ? '未填写金额' : `诉求 ¥${item.requestedAmount}`}</Text></View>
          <View className='mas-line mas-subline'><Text className='mas-type'>问题类型 {item.typeCode}</Text><Text className='mas-date'>{dateText(item.createdAt)}</Text></View>
          <View className='mas-line mas-list-actions'><Text className='mas-tag' data-status={item.status}>{statusText[item.status]}</Text>
            <Button id={`mas-case-${item.afterSaleId}`} className='mas-card-action' onClick={() => Taro.navigateTo({ url: `/merchant/pages/aftersale/detail?afterSaleId=${encodeURIComponent(item.afterSaleId)}` }).catch(() => controller.showNotice('页面跳转失败，请重试。'))}>查看详情</Button></View>
        </View>)}
        {state.items.length < state.total && <Button id='mas-more' className='mas-more' disabled={state.loadingMore} onClick={() => void controller.loadMore()}>{state.loadingMore ? '正在加载…' : '加载更多'}</Button>}
        {state.notice && <View className='mas-notice' role='status'>{state.notice}</View>}
        <View className='mas-bottom-space' />
      </ScrollView>
    </>}
  </MerchantAfterSaleShell>
}
