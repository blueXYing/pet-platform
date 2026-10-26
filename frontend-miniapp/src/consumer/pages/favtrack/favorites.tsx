import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useRouter, useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { integrationMessage } from '../../../shared/consumer-api'
import { switchConsumerTab } from '../../components/navigation/switch'
import { ConsumerPageLayout } from '../../components/page-layout'
import { BookingSheet } from '../../components/booking-sheet/booking-sheet'
import { RealFavoriteFootprintRepository, isFavoriteFootprintUnauthorized } from '../../favtrack/repository'
import { rowSubtitle, rowTitle, targetTypeLabels, type FavoriteRow, type TrackTargetType } from '../../favtrack/model'
import { detailActionGate } from '../store-services/browse-gate'
import back from '../../assets/profile/back.png'
import './favtrack.css'

// 收藏列表（Contract 58 / 32号裁决第二节批次）。设计源 128:3200（我的-我的收藏）：顶部
// 返回+标题导航、列表卡片带标题/类目标签/价格与"预约服务"行内动作、官方色 #5baae8/
// #c0ecff/#f0fbff。原稿为"服务人员"卡（头像/评分/已服务N单/资历标签）——V1 可收藏目标
// 只有服务与门店（58号契约），评分/销量/资历无契约字段不虚构，按工程契约口径渲染，
// 偏差登记 20号 §4.13。"详情"不新造页：行点击落既有服务/门店详情页；预约沿
// BookingSheet 半屏弹层（2026-10-08 验收惯例）。preview=1 走本地夹具（设计验收通道）。
type Phase = 'loading' | 'ready' | 'expired' | 'load-error'
type Filter = 'ALL' | TrackTargetType
const PAGE_SIZE = 20
const filterTabs: readonly { key: Filter; label: string }[] = [
  { key: 'ALL', label: '全部' },
  { key: 'SERVICE', label: '服务' },
  { key: 'STORE', label: '商家' },
]

// preview 夹具：目录投影事实（服务名/类目/两位小数价格/门店归属）+ 收藏时间样例，
// 不含范围外的评分/销量/服务人员字段。
const previewRows: readonly FavoriteRow[] = [
  { favoriteId: '760101', targetType: 'SERVICE', targetId: '20001', favoritedAt: '2026-10-13T02:24:00.000Z',
    display: { targetAvailable: true, title: '宠物洗澡美容全套', categoryLabel: '宠物美容', salePrice: '80.00', storeId: '30001', coverUrl: null } },
  { favoriteId: '760102', targetType: 'STORE', targetId: '30001', favoritedAt: '2026-10-12T01:51:00.000Z',
    display: { targetAvailable: true, title: '萌宠之家宠物店', categoryLabel: null, salePrice: null, storeId: null, coverUrl: null } },
  { favoriteId: '760103', targetType: 'SERVICE', targetId: '20002', favoritedAt: '2026-10-11T08:42:00.000Z',
    display: { targetAvailable: true, title: '上门遛狗陪护', categoryLabel: '遛狗陪护', salePrice: '45.00', storeId: '30002', coverUrl: null } },
  { favoriteId: '760104', targetType: 'SERVICE', targetId: '20009', favoritedAt: '2026-10-10T12:33:00.000Z',
    display: { targetAvailable: false, title: null, categoryLabel: null, salePrice: null, storeId: null, coverUrl: null } },
]

