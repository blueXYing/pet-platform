import { Button, Image, Input, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { consumerApi, consumerStorage } from '../../../shared/consumer-runtime'
import { merchantStorefrontDeps } from '../../storefront/repository'
import { ShopInfoController } from '../../storefront/shop-info-controller'
import { businessStatusLabel, operatingLabel } from '../../storefront/model'
import navBack from '../aftersale/assets/nav-back@3x.png'
import './page.css'

/** Contract 27 §6.4 M face: the store coordinates come from the merchant workspace handoff. */
function useShopInfo() {
  const [controller] = useState(() => {
    const context = consumerApi.scope.capture().context
    if (context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      throw new Error('MERCHANT_ENTRY_REQUIRED')
    }
    return new ShopInfoController(
      merchantStorefrontDeps(consumerApi, consumerStorage),
      { merchantId: context.merchantId, storeId: context.storeId })
  })
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => { void controller.load() })
  useDidHide(() => controller.hide())
  useEffect(() => () => controller.dispose(), [controller])
  const [info] = useState(() => Taro.getWindowInfo())
  const style = { '--msi-unit': `${info.windowWidth / 402}px`, '--msi-status-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  return { controller, state, style }
}

async function backToWorkbench() {
  try {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/merchant/pages/workspace/index' })
  } catch { await Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) }
}

async function chooseImage(controller: ShopInfoController, mode: 'photo' | 'avatar') {
  try {
    const picked = await Taro.chooseImage({ count: 1, sizeType: ['compressed'], sourceType: ['album', 'camera'] })
    const path = picked.tempFilePaths?.[0]
    if (!path) return
    if (mode === 'avatar') await controller.setAvatar(path)
    else await controller.addPhoto(path)
  } catch { /* picker cancellation or upload failure already surfaced by the controller */ }
}

