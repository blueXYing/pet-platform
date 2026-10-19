import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useState, type CSSProperties } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealFeedbackRepository } from '../../feedback/repository'
import {
  feedbackStatusFallback, feedbackStatusLabels, feedbackTypeLabel, formatTicketTime,
  type FeedbackTicketDetail, type FeedbackTicketSummary,
} from '../../feedback/model'
import backIcon from '../../assets/profile/back.png'
import './list.css'

// 我的反馈列表页（67号 v0.2 / PRD 5.1.28「查看运营回复和处理结论」的 C 端承载；反馈列表无
// 原稿，沿 C 端现行列表家族语言实现——登记缺稿口径，不做 1:1 还原或 VIS 走查）。
// 卡片：类型标签 + 状态标签 + 摘要 + 提交时间；点卡片进详情半屏：状态/附件数/处理时间线
// + 运营回复（v0.2 RESOLVED 单可见，CCR-CS-FEEDBACK-001 v0.2）。分页 1..10000/pageSize 20。
const PAGE_SIZE = 20
const FEEDBACK_NEW_URL = '/consumer/pages/feedback/index'
const MINE_TAB_URL = '/consumer/pages/mine/index'

type Phase = 'loading' | 'ready' | 'load-error' | 'unauthorized'

export default function FeedbackListPage() {
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = {
    '--fb-top': `${platformInfo.statusBarHeight || 0}px`,
    '--fb-unit': `${unit}px`,
  } as CSSProperties
  const [phase, setPhase] = useState<Phase>('loading')
  const [items, setItems] = useState<readonly FeedbackTicketSummary[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [detailId, setDetailId] = useState<string | null>(null)
  const [detail, setDetail] = useState<FeedbackTicketDetail | null>(null)
  const [notice, setNotice] = useState('')

  async function load(nextPage: number) {
    setPhase('loading')
    try {
      const result = await new RealFeedbackRepository(consumerApi).list(nextPage, PAGE_SIZE)
      setItems(current => nextPage === 1 ? result.items : [...current, ...result.items])
      setTotal(result.total)
      setPage(nextPage)
      setPhase('ready')
    } catch (error) {
      const status = (error as { statusCode?: number }).statusCode
      setPhase(status === 401 ? 'unauthorized' : 'load-error')
    }
  }

  async function openDetail(ticketId: string) {
    setNotice('')
    try {
      const result = await new RealFeedbackRepository(consumerApi).get(ticketId)
      setDetailId(ticketId)
      setDetail(result)
    } catch {
      setNotice('详情读取失败，请稍后重试。')
    }
  }

  function loadMore() {
    if (phase !== 'ready' || items.length >= total) return
    void load(page + 1)
  }

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: MINE_TAB_URL })
  }

  const listBody = (() => {
    if (phase === 'loading' && items.length === 0) {
      return <View className='fbl-state' role='status'><Text>正在读取我的反馈…</Text></View>
    }
    if (phase === 'unauthorized') {
      return <View className='fbl-state'><Text>登录已失效，请回到「我的」重新登录。</Text>
        <Button id='fbl-login' className='fbl-state-btn' hoverClass='none'
          onClick={() => void Taro.switchTab({ url: MINE_TAB_URL })}>去登录</Button></View>
    }
    if (phase === 'load-error' && items.length === 0) {
      return <View className='fbl-state'><Text>反馈列表读取失败，请稍后重试。</Text>
        <Button id='fbl-retry' className='fbl-state-btn' hoverClass='none' onClick={() => void load(1)}>重试</Button></View>
    }
    if (phase === 'ready' && items.length === 0) {
      return <View className='fbl-state'><Text>暂无反馈记录。</Text>
        <Button id='fbl-new' className='fbl-state-btn' hoverClass='none'
          onClick={() => Taro.redirectTo({ url: FEEDBACK_NEW_URL }).catch(() => setNotice('页面打开失败，请重试'))}>去提交反馈</Button></View>
    }
    return <>
      {items.map(item => <Button key={item.ticketId} id={`fbl-item-${item.ticketId}`} className='fbl-card'
        hoverClass='none' onClick={() => void openDetail(item.ticketId)}>
        <View className='fbl-card-head'>
          <Text className='fbl-tag fbl-tag-type'>{feedbackTypeLabel(item.type)}</Text>
          <Text className='fbl-tag fbl-tag-status'>{feedbackStatusLabels[item.status] ?? feedbackStatusFallback}</Text>
          <Text className='fbl-time'>{formatTicketTime(item.createdAt)}</Text>
        </View>
        <Text className='fbl-preview'>{item.contentPreview}</Text>
      </Button>)}
      {items.length < total && <Button id='fbl-more' className='fbl-more' hoverClass='none' onClick={loadMore}>加载更多</Button>}
    </>
  })()

  return <View className='cpn-page fb-page fbl-page' style={style}>
    <View className='fb-status-area' />
    <View className='fb-header'>
      <Button id='fbl-back' className='fb-back' hoverClass='none' ariaLabel='返回' onClick={() => void back()}>
        <Image src={backIcon} className='fb-back-icon' />
      </Button>
      <Text className='fb-title'>我的反馈</Text>
    </View>
    <ScrollView className='fb-body' scrollY enhanced showScrollbar={false}
      onScrollToLower={() => loadMore()}>
      {listBody}
      {notice !== '' && <Text id='fbl-notice' className='fb-notice'>{notice}</Text>}
    </ScrollView>
    {detailId !== null && detail !== null && <View className='fbl-mask' onClick={() => { setDetailId(null); setDetail(null) }} catchMove>
      <View className='fbl-sheet' onClick={event => event.stopPropagation()} catchMove>
        <View className='fbl-sheet-head'>
          <Text className='fbl-sheet-title'>反馈详情</Text>
          <Button id='fbl-sheet-close' className='fbl-sheet-close' hoverClass='none' ariaLabel='关闭'
            onClick={() => { setDetailId(null); setDetail(null) }}>关闭</Button>
        </View>
        <ScrollView className='fbl-sheet-body' scrollY enhanced showScrollbar={false}>
          <Text className='fbl-sheet-label'>当前状态</Text>
          <Text className='fbl-sheet-text'>{feedbackStatusLabels[detail.status] ?? feedbackStatusFallback}</Text>
          <Text className='fbl-sheet-label'>反馈内容</Text>
          <Text className='fbl-sheet-text'>{detail.content}</Text>
          <Text className='fbl-sheet-label'>图片附件</Text>
          <Text className='fbl-sheet-text'>{detail.attachmentAssetIds.length === 0 ? '无附件' : `${detail.attachmentAssetIds.length} 张图片`}</Text>
          <Text className='fbl-sheet-label'>联系方式</Text>
          <Text className='fbl-sheet-text'>{detail.contact ?? '未留下联系方式'}</Text>
          <Text className='fbl-sheet-label'>处理进度</Text>
          {detail.events.map((event, index) => <Text key={index} className='fbl-sheet-text'>
            {`${event.action === 'SUBMITTED' ? '已提交' : event.action === 'PROCESSING' ? '处理中' : '已处理'} · ${formatTicketTime(event.createdAt)}`}
          </Text>)}
          <Text className='fbl-sheet-label'>运营回复与处理结论</Text>
          {detail.replies.length === 0
            ? <Text className='fbl-sheet-text'>暂无回复；工单将由运营跟进处理。</Text>
            : detail.replies.map((reply, index) => <View key={index}>
              <Text className='fbl-sheet-text'>{reply.reply}</Text>
              <Text className='fbl-sheet-text'>{`回复时间：${formatTicketTime(reply.repliedAt)}`}</Text>
            </View>)}
        </ScrollView>
      </View>
    </View>}
  </View>
}