export default function FavoritesPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const { scope, revision, context } = useWorkspace(preview ? 'preview' : 'real')
  const repository = useRef(preview ? undefined : new RealFavoriteFootprintRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  const [filter, setFilter] = useState<Filter>('ALL')
  const [rows, setRows] = useState<readonly FavoriteRow[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loadingMore, setLoadingMore] = useState(false)
  const [notice, setNotice] = useState('')
  const [busyId, setBusyId] = useState('')
  const [booking, setBooking] = useState<FavoriteRow | null>(null)
  const mounted = useRef(true)
  const sequence = useRef(0)
  const previousRevision = useRef(revision)
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--fvt-status-top': `${platformInfo.statusBarHeight || 0}px`, '--fvt-unit': `${unit}px` } as CSSProperties

  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setNotice('')
    if (preview) {
      setRows(previewRows); setTotal(previewRows.length); setPhase('ready')
      return
    }
    // 真实模式：先补一次会话校验，避免 restore 在途时误报未登录（aftersale/积分页先例）。
    if (!scope.current) { try { await consumerApi.restore() } catch { /* 未登录，走下方登录引导 */ } }
    if (current !== sequence.current || currentRevision !== scope.revision) return
    if (!scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    setPhase('loading')
    try {
      const value = await scope.run(undefined, () => repository.current!
        .listFavorites(filter === 'ALL' ? null : filter, 1, PAGE_SIZE))
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setRows(value.items); setTotal(value.total); setPage(1); setPhase('ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setPhase(isFavoriteFootprintUnauthorized(error) ? 'expired' : 'load-error')
    }
  }, [preview, scope, filter])
  useDidShow(() => { void load() })
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; sequence.current++ } }, [])
  useEffect(() => {
    // 过滤切换与会话坐标变化（登录/退出）都整页重读（固定排序服务端保证稳定）。
    if (previousRevision.current === revision) return
    previousRevision.current = revision
    void load()
  }, [revision, load])
  useEffect(() => { void load() }, [filter, load])
  async function loadMore() {
    if (loadingMore || preview || phase !== 'ready' || rows.length >= total) return
    setLoadingMore(true)
    const current = sequence.current
    const currentRevision = scope.revision
    try {
      const result = await scope.run(undefined, () => repository.current!
        .listFavorites(filter === 'ALL' ? null : filter, page + 1, PAGE_SIZE))
      if (current !== sequence.current || currentRevision !== scope.revision) return
      const known = new Set(rows.map(row => row.favoriteId))
      setRows([...rows, ...result.items.filter(row => !known.has(row.favoriteId))])
      setPage(result.page); setTotal(result.total)
    } catch { setNotice('加载更多失败，请重试。') } finally { setLoadingMore(false) }
  }
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: '/consumer/pages/mine/index' })
  }
  function openDetail(row: FavoriteRow) {
    if (!row.display.targetAvailable) { setNotice('该内容已下架或不可访问。'); return }
    const url = row.targetType === 'SERVICE'
      ? `/consumer/pages/store-services/service-detail?serviceId=${row.targetId}`
      : `/consumer/pages/store-services/index?storeId=${row.targetId}`
    Taro.navigateTo({ url }).catch(() => setNotice('页面打开失败，请重试'))
  }
  function book(row: FavoriteRow) {
    // SERVICE 行才可预约；门店归属缺失（不可用行）不开弹层。
    if (row.targetType !== 'SERVICE' || !row.display.targetAvailable || !row.display.storeId) {
      setNotice('该服务暂不可预约。'); return
    }
    if (detailActionGate(context) === 'login-required') {
      void Taro.showModal({ title: '请先登录', content: '预约需要先登录，是否前往登录？', confirmText: '去登录', cancelText: '暂不' })
        .then(answer => { if (answer.confirm) void Taro.switchTab({ url: '/consumer/pages/mine/index' }) })
        .catch(() => setNotice(''))
      return
    }
    setBooking(row)
  }
  async function removeFavorite(row: FavoriteRow) {
    if (busyId) return
    setBusyId(row.favoriteId); setNotice('')
    try {
      await scope.run(undefined, () => repository.current!.cancelFavorite(row.targetType, row.targetId))
      if (!mounted.current) return
      setRows(current => current.filter(item => item.favoriteId !== row.favoriteId))
      setTotal(current => Math.max(0, current - 1))
      setNotice('已取消收藏。')
    } catch (error) {
      if (!mounted.current) return
      setNotice(integrationMessage(error))
    } finally {
      if (mounted.current) setBusyId('')
    }
  }
  const ready = phase === 'ready'
  return <View>
    <ConsumerPageLayout page='favoriteList' unit={unit} className={`fvt-page${booking ? ' bks-lock' : ''}`}
      navigation={preview ? { idPrefix: 'fvt', disabled: !ready, onSelect: key => void switchConsumerTab(key) } : undefined} style={style}>
      <View className='fvt-status-area' />
      <View className='fvt-nav'>
        <Button id='fav-back' ariaLabel='返回' className='fvt-nav-back' onClick={() => void goBack()}>
          <Image src={back} className='fvt-nav-back-icon' />
        </Button>
        <Text className='fvt-nav-title'>我的收藏</Text>
      </View>
      <View className='fvt-body'>
        <View className='fvt-tabs'>
          {filterTabs.map(tab => <Button key={tab.key} id={`fav-tab-${tab.key.toLowerCase()}`}
            className={`fvt-tab${filter === tab.key ? ' is-active' : ''}`} hoverClass='none'
            onClick={() => { if (filter !== tab.key) setFilter(tab.key) }}>
            <Text>{tab.label}</Text>
          </Button>)}
        </View>
        {phase === 'loading' && <View className='fvt-state' role='status'><Text>正在读取收藏…</Text></View>}
        {phase === 'expired' && <View className='fvt-state' role='status'>
          <Text id='fav-login-hint'>登录后可查看我的收藏。</Text>
          <Button id='fav-login' className='fvt-state-action' onClick={() => { void Taro.switchTab({ url: '/consumer/pages/mine/index' }) }}>去登录</Button>
        </View>}
        {phase === 'load-error' && <View className='fvt-state' role='status'>
          <Text>收藏读取失败，请稍后重试。</Text>
          <Button id='fav-retry' className='fvt-state-action' onClick={() => void load()}>重新加载</Button>
        </View>}
        {ready && rows.length === 0 && <View className='fvt-state'><Text>暂无收藏。浏览服务或门店时点收藏，就能在这里找到。</Text></View>}
        {ready && rows.length > 0 && <ScrollView scrollY enhanced showScrollbar={false} className='fvt-scroll'
          onScrollToLower={() => void loadMore()}>
          {preview && <View className='fvt-preview-note'><Text>preview=1 本地夹具（设计验收通道，不联调）</Text></View>}
          {notice && <View className='fvt-notice' role='status'><Text id='fav-notice'>{notice}</Text></View>}
          {rows.map(row => <View key={row.favoriteId} id={`fav-row-${row.targetId}`}
            className={`fvt-card${row.display.targetAvailable ? '' : ' is-unavailable'}`} onClick={() => openDetail(row)}>
            <View className={`fvt-card-badge fvt-badge-${row.targetType.toLowerCase()}`}><Text>{targetTypeLabels[row.targetType]}</Text></View>
            <View className='fvt-card-main'>
              <Text className='fvt-card-title'>{rowTitle(row)}</Text>
              <Text className='fvt-card-sub'>{rowSubtitle(row)}</Text>
              {row.display.targetAvailable && row.display.salePrice !== null
                && <Text className='fvt-card-price'>¥{row.display.salePrice} 起</Text>}
            </View>
            <View className='fvt-card-actions' onClick={event => event.stopPropagation()}>
              {row.display.targetAvailable && row.targetType === 'SERVICE' && row.display.storeId
                && <Button id={`fav-book-${row.targetId}`} className='fvt-card-book' hoverClass='none'
                  disabled={busyId === row.favoriteId} onClick={() => book(row)}><Text>预约服务</Text></Button>}
              <Button id={`fav-remove-${row.targetId}`} className='fvt-card-remove' hoverClass='none'
                ariaLabel={`取消收藏${rowTitle(row)}`} disabled={busyId === row.favoriteId}
                onClick={() => void removeFavorite(row)}><Text>取消收藏</Text></Button>
            </View>
          </View>)}
          {rows.length < total && <Button id='fav-more' className='fvt-more' hoverClass='none' disabled={loadingMore}
            onClick={() => void loadMore()}><Text>{loadingMore ? '正在加载…' : '加载更多'}</Text></Button>}
          <View className='fvt-tail'><Text>共 {total} 条收藏</Text></View>
        </ScrollView>}
      </View>
    </ConsumerPageLayout>
    {/* 弹层渲染在页面级 button 重置之外（booking 直连页惯例）。 */}
    {booking && <BookingSheet key={revision} idPrefix='fav-booking' preview={preview}
      serviceId={booking.targetId} storeId={booking.display.storeId ?? undefined} onClose={() => setBooking(null)} />}
  </View>
}