// 店铺信息 M 面（27号 §6.4 + 2026-10-19 扩展，帧 25:20684）：审核基础信息只读区 +
// 店铺事实（名称/地址/电话，扩展后可维护、不触发复审、落审计）+ 店铺基础信息表单 +
// 营业状态开关（营业中/休息中）+ 店铺照片/头像页内上传（STORE_PHOTO 私有资产管线）。
// 资质类字段（营业执照/身份证件/商家类型/城市/定位）不开放变更（CCR 口径）。
export default function MerchantShopInfoPage() {
  const { controller, state, style } = useShopInfo()
  const view = state.view
  const facts = view?.readonlyFacts
  const profile = view?.profile
  return <View className='msi-page' style={style}>
    <View className='msi-status-area' />
    <View className='msi-header'>
      <Button className='msi-back' ariaLabel='返回' onClick={() => void backToWorkbench()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='msi-title'>店铺信息</Text>
    </View>
    {state.phase !== 'ready' ? <View className='msi-state' role='status'>
      <Text>{state.phase === 'loading' || state.phase === 'idle' ? '正在读取店铺资料…' : state.notice || (state.phase === 'entry' ? '仅商家 OWNER 可查看店铺资料' : '页面暂时不可用')}</Text>
      {state.phase === 'error' && <Button className='msi-primary' onClick={() => void controller.load()}>重新加载</Button>}
      {state.phase === 'entry' && <Button className='msi-primary' onClick={() => void Taro.redirectTo({ url: '/merchant/pages/workspace/index' })}>去商家工作台</Button>}
    </View> : <>
      {state.frozen && <View className='msi-alert'>商家或门店受限，当前仅可查看店铺资料。</View>}
      {facts && <View className='msi-card'>
        <Text className='msi-card-title'>资质信息（入驻审核通过后自动回填，不可在此修改）</Text>
        <View className='msi-line'><Text className='msi-label'>城市</Text><Text className='msi-value'>{facts.cityCode ?? '--'}</Text></View>
        <View className='msi-line'><Text className='msi-label'>门店定位</Text><Text className='msi-value'>{facts.longitude && facts.latitude ? `${facts.longitude}, ${facts.latitude}` : '--'}</Text></View>
        <View className='msi-line'><Text className='msi-label'>平台状态</Text><Text className='msi-value'>{operatingLabel[facts.operatingStatus] ?? facts.operatingStatus}{facts.merchantStatus !== 'ACTIVE' || facts.storeStatus !== 'ACTIVE' ? '（平台态优先，仅可查看）' : ''}</Text></View>
      </View>}
      <View className='msi-card msi-form'>
        <Text className='msi-card-title'>店铺信息（可自行维护，保存后生效；不触发平台复审）</Text>
        <View className='msi-field'>
          <Text className='msi-label'>营业状态</Text>
          <View className='msi-switch-row' role='radiogroup'>
            <Button id='msi-status-open'
              className={state.draft.businessStatus !== 'RESTING' ? 'msi-switch msi-switch-on' : 'msi-switch'}
              disabled={state.frozen}
              onClick={() => controller.toggleBusinessStatus(false)}>{businessStatusLabel.OPEN}</Button>
            <Button id='msi-status-resting'
              className={state.draft.businessStatus === 'RESTING' ? 'msi-switch msi-switch-on' : 'msi-switch'}
              disabled={state.frozen}
              onClick={() => controller.toggleBusinessStatus(true)}>{businessStatusLabel.RESTING}</Button>
          </View>
          <Text className='msi-hint'>休息中仅作展示态（C 端展示属后续切片）；平台下线/冻结状态仍优先生效。</Text>
        </View>
        <View className='msi-field'>
          <Text className='msi-label'>店铺头像</Text>
          <View className='msi-avatar-row'>
            {state.draft.avatarAssetId
              ? <Button className='msi-avatar-clear' onClick={() => controller.clearAvatar()}>移除头像（保存后生效）</Button>
              : <Button id='msi-avatar-add' className='msi-photo-add' disabled={state.uploadingPhoto || state.frozen}
                onClick={() => void chooseImage(controller, 'avatar')}>{state.uploadingPhoto ? '上传中…' : '上传头像'}</Button>}
          </View>
          <Text className='msi-hint'>JPEG/PNG，随保存一并提交；与店铺照片同一私有素材管线（STORE_PHOTO）。</Text>
        </View>
        <View className='msi-field'>
          <Text className='msi-label'>店铺名称</Text>
          <Input className='msi-input' value={state.draft.storeName} maxlength={128}
            placeholder='1-128 字'
            onInput={event => controller.editDraft({ storeName: String(event.detail.value) })} />
        </View>
        <View className='msi-field'>
          <Text className='msi-label'>门店地址</Text>
          <Input className='msi-input' value={state.draft.address} maxlength={255}
            placeholder='1-255 字（定位/城市不在此修改）'
            onInput={event => controller.editDraft({ address: String(event.detail.value) })} />
        </View>
        <View className='msi-field'>
          <Text className='msi-label'>联系电话</Text>
          <Input className='msi-input' type='number' value={state.draft.phone} maxlength={32}
            placeholder='5-32 位数字，留空保持不变'
            onInput={event => controller.editDraft({ phone: String(event.detail.value) })} />
        </View>
        <View className='msi-field'>
          <Text className='msi-label'>店铺照片</Text>
          <View className='msi-photo-row'>
            {state.draft.photoAssetIds.map(id => <View key={id} className='msi-photo-item'>
              <Text className='msi-photo-id'>素材 {id.slice(-6)}</Text>
              <Button className='msi-photo-remove' onClick={() => controller.removePhoto(id)}>移除</Button>
            </View>)}
            <Button id='msi-photo-add' className='msi-photo-add' disabled={state.uploadingPhoto || state.frozen || state.draft.photoAssetIds.length >= 6}
              onClick={() => void chooseImage(controller, 'photo')}>{state.uploadingPhoto ? '上传中…' : '添加照片'}</Button>
          </View>
          <Text className='msi-hint'>最多 6 张（已设 {state.draft.photoAssetIds.length} 张）；JPEG/PNG，页内上传走 STORE_PHOTO 私有资产管线（27号 §6.4）。</Text>
        </View>
        <View className='msi-field'>
          <Text className='msi-label'>店铺简介</Text>
          <Textarea className='msi-textarea' value={state.draft.intro} maxlength={500}
            placeholder='0-500 字，介绍门店经营特色'
            onInput={event => controller.editDraft({ intro: String(event.detail.value) })} />
          <Text className='msi-count'>{Array.from(state.draft.intro).length} / 500</Text>
        </View>
        <View className='msi-field'>
          <Text className='msi-label'>店铺公告</Text>
          <Textarea className='msi-textarea' value={state.draft.announcement} maxlength={200}
            placeholder='0-200 字，前台可见的重要通知'
            onInput={event => controller.editDraft({ announcement: String(event.detail.value) })} />
          <Text className='msi-count'>{Array.from(state.draft.announcement).length} / 200</Text>
        </View>
        {profile && profile.businessHours.length > 0 && <View className='msi-field'>
          <Text className='msi-label'>营业时间</Text>
          {profile.businessHours.map((hour, index) => <Text key={index} className='msi-value'>
            每{hour.days.map(day => '周' + '一二三四五六日'[day - 1]).join('、')} {hour.start} - {hour.end}
          </Text>)}
          <Text className='msi-hint'>信息性维护面，不参与排期计算（27号 §6.4 偏差）。</Text>
        </View>}
        <View className='msi-field'>
          <Text className='msi-label'>促销等级（1-9 折，0 为未设置）</Text>
          <Input className='msi-input' type='number' value={String(state.draft.promoLevel)}
            onInput={event => {
              const raw = String(event.detail.value)
              const value = /^[0-9]$/.test(raw) ? Number(raw) : 0
              controller.editDraft({ promoLevel: Math.min(9, value) })
            }} />
        </View>
        <View className='msi-field'>
          <Text className='msi-label'>促销文案标题</Text>
          <Input className='msi-input' value={state.draft.promoTitle} maxlength={100}
            placeholder='0-100 字，当前活动标题'
            onInput={event => controller.editDraft({ promoTitle: String(event.detail.value) })} />
        </View>
        <View className='msi-field'>
          <Text className='msi-label'>促销文案说明</Text>
          <Input className='msi-input' value={state.draft.promoText} maxlength={100}
            placeholder='0-100 字，当前活动说明'
            onInput={event => controller.editDraft({ promoText: String(event.detail.value) })} />
        </View>
      </View>
      {state.notice && <View className='msi-notice' role='alert'><Text>{state.notice}</Text></View>}
      {state.savedVersion && <View className='msi-receipt' role='status'>
        <Text>店铺资料已保存（版本 {state.savedVersion}）。</Text>
      </View>}
      <Button id='msi-save' className='msi-primary msi-save' disabled={state.saving || state.frozen}
        onClick={() => void controller.save()}>{state.saving ? '保存中…' : '保存修改'}</Button>
      <View className='msi-bottom-space' />
    </>}
  </View>
}
