import { Button, Switch, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { ConsumerPageLayout } from '../../components/page-layout'
import {
  NotificationPreferencesController, PreviewPreferenceDeps, isPreferenceScenario,
  mandatoryNotice, preferenceKeys, preferenceMeta,
} from '../../notifications/preferences'
import { realPreferenceDeps } from '../../notifications/preferences-runtime'
import './index.scss'

// 通知偏好设置（NTF 偏好切片）。设计源登记表无该页原稿（登记缺稿），沿现行规范按消息页
// 的应用页语言实现，不做 1:1 还原或 VIS 走查。偏好项严格等于 SQL06 §11
// notification_preference 的两个开关（SSOT §16.4）：普通互动提醒、微信外部推送；
// 订单/退款/核销/售后/审核站内消息不可关闭（服务端事实，本页只作说明呈现）。
// 真实模式读当前偏好 → 本地草稿逐项开关 → 保存（requestId 幂等由 ConsumerApi.write 保障）；
// 读取/保存失败均失败关闭（面板不可用，重试引导）；preview=1 走本地夹具。
export default function NotificationPreferencesPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const [controller] = useState(() => new NotificationPreferencesController(
    preview
      ? new PreviewPreferenceDeps(isPreferenceScenario(route.params.scenario) ? route.params.scenario : 'normal')
      : realPreferenceDeps()))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => { void controller.load() })
  useEffect(() => () => controller.dispose(), [controller])
  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: '/consumer/pages/mine/index' })
  }
  const [windowInfo] = useState(() => Taro.getWindowInfo())
  const style = { '--ntf-pref-top': `${windowInfo.statusBarHeight || 0}px` } as CSSProperties
  return <ConsumerPageLayout page='profileEdit' unit={1} className='ntf-pref-page ntf-pref-subpage' style={style}>
    <View className='ntf-pref-design' data-state={state.status}>
      <View className='ntf-pref-header'>
        <Button id='ntf-pref-back' ariaLabel='返回' onClick={() => void back()}>返回</Button>
        <Text>通知偏好</Text>
      </View>
      <View className='ntf-pref-body'>
        {state.status === 'loading' && <View className='ntf-pref-line' role='status'>正在读取通知偏好…</View>}
        {(state.status === 'error' || state.status === 'unauthorized') && <View className='ntf-pref-card'>
          <Text>{state.notice || '通知偏好读取失败，请稍后重试。'}</Text>
          {state.status === 'unauthorized'
            ? <Button id='ntf-pref-login' onClick={() => void Taro.switchTab({ url: '/consumer/pages/mine/index' })}>去登录</Button>
            : <Button id='ntf-pref-retry' onClick={() => void controller.load()}>重试</Button>}
        </View>}
        {state.status === 'ready' && state.draft !== null && <>
          {preferenceKeys.map(key => <View key={key} className='ntf-pref-row' id={`ntf-pref-${key}`}>
            <View className='ntf-pref-row-main'>
              <Text className='ntf-pref-row-title'>{preferenceMeta[key].title}</Text>
              <Text className='ntf-pref-row-desc'>{preferenceMeta[key].description}</Text>
            </View>
            <Switch
              checked={state.draft![key]}
              disabled={state.saving}
              ariaLabel={preferenceMeta[key].title}
              onChange={() => controller.toggle(key)} />
          </View>)}
          <View className='ntf-pref-card'>
            <Text className='ntf-pref-note'>{mandatoryNotice}</Text>
          </View>
          <View className='ntf-pref-actions'>
            <Button id='ntf-pref-save' disabled={state.saving || !controller.dirty()} onClick={() => void controller.save()}>
              {state.saving ? '正在保存…' : '保存'}
            </Button>
            <Button id='ntf-pref-reset' disabled={state.saving || !controller.dirty()} onClick={() => controller.reset()}>放弃修改</Button>
          </View>
          {state.saved?.updatedAt && <Text className='ntf-pref-saved-at'>最近保存时间：{state.saved.updatedAt}</Text>}
          {preview && <Text className='ntf-pref-preview-note'>预览模式：本地样例数据，仅用于设计验收，不发起真实请求。</Text>}
          {state.notice && <Text id='ntf-pref-notice' className='ntf-pref-notice'>{state.notice}</Text>}
        </>}
      </View>
    </View>
  </ConsumerPageLayout>
}
