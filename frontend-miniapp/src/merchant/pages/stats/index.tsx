import { Button, Image, Picker, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { consumerApi, consumerStorage } from '../../../shared/consumer-runtime'
import { merchantStorefrontDeps } from '../../storefront/repository'
import { MerchantStatsController } from '../../storefront/read-controllers'
import { deltaLabel, rangeLabel, validCustomWindow, windowLabel } from '../../storefront/model'
import navBack from '../aftersale/assets/nav-back@3x.png'
import './page.css'

const RANGES = ['TODAY', 'LAST_7D', 'LAST_30D', 'CUSTOM'] as const

function useStats() {
  const [controller] = useState(() => {
    const context = consumerApi.scope.capture().context
    if (context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      throw new Error('MERCHANT_ENTRY_REQUIRED')
    }
    return new MerchantStatsController(
      merchantStorefrontDeps(consumerApi, consumerStorage),
      { merchantId: context.merchantId, storeId: context.storeId })
  })
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => { void controller.load() })
  useDidHide(() => controller.hide())
  useEffect(() => () => controller.dispose(), [controller])
  const [info] = useState(() => Taro.getWindowInfo())
  const style = { '--mst-unit': `${info.windowWidth / 402}px`, '--mst-status-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  return { controller, state, style }
}

async function backToWorkbench() {
  try {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/merchant/pages/workspace/index' })
  } catch { await Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) }
}

function MetricCard(props: { label: string; value: string; delta: string | null; unitPrefix?: string }) {
  return <View className='mst-metric'>
    <Text className='mst-metric-label'>{props.label}</Text>
    <Text className='mst-metric-value'>{props.unitPrefix ?? ''}{props.value}</Text>
    <Text className={props.delta !== null && props.delta.startsWith('↓') ? 'mst-delta mst-delta-down' : 'mst-delta'}>{deltaLabel(props.delta)}</Text>
  </View>
}

