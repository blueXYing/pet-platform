import { Button, Image, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useEffect, useState, type CSSProperties } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { RealProfileRepository } from '../../api/repositories'
import { RealSettingsRepository } from '../../settings/repository'
import backIcon from '../../assets/profile/back.png'
import './index.css'

// 设置枢纽页（契约 66 / PRD 5.1.29；设计稿 131:18372，登记表偏差小节）。
// 页面为纯枢纽：手机号脱敏展示（编辑资料仓库，只读）；通知设置/收货地址/隐私政策/用户协议/
// 注销账号为入口行；清除缓存为端侧本地行为（wx.getStorageInfoSync 统计 + 仅清非凭据键，
// 失败提示且缓存大小保持不变——PRD 5.1.29 异常口径）；关于展示版本常量；退出登录复用
// ConsumerApi.logout 既有语义。偏差登记（66号 §6）：修改密码/支付设置行不渲染；
// 推送通知/消息提醒两开关行改为「通知设置」入口行（承载既有通知偏好页）；
// 注销账号为 PRD 明确要求新增（原稿无此元素）；底部 tab 为原稿残留不还原。
export const APP_VERSION = 'v1.0.0'
const SETTINGS_PRIVACY_URL = '/consumer/pages/settings/privacy-policy'
const SETTINGS_AGREEMENT_URL = '/consumer/pages/settings/user-agreement'
const SETTINGS_DELETION_URL = '/consumer/pages/settings/deletion'
const NOTIFICATION_PREFERENCES_URL = '/consumer/pages/notification-preferences/index'
const ADDRESS_BOOK_URL = '/consumer/pages/address/index'
const MINE_TAB_URL = '/consumer/pages/mine/index'

// 凭据保留键前缀：清除缓存不触碰会话/待写幂等槽/登出回执（ConsumerApi 既有语义）。
const CREDENTIAL_KEY_HINTS = ['session', 'write', 'logout', 'pet.c.phone-guide']

type CacheStat = { sizeKb: number; keys: string[] }

function readCacheStat(): CacheStat {
  try {
    const info = Taro.getStorageInfoSync()
    return { sizeKb: info.currentSize, keys: info.keys }
  } catch {
    return { sizeKb: 0, keys: [] }
  }
}

export default function SettingsPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = {
    '--st-top': `${platformInfo.statusBarHeight || 0}px`,
    '--st-unit': `${unit}px`,
  } as CSSProperties
  // 手机号脱敏展示（既有 /c/profile 只读投影；失败软降级为「未绑定」占位，不阻塞枢纽页）。
  const [phoneMasked, setPhoneMasked] = useState(preview ? '138****5678' : '…')
  // v0.2 冷静期提示（"登录后提示可撤销"的枢纽落点）：注销申请中时入口行展示「申请中」徽标。
  const [deletionPending, setDeletionPending] = useState(preview)
  useEffect(() => {
    if (preview) return
    let alive = true
    void new RealProfileRepository(consumerApi).load()
      .then(profile => { if (alive) setPhoneMasked(profile.phoneMasked || '未绑定') })
      .catch(() => { if (alive) setPhoneMasked('未绑定') })
    void new RealSettingsRepository(consumerApi).deletionStatus()
      .then(status => { if (alive) setDeletionPending(status.status === 'PENDING') })
      .catch(() => { if (alive) setDeletionPending(false) })
    return () => { alive = false }
  }, [preview])
  const [cache, setCache] = useState<CacheStat>(() => readCacheStat())
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)

  function open(url: string) {
    Taro.navigateTo({ url }).catch(() => setNotice('页面打开失败，请重试'))
  }

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: MINE_TAB_URL })
  }

  function clearCache() {
    const stat = readCacheStat()
    try {
      const removable = stat.keys.filter(key => !CREDENTIAL_KEY_HINTS.some(hint => key.includes(hint)))
      removable.forEach(key => Taro.removeStorageSync(key))
      const after = readCacheStat()
      setCache(after)
      setNotice(`已清除 ${removable.length} 项本地缓存`)
    } catch {
      // PRD 5.1.29：清除失败提示且缓存大小保持不变。
      setCache(stat)
      setNotice('清除失败，请稍后重试')
    }
  }

  async function logout() {
    if (busy) return
    setBusy(true)
    try {
      await consumerApi.logout()
      await Taro.switchTab({ url: MINE_TAB_URL })
    } catch {
      setNotice('退出登录失败，请稍后重试')
    } finally {
      setBusy(false)
    }
  }

  return <View className='cpn-page st-page' style={style}>
    <View className='st-status-area' />
    <View className='st-header'>
      <Button id='st-back' className='st-back' hoverClass='none' ariaLabel='返回' onClick={() => void back()}>
        <Image src={backIcon} className='st-back-icon' />
      </Button>
      <Text className='st-title'>设置</Text>
    </View>
    <View className='st-body'>
      <Text className='st-group-title'>账号与安全</Text>
      <View className='st-card'>
        <View className='st-row' id='st-row-phone'>
          <Text className='st-row-label'>手机号</Text>
          <Text className='st-row-value'>{phoneMasked}</Text>
        </View>
      </View>

      <Text className='st-group-title'>通知设置</Text>
      <View className='st-card'>
        <Button id='st-row-notification' className='st-row st-row-btn' hoverClass='none'
          onClick={() => open(NOTIFICATION_PREFERENCES_URL)}>
          <Text className='st-row-label'>推送通知与消息提醒</Text>
          <View className='st-chevron' />
        </Button>
      </View>

      <Text className='st-group-title'>通用</Text>
      <View className='st-card'>
        <Button id='st-row-address' className='st-row st-row-btn' hoverClass='none'
          onClick={() => open(ADDRESS_BOOK_URL)}>
          <Text className='st-row-label'>收货地址</Text>
          <View className='st-chevron' />
        </Button>
        <Button id='st-row-cache' className='st-row st-row-btn' hoverClass='none' onClick={clearCache}>
          <Text className='st-row-label'>清除缓存</Text>
          <Text className='st-row-value'>{cache.sizeKb}KB</Text>
          <View className='st-chevron' />
        </Button>
        <Button id='st-row-privacy' className='st-row st-row-btn' hoverClass='none'
          onClick={() => open(SETTINGS_PRIVACY_URL)}>
          <Text className='st-row-label'>隐私政策</Text>
          <View className='st-chevron' />
        </Button>
        <Button id='st-row-agreement' className='st-row st-row-btn' hoverClass='none'
          onClick={() => open(SETTINGS_AGREEMENT_URL)}>
          <Text className='st-row-label'>用户协议</Text>
          <View className='st-chevron' />
        </Button>
        <View className='st-row' id='st-row-about'>
          <Text className='st-row-label'>关于宠灵工</Text>
          <Text className='st-row-value'>{APP_VERSION}</Text>
        </View>
      </View>

      <Button id='st-logout' className='st-logout' hoverClass='none' disabled={busy} onClick={() => void logout()}>
        退出登录
      </Button>
      <Button id='st-deletion' className='st-deletion' hoverClass='none' onClick={() => open(SETTINGS_DELETION_URL)}>
        注销账号{deletionPending ? '（申请中，点入可撤销）' : ''}
      </Button>
      {notice !== '' && <Text id='st-notice' className='st-notice'>{notice}</Text>}
    </View>
  </View>
}
