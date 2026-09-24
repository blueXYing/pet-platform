import { Button, Image, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'
import type { ServiceCoverView } from '../../service/model'
import { usableCoverUrl } from '../../service/cover'

export function ServiceCover({ cover, onRefresh }: { cover: ServiceCoverView | null; onRefresh: () => void }) {
  const [now, setNow] = useState(Date.now)
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  useEffect(() => {
    setNow(Date.now()); setFailedUrl(null)
    if (!cover) return
    const remaining = Date.parse(cover.coverUrlExpiresAt) - Date.now()
    if (remaining <= 0 || !Number.isFinite(remaining)) return
    const timer = setTimeout(() => setNow(Date.now()), Math.min(remaining + 1, 2147483647))
    return () => clearTimeout(timer)
  }, [cover])
  const url = usableCoverUrl(cover, now)
  return url && failedUrl !== url
    ? <Image id='svcd-service-cover' className='svc-abs svc-banner' src={url} mode='aspectFill' onError={() => setFailedUrl(url)} />
    : <View className='svc-abs svc-banner svc-cover-unavailable'>
      <Text>{cover ? '封面暂不可用' : '暂无服务封面'}</Text>
      {cover && <Button id='svcd-cover-refresh' onClick={onRefresh}>重新加载封面</Button>}
    </View>
}
