import { Button, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useRouter, useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { integrationMessage } from '../../../shared/consumer-api'
import { switchConsumerTab } from '../../components/navigation/switch'
import { ConsumerPageLayout } from '../../components/page-layout'
import { SubpageHeader, SubpageHeaderSpacer } from '../../components/navigation/subpage-header'
import { RealFavoriteFootprintRepository, isFavoriteFootprintUnauthorized } from '../../favtrack/repository'
import { formatTrackClock, groupFootprints, rowSubtitle, rowTitle, targetTypeLabels, type FootprintRow } from '../../favtrack/model'
import './favtrack.css'

// 浏览足迹列表（Contract 58 / 32号裁决第二节批次）。设计源 129:3484（我的-浏览足迹）：
// 今天/昨天/更早 分组行——类型 chip（服务/商家）+ 时间 + 标题 + 副题 + 行内删除钮，官方色
// #5baae8/#c0ecff/#f0fbff。原稿含"动态"（宠友圈内容）行：V1 足迹目标只有服务/门店
// （58号契约，客户端埋点口径），动态行不做，偏差登记 20号 §4.13。"足迹详情"不新造页：
// 行点击落既有服务/门店详情页（690:11109 原稿即门店详情形态）。preview=1 本地夹具。
type Phase = 'loading' | 'ready' | 'expired' | 'load-error'
const PAGE_SIZE = 20

// preview 夹具：今天/昨天/更早 三组各两行（服务+门店），时间戳对齐分组口径（UTC 日历日）。
const previewRows: readonly FootprintRow[] = [
  { footprintId: '770101', targetType: 'SERVICE', targetId: '20001', lastViewedAt: '2026-10-13T02:24:00.000Z',
    display: { targetAvailable: true, title: '宠物洗澡美容全套', categoryLabel: '宠物美容', salePrice: '80.00', storeId: '30001', coverUrl: null } },
  { footprintId: '770102', targetType: 'STORE', targetId: '30001', lastViewedAt: '2026-10-13T01:51:00.000Z',
    display: { targetAvailable: true, title: '萌宠之家宠物店', categoryLabel: null, salePrice: null, storeId: null, coverUrl: null } },
  { footprintId: '770103', targetType: 'SERVICE', targetId: '20002', lastViewedAt: '2026-10-12T08:42:00.000Z',
    display: { targetAvailable: true, title: '上门遛狗陪护', categoryLabel: '遛狗陪护', salePrice: '45.00', storeId: '30002', coverUrl: null } },
  { footprintId: '770104', targetType: 'STORE', targetId: '30002', lastViewedAt: '2026-10-12T06:05:00.000Z',
    display: { targetAvailable: true, title: '宠爱美容坊', categoryLabel: null, salePrice: null, storeId: null, coverUrl: null } },
  { footprintId: '770105', targetType: 'SERVICE', targetId: '20003', lastViewedAt: '2026-10-10T12:33:00.000Z',
    display: { targetAvailable: true, title: '宠物训练 · 行为矫正', categoryLabel: '宠物训练', salePrice: '200.00', storeId: '30003', coverUrl: null } },
  { footprintId: '770106', targetType: 'SERVICE', targetId: '20009', lastViewedAt: '2026-10-09T03:47:00.000Z',
    display: { targetAvailable: false, title: null, categoryLabel: null, salePrice: null, storeId: null, coverUrl: null } },
]

export default function FootprintsPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const { scope, revision } = useWorkspace(preview ? 'preview' : 'real')
  const repository = useRef(preview ? undefined : new RealFavoriteFootprintRepository(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  const [rows, setRows] = useState<readonly FootprintRow[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loadingMore, setLoadingMore] = useState(false)
  const [notice, setNotice] = useState('')
  const [busyId, setBusyId] = useState('')
  const mounted = useRef(true)
  const sequence = useRef(0)
  const previousRevision = useRef(revision)
  const [now, setNow] = useState(() => new Date())
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--fvt-status-top': `${platformInfo.statusBarHeight || 0}px`, '--fvt-unit': `${unit}px` } as CSSProperties

  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setNotice('')
    if (preview) {
      setRows(previewRows); setTotal(previewRows.length); setNow(new Date()); setPhase('ready')
      return
    }
    // 真实模式：先补一次会话校验，避免 restore 在途时误报未登录（aftersale/积分页先例）。
    if (!scope.current) { try { await consumerApi.restore() } catch { /* 未登录，走下方登录引导 */ } }
    if (current !== sequence.current || currentRevision !== scope.revision) return
    if (!scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    setPhase('loading')
    try {
      const value = await scope.run(undefined, () => repository.current!.listFootprints(1, PAGE_SIZE))
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setRows(value.items); setTotal(value.total); setPage(1); setNow(new Date()); setPhase('ready')
    } catch (error) {
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setPhase(isFavoriteFootprintUnauthorized(error) ? 'expired' : 'load-error')
    }
  }, [preview, scope])
  useDidShow(() => { void load() })
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; sequence.current++ } }, [])
  useEffect(() => {
    if (previousRevision.current === revision) return
    previousRevision.current = revision
    void load()
  }, [revision, load])
  async function loadMore() {
    if (loadingMore || preview || phase !== 'ready' || rows.length >= total) return
    setLoadingMore(true)
    const current = sequence.current
    const currentRevision = scope.revision
    try {
      const result = await scope.run(undefined, () => repository.current!.listFootprints(page + 1, PAGE_SIZE))
      if (current !== sequence.current || currentRevision !== scope.revision) return
      const known = new Set(rows.map(row => row.footprintId))
      setRows([...rows, ...result.items.filter(row => !known.has(row.footprintId))])
      setPage(result.page); setTotal(result.total)
    } catch { setNotice('加载更多失败，请重试。') } finally { setLoadingMore(false) }
  }
  async function goBack() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: '/consumer/pages/mine/index' })
  }
  function openDetail(row: FootprintRow) {
    if (!row.display.targetAvailable) { setNotice('该内容已下架或不可访问。'); return }
    const url = row.targetType === 'SERVICE'
      ? `/consumer/pages/store-services/service-detail?serviceId=${row.targetId}`
      : `/consumer/pages/store-services/index?storeId=${row.targetId}`
    Taro.navigateTo({ url }).catch(() => setNotice('页面打开失败，请重试'))
  }
  async function removeFootprint(row: FootprintRow) {
    if (busyId) return
    setBusyId(row.footprintId); setNotice('')
    try {
      await scope.run(undefined, () => repository.current!.deleteFootprint(row.footprintId))
      if (!mounted.current) return
      setRows(current => current.filter(item => item.footprintId !== row.footprintId))
      setTotal(current => Math.max(0, current - 1))
      setNotice('已删除该条足迹。')
    } catch (error) {
      if (!mounted.current) return
      setNotice(integrationMessage(error))
    } finally {
      if (mounted.current) setBusyId('')
    }
  }
  const ready = phase === 'ready'
  const groups = ready ? groupFootprints(now, rows) : []
  return <ConsumerPageLayout page='footprintList' unit={unit} className='fvt-page'
    navigation={preview ? { idPrefix: 'trk', disabled: !ready, onSelect: key => void switchConsumerTab(key) } : undefined} style={style}>
    <SubpageHeader title='浏览足迹' backId='trk-back' height={47} onBack={() => void goBack()} />
    <SubpageHeaderSpacer height={47} />
    <View className='fvt-body'>
      {phase === 'loading' && <View className='fvt-state' role='status'><Text>正在读取足迹…</Text></View>}
      {phase === 'expired' && <View className='fvt-state' role='status'>
        <Text id='trk-login-hint'>登录后浏览的服务与门店会记入足迹。</Text>
        <Button id='trk-login' className='fvt-state-action' onClick={() => { void Taro.switchTab({ url: '/consumer/pages/mine/index' }) }}>去登录</Button>
      </View>}
      {phase === 'load-error' && <View className='fvt-state' role='status'>
        <Text>足迹读取失败，请稍后重试。</Text>
        <Button id='trk-retry' className='fvt-state-action' onClick={() => void load()}>重新加载</Button>
      </View>}
      {ready && rows.length === 0 && <View className='fvt-state'><Text>暂无足迹。登录后浏览的服务与门店会自动记入。</Text></View>}
      {ready && rows.length > 0 && <ScrollView scrollY enhanced showScrollbar={false} className='fvt-scroll'
        onScrollToLower={() => void loadMore()}>
        {preview && <View className='fvt-preview-note'><Text>preview=1 本地夹具（设计验收通道，不联调）</Text></View>}
        {notice && <View className='fvt-notice' role='status'><Text id='trk-notice'>{notice}</Text></View>}
        {groups.map(group => <View key={group.key} className='fvt-group'>
          <Text className='fvt-group-title'>{group.label}</Text>
          {group.rows.map(row => <View key={row.footprintId} id={`trk-row-${row.targetId}`}
            className={`fvt-track-row${row.display.targetAvailable ? '' : ' is-unavailable'}`} onClick={() => openDetail(row)}>
            <View className={`fvt-track-chip fvt-badge-${row.targetType.toLowerCase()}`}><Text>{targetTypeLabels[row.targetType]}</Text></View>
            <View className='fvt-track-main'>
              <View className='fvt-track-head'>
                <Text className='fvt-track-time'>{formatTrackClock(now, row.lastViewedAt)}</Text>
              </View>
              <Text className='fvt-track-title'>{rowTitle(row)}</Text>
              <Text className='fvt-track-sub'>{rowSubtitle(row)}</Text>
            </View>
            <View className='fvt-track-side' onClick={event => event.stopPropagation()}>
              <Button id={`trk-delete-${row.footprintId}`} className='fvt-track-delete' hoverClass='none'
                ariaLabel={`删除足迹${rowTitle(row)}`} disabled={busyId === row.footprintId}
                onClick={() => void removeFootprint(row)}><Text>删除</Text></Button>
            </View>
          </View>)}
        </View>)}
        {rows.length < total && <Button id='trk-more' className='fvt-more' hoverClass='none' disabled={loadingMore}
          onClick={() => void loadMore()}><Text>{loadingMore ? '正在加载…' : '加载更多'}</Text></Button>}
        <View className='fvt-tail'><Text>共 {total} 条足迹 · 重复浏览自动置顶去重</Text></View>
      </ScrollView>}
    </View>
  </ConsumerPageLayout>
}
