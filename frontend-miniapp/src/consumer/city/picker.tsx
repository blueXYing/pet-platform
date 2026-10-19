// 城市选择面板（HTTP10 §3.3.4，2026-10-19 城市选择裁决；设计源 76:761「首页地址」下拉：
// #f8f5ee 面板 r12 + 「当前城市」#b89682 10 + 城市行 14px（当前项 #5a4135 + 右缘 ✓，其余 #383838））。
// 纯展示组件：目录与选择回调由页面注入；定位能力 V1 没有（§4.4 维持），面板即手动选择入口。
// 尺寸随宿主页面 unit（windowWidth/402）走 --city-unit，两个宿主页（home/stores）各自传入。
import { Button, Text, View } from '@tarojs/components'
import type { CSSProperties } from 'react'
import type { StoreCity } from '../store/model'
import './picker.css'

const CityPicker = ({ cities, current, includeAll, onPick, idPrefix, unit }: {
  cities: readonly StoreCity[]
  current: string | undefined
  includeAll: boolean
  onPick: (cityCode: string | undefined) => void
  idPrefix: string
  unit: number
}) => <View className='city-picker'
  style={{ '--city-unit': `${unit}px` } as CSSProperties}>
  <Text className='city-picker-label'>当前城市</Text>
  {includeAll && <Button id={`${idPrefix}-all`} hoverClass='none'
    className={`city-picker-row ${current === undefined ? 'is-current' : ''}`}
    onClick={() => onPick(undefined)}>
    <Text>全部开放城市</Text>
    {current === undefined && <Text className='city-picker-check'>✓</Text>}
  </Button>}
  {cities.map(city => <Button key={city.cityCode} id={`${idPrefix}-${city.cityCode}`} hoverClass='none'
    className={`city-picker-row ${current === city.cityCode ? 'is-current' : ''}`}
    onClick={() => onPick(city.cityCode)}>
    <Text>{city.cityName}</Text>
    {current === city.cityCode && <Text className='city-picker-check'>✓</Text>}
  </Button>)}
</View>

export default CityPicker
