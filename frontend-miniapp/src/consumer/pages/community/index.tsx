import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useRef, useState, type CSSProperties } from 'react'
import { syncCustomTabBar } from '../../components/navigation/custom-tab-bar'
import { RootTabBarSpacer } from '../../components/navigation/root-tab-bar-spacer'
import { consumerApi } from '../../../shared/consumer-runtime'
import defaultAvatar from '../../assets/profile/avatar.png'
import { CommunityRepository, type CommunityPost } from '../../community/model'
import './community.css'

// CCR-COM-001 宠友圈：真实动态流 + 发布入口。没有帖子时显示空态，不伪造内容。
// 设计源 115:3818（宠友圈）/ 103:2465（宠友圈热门）；发现=热门排序，本地=最新，
// 关注流需要登录。图片/作者/计数全部来自 /api/v1/c/community。
const repository = new CommunityRepository(consumerApi)
type Scope = 'FOLLOWING' | 'HOT' | 'LATEST'
const TABS: ReadonlyArray<{ key: Scope; label: string }> = [
  { key: 'FOLLOWING', label: '关注' }, { key: 'HOT', label: '发现' }, { key: 'LATEST', label: '本地' },
]

function timeLabel(value: string | null) {
  if (!value) return ''
  const time = Date.parse(value)
  if (!Number.isFinite(time)) return ''
  const minutes = Math.max(0, Math.floor((Date.now() - time) / 60000))
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  if (minutes < 1440) return `${Math.floor(minutes / 60)}小时前`
  return `${Math.floor(minutes / 1440)}天前`
}

export default function CommunityPage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = { '--cmy-unit': `${unit}px`, '--cmy-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  const [scope, setScope] = useState<Scope>('HOT')
  const [posts, setPosts] = useState<readonly CommunityPost[]>([])
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [notice, setNotice] = useState('')
  const sequence = useRef(0)
  const loading = useRef(false)

  const load = useCallback(async (nextScope: Scope) => {
    const seq = ++sequence.current
    setPhase('loading'); setNotice('')
    try {
      const page = await repository.feed(nextScope, undefined, 1, 20)
      if (seq !== sequence.current) return
      setPosts(page.items)
      setPhase('ready')
      if (nextScope === 'FOLLOWING' && page.total === 0) setNotice('关注的人还没有发布动态')
    } catch {
      if (seq !== sequence.current) return
      setPosts([]); setPhase('error'); setNotice(nextScope === 'FOLLOWING' ? '登录后可查看关注动态' : '动态加载失败，请稍后重试')
    }
  }, [])

  useDidShow(() => {
    syncCustomTabBar(2)
    void load(scope)
  })

  function choose(next: Scope) {
    if (next === scope) return
    setScope(next)
    void load(next)
  }
  function requireLogin() {
    if (consumerApi.currentSession) return true
    setNotice('登录后即可发布、点赞和评论')
    return false
  }
  function openPublish() {
    if (!requireLogin()) return
    Taro.navigateTo({ url: '/consumer/pages/community/publish' }).catch(() => setNotice('页面打开失败，请重试'))
  }
  function openDetail(post: CommunityPost) {
    Taro.navigateTo({ url: `/consumer/pages/community/detail?postId=${encodeURIComponent(post.postId)}` })
      .catch(() => setNotice('页面打开失败，请重试'))
  }
  async function toggleLike(post: CommunityPost) {
    if (!requireLogin() || loading.current) return
    loading.current = true
    try {
      const result = post.liked ? await repository.unlike(post.postId) : await repository.like(post.postId)
      setPosts(items => items.map(item => item.postId === post.postId
        ? { ...item, liked: result.active, likeCount: result.count } : item))
    } catch { setNotice('操作失败，请重试') }
    finally { loading.current = false }
  }

  const left = posts.filter((_, index) => index % 2 === 0)
  const right = posts.filter((_, index) => index % 2 === 1)
  function card(post: CommunityPost, index: number) {
    return <Button key={post.postId} className={`cmy-card${index % 3 === 1 ? ' cmy-card-tall' : ''}`}
      hoverClass='none' onClick={() => openDetail(post)}>
      {post.images.length > 0
        ? <Image className='cmy-card-image' src={post.images[0]} mode='aspectFill' lazyLoad />
        : <View className='cmy-card-noimage'><Text>毛孩子的日常</Text></View>}
      <View className='cmy-card-body'>
        {post.title ? <Text className='cmy-card-title'>{post.title}</Text> : null}
        <Text className='cmy-card-text'>{post.content}</Text>
        <View className='cmy-card-meta'>
          <Image className='cmy-card-avatar' src={post.author.avatarUrl || defaultAvatar} mode='aspectFill' />
          <Text className='cmy-card-author'>{post.author.nickname}</Text>
          <Button className='cmy-card-like' hoverClass='none'
            onClick={event => { event.stopPropagation(); void toggleLike(post) }}>
            <Text className={post.liked ? 'cmy-heart cmy-heart-on' : 'cmy-heart'}>{post.liked ? '♥' : '♡'}</Text>
            <Text className='cmy-like-count'>{post.likeCount}</Text>
          </Button>
        </View>
        <Text className='cmy-card-time'>{timeLabel(post.publishedAt)}</Text>
      </View>
    </Button>
  }

  return <View className='cmy-page' style={style}>
    <View className='cmy-status-area' />
    <View className='cmy-header'>
      <Text className='cmy-title'>宠友圈</Text>
    </View>
    <View className='cmy-tabs'>
      {TABS.map(tab => <Button key={tab.key} className={tab.key === scope ? 'cmy-tab cmy-tab-active' : 'cmy-tab'}
        hoverClass='none' onClick={() => choose(tab.key)}>
        <Text>{tab.label}</Text>
      </Button>)}
    </View>
    <View className='cmy-publish-bar' role='button' onClick={openPublish}>
      <Image className='cmy-publish-avatar' src={defaultAvatar} mode='aspectFill' />
      <Text className='cmy-publish-placeholder'>分享你和毛孩子的日常…</Text>
      <Text className='cmy-publish-icon'>＋</Text>
    </View>
    {phase === 'loading' && <View className='cmy-state'><Text>正在加载动态…</Text></View>}
    {phase === 'error' && <View className='cmy-state'><Text>{notice || '动态加载失败'}</Text>
      <Button className='cmy-state-action' onClick={() => void load(scope)}>重新加载</Button></View>}
    {phase === 'ready' && posts.length === 0 && <View className='cmy-state'>
      <Text>{notice || '还没有动态，来发第一条吧'}</Text>
      <Button className='cmy-state-action' onClick={openPublish}>发布动态</Button>
    </View>}
    {phase === 'ready' && posts.length > 0 && <View className='cmy-feed'>
      <View className='cmy-column'>{left.map((post, index) => card(post, index * 2))}</View>
      <View className='cmy-column'>{right.map((post, index) => card(post, index * 2 + 1))}</View>
    </View>}
    {notice && phase === 'ready' && posts.length > 0 && <Text className='cmy-notice'>{notice}</Text>}
    <RootTabBarSpacer />
  </View>
}
