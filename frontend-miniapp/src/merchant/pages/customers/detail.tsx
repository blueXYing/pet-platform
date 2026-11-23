import { Button, Image, ScrollView, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useRouter } from '@tarojs/taro'
import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { consumerApi, consumerSession, consumerStorage } from '../../../shared/consumer-runtime'
import { merchantStorefrontDeps } from '../../storefront/repository'
import { CustomerDetailController } from '../../storefront/read-controllers'
import { lastVisitLabel } from '../../storefront/model'
import { previewStatsDeps } from '../../workbench/preview'
import { MerchantTabBar } from '../../components/tab-bar'
import navBack from '../aftersale/assets/nav-back@3x.png'
import './page.css'

/** Contract 69 detail face: the route carries the derived customer's userId only. */
function useCustomerDetail(preview = false) {
  // ?preview=1：先注入商户坐标，再用本地客户详情样本；真实路径不变。
  useState(() => {
    if (!preview) return false
    const current = consumerApi.scope.current
    if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      consumerApi.scope.replace({
        userId: '9007199254740993', workspace: 'merchant',
        merchantId: '9007199254740995', storeId: '9007199254740996',
      })
    }
    return true
  })
  const [controller] = useState(() => {
    const context = consumerApi.scope.current
    if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      throw new Error('MERCHANT_ENTRY_REQUIRED')
    }
    const userId = Taro.getCurrentInstance().router?.params?.userId ?? ''
    if (!/^[1-9][0-9]{0,18}$/.test(userId)) throw new Error('INVALID_CUSTOMER_ID')
    return new CustomerDetailController(
      preview ? previewStatsDeps() : merchantStorefrontDeps(consumerApi, consumerStorage),
      { merchantId: context.merchantId, storeId: context.storeId }, userId)
  })
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => {
    void (async () => {
      try { await consumerSession.launch() } catch { /* controller.load() renders the error state */ }
      await controller.load()
    })()
  })
  useDidHide(() => controller.hide())
  useEffect(() => () => controller.dispose(), [controller])
  const [info] = useState(() => Taro.getWindowInfo())
  const style = { '--mcu-unit': `${info.windowWidth / 402}px`, '--mcu-status-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  return { controller, state, style }
}

async function backToList() {
  try {
    // P-03 追缉：商家域返回不用 navigateBack（隐藏页树可能已被 dispose，回来是死页）。
    await Taro.reLaunch({ url: '/merchant/pages/customers/index' })
  } catch { await Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) }
}

