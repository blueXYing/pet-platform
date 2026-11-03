import { Button, ScrollView, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useState, type CSSProperties } from 'react'
import { faqGroups, SERVICE_HOTLINE, SERVICE_HOTLINE_NOTE } from '../../settings/model'
import { SubpageHeader } from '../../components/navigation/subpage-header'
import './index.css'

// 帮助与客服页（67号 / PRD 5.1.25；设计稿 130:16491/130:16704，登记表偏差小节）。
// 布局 1:1 沿原稿：客服卡（标题+说明+联系客服按钮）+ FAQ 三组（问题行点击展开/收起答案，
// 展开态即 130:16704）。承载形态按 CCR-CS-FEEDBACK-001 最小实现：人工客服=热线联系方式
// 展示 + wx.makePhoneCall 电话唤起（无 IM、无 AI、无在线会话——SSOT §17 + AGENTS 硬规则）；
// FAQ 答案按平台已批规则改写（原稿「取消订单」答案与 V1 无取消入口规则冲突，记偏差）。
// 偏差：原稿底部 tab（首页/订单/核销/消息/我的）为设计残留不还原；「7×24」文案按人工
// 服务时间口径改写。
const FEEDBACK_URL = '/consumer/pages/feedback/index'
const MINE_TAB_URL = '/consumer/pages/mine/index'

export default function HelpPage() {
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = {
    '--hp-top': `${platformInfo.statusBarHeight || 0}px`,
    '--hp-unit': `${unit}px`,
  } as CSSProperties
  // 单展开态（手风琴）；null=全部收起。
  const [openKey, setOpenKey] = useState<string | null>(null)
  const [notice, setNotice] = useState('')

  function toggle(groupIndex: number, itemIndex: number) {
    const key = `${groupIndex}-${itemIndex}`
    setOpenKey(current => (current === key ? null : key))
  }

  function callHotline() {
    Taro.makePhoneCall({ phoneNumber: SERVICE_HOTLINE })
      .catch(() => setNotice('拨号未完成，请手动拨打客服热线。'))
  }

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: MINE_TAB_URL })
  }

  return <View className='cpn-page hp-page' style={style}>
    <SubpageHeader title='帮助与客服' backId='hp-back' fixed={false} height={46} onBack={() => void back()} />
    <ScrollView className='hp-body' scrollY enhanced showScrollbar={false}>
      <View className='hp-service-card'>
        <Text className='hp-service-title'>人工客服热线</Text>
        <Text className='hp-service-note'>{SERVICE_HOTLINE_NOTE}</Text>
        <Button id='hp-call' className='hp-call' hoverClass='none' onClick={callHotline}>联系客服</Button>
        <Text className='hp-hotline'>{SERVICE_HOTLINE}</Text>
      </View>
      {faqGroups.map((group, groupIndex) => <View key={group.group} className='hp-group'>
        <Text className='hp-group-title'>{group.group}</Text>
        <View className='hp-group-card'>
          {group.items.map((item, itemIndex) => {
            const key = `${groupIndex}-${itemIndex}`
            const open = openKey === key
            return <View key={item.question} className='hp-item'>
              <Button id={`hp-q-${key}`} className='hp-question' hoverClass='none'
                ariaLabel={`${item.question}，${open ? '已展开' : '未展开'}`} onClick={() => toggle(groupIndex, itemIndex)}>
                <Text className='hp-question-text'>{item.question}</Text>
                <View className={`hp-arrow${open ? ' hp-arrow-open' : ''}`} />
              </Button>
              {open && <View className='hp-answer' id={`hp-a-${key}`}>
                <Text className='hp-answer-text'>{item.answer}</Text>
              </View>}
            </View>
          })}
        </View>
      </View>)}
      <View className='hp-feedback-hint'>
        <Text className='hp-feedback-text'>没有找到答案？</Text>
        <Button id='hp-feedback' className='hp-feedback-btn' hoverClass='none'
          onClick={() => Taro.navigateTo({ url: FEEDBACK_URL }).catch(() => setNotice('页面打开失败，请重试'))}>
          去提交意见反馈
        </Button>
      </View>
      {notice !== '' && <Text id='hp-notice' className='hp-notice'>{notice}</Text>}
    </ScrollView>
  </View>
}
