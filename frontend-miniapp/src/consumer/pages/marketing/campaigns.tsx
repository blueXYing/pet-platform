import { Button, Image, Text, View } from '@tarojs/components'
import { SubpageHeader, SubpageHeaderSpacer } from '../../components/navigation/subpage-header'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { readSelectedCity } from '../../city/model'
import { MarketingRepository, type CampaignCard, type MarketingTag } from '../../marketing/model'
import '../../marketing/marketing.css'

const repository = new MarketingRepository(consumerApi)

export default function MarketingCampaignsPage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = { '--mkt-unit': `${unit}px` } as CSSProperties
  const [tags, setTags] = useState<readonly MarketingTag[]>([])
  const [activeTag, setActiveTag] = useState<string | undefined>(undefined)
  const [items, setItems] = useState<readonly CampaignCard[]>([])
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const sequence = useRef(0)

  const load = useCallback((tagCode?: string) => {
    const seq = ++sequence.current
    const cityCode = readSelectedCity().cityCode
    setPhase('loading')
    Promise.all([repository.tags(cityCode), repository.campaigns(tagCode, cityCode, 1, 50)])
      .then(([nextTags, page]) => {
        if (seq !== sequence.current) return
        setTags(nextTags)
        setItems(page.items)
        setPhase('ready')
      })
      .catch(() => { if (seq === sequence.current) setPhase('error') })
  }, [])

  useDidShow(() => { load(activeTag) })
  useEffect(() => { load(activeTag) }, [activeTag, load])

  function back() {
    Taro.navigateBack().catch(() => Taro.switchTab({ url: '/consumer/pages/home/index' }))
  }

  return <View className='mkt-page' style={style}>
    <SubpageHeader title='优惠活动' height={46} onBack={back} />
    <SubpageHeaderSpacer height={46} />
    <View className='mkt-tags'>
      <View className={activeTag === undefined ? 'mkt-tag mkt-tag-active' : 'mkt-tag'} onClick={() => setActiveTag(undefined)}>
        <Text>全部</Text>
      </View>
      {tags.map(tag => <View key={tag.tagCode}
        className={activeTag === tag.tagCode ? 'mkt-tag mkt-tag-active' : 'mkt-tag'}
        onClick={() => setActiveTag(tag.tagCode)}>
        <Text>{tag.tagName}</Text>
      </View>)}
    </View>
    {phase === 'loading' && <View className='mkt-empty'><Text>正在加载活动…</Text></View>}
    {phase === 'error' && <View className='mkt-error'>
      <Text>活动暂时加载失败</Text>
      <View className='mkt-retry' onClick={() => load(activeTag)}><Text>重新加载</Text></View>
    </View>}
    {phase === 'ready' && items.length === 0 && <View className='mkt-empty'><Text>当前城市暂无进行中的活动</Text></View>}
    {phase === 'ready' && items.length > 0 && <View className='mkt-campaign-list'>
      {items.map(item => <View key={item.campaignId} className='mkt-campaign-card'
        onClick={() => Taro.navigateTo({ url: `/consumer/pages/marketing/campaign?campaignId=${encodeURIComponent(item.campaignId)}` }).catch(() => undefined)}>
        {item.coverUrl
          ? <Image className='mkt-campaign-cover' src={item.coverUrl} mode='aspectFill' />
          : <View className='mkt-campaign-cover mkt-cover-fallback' />}
        <View className='mkt-campaign-body'>
          {item.tagName ? <View className='mkt-pill'><Text>{item.tagName}</Text></View> : null}
          <Text className='mkt-campaign-title'>{item.title}</Text>
          {item.subtitle ? <Text className='mkt-campaign-sub'>{item.subtitle}</Text> : null}
          <View className='mkt-campaign-meta'>
            <Text>{item.storeCount} 家门店参与</Text>
            <Text>{item.startAt.slice(0, 10)} 至 {item.endAt.slice(0, 10)}</Text>
          </View>
        </View>
      </View>)}
    </View>}
    <View className='mkt-detail-footer'><Text>活动内容与优惠以页面展示及门店实际服务为准</Text></View>
  </View>
}