// 数据统计 M 面（70号 + 2026-10-19 CCR 扩展，帧 5:3759 / 143:5192 偏差已登记）：
// 快捷区间 + 自定义区间（from/to ≤92 个 UTC 日）、概览指标（净营收/订单量/新增客户/客单价 +
// 环比；核销率/退款率/复购率/评价均分/拒单率）、热门服务 Top5（可下钻）、营收趋势、排期利用率。
export default function MerchantStatsPage() {
  const { controller, state, style } = useStats()
  const overview = state.overview
  const maxRevenue = Math.max(1, ...state.trend.map(bucket => Number(bucket.revenue)))
  const customValid = validCustomWindow(state.custom.from, state.custom.to)
  return <View className='mst-page' style={style}>
    <View className='mst-status-area' />
    <View className='mst-header'>
      <Button className='mst-back' ariaLabel='返回' onClick={() => void backToWorkbench()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mst-title'>数据统计</Text>
    </View>
    {state.phase !== 'ready' ? <View className='mst-state' role='status'>
      <Text>{state.phase === 'loading' || state.phase === 'idle' ? '正在读取经营数据…' : state.notice || (state.phase === 'entry' ? '仅商家 OWNER 可查看本店经营数据' : '页面暂时不可用')}</Text>
      {state.phase === 'error' && <Button className='mst-primary' onClick={() => void controller.load()}>重新加载</Button>}
      {state.phase === 'entry' && <Button className='mst-primary' onClick={() => void Taro.redirectTo({ url: '/merchant/pages/workspace/index' })}>去商家工作台</Button>}
    </View> : <ScrollView className='mst-body' scrollY enhanced showScrollbar={false}>
      {state.frozen && <View className='mst-alert'>门店受限，经营数据只读。</View>}
      <View className='mst-range-row' role='tablist'>
        {RANGES.map(range => <Button key={range} id={`mst-range-${range}`}
          className={state.range === range ? 'mst-range mst-range-selected' : 'mst-range'}
          disabled={state.loadingRange}
          onClick={() => void controller.selectRange(range)}>{rangeLabel[range]}</Button>)}
      </View>
      {state.range === 'CUSTOM' && <View className='mst-card mst-custom'>
        <View className='mst-kv'>
          <Text className='mst-kv-label'>开始日期</Text>
          <Picker mode='date' value={state.custom.from} end={state.custom.to}
            onChange={event => controller.editCustomWindow(String(event.detail.value), state.custom.to)}>
            <Text className='mst-kv-value'>{state.custom.from}</Text>
          </Picker>
        </View>
        <View className='mst-kv'>
          <Text className='mst-kv-label'>结束日期</Text>
          <Picker mode='date' value={state.custom.to} start={state.custom.from}
            onChange={event => controller.editCustomWindow(state.custom.from, String(event.detail.value))}>
            <Text className='mst-kv-value'>{state.custom.to}</Text>
          </Picker>
        </View>
        <Button className='mst-primary' disabled={state.loadingRange || !customValid}
          onClick={() => void controller.applyCustomWindow()}>应用区间（最长 92 天）</Button>
      </View>}
      {overview && <>
        <View className='mst-grid'>
          <MetricCard label='营业额' value={overview.revenue} delta={overview.deltas.revenue} unitPrefix='¥' />
          <MetricCard label='订单量' value={String(overview.orderCount)} delta={overview.deltas.orderCount} />
          <MetricCard label='新增客户' value={String(overview.newCustomers)} delta={overview.deltas.newCustomers} />
          <MetricCard label='客单价' value={overview.avgTicket ?? '--'} delta={overview.deltas.avgTicket} unitPrefix='¥' />
        </View>
        <View className='mst-card'>
          <Text className='mst-card-title'>履约与质量</Text>
          <View className='mst-kv'><Text className='mst-kv-label'>核销率</Text><Text className='mst-kv-value'>{overview.verifyRate ? `${overview.verifyRate}%` : '--'}</Text></View>
          <View className='mst-kv'><Text className='mst-kv-label'>拒单率</Text><Text className='mst-kv-value'>{overview.rejectRate ? `${overview.rejectRate}%` : '--'}</Text></View>
          <View className='mst-kv'><Text className='mst-kv-label'>退款率</Text><Text className='mst-kv-value'>{overview.refundRate ? `${overview.refundRate}%` : '--'}</Text></View>
          <View className='mst-kv'><Text className='mst-kv-label'>复购率</Text><Text className='mst-kv-value'>{overview.repeatRate ? `${overview.repeatRate}%` : '--'}</Text></View>
          <View className='mst-kv'><Text className='mst-kv-label'>评价均分</Text><Text className='mst-kv-value'>{overview.reviewAvg ?? '--'}</Text></View>
          <Text className='mst-hint'>净营收已扣除成功退款；拒单率=商家拒单取消数/已支付订单数；评价均分仅统计有效完成订单评价（70号口径）。统计更新时间 {overview.generatedAt.slice(0, 16).replace('T', ' ')}Z</Text>
        </View>
      </>}
      <View className='mst-card'>
        <Text className='mst-card-title'>热门服务 Top 5</Text>
        {state.topItems.length === 0 && <Text className='mst-empty'>该周期暂无服务订单</Text>}
        {state.topItems.map((item, index) => <Button key={item.serviceId} id={`mst-top-${item.serviceId}`}
          className='mst-top-row' onClick={() => { void openDrillDown(item.serviceId, state) }}>
          <Text className='mst-top-rank'>{index + 1}</Text>
          <Text className='mst-top-name'>{item.serviceName ?? '已下架服务'}</Text>
          <Text className='mst-top-count'>{item.orderCount} 单 · ¥{item.revenue}</Text>
        </Button>)}
        {state.topItems.length > 0 && <Text className='mst-hint'>点击服务行查看该周期订单明细（只读）。</Text>}
      </View>
      <View className='mst-card'>
        <Text className='mst-card-title'>排期利用率</Text>
        <View className='mst-kv'><Text className='mst-kv-label'>可用产能</Text>
          <Text className='mst-kv-value'>{state.utilization?.availableMinutes != null ? `${state.utilization.availableMinutes} 分钟` : '--'}</Text></View>
        <View className='mst-kv'><Text className='mst-kv-label'>已约占用</Text>
          <Text className='mst-kv-value'>{state.utilization?.bookedMinutes != null ? `${state.utilization.bookedMinutes} 分钟` : '--'}</Text></View>
        <View className='mst-kv'><Text className='mst-kv-label'>利用率</Text>
          <Text className='mst-kv-value'>{state.utilization?.utilizationRate ? `${state.utilization.utilizationRate}%` : '--'}</Text></View>
        <Text className='mst-hint'>OPEN 排期窗口产能与已确认预约占用（53号排期读侧聚合，70号扩展口径）。</Text>
      </View>
      <View className='mst-card'>
        <Text className='mst-card-title'>营收趋势（{windowLabel(state.range, state.custom.from, state.custom.to)}）</Text>
        <View className='mst-trend'>
          {state.trend.map(bucket => <View key={bucket.date} className='mst-trend-col'>
            <View className='mst-trend-bar-zone'>
              <View className='mst-trend-bar' style={{ height: `${Math.max(2, Math.round(Number(bucket.revenue) / maxRevenue * 96)) }}px` }} />
            </View>
            <Text className='mst-trend-day'>{bucket.date.slice(5)}</Text>
          </View>)}
        </View>
      </View>
      {state.notice && <View className='mst-notice' role='alert'><Text>{state.notice}</Text></View>}
      <Text className='mst-tail'>本店经营数据 · 实时读侧聚合（70号）</Text>
      <View className='mst-bottom-space' />
    </ScrollView>}
  </View>
}

async function openDrillDown(serviceId: string, state: { range: string; custom: { from: string; to: string } }) {
  const query = state.range === 'CUSTOM'
    ? `&from=${state.custom.from}&to=${state.custom.to}` : ''
  try {
    await Taro.navigateTo({
      url: `/merchant/pages/stats/service-orders?serviceId=${serviceId}&range=${state.range}${query}`,
    })
  } catch { await Taro.showToast({ title: '打开失败，请重试', icon: 'none' }) }
}
