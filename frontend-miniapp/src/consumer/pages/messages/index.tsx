import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { ConsumerPageLayout } from '../../components/page-layout'
import { MessagesController, jumpLabelFor, routeForNotification } from '../../notifications/messages'
import { realNotificationDeps } from '../../notifications/messages-runtime'
import backIcon from '../../assets/messages/header-back.png'
import gearIcon from '../../assets/messages/header-gear.png'
import chevronIcon from '../../assets/messages/row-chevron.png'
import entryInteractionIcon from '../../assets/messages/entry-interaction.png'
import entryLikesIcon from '../../assets/messages/entry-likes.png'
import entryFollowsIcon from '../../assets/messages/entry-follows.png'
import entryCommentsIcon from '../../assets/messages/entry-comments.png'
import avatarPlaceholder from '../../assets/profile/avatar.png'
import './index.scss'

// 消息族页面视觉源（2026-10-12 Figma 新版，file bp2vpcjjA5vZbHvtKkA8wl）：
// 消息主页 116:5219（690:9623 与其逐节点同构、690:10109 仅多一组重复 tabbar，均取 116:5219 语义）；
// 通知列表行结构借用子稿 116:5755 互动消息 / 116:6068 赞和收藏 / 116:6213、116:6311 新增关注（评论@）。
// V1 范围排除：聊天会话区（117:8821，V1 无私信 IM）、创作中心卡（宠友圈族不做）；
// 四个快捷入口按原稿做视觉分区（V1 后端无对应分类/计数，原稿角标不渲染，见登记表 §4.6）。
// 功能/契约零改动：控制器、状态机、跳转白名单、按钮 id 与交互全部保持，仅重排视觉。
// tabbar+login slice (2026-10-08): this page is a native tabBar page; the back button only
// renders when actually stacked (tab entry is a page-stack root) and the 401 guide leads to the
// phone-authorization card on the “mine” tab instead of the demoted engineering shell.
const QUICK_ENTRIES = [
  { key: 'interaction', label: '互动消息', icon: entryInteractionIcon },
  { key: 'likes', label: '赞和收藏', icon: entryLikesIcon },
  { key: 'follows', label: '新增关注', icon: entryFollowsIcon },
  { key: 'comments', label: '评论和@', icon: entryCommentsIcon },
] as const

export default function MessagesPage() {
  const [controller] = useState(() => new MessagesController(realNotificationDeps()))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [stacked] = useState(() => Taro.getCurrentPages().length > 1)
  useDidShow(() => { void controller.load() })
  useEffect(() => () => controller.dispose(), [controller])
  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: '/consumer/pages/home/index' })
  }
  function goAuthorize() {
    void Taro.switchTab({ url: '/consumer/pages/mine/index' })
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
  // 原稿画板宽 402：--messages-unit 把设计稿 px 一比一映射到当前机型（与 mine 页同法）。
  const style = {
    '--messages-top': `${windowInfo.statusBarHeight || 0}px`,
    '--messages-unit': `${windowInfo.windowWidth / 402}px`,
  } as CSSProperties
  const mode = state.detail === null ? 'messages-mode-list' : 'messages-mode-detail'
  return <ConsumerPageLayout page='profileEdit' unit={1} className='messages-page messages-subpage' style={style}>
    <View className={`messages-design ${mode} messages-state-${state.status}`}>
      <View className='messages-header'>
        {stacked && <Button id='messages-back' className='messages-iconbtn' ariaLabel='返回' onClick={() => void back()}>
          <Image className='messages-iconbtn-img' src={backIcon} mode='aspectFit' />
        </Button>}
        <Text className='messages-header-title'>{state.detail ? '消息详情' : '消息'}</Text>
        {state.detail === null && <Button id='messages-preferences' className='messages-iconbtn' ariaLabel='通知偏好设置'
          onClick={() => void Taro.navigateTo({ url: '/consumer/pages/notification-preferences/index' })}>
          <Image className='messages-iconbtn-img' src={gearIcon} mode='aspectFit' />
        </Button>}
      </View>
      {state.detail === null && <View className='messages-quick' aria-label='消息快捷分区'>
        {QUICK_ENTRIES.map(entry => <View key={entry.key} className='messages-quick-entry'>
          <View className='messages-quick-icon'>
            <Image className='messages-quick-img' src={entry.icon} mode='aspectFit' />
          </View>
          <Text className='messages-quick-label'>{entry.label}</Text>
        </View>)}
      </View>}
      <View className='messages-body'>
        {state.status === 'loading' && <View className='messages-line' role='status'>正在读取消息…</View>}
        {(state.status === 'empty' || state.status === 'error' || state.status === 'unauthorized') && <View className='messages-card'>
          <Text className='messages-notice'>{state.notice || '暂无消息。'}</Text>
          {state.status === 'unauthorized'
            ? <Button id='messages-login' className='messages-action' onClick={goAuthorize}>去登录</Button>
            : state.status === 'error' ? <Button id='messages-retry' className='messages-action' onClick={() => void controller.load()}>重试</Button> : null}
        </View>}
        {state.status === 'ready' && state.detail === null && <View className='messages-list'>
          {state.items.map(item => <Button key={item.id} className='messages-item' onClick={() => void controller.open(item.id)}>
            <View className='messages-item-side'>
              <Image className='messages-item-avatar' src={avatarPlaceholder} mode='aspectFill' />
              {item.readAt === null && <View className='messages-dot' aria-label='未读' />}
            </View>
            <View className='messages-item-main'>
              <View className='messages-item-head'>
                <Text className='messages-item-title'>{item.title}</Text>
                <Text className='messages-item-meta'>{item.createdAt}</Text>
              </View>
              <Text className='messages-item-content'>{item.content}</Text>
            </View>
            <Image className='messages-item-chevron' src={chevronIcon} mode='aspectFit' />
          </Button>)}
        </View>}
        {state.status === 'ready' && state.detail !== null && <View className='messages-card messages-card-detail'>
          <Text className='messages-title'>{state.detail.title}</Text>
          <Text className='messages-meta'>{state.detail.createdAt} · {state.detail.readAt ? `已读于 ${state.detail.readAt}` : '未读'}</Text>
          <Text className='messages-content'>{state.detail.content}</Text>
          <View className='messages-card-actions'>
            {jumpLabelFor(state.detail) && <Button id='messages-jump' className='messages-action' onClick={jump}>{jumpLabelFor(state.detail)}</Button>}
            <Button id='messages-back-list' className='messages-action messages-action-secondary' onClick={() => controller.closeDetail()}>返回列表</Button>
          </View>
        </View>}
      </View>
    </View>
  </ConsumerPageLayout>
}
