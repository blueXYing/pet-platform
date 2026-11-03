import { Image, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect, useRef, useState } from 'react'
import { consumerApi } from '../../shared/consumer-runtime'
import { switchConsumerTab } from '../components/navigation/switch'
import { MarketingRepository, type HomeBlock } from './model'
import paper from '../assets/home/home-blocks/paper-bg.png'
import hotPets from '../assets/home/home-blocks/hot-services-pets.png'
import communityPets from '../assets/home/home-blocks/community-pets.png'
import exoticTrees from '../assets/home/home-blocks/exotic-trees.png'
import exoticFlower from '../assets/home/home-blocks/exotic-flower.png'
import tagLeaf from '../assets/home/home-blocks/tag-leaf.svg'
import './marketing.css'

const repository = new MarketingRepository(consumerApi)

type BlockMap = Readonly<{
  hot?: HomeBlock
  community?: HomeBlock
  exotic?: HomeBlock
}>

const FALLBACK_IMAGE: Record<string, string> = {
  HOT_SERVICE_FEATURE: hotPets,
  COMMUNITY_HOT: communityPets,
  EXOTIC_PLANT: exoticTrees,
}

function groups(items: readonly HomeBlock[]): BlockMap {
  return {
    hot: items.find(item => item.positionCode === 'HOME_SERVICE'),
    community: items.find(item => item.positionCode === 'HOME_COMMUNITY'),
    exotic: items.find(item => item.positionCode === 'HOME_EXOTIC_PLANT'),
  }
}

function categoryTarget(block: HomeBlock): string {
  const value = (block.targetValue || '').trim()
  if (block.targetType === 'SERVICE_DIRECTORY') return '/consumer/pages/store-services/stores?preview=0'
  if (block.targetType === 'SERVICE_CATEGORY') {
    return `/consumer/pages/store-services/stores?preview=0&keyword=${encodeURIComponent(value)}`
  }
  if (block.targetType === 'STORE') {
    return `/consumer/pages/store-services/index?preview=0&storeId=${encodeURIComponent(value)}`
  }
  if (block.targetType === 'SERVICE') {
    return `/consumer/pages/store-services/service-detail?preview=0&serviceId=${encodeURIComponent(value)}`
  }
  if (block.targetType === 'CAMPAIGN') {
    return `/consumer/pages/marketing/campaign?campaignId=${encodeURIComponent(value)}`
  }
  if (block.targetType === 'MINIAPP_PATH') return value
  return ''
}

function openBlock(block: HomeBlock) {
  if (block.targetType === 'COMMUNITY') {
    void switchConsumerTab('community').catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
    return
  }
  const path = categoryTarget(block)
  if (!path) return
  Taro.navigateTo({ url: path }).catch(() => Taro.showToast({ title: '页面打开失败，请重试', icon: 'none' }))
}

function Tag({ name, tone }: { name: string; tone: 'green' | 'blue' | 'yellow' }) {
  return <View className={`home-content-tag home-content-tag-${tone}`}>
    <Image className='home-content-tag-icon' src={tagLeaf} mode='aspectFit' />
    <Text className='home-content-tag-text'>{name}</Text>
  </View>
}

/**
 * CCR-MKT-003 home operations blocks. Content (title/copy/image/target) comes from the backend;
 * the paper/card shells and fallback template illustrations are design-system assets. The whole
 * section hides when no active block is returned or the read fails.
 */
export function HomeContentSections({ cityCode }: { cityCode?: string }) {
  const [blocks, setBlocks] = useState<BlockMap>({})
  const [ready, setReady] = useState(false)
  const sequence = useRef(0)
  useEffect(() => {
    const seq = ++sequence.current
    setReady(false)
    repository.homeBlocks(cityCode)
      .then(items => {
        if (seq !== sequence.current) return
        setBlocks(groups(items))
        setReady(true)
      })
      .catch(() => {
        if (seq !== sequence.current) return
        setBlocks({})
        setReady(true)
      })
  }, [cityCode])
  if (!ready || (!blocks.hot && !blocks.community && !blocks.exotic)) return null
  return <View className='home-content-section'>
    <Image className='home-content-paper' src={paper} mode='scaleToFill' />
    <View className='home-content-grid'>
      {blocks.hot && <View className='home-content-hot' onClick={() => openBlock(blocks.hot!)}>
        <Tag name={blocks.hot.tagName || '热门服务'} tone='green' />
        <Text className='home-content-hot-title'>{blocks.hot.title}</Text>
        <Text className='home-content-hot-sub'>{blocks.hot.subtitle || '附近宠托师一键预约'}</Text>
        <Image className='home-content-hot-art' src={blocks.hot.imageUrl || FALLBACK_IMAGE.HOT_SERVICE_FEATURE} mode='aspectFit' />
      </View>}
      <View className='home-content-side'>
        {blocks.community && <View className='home-content-small home-content-community' onClick={() => openBlock(blocks.community!)}>
          <Tag name={blocks.community.tagName || '宠友圈热门'} tone='blue' />
          <Text className='home-content-small-title'>{blocks.community.title}</Text>
          <Text className='home-content-small-sub'>{blocks.community.subtitle || '发动态、晒萌宠、找同城伙伴'}</Text>
          <Image className='home-content-community-art' src={blocks.community.imageUrl || FALLBACK_IMAGE.COMMUNITY_HOT} mode='aspectFit' />
        </View>}
        {blocks.exotic && <View className='home-content-small home-content-exotic' onClick={() => openBlock(blocks.exotic!)}>
          <Tag name={blocks.exotic.tagName || '植物&异宠'} tone='yellow' />
          <Text className='home-content-small-title'>{blocks.exotic.title}</Text>
          <Text className='home-content-small-sub'>{blocks.exotic.subtitle || '除了猫猫狗狗，我们同样呵护您的小宠与绿植'}</Text>
          <Image className='home-content-exotic-tree' src={blocks.exotic.imageUrl || FALLBACK_IMAGE.EXOTIC_PLANT} mode='aspectFit' />
          <Image className='home-content-exotic-flower' src={exoticFlower} mode='aspectFit' />
        </View>}
      </View>
    </View>
  </View>
}
