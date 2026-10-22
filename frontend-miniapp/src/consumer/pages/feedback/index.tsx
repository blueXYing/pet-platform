import { Button, Image, Input, ScrollView, Text, Textarea, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useState, type CSSProperties } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealFeedbackRepository } from '../../feedback/repository'
import {
  codePointLength, feedbackContactMax, feedbackContactPlaceholder,
  feedbackContentMax, feedbackContentPlaceholder, feedbackTypeOptions,
  validateFeedback, type FeedbackType,
} from '../../feedback/model'
import backIcon from '../../assets/profile/back.png'
import './index.css'

// 意见反馈提交页（67号 v0.2 / PRD 5.1.28 投诉类；设计稿 130:17185）。
// 布局 1:1 沿原稿：反馈类型四胶囊（单选，选中态高亮）→ 联系方式选填输入（占位提示原文）
// → 反馈内容多行输入（0/500 计数）→ 提交反馈主按钮。提交走 ConsumerApi.write 幂等槽
// feedback:create（首次 201 / 同键重放 200）；成功 toast 并跳「我的反馈」列表。
// v0.2 附件（2026-10-19 用户裁决"继续扩展CCR"）：内容区下方增图片附件位（0..6 张，
// 走 31号私有资产管线 FEEDBACK_ATTACHMENT 用途上传，PDFD 5MB 上界沿管线 10MiB 口径披露）；
// 上传成功持有 assetId，提交时随单携带，移除即弃用该 asset。未登录不可达（mine 入口登录态
// 才展示，本页请求 401 引导去登录）。
const FEEDBACK_LIST_URL = '/consumer/pages/feedback/list'
const MINE_TAB_URL = '/consumer/pages/mine/index'
const ATTACHMENT_MAX = 6

