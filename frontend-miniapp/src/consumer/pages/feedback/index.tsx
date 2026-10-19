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

// 意见反馈提交页（67号 / PRD 5.1.28 投诉类；设计稿 130:17185）。
// 布局 1:1 沿原稿：反馈类型四胶囊（单选，选中态高亮）→ 联系方式选填输入（占位提示原文）
// → 反馈内容多行输入（0/500 计数）→ 提交反馈主按钮。提交走 ConsumerApi.write 幂等槽
// feedback:create（首次 201 / 同键重放 200）；成功 toast 并跳「我的反馈」列表。
// 偏差登记（CCR-CS-FEEDBACK-001）：图片附件 V1 不做（设计稿未见上传控件，PRD 6 张附件
// 需私有资产通道扩展，另立项）；未登录不可达（mine 入口登录态才展示，本页请求 401 引导去登录）。
const FEEDBACK_LIST_URL = '/consumer/pages/feedback/list'
const MINE_TAB_URL = '/consumer/pages/mine/index'

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
  const [submitting, setSubmitting] = useState(false)
  const [notice, setNotice] = useState('')
  const [unauthorized, setUnauthorized] = useState(false)

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
    setSubmitting(true)
    setNotice('')
    try {
      const repository = new RealFeedbackRepository(consumerApi)
      await repository.create(type as FeedbackType, contact.trim() === '' ? null : contact.trim(), content)
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
