import { Button, Image, ScrollView, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { consumerApi, consumerStorage } from '../../../shared/consumer-runtime'
import { merchantReviewDeps } from '../../review-appeal/repository'
import { MerchantReviewController } from '../../review-appeal/controller'
import navBack from '../aftersale/assets/nav-back@3x.png'
import './page.css'

/** Contract56 M face: the store coordinates come from the merchant workspace handoff. */
function useMerchantReviews() {
  const [controller] = useState(() => {
    const context = consumerApi.scope.capture().context
    if (context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      throw new Error('MERCHANT_ENTRY_REQUIRED')
    }
    return new MerchantReviewController(
      merchantReviewDeps(consumerApi, consumerStorage), consumerApi.scope,
      { merchantId: context.merchantId, storeId: context.storeId })
  })
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => { void controller.load() })
  useDidHide(() => controller.hide())
  useEffect(() => () => controller.dispose(), [controller])
  const [info] = useState(() => Taro.getWindowInfo())
  const style = { '--mrv-unit': `${info.windowWidth / 402}px`, '--mrv-status-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  return { controller, state, style }
}

async function backToWorkbench() {
  try {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/merchant/pages/workspace/index' })
  } catch { await Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) }
}

/** Star row is a display derivation of the contract compositeScore (round-to-nearest). */
function starCount(score: string): number {
  const value = Math.round(Number(score))
  return Number.isFinite(value) ? Math.min(5, Math.max(0, value)) : 0
}

