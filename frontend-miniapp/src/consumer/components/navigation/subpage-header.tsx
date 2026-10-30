import { Button, Image, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useState, type CSSProperties, type PropsWithChildren, type ReactNode } from 'react'
import subpageBack from './assets/subpage-back.svg'
import './subpage-header.css'

/**
 * 二级/三级页统一头部（2026-10-28 用户裁决：标题左对齐）。
 *
 * 视觉令牌：奶油底 #fff6e5；返回左 18、标题左 50（16/24 Bold #3c3c3c）、右侧操作右 18；
 * 返回与标题以标题行盒中心（design y=34）对齐；筛选行作为 bottom 插槽（design y=66）。
 * fixed 默认固定；reference 用于 Figma 一比一绝对画布（无状态栏偏移）。
 */
type Props = PropsWithChildren<{
  title: string
  backId?: string
  titleId?: string
  backAriaLabel?: string
  backDisabled?: boolean
  onBack?: () => void
  right?: ReactNode
  bottom?: ReactNode
  fixed?: boolean
  reference?: boolean
  /** false 用于已把状态栏画进设计画布的 Figma 页面（如商家详情）。 */
  statusBar?: boolean
  background?: 'cream' | 'transparent'
  /** 头部内容高度（不含状态栏），默认 113；带 bottom 筛选行时沿用该高度。 */
  height?: number
  className?: string
}>

function metrics(reference: boolean, statusBar: boolean) {
  const info = Taro.getWindowInfo()
  const unit = info.windowWidth / 402
  let menu: { left: number } | null = null
  try { menu = Taro.getMenuButtonBoundingClientRect() } catch { menu = null }
  // 右侧操作避让微信胶囊：与首页头部同一口径（胶囊左侧再留 10 设计px）。
  const right = menu && menu.left > 0 ? info.windowWidth - menu.left + 10 * unit : 18 * unit
  return {
    unit,
    top: reference || !statusBar ? 0 : (info.statusBarHeight || 0),
    right,
  }
}

export function SubpageHeader({
  title, backId, titleId, backAriaLabel = '返回', backDisabled = false, onBack, right, bottom,
  fixed = true, reference = false, statusBar = true, background = 'cream', height = 113, className,
}: Props) {
  const [{ unit, top, right: rightInset }] = useState(() => metrics(reference, statusBar))
  const style = {
    '--sph-unit': `${unit}px`,
    '--sph-top': `${top}px`,
    '--sph-height': `${height}`,
    '--sph-right': `${rightInset}px`,
  } as CSSProperties
  function goBack() {
    if (onBack) { onBack(); return }
    Taro.navigateBack().catch(() => Taro.switchTab({ url: '/consumer/pages/home/index' }))
  }
  const positionClass = reference ? ' sph-reference' : (fixed ? ' sph-fixed' : ' sph-static')
  const backgroundClass = background === 'cream' ? ' sph-cream' : ' sph-transparent'
  return <View className={`sph-root${positionClass}${backgroundClass}${className ? ` ${className}` : ''}`} style={style}>
    <Button id={backId} className='sph-back' ariaLabel={backAriaLabel} hoverClass='none' disabled={backDisabled} onClick={goBack}>
      <Image className='sph-back-icon' src={subpageBack} mode='aspectFit' />
    </Button>
    <Text id={titleId} className='sph-title'>{title}</Text>
    {right ? <View className='sph-right'>{right}</View> : null}
    {bottom ? <View className='sph-bottom'>{bottom}</View> : null}
  </View>
}

/** 固定头部的等高占位；放在页面内容最前，避免内容从头部下方穿出。 */
export function SubpageHeaderSpacer({ height = 113, reference = false, statusBar = true }: {
  height?: number
  reference?: boolean
  statusBar?: boolean
}) {
  const [{ unit, top }] = useState(() => metrics(reference, statusBar))
  return <View className='sph-spacer' style={{ height: `calc(${top}px + ${unit * height}px)` }} />
}
