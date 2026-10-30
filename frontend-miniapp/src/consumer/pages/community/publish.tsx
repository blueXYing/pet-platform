import { Button, Image, Input, Text, Textarea, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect, useState, type CSSProperties } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { CommunityRepository, assetUrl, type CommunityTopic } from '../../community/model'
import { SubpageHeader, SubpageHeaderSpacer } from '../../components/navigation/subpage-header'
import './community.css'

const repository = new CommunityRepository(consumerApi)

type Uploaded = Readonly<{ mediaId: string; url: string }>

export default function CommunityPublishPage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = { '--cmy-unit': `${unit}px`, '--cmy-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [images, setImages] = useState<readonly Uploaded[]>([])
  const [topics, setTopics] = useState<readonly CommunityTopic[]>([])
  const [topicCode, setTopicCode] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const loggedIn = !!consumerApi.currentSession

  useEffect(() => {
    repository.topics().then(items => setTopics(items)).catch(() => setTopics([]))
  }, [])
  function back() { Taro.navigateBack().catch(() => Taro.switchTab({ url: '/consumer/pages/community/index' })) }
  async function chooseImages() {
    if (!loggedIn || busy || images.length >= 9) return
    try {
      const result = await Taro.chooseImage({ count: 9 - images.length, sizeType: ['compressed'], sourceType: ['album', 'camera'] })
      const paths = result.tempFilePaths || []
      if (paths.length === 0) return
      setBusy(true); setNotice('')
      const uploaded: Uploaded[] = []
      for (const path of paths) {
        const media = await repository.uploadMedia(path)
        uploaded.push({ mediaId: media.mediaId, url: assetUrl(media.url) })
      }
      setImages(items => [...items, ...uploaded].slice(0, 9))
    } catch (error) {
      setNotice(error instanceof Error && error.message === 'UPLOAD_NOT_CONNECTED' ? '图片上传未连接' : '图片上传失败，请重试')
    } finally { setBusy(false) }
  }
  function removeImage(mediaId: string) { setImages(items => items.filter(item => item.mediaId !== mediaId)) }
  async function publish() {
    if (!loggedIn) { setNotice('登录后即可发布'); return }
    const value = content.trim()
    if (!value) { setNotice('写点什么再发布吧'); return }
    setBusy(true); setNotice('')
    try {
      await repository.createPost({
        content: value, title: title.trim() || undefined, topicCode,
        imageIds: images.map(image => image.mediaId),
      })
      await Taro.showToast({ title: '发布成功', icon: 'success' })
      setTimeout(() => { void back() }, 300)
    } catch (error) {
      setNotice(error instanceof Error && error.message.includes('PENDING') ? '内容已提交审核' : '发布失败，请重试')
    } finally { setBusy(false) }
  }

  if (!loggedIn) return <View className='cmy-publish-page' style={style}>
    <SubpageHeader title='发布动态' height={46} onBack={back} />
    <SubpageHeaderSpacer height={46} />
    <View className='cmy-publish-state'>
      <Text>登录后即可发布动态、上传图片和参与互动。</Text>
      <Button className='cmy-state-action' onClick={() => Taro.switchTab({ url: '/consumer/pages/mine/index' })}>去登录</Button>
    </View>
  </View>

  return <View className='cmy-publish-page' style={style}>
    <SubpageHeader title='发布动态' height={46} onBack={back}
      right={<Text className='cmy-top-action'>草稿箱</Text>} />
    <SubpageHeaderSpacer height={46} />
    <View className='cmy-publish-form'>
      <View className='cmy-publish-images'>
        {images.map(image => <View key={image.mediaId} className='cmy-publish-thumb-wrap'>
          <Image className='cmy-publish-thumb' src={image.url} mode='aspectFill' />
          <View className='cmy-publish-remove' onClick={() => removeImage(image.mediaId)}>×</View>
        </View>)}
        {images.length < 9 && <View className='cmy-publish-add' onClick={() => void chooseImages()}>＋</View>}
      </View>
      <Input className='cmy-publish-title' value={title} maxlength={60}
        placeholder='给作品添加一个标题吧' onInput={event => setTitle(event.detail.value)} />
      <Textarea className='cmy-publish-text' value={content} maxlength={2000}
        placeholder='分享你和毛孩子的日常…' onInput={event => setContent(event.detail.value)} autoHeight />
      <Text className='cmy-publish-count'>{content.length}/2000</Text>
      <View className='cmy-publish-topics'>
        <Text className='cmy-publish-topics-title'>添加话题</Text>
        <View className='cmy-topic-chips'>
          {topics.map(topic => <View key={topic.topicCode}
            className={topic.topicCode === topicCode ? 'cmy-topic-chip cmy-topic-chip-on' : 'cmy-topic-chip'}
            onClick={() => setTopicCode(topic.topicCode === topicCode ? undefined : topic.topicCode)}>
            #{topic.name}
          </View>)}
        </View>
      </View>
      {notice ? <Text className='cmy-notice'>{notice}</Text> : null}
    </View>
    <Button className='cmy-publish-submit' hoverClass='none' disabled={busy} onClick={() => void publish()}>
      {busy ? '处理中…' : '保存并发布'}
    </Button>
  </View>
}
