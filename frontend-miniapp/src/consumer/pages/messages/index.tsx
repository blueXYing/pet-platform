import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { ConsumerPageLayout } from '../../components/page-layout'
import { MessagesController, routeForNotification } from '../../notifications/messages'
import { realNotificationDeps } from '../../notifications/messages-runtime'
import './index.scss'

// No Figma original exists for the message center (design registry section 4); this page
// follows the application-page language and is not a 1:1 restoration or VIS pass.
export default function MessagesPage() {
  const [controller] = useState(() => new MessagesController(realNotificationDeps()))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => { void controller.load() })
  useEffect(() => () => controller.dispose(), [controller])
  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/consumer/pages/shell/index' })
  }
  function jump() {
    const detail = state.detail
    if (!detail) return
    const url = routeForNotification(detail)
    if (url) void Taro.navigateTo({ url })
    else void Taro.showToast({ title: '该消息无需跳转', icon: 'none' })
  }
  // Custom navigation: the page renders under the OS status bar, so reserve its real height
  // (env(safe-area-inset-top) is 0 in WeChat) the same way the application and signing pages do.
  const [windowInfo] = useState(() => Taro.getWindowInfo())
  const style = { '--messages-top': `${windowInfo.statusBarHeight || 0}px` } as CSSProperties
  return <ConsumerPageLayout page='profileEdit' unit={1} className='messages-page messages-subpage' style={style}>
    <View className='messages-design' data-state={state.status}>
      <View className='messages-header'>
        <Button id='messages-back' ariaLabel='返回' onClick={() => void back()}>返回</Button>
        <Text>{state.detail ? '消息详情' : '消息中心'}</Text>
      </View>
      <View className='messages-body'>
        {state.status === 'loading' && <View className='messages-line' role='status'>正在读取消息…</View>}
        {(state.status === 'empty' || state.status === 'error' || state.status === 'unauthorized') && <View className='messages-card'>
          <Text>{state.notice || '暂无消息。'}</Text>
          {state.status === 'unauthorized'
            ? <Button id='messages-login' onClick={() => void Taro.redirectTo({ url: '/consumer/pages/shell/index' })}>去登录</Button>
            : state.status === 'error' ? <Button id='messages-retry' onClick={() => void controller.load()}>重试</Button> : null}
        </View>}
        {state.status === 'ready' && state.detail === null && <View className='messages-list'>
          {state.items.map(item => <Button key={item.id} className='messages-item' onClick={() => void controller.open(item.id)}>
            <View className='messages-item-head'>
              <Text className='messages-item-title'>{item.title}</Text>
              {item.readAt === null && <View className='messages-dot' aria-label='未读' />}
            </View>
            <Text className='messages-item-content'>{item.content}</Text>
            <Text className='messages-item-meta'>{item.createdAt}</Text>
          </Button>)}
        </View>}
        {state.status === 'ready' && state.detail !== null && <View className='messages-card'>
          <Text className='messages-title'>{state.detail.title}</Text>
          <Text className='messages-meta'>{state.detail.createdAt} · {state.detail.readAt ? `已读于 ${state.detail.readAt}` : '未读'}</Text>
          <Text className='messages-content'>{state.detail.content}</Text>
          {routeForNotification(state.detail) && <Button id='messages-jump' onClick={jump}>查看入驻申请</Button>}
          <Button id='messages-back-list' onClick={() => controller.closeDetail()}>返回列表</Button>
        </View>}
      </View>
    </View>
  </ConsumerPageLayout>
}
