import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties, type PropsWithChildren } from 'react'
import { afterSaleClient } from '../../../shared/aftersale-runtime'
import { consumerStorage } from '../../../shared/consumer-runtime'
import { privateUploadFiles } from '../../../shared/private-upload-platform'
import { merchantAfterSaleDeps } from '../../aftersale/repository'
import { MerchantAfterSaleController } from '../../aftersale/controller'
import navBack from './assets/nav-back@3x.png'
import './page.css'

export function useMerchantAfterSale(caseId?: string, enabled = true) {
  const [client] = useState(() => afterSaleClient('merchant'))
  const [controller] = useState(() => new MerchantAfterSaleController(merchantAfterSaleDeps(client, consumerStorage, privateUploadFiles()), caseId))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => { if (enabled) void controller.load() })
  useDidHide(() => controller.hide())
  useEffect(() => () => { controller.dispose(); client.dispose() }, [client, controller])
  const [info] = useState(() => Taro.getWindowInfo())
  const style = { '--mas-unit': `${info.windowWidth / 402}px`, '--mas-status-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  return { controller, state, style }
}
export function MerchantAfterSaleShell({ children, title, style, onBack }: PropsWithChildren<{ title: string; style: CSSProperties; onBack?: () => void }>) {
  async function back() {
    if (onBack) { onBack(); return }
    try {
      if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
      else await Taro.redirectTo({ url: '/merchant/pages/workspace/index' })
    } catch { await Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) }
  }
  return <View className='mas-page' style={style}>
    <View className='mas-status-area' />
    <View className='mas-header'>
      <Button id='mas-back' className='mas-back' ariaLabel='返回' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mas-title'>{title}</Text>
    </View>
    {children}
  </View>
}
export function MerchantAfterSaleState({ controller, text, status }: { controller: MerchantAfterSaleController; text: string; status: string }) {
  return <View className='mas-state' role='status'>
    <Text>{status === 'loading' || status === 'idle' ? '正在读取售后工单…' : text}</Text>
    {status === 'error' && <Button id='mas-reload' className='mas-primary' onClick={() => void controller.load()}>重新加载</Button>}
    {(status === 'entry' || status === 'denied') && <Button className='mas-primary' onClick={() => Taro.redirectTo({ url: '/merchant/pages/workspace/index' })}>去商家工作台</Button>}
  </View>
}
