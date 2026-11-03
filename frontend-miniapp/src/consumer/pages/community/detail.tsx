import { Button, Image, Input, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useState, type CSSProperties } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import defaultAvatar from '../../assets/profile/avatar.png'
import { CommunityRepository, type CommunityComment, type CommunityPost } from '../../community/model'
import { SubpageHeader, SubpageHeaderSpacer } from '../../components/navigation/subpage-header'
import './community.css'

const repository = new CommunityRepository(consumerApi)

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

export default function CommunityDetailPage() {
  const route = useRouter()
  const postId = route.params.postId ?? ''
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = { '--cmy-unit': `${unit}px`, '--cmy-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  const [post, setPost] = useState<CommunityPost>()
  const [comments, setComments] = useState<readonly CommunityComment[]>([])
  const [commentTotal, setCommentTotal] = useState(0)
  const [draft, setDraft] = useState('')
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    if (!postId) { setPhase('error'); return }
    setPhase('loading'); setNotice('')
    try {
      const [nextPost, page] = await Promise.all([repository.post(postId), repository.comments(postId, 1, 20)])
      setPost(nextPost); setComments(page.items); setCommentTotal(page.total); setPhase('ready')
    } catch { setPhase('error'); setNotice('动态不存在或已下线') }
  }, [postId])

  useEffect(() => { void load() }, [load])
  function back() { Taro.navigateBack().catch(() => Taro.switchTab({ url: '/consumer/pages/community/index' })) }
  function requireLogin() {
    if (consumerApi.currentSession) return true
    setNotice('登录后即可互动'); return false
  }
  async function toggleLike() {
    if (!post || !requireLogin() || busy) return
    setBusy(true)
    try {
      const result = post.liked ? await repository.unlike(post.postId) : await repository.like(post.postId)
      setPost({ ...post, liked: result.active, likeCount: result.count })
    } catch { setNotice('操作失败，请重试') } finally { setBusy(false) }
  }
  async function toggleFavorite() {
    if (!post || !requireLogin() || busy) return
    setBusy(true)
    try {
      const result = post.favorited ? await repository.unfavorite(post.postId) : await repository.favorite(post.postId)
      setPost({ ...post, favorited: result.active, favoriteCount: result.count })
    } catch { setNotice('操作失败，请重试') } finally { setBusy(false) }
  }
  async function toggleFollow() {
    if (!post || !requireLogin() || busy) return
    setBusy(true)
    try {
      await repository.follow(post.author.userId, !post.followingAuthor)
      setPost({ ...post, followingAuthor: !post.followingAuthor })
    } catch { setNotice('操作失败，请重试') } finally { setBusy(false) }
  }
  async function sendComment() {
    const content = draft.trim()
    if (!post || !content || busy) return
    if (!requireLogin()) return
    setBusy(true)
    try {
      await repository.comment(post.postId, content)
      setDraft('')
      await load()
    } catch { setNotice('评论失败，请重试') } finally { setBusy(false) }
  }

  return <View className='cmy-detail-page' style={style}>
    <SubpageHeader title='动态详情' height={46} onBack={back}
      right={<View className='cmy-top-action'>···</View>} />
    <SubpageHeaderSpacer height={46} />
    {phase === 'loading' && <View className='cmy-state'><Text>正在加载…</Text></View>}
    {phase === 'error' && <View className='cmy-state'><Text>{notice}</Text>
      <Button className='cmy-state-action' onClick={back}>返回宠友圈</Button></View>}
    {phase === 'ready' && post && <>
      <View className='cmy-detail-body'>
        <View className='cmy-author-row'>
          <Image className='cmy-author-avatar' src={post.author.avatarUrl || defaultAvatar} mode='aspectFill' />
          <View className='cmy-author-main'>
            <Text className='cmy-author-name'>{post.author.nickname}</Text>
            <Text className='cmy-author-time'>{timeLabel(post.publishedAt)}{post.topic ? ` · #${post.topic.name}` : ''}</Text>
          </View>
          <Button className={post.followingAuthor ? 'cmy-follow cmy-follow-on' : 'cmy-follow'}
            hoverClass='none' onClick={() => void toggleFollow()}>
            {post.followingAuthor ? '已关注' : '关注'}
          </Button>
        </View>
        {post.title ? <Text className='cmy-detail-title'>{post.title}</Text> : null}
        <Text className='cmy-detail-text'>{post.content}</Text>
        {post.images.length > 0 && <View className='cmy-detail-images'>
          {post.images.map((image, index) => <Image key={`${image}-${index}`} className='cmy-detail-image' src={image} mode='aspectFill' />)}
        </View>}
        <View className='cmy-detail-actions'>
          <View className={post.liked ? 'cmy-detail-action cmy-detail-action-on' : 'cmy-detail-action'} onClick={() => void toggleLike()}>
            <Text>{post.liked ? '♥' : '♡'}</Text><Text>{post.likeCount}</Text>
          </View>
          <View className='cmy-detail-action'><Text>💬</Text><Text>{post.commentCount}</Text></View>
          <View className={post.favorited ? 'cmy-detail-action cmy-detail-action-on' : 'cmy-detail-action'} onClick={() => void toggleFavorite()}>
            <Text>{post.favorited ? '★' : '☆'}</Text><Text>收藏</Text>
          </View>
        </View>
        <Text className='cmy-comments-head'>全部评论（{commentTotal}）</Text>
        {comments.length === 0 && <Text className='cmy-comment-state'>还没有评论，来说点什么吧</Text>}
        {comments.map(comment => <View key={comment.commentId} className='cmy-comment'>
          <Image className='cmy-comment-avatar' src={comment.author.avatarUrl || defaultAvatar} mode='aspectFill' />
          <View className='cmy-comment-main'>
            <Text className='cmy-comment-name'>{comment.author.nickname}</Text>
            <Text className='cmy-comment-text'>{comment.content}</Text>
            <Text className='cmy-comment-time'>{timeLabel(comment.createdAt)}</Text>
          </View>
        </View>)}
        {notice ? <Text className='cmy-notice'>{notice}</Text> : null}
      </View>
      <View className='cmy-comment-bar'>
        <Input className='cmy-comment-input' value={draft} maxlength={500}
          placeholder='说点什么…' onInput={event => setDraft(event.detail.value)} confirmType='send'
          onConfirm={() => void sendComment()} />
        <Button className='cmy-comment-send' hoverClass='none' disabled={busy}
          onClick={() => void sendComment()}>发送</Button>
      </View>
    </>}
  </View>
}