// REV-002 评价管理 M face — 2026-10-13 visual pass on merchant frame 23:14160「首页-店铺评价-全部」
// (family: 68:2106 待回复 / 68:3178 差评 / 68:4048 申诉中): header band 86 #FFF6E5 + centered
// 店铺评价 title + back arrow; review cards white with the #FF7A00 star row and #9CA3AF date,
// #1A1A2E 13.47 content, appeal tag pills and the #5BAAE8 appeal button. The frame's aggregate
// blocks (score summary card / star histogram / 总评价·待回复·好评率·申诉中 stat card / filter
// chips / reviewer identity / image grid / 有帮助·回复 actions) have no contract-56 read side
// and are not rendered — registry §4.10 deviations. Zero functional change: every id, mount
// condition, handler and the one-appeal sheet stay byte-identical.
export default function MerchantReviewsPage() {
  const { controller, state, style } = useMerchantReviews()
  const [draft, setDraft] = useState('')
  const sheetReview = state.sheetReviewId === null ? null
    : state.items.find(item => item.reviewId === state.sheetReviewId) ?? null
  return <View className='mrv-page' style={style}>
    <View className='mrv-status-area' />
    <View className='mrv-header'>
      <Button className='mrv-back' ariaLabel='返回' onClick={() => void backToWorkbench()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mrv-title'>店铺评价</Text>
    </View>
    {state.phase !== 'ready' ? <View className='mrv-state' role='status'>
      <Text>{state.phase === 'loading' || state.phase === 'idle' ? '正在读取本店评价…' : state.notice || (state.phase === 'entry' || state.phase === 'denied' ? '仅商家 OWNER 可查看本店评价' : '页面暂时不可用')}</Text>
      {state.phase === 'error' && <Button className='mrv-primary' onClick={() => void controller.load()}>重新加载</Button>}
      {(state.phase === 'entry' || state.phase === 'denied' || state.phase === 'unauthorized') && <Button className='mrv-primary' onClick={() => void Taro.redirectTo({ url: '/merchant/pages/workspace/index' })}>去商家工作台</Button>}
    </View> : <>
      {state.frozen && <View className='mrv-alert'>商家或门店已冻结，当前仅可查看评价与申诉状态。</View>}
      <ScrollView className='mrv-list' scrollY enhanced showScrollbar={false}>
        <Text className='mrv-count'>当前门店 · 共 {state.total} 条评价</Text>
        {state.items.length === 0 && <View className='mrv-empty'>本店暂无评价</View>}
        {state.items.map(item => <View key={item.reviewId} className='mrv-card'>
          <View className='mrv-line mrv-card-head'>
            <View className='mrv-stars' ariaLabel={`${item.compositeScore} 分`}>
              {[1, 2, 3, 4, 5].map(star => <Text key={star} className={starCount(item.compositeScore) >= star ? 'mrv-star mrv-star-on' : 'mrv-star'}>★</Text>)}
            </View>
            <Text className={item.visibilityStatus === 'HIDDEN' ? 'mrv-visibility mrv-visibility-hidden' : 'mrv-visibility'}>{item.visibilityStatus === 'HIDDEN' ? '已隐藏' : '公开展示'}</Text>
            <Text className='mrv-date'>{item.createdAt.slice(0, 10)}</Text>
          </View>
          <Text className='mrv-dimensions'>综合 {item.compositeScore} 分 · 门店 {item.storeScore} · 服务 {item.serviceScore} · 人员 {item.staffScore}{item.scoreIncluded ? '' : ' · 不计入商家评分'}</Text>
          {item.content !== null && <Text className='mrv-content'>{item.content}</Text>}
          <View className='mrv-line mrv-subline'>
            <Text className={item.appealStatus === null ? 'mrv-tag mrv-tag-none' : item.appealStatus === 'APPROVED' ? 'mrv-tag mrv-tag-approved' : item.appealStatus === 'REJECTED' ? 'mrv-tag mrv-tag-closed' : 'mrv-tag'}>
              {item.appealStatus === null ? '未申诉' : item.appealStatus === 'APPROVED' ? '申诉成立' : item.appealStatus === 'REJECTED' ? '申诉未成立' : '申诉处理中'}
            </Text>
            {item.appealStatus === null && item.visibilityStatus === 'PUBLISHED' && state.writable
              ? <Button id={`mrv-appeal-${item.reviewId}`} className='mrv-card-action'
                disabled={state.busy || state.sheetReviewId !== null}
                onClick={() => { setDraft(controller.draft(item.reviewId)); controller.openSheet(item.reviewId) }}>申诉</Button>
              : null}
          </View>
        </View>)}
        {state.items.length < state.total && <Button id='mrv-more' className='mrv-more' disabled={state.loadingMore} onClick={() => void controller.loadMore()}>{state.loadingMore ? '正在加载…' : '加载更多'}</Button>}
        {state.receiptReviewId !== null && <View className='mrv-receipt' role='status'>
          <Text>申诉已提交，平台将尽快处理；结果会展示在评价卡片上。</Text>
          <Button className='mrv-receipt-close' onClick={() => controller.dismissReceipt()}>知道了</Button>
        </View>}
        {state.notice && state.sheetReviewId === null && <View className='mrv-notice' role='status'>{state.notice}</View>}
        <View className='mrv-bottom-space' />
      </ScrollView>
    </>}
    {sheetReview !== null && state.sheetReviewId !== null && <View className='mrv-overlay'>
      <Button className='mrv-sheet-dismiss' ariaLabel='关闭' onClick={() => controller.closeSheet()} />
      <View className='mrv-sheet'>
        <View className='mrv-sheet-handle' />
        <Text className='mrv-sheet-title'>申诉该评价</Text>
        <Text className='mrv-sheet-description'>每条评价最多申诉一次（SSOT §11.3）。请说明违规事实，如内容失实、辱骂或与订单不符；申诉结果由平台运营终局裁决。</Text>
        {state.sheetPending !== null
          ? <View className='mrv-pending' role='status'>
            <Text>上次申诉尚未确认结果，请重试原操作（不会重复提交）。</Text>
            <Text className='mrv-pending-reason'>{state.sheetPending.reason}</Text>
          </View>
          : <Textarea className='mrv-textarea' value={draft} maxlength={1000}
            placeholder='请填写 1~1000 个字符的申诉理由'
            onInput={event => { const value = String(event.detail.value); setDraft(value); controller.saveDraft(state.sheetReviewId!, value) }} />}
        <Text className='mrv-text-count'>{Array.from(draft).length} / 1000</Text>
        <Button id='mrv-submit' className='mrv-primary mrv-submit'
          disabled={state.busy}
          onClick={() => state.sheetPending !== null ? void controller.retry(state.sheetReviewId!) : void controller.submit(state.sheetReviewId!, draft)}>{state.busy ? '提交中…' : state.sheetPending !== null ? '重试原操作' : '提交申诉'}</Button>
        <Button className='mrv-close-sheet' disabled={state.busy} onClick={() => controller.closeSheet()}>取消</Button>
      </View>
    </View>}
  </View>
}
