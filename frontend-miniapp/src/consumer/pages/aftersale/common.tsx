import { Button, Image, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect, useState, type CSSProperties, type PropsWithChildren } from 'react'
import { ConsumerPageLayout } from '../../components/page-layout'
import { switchConsumerTab } from '../../components/navigation/switch'
import backIcon from './assets/back.svg'
import addIcon from './assets/add.svg'
import './page.scss'

export function AfterSaleFrame({ title, children }: PropsWithChildren<{ title: string }>) {
  const [info, setInfo] = useState(() => Taro.getWindowInfo())
  useEffect(() => { const resize = () => setInfo(Taro.getWindowInfo()); Taro.onWindowResize(resize); return () => Taro.offWindowResize(resize) }, [])
  const unit = info.windowWidth / 402
  const style = { '--afs-unit': `${unit}px`, '--afs-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/consumer/pages/shell/index' })
  }
  return <ConsumerPageLayout page='profileEdit' unit={unit} className='afs-page' style={style} navigation={{ idPrefix: 'afs', onSelect: key => switchConsumerTab(key) }}>
    <View className='afs-header'><Button id='afs-back' className='afs-back' ariaLabel='返回' onClick={() => void back()}><Image src={backIcon} className='afs-back-icon' /></Button><Text>{title}</Text></View>
    <View className='afs-body'>{children}</View>
  </ConsumerPageLayout>
}
export function Card({ title, children }: PropsWithChildren<{ title?: string }>) {
  return <View className='afs-card'>{title && <Text className='afs-heading'>{title}</Text>}{children}</View>
}
export function Notice({ text }: { text: string }) { return text ? <View className='afs-notice' role='status'>{text}</View> : null }
export function UploadButton({ disabled, onClick }: { disabled: boolean; onClick(): void }) {
  return <Button id='afs-add-image' className='afs-upload-button' disabled={disabled} onClick={onClick}><View className='afs-add-icon-slot'><Image src={addIcon} className='afs-add-icon' /></View><Text>添加图片</Text></Button>
}
export function EvidenceDraft({ assets, disabled, onAdd, onRemove }: { assets: readonly { assetId: string }[]; disabled: boolean; onAdd(): void; onRemove(id: string): void }) {
  return <Card title='上传图片说明'><Text className='afs-hint'>请上传问题照片或凭证，最多6张，支持JPG / PNG，单张不超过10MiB</Text>
    <View className='afs-image-draft'>{assets.map((asset, index) => <View className='afs-uploaded' key={asset.assetId}><Text>图片 {index + 1}</Text><Text className='afs-hint'>已上传</Text><Button disabled={disabled} ariaLabel={`移除图片${index + 1}`} onClick={() => onRemove(asset.assetId)}>移除</Button></View>)}
      {assets.length < 6 && <UploadButton disabled={disabled} onClick={onAdd} />}</View>
  </Card>
}
export function formatTime(value: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '—'
  // The WeChat device timezone is not assumed; business display uses Asia/Shanghai UTC+8.
  return new Date(date.getTime() + 8 * 3600000).toISOString().slice(0, 19).replace('T', ' ') + '（北京时间）'
}
