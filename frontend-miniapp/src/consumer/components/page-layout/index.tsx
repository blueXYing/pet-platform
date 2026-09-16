import { View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect, useState, type CSSProperties, type PropsWithChildren } from 'react'
import { ConsumerBottomNavigation, type ConsumerBottomNavigationProps } from '../navigation'
import { consumerPageSections, type ConsumerNavigationPage } from '../navigation/model'
import './style.css'

type Props = PropsWithChildren<{
  className: string
  style: CSSProperties
  unit: number
  page: ConsumerNavigationPage
  navigation?: Omit<ConsumerBottomNavigationProps, 'current'>
}>

// One owner for bottom navigation and its reserved content space, including subpackage pages.
// Pure business sections remain children; they never mount another global navigation bar.
export function ConsumerPageLayout({ className, style, unit, page, navigation, children }: Props) {
  const [keyboardHeight, setKeyboardHeight] = useState(0)
  useEffect(() => {
    const onKeyboard = ({ height }: { height: number }) => setKeyboardHeight(height)
    Taro.onKeyboardHeightChange(onKeyboard)
    return () => Taro.offKeyboardHeightChange(onKeyboard)
  }, [])
  const reference = Boolean(navigation?.referencePlacement)
  return <View className={`${className} consumer-page-layout${reference ? ' consumer-page-layout-reference' : ''}${navigation ? '' : ' consumer-page-layout-without-navigation'}`}
    style={{ ...style, '--consumer-nav-unit': `${unit}px` } as CSSProperties}>
    {children}
    {keyboardHeight === 0 && navigation && <ConsumerBottomNavigation {...navigation} current={consumerPageSections[page]} />}
  </View>
}
