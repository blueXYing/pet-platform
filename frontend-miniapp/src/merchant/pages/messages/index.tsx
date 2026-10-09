import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { MessagesController, jumpLabelFor, routeForNotification } from '../../../shared/notifications/messages'
import { previewNotificationDeps } from '../../notifications/preview'
import { realNotificationDeps } from '../../notifications/messages-runtime'
import './page.css'

// M-002 消息通知页 (design source: merchant frame 23:19357「消息通知」, 402x898). The merchant
// owner reads the unified owner-scoped inbox (CCR-W2-NOTIFICATION-001): same endpoints as the
// consumer message center, receiver resolved from the session. Data flows through preview
// fixtures (preview=1, no live calls) or the real repository. The whitelist jump re-validates
// on the target page — a jump never bypasses the services-page workbench gate.
export default function MerchantMessagesPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const [controller] = useState(() => new MessagesController(preview ? previewNotificationDeps() : realNotificationDeps()))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => { void controller.load() })
  useEffect(() => () => controller.dispose(), [controller])
  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: '/consumer/pages/mine/index' })
  }
  function jump() {
    const detail = state.detail
    if (!detail) return
    const url = routeForNotification(detail)
    if (url) void Taro.navigateTo({ url })
    else void Taro.showToast({ title: '该消息无需跳转', icon: 'none' })
  }
  // Custom navigation: reserve the real status-bar height (env(safe-area-inset-top) is 0 in
  // WeChat), the same mechanism as the services and application pages.
  const [windowInfo] = useState(() => Taro.getWindowInfo())
  const style = {
    '--mmsg-top': `${windowInfo.statusBarHeight || 0}px`,
    '--mmsg-unit': `${windowInfo.windowWidth / 402}px`,
  } as CSSProperties
  return <View className='mmsg-page' style={style}>
    <View className='mmsg-status-area' />
    <View className='mmsg-header'>
      <Button id='mmsg-back' ariaLabel='返回' className='mmsg-back' onClick={() => void back()}>返回</Button>
      <Text className='mmsg-title'>消息通知</Text>
    </View>
    <View className='mmsg-body'>
      {state.status === 'loading' && <View className='mmsg-line' role='status'>正在读取消息…</View>}
      {(state.status === 'empty' || state.status === 'error' || state.status === 'unauthorized') && <View className='mmsg-card'>
        <Text>{state.notice || '暂无消息。'}</Text>
        {state.status === 'unauthorized'
          ? <Button id='mmsg-login' className='mmsg-jump' onClick={() => void Taro.switchTab({ url: '/consumer/pages/mine/index' })}>去登录</Button>
          : state.status === 'error' ? <Button id='mmsg-retry' className='mmsg-jump' onClick={() => void controller.load()}>重试</Button> : null}
      </View>}
      {state.status === 'ready' && state.detail === null && <View className='mmsg-list'>
        {state.items.map(item => <Button key={item.id} id={`mmsg-item-${item.id}`} className='mmsg-card mmsg-item' onClick={() => void controller.open(item.id)}>
          <View className='mmsg-item-main'>
            <View className='mmsg-item-head'>
              <Text className='mmsg-item-title'>{item.title}</Text>
              {item.readAt === null && <View className='mmsg-dot' aria-label='未读' />}
            </View>
            <Text className='mmsg-item-content'>{item.content}</Text>
            <Text className='mmsg-item-time'>{item.createdAt}</Text>
          </View>
          {jumpLabelFor(item) && <View className='mmsg-jump' ariaLabel={jumpLabelFor(item) || undefined}>{jumpLabelFor(item)}</View>}
        </Button>)}
        <Text className='mmsg-tail'>{preview ? '页面数据：视觉预览（preview=1，本地数据，不联调）' : '页面数据：真实接口（消息随会话可用）'}</Text>
      </View>}
      {state.status === 'ready' && state.detail !== null && <View className='mmsg-detail'>
        <Text className='mmsg-detail-title'>{state.detail.title}</Text>
        <Text className='mmsg-detail-meta'>{state.detail.createdAt} · {state.detail.readAt ? `已读于 ${state.detail.readAt}` : '未读'}</Text>
        <Text className='mmsg-detail-content'>{state.detail.content}</Text>
        {jumpLabelFor(state.detail) && <Button id='mmsg-jump' className='mmsg-jump' onClick={jump}>{jumpLabelFor(state.detail)}</Button>}
        <Button id='mmsg-back-list' className='mmsg-plain' onClick={() => controller.closeDetail()}>返回列表</Button>
      </View>}
    </View>
  </View>
}
