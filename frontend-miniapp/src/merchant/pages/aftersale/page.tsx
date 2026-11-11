import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties, type PropsWithChildren } from 'react'
import { afterSaleClient } from '../../../shared/aftersale-runtime'
import { consumerSession, consumerStorage } from '../../../shared/consumer-runtime'
import { privateUploadFiles } from '../../../shared/private-upload-platform'
import { merchantAfterSaleDeps } from '../../aftersale/repository'
import { MerchantAfterSaleController } from '../../aftersale/controller'
import { caseCustomerInitial, caseCustomerName, type CaseLabelView } from '../../aftersale/model'
import { MerchantTabBar } from '../../components/tab-bar'
import navBack from './assets/nav-back@3x.png'
import './page.css'

export function useMerchantAfterSale(caseId?: string, enabled = true) {
  const [client] = useState(() => afterSaleClient('merchant'))
  const [controller] = useState(() => new MerchantAfterSaleController(merchantAfterSaleDeps(client, consumerStorage, privateUploadFiles()), caseId))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => {
    if (!enabled) return
    void (async () => {
      // Deep links and restored simulator pages can reach this hook before the C session
      // exists; the list/detail would otherwise fail closed with a 401-style notice.
      try { await consumerSession.launch() } catch { /* controller.load() renders the error state */ }
      await controller.load()
    })()
  })
  useDidHide(() => controller.hide())
  useEffect(() => () => { controller.dispose(); client.dispose() }, [client, controller])
  const [info] = useState(() => Taro.getWindowInfo())
  const style = { '--mas-unit': `${info.windowWidth / 402}px`, '--mas-status-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  return { controller, state, style }
}
export function MerchantAfterSaleShell({ children, title, style, onBack }: PropsWithChildren<{ title: string; style: CSSProperties; onBack?: () => void }>) {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  async function back() {
    if (onBack) { onBack(); return }
    try {
      // Detail → list keeps the stack; returning to the workbench rebuilds it because Taro
      // disposes the hidden workbench React tree (navigateBack leaves stale placeholders).
      if (Taro.getCurrentPages().length > 2) await Taro.navigateBack()
      else await Taro.reLaunch({ url: '/merchant/pages/workspace/index' })
    } catch { await Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) }
  }
  return <View className='mas-page' style={style}>
    <View className='mas-status-area' />
    <View className='mas-header'>
      <Button id='mas-back' className='mas-back' ariaLabel='返回' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mas-title'>{title}</Text>
    </View>
    {children}
    <MerchantTabBar active='orders' unit={unit} />
  </View>
}
export function MerchantAfterSaleState({ controller, text, status }: { controller: MerchantAfterSaleController; text: string; status: string }) {
  return <View className='mas-state' role='status'>
    <Text>{status === 'loading' || status === 'idle' ? '正在读取售后工单…' : text}</Text>
    {status === 'error' && <Button id='mas-reload' className='mas-primary' onClick={() => void controller.load()}>重新加载</Button>}
    {(status === 'entry' || status === 'denied') && <Button className='mas-primary' onClick={() => Taro.reLaunch({ url: '/merchant/pages/workspace/index' })}>去商家工作台</Button>}
  </View>
}
/** CCR-M-AFTERSALE-VIEW-001 客户块（列表卡/详情首卡共用）：首字圆 + 昵称（null→「顾客」占位）+ 可选副行。
 *  本 CCR 无头像字段，恒为首字圆不渲染图片；不显示手机号。售后无专属稿（登记表 §4.6），沿商家订单卡家族语言。 */
export function MerchantAfterSaleCustomer({ view, line, className }: { view: CaseLabelView; line?: string | null; className?: string }) {
  return <View className={className ? `mas-customer ${className}` : 'mas-customer'}>
    <View className='mas-customer-avatar'><Text>{caseCustomerInitial(view)}</Text></View>
    <View className='mas-customer-who'>
      <Text className='mas-customer-name'>{caseCustomerName(view)}</Text>
      {line ? <Text className='mas-customer-service'>{line}</Text> : null}
    </View>
  </View>
}