// 客户详情 M 面（69号 + 2026-10-19 备注扩展）：一比一还原帧 23:13478（三卡布局，mcud- 家族
// 类独立于列表页）。产品口径覆盖设计稿的两处：手机号脱敏（69号硬规则）；设计稿的性别/年龄/
// 体重/疫苗/健康/驱虫区块不实现——宠物快照契约只到名字/品种级（CCR 隐私口径，偏差沿用已登记项）。
// 订单/评价只读跳转入口随本次还原移除（底部 tab 已覆盖订单页；评价页入口待后续裁决是否回归）。
export default function MerchantCustomerDetailPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const { controller, state, style } = useCustomerDetail(preview)
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const detail = state.detail
  const customer = detail?.customer
  // 2026-11-18 用户反馈：保存成功零提示。noteSaved 计数递增即弹轻提示（控制器保持无 UI 依赖）。
  useEffect(() => {
    if (state.noteSaved > 0) void Taro.showToast({ title: '保存成功', icon: 'success', duration: 1500 })
  }, [state.noteSaved])
  return <View className='mcu-page' style={style}>
    <View className='mcu-status-area' />
    <View className='mcu-header'>
      <Button className='mcu-back' ariaLabel='返回' onClick={() => void backToList()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mcu-title'>客户详情</Text>
    </View>
    {state.phase !== 'ready' || !customer ? <View className='mcu-state' role='status'>
      <Text>{state.phase === 'loading' || state.phase === 'idle' ? '正在读取客户档案…' : state.notice || (state.phase === 'entry' ? '仅商家 OWNER 可查看本店客户档案' : '页面暂时不可用')}</Text>
      {state.phase === 'error' && <Button className='mcu-primary' onClick={() => void controller.load()}>重新加载</Button>}
      <Button className='mcu-primary' onClick={() => void backToList()}>返回客户列表</Button>
    </View> : <ScrollView className='mcud-scroll' scrollY enhanced showScrollbar={false}>
      {/* 卡1 客户+消费统计（DIV-15：头像53.9圆 + 名/脱敏手机号/最近到店 + 三指标块 #F0FBFF r11.5 左对齐） */}
      <View className='mcud-card'>
        <View className='mcud-head'>
          <View className='mcud-avatar'><Text>{Array.from(customer.nickname ?? '客')[0] ?? '客'}</Text></View>
          <View className='mcud-identity'>
            <Text className='mcud-name'>{customer.nickname ?? '未命名用户'}</Text>
            <Text className='mcud-phone'>{customer.phoneMasked ?? '未绑定手机号'}</Text>
          </View>
          <Text className='mcud-visit'>最近到店 {lastVisitLabel(customer.lastVisitAt, new Date())}</Text>
        </View>
        <View className='mcud-metrics'>
          <View className='mcud-metric'><Text className='mcud-metric-value'>¥{customer.totalSpend}</Text><Text className='mcud-metric-label'>累计消费</Text></View>
          <View className='mcud-metric'><Text className='mcud-metric-value'>{customer.orderCount}</Text><Text className='mcud-metric-label'>累积订单</Text></View>
          <View className='mcud-metric'><Text className='mcud-metric-value'>{customer.avgTicket ? `¥${customer.avgTicket}` : '--'}</Text><Text className='mcud-metric-label'>客单价</Text></View>
        </View>
      </View>
      {/* 卡2 宠物信息（DIV-52：题13.46/700 + 行=46.2圆头像+名/品种；健康三宫格与疫苗/健康/驱虫行=隐私口径不实现） */}
      <View className='mcud-card'>
        <Text className='mcud-card-title'>宠物信息</Text>
        {detail.pets.length === 0 && <Text className='mcud-empty'>本店订单暂无宠物快照</Text>}
        {detail.pets.map((pet, index) => <View key={index} className='mcud-pet-row'>
          <View className='mcud-pet-avatar'><Text>{Array.from(pet.petName)[0] ?? '宠'}</Text></View>
          <View className='mcud-pet-info'>
            <Text className='mcud-pet-name'>{pet.petName}</Text>
            <Text className='mcud-pet-breed'>{pet.breedName ?? pet.petType}</Text>
          </View>
        </View>)}
      </View>
      {/* 卡3 商家备注（DIV-140：题13.46/700 + 13.46/400/19.2 正文 + 发丝分隔；保留 OWNER 写入/幂等/版本功能） */}
      <View className='mcud-card'>
        <Text className='mcud-card-title'>商家备注</Text>
        <View className='mcud-note-wrap'>
          <Textarea className='mcud-note-input' value={state.noteDraft} maxlength={200}
            placeholder='0-200 字，仅在商家端可见（不进入 C 端）' placeholderStyle='color:#6B7280'
            onInput={event => controller.editNote(String(event.detail.value))} />
          <View className='mcud-note-foot'>
            <Text className='mcud-note-count'>{Array.from(state.noteDraft).length} / 200</Text>
            <Button id='mcu-note-save' className='mcud-note-save' disabled={state.noteSaving}
              onClick={() => void controller.saveNote()}>{state.noteSaving ? '保存中…' : '保存备注'}</Button>
          </View>
        </View>
        {state.notice && <View className='mcu-notice' role='alert'><Text>{state.notice}</Text></View>}
      </View>
      <View className='mcud-bottom-space' />
      <View className='mcu-tab-space' />
    </ScrollView>}
    <MerchantTabBar active='home' unit={unit} />
  </View>
}