type Attachment = { filePath: string; assetId: string | null; uploading: boolean }
export default function FeedbackPage() {
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = {
    '--fb-top': `${platformInfo.statusBarHeight || 0}px`,
    '--fb-unit': `${unit}px`,
  } as CSSProperties
  const [type, setType] = useState<FeedbackType | null>(null)
  const [contact, setContact] = useState('')
  const [content, setContent] = useState('')
  const [attachments, setAttachments] = useState<Attachment[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [notice, setNotice] = useState('')
  const [unauthorized, setUnauthorized] = useState(false)

  async function addAttachment() {
    if (attachments.length >= ATTACHMENT_MAX) return
    setNotice('')
    try {
      const chosen = await Taro.chooseImage({ count: ATTACHMENT_MAX - attachments.length, sizeType: ['compressed'] })
      const ownerUserId = consumerApi.currentSession?.userId
      if (!ownerUserId) {
        setUnauthorized(true)
        setNotice('登录已失效，请回到「我的」重新登录后再提交。')
        return
      }
      for (const filePath of chosen.tempFilePaths) {
        const assetId = await uploadOne(filePath, ownerUserId)
        setAttachments(current => current.length >= ATTACHMENT_MAX
          ? current
          : [...current, { filePath, assetId, uploading: false }])
      }
    } catch (error) {
      const status = (error as { statusCode?: number }).statusCode
      if (status === 401) {
        setUnauthorized(true)
        setNotice('登录已失效，请回到「我的」重新登录后再提交。')
      } else if (status === 413 || status === 415) {
        setNotice('仅支持 JPEG/PNG 图片，且不超过大小限制。')
      } else if (status !== undefined) {
        setNotice('图片上传失败，请稍后重试。')
      }
    }
  }

  async function uploadOne(filePath: string, ownerUserId: string): Promise<string> {
    return consumerApi.uploadPrivateAsset({
      filePath,
      requestId: await consumerApi.uuid(),
      ownerUserId,
      purpose: 'FEEDBACK_ATTACHMENT',
    }).then(receipt => receipt.assetId)
  }

  function removeAttachment(filePath: string) {
    setAttachments(current => current.filter(item => item.filePath !== filePath))
  }

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: MINE_TAB_URL })
  }

  async function submit() {
    if (submitting) return
    const problem = validateFeedback({ type, contact, content })
    if (problem !== null) {
      setNotice(problem)
      return
    }
    if (attachments.some(item => item.assetId === null)) {
      setNotice('图片还在上传中，请稍候。')
      return
    }
    setSubmitting(true)
    setNotice('')
    try {
      const repository = new RealFeedbackRepository(consumerApi)
      await repository.create(type as FeedbackType, contact.trim() === '' ? null : contact.trim(), content,
        attachments.map(item => item.assetId).filter((value): value is string => value !== null))
      Taro.showToast({ title: '反馈已提交', icon: 'success' })
      await Taro.redirectTo({ url: FEEDBACK_LIST_URL })
    } catch (error) {
      const status = (error as { statusCode?: number }).statusCode
      if (status === 401) {
        setUnauthorized(true)
        setNotice('登录已失效，请回到「我的」重新登录后再提交。')
      } else if (status === 429) {
        setNotice('提交过于频繁，请稍后再试。')
      } else {
        setNotice('提交失败，请稍后重试。')
      }
    } finally {
      setSubmitting(false)
    }
  }

  function openList() {
    Taro.navigateTo({ url: FEEDBACK_LIST_URL }).catch(() => setNotice('页面打开失败，请重试'))
  }

  return <View className='cpn-page fb-page' style={style}>
    <View className='fb-status-area' />
    <View className='fb-header'>
      <Button id='fb-back' className='fb-back' hoverClass='none' ariaLabel='返回' onClick={() => void back()}>
        <Image src={backIcon} className='fb-back-icon' />
      </Button>
      <Text className='fb-title'>意见反馈</Text>
      <Button id='fb-list' className='fb-list-entry' hoverClass='none' onClick={openList}>我的反馈</Button>
    </View>
    <ScrollView className='fb-body' scrollY enhanced showScrollbar={false}>
      <Text className='fb-label'>反馈类型</Text>
      <View className='fb-types'>
        {feedbackTypeOptions.map(option => (
          <Button key={option.code} id={`fb-type-${option.code}`} hoverClass='none'
            className={`fb-type-chip${type === option.code ? ' fb-type-on' : ''}`}
            ariaLabel={option.label} onClick={() => { setType(option.code); setNotice('') }}>
            {option.label}
          </Button>
        ))}
      </View>

      <Text className='fb-label'>联系方式（选填）</Text>
      <View className='fb-input-card'>
        <Input
          id='fb-contact'
          className='fb-input'
          value={contact}
          maxlength={feedbackContactMax}
          placeholder={feedbackContactPlaceholder}
          placeholderClass='fb-placeholder'
          onInput={event => setContact(event.detail.value)}
        />
      </View>

      <Text className='fb-label'>图片附件（选填，最多 {ATTACHMENT_MAX} 张）</Text>
      <View className='fb-attachments'>
        {attachments.map(item => <View key={item.filePath} className='fb-attachment'>
          <Image src={item.filePath} className='fb-attachment-thumb' mode='aspectFill' />
          <Button hoverClass='none' ariaLabel='移除图片' className='fb-attachment-remove'
            onClick={() => removeAttachment(item.filePath)}>×</Button>
        </View>)}
        {attachments.length < ATTACHMENT_MAX && <Button id='fb-attach' hoverClass='none'
          className='fb-attachment-add' ariaLabel='添加图片' onClick={() => void addAttachment()}>＋</Button>}
      </View>

      <Text className='fb-label'>反馈内容</Text>
      <View className='fb-content-card'>
        <Textarea
          id='fb-content'
          className='fb-textarea'
          value={content}
          maxlength={feedbackContentMax}
          placeholder={feedbackContentPlaceholder}
          placeholderClass='fb-placeholder'
          onInput={event => setContent(event.detail.value)}
        />
        <Text className='fb-counter'>{codePointLength(content)}/{feedbackContentMax}</Text>
      </View>

      <Button id='fb-submit' className='fb-submit' hoverClass='none'
        disabled={submitting} onClick={() => void submit()}>
        {submitting ? '正在提交…' : '提交反馈'}
      </Button>
      {notice !== '' && <Text id='fb-notice' className='fb-notice'>{notice}</Text>}
      {unauthorized && <Button id='fb-login' className='fb-login' hoverClass='none'
        onClick={() => void Taro.switchTab({ url: MINE_TAB_URL })}>去登录</Button>}
    </ScrollView>
  </View>
}
