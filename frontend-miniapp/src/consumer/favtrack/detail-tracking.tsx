import { Button, Text } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect, useRef, useState } from 'react'
import { integrationMessage } from '../../shared/consumer-api'
import { consumerApi } from '../../shared/consumer-runtime'
import type { WorkspaceScope } from '../../shared/workspace'
import { RealFavoriteFootprintRepository } from './repository'
import type { TrackTargetType } from './model'

// 服务/门店详情页的收藏入口与足迹埋点（Contract 58，32号裁决第二节批次）。
// 收藏入口按设计稿 128:3200 的行内 BUTTON-25（30x30 图标钮）落位在详情页导航右缘；
// 该原稿 frame 无可用原切图（缓存 favtrack/img 为空），以官方色 chip 变体呈现
// （#5baae8 实底=已收藏 / 描边=未收藏），偏差登记 20号 §4.13。浏览本身匿名（STR-D8），
// 收藏与埋点都是登录态动作：匿名点击给登录引导；埋点失败不阻塞详情渲染（口径披露）。

type FavoriteState = 'unknown' | 'on' | 'off'

export function DetailFavoriteEntry({ targetType, targetId, preview, idPrefix, onNotice }: {
  targetType: TrackTargetType
  targetId: string
  preview: boolean
  idPrefix: string
  onNotice?: (message: string) => void
}) {
  const [state, setState] = useState<FavoriteState>('unknown')
  const [busy, setBusy] = useState(false)
  const scope = consumerApi.scope
  const busyRef = useRef(false)

  useEffect(() => {
    // 状态探针只在登录态发（未登录收藏态必然是"未收藏"，防枚举：探针只看本人）。
    let cancelled = false
    async function probe() {
      if (preview || !consumerApi.currentSession) { setState('unknown'); return }
      try {
        const status = await new RealFavoriteFootprintRepository(consumerApi)
          .favoriteStatus(targetType, targetId)
        if (!cancelled) setState(status.favoriteId === null ? 'off' : 'on')
      } catch { if (!cancelled) setState('unknown') }
    }
    void probe()
    return () => { cancelled = true }
  }, [targetType, targetId, preview, scope.revision])

  function toggle() {
    if (busyRef.current) return
    if (preview) { onNotice?.('preview 夹具通道不联调收藏。'); return }
    if (!consumerApi.currentSession) {
      void Taro.showModal({ title: '请先登录', content: '收藏需要先登录，是否前往登录？', confirmText: '去登录', cancelText: '暂不' })
        .then(answer => { if (answer.confirm) void Taro.switchTab({ url: '/consumer/pages/mine/index' }) })
        .catch(() => onNotice?.(''))
      return
    }
    busyRef.current = true; setBusy(true)
    const repository = new RealFavoriteFootprintRepository(consumerApi)
    const next: FavoriteState = state === 'on' ? 'off' : 'on'
    setState(next)
    void (next === 'on'
      ? repository.addFavorite(targetType, targetId)
      : repository.cancelFavorite(targetType, targetId))
      .then(() => { onNotice?.(next === 'on' ? '已收藏。' : '已取消收藏。') })
      .catch(error => {
        setState(state)
        onNotice?.(integrationMessage(error))
      })
      .finally(() => { busyRef.current = false; setBusy(false) })
  }

  const on = state === 'on'
  return <Button id={`${idPrefix}-favorite`} className={`svc-fav-toggle${on ? ' is-on' : ''}`}
    hoverClass='none' disabled={busy} ariaLabel={on ? '取消收藏' : '收藏'}
    onClick={toggle}>
    <Text>{on ? '已收藏' : '收藏'}</Text>
  </Button>
}

/** 登录态详情页加载成功后上报一次浏览（客户端埋点，58号口径；失败静默，不阻塞详情）。 */
export function useFootprintRecord(input: { enabled: boolean; targetType: TrackTargetType; targetId: string; preview: boolean; scope: WorkspaceScope }) {
  const { enabled, targetType, targetId, preview, scope } = input
  const recorded = useRef('')
  useEffect(() => {
    if (!enabled || preview || !consumerApi.currentSession) return
    const key = `${scope.revision}:${targetType}:${targetId}`
    if (recorded.current === key) return
    recorded.current = key
    // 匿名浏览不产生足迹（口径披露）：仅登录态埋点；结果不影响页面状态。
    void scope.run(undefined, () => new RealFavoriteFootprintRepository(consumerApi)
      .recordFootprint(targetType, targetId)).catch(() => undefined)
  }, [enabled, targetType, targetId, preview, scope])
}
