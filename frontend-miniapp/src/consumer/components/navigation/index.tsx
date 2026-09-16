import { Button, Image, Text, View } from '@tarojs/components'
import type { CSSProperties } from 'react'
import home from '../../assets/navigation/home.png'
import services from '../../assets/navigation/services.png'
import community from '../../assets/navigation/community.png'
import messages from '../../assets/navigation/messages.png'
import mine from '../../assets/navigation/mine.png'
import { consumerNavigationItems, type ConsumerNavigationKey } from './model'
import './style.css'

const icons = { home, services, community, messages, mine }
export type ConsumerBottomNavigationProps = {
  current: ConsumerNavigationKey
  disabled?: boolean
  idPrefix?: string
  onSelect: (key: ConsumerNavigationKey) => void | Promise<void>
  // Figma comparison only. Normal pages always use the viewport bottom/safe area.
  referencePlacement?: { top: number } | { bottom: number; height?: number }
}

export function ConsumerBottomNavigation({ current, disabled = false, idPrefix = 'consumer', onSelect, referencePlacement }: ConsumerBottomNavigationProps) {
  const style: CSSProperties = referencePlacement ? {
    position: 'absolute',
    ...('top' in referencePlacement
      ? { top: `calc(var(--consumer-nav-unit) * ${referencePlacement.top})`, bottom: 'auto' }
      : { bottom: `calc(var(--consumer-nav-unit) * ${referencePlacement.bottom})`, height: `calc(var(--consumer-nav-unit) * ${referencePlacement.height || 62})` }),
  } : {}
  return <View className={`consumer-bottom-nav${referencePlacement ? ' consumer-bottom-nav-reference' : ''}`} style={style} role='navigation' ariaLabel='主导航' data-current={current}>
    {consumerNavigationItems.map(item => <Button key={item.key} id={`${idPrefix}-tab-${item.key}`}
      className={`consumer-nav-item consumer-nav-${item.key}`} disabled={disabled}
      ariaLabel={`${item.label}${item.key === current ? '，当前栏目' : ''}`}
      onClick={() => { if (!disabled) void onSelect(item.key) }}>
      <Image className='consumer-nav-icon' src={icons[item.key]} mode='scaleToFill' />
      <Text className='consumer-nav-label'>{item.label}</Text>
    </Button>)}
  </View>
}
