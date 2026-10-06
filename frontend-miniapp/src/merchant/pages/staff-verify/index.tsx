import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import navBack from '../members/assets/nav-back@2x.png'
import './page.css'

// 员工核销页（48 号 K1 v0.2 员工核销入口）。仓库内不存在 OWNER 核销页可复用（merchant 与
// consumer 分包均无核销页面），且核销 HTTP 契约未交付：47 号 §4 明确 GET/POST
// /c/orders/{orderId}/verification-code 保留 NOT_IMPLEMENTED，48 号 K2 明确「没有 HTTP
// Controller」。因此本页失败关闭为整页不可交互面板——不渲染扫码/输码表单、不虚构任何核销
// 数据；开关 pet.verification.staff-identity.enabled 默认关亦无从探测（无任何已装配路由）。
// 设计源：登记表 §3 有「核销」帧 23:18830/23:19210，但语义为第三方平台（美团/大众点评/抖音）
// 扫码 + 核销码/手机号查询 + 底部 tab 导航，与 47/48 号契约（平台签发 32 位码、无第三方、
// 无手机号兜底、工作台导航）冲突，已按排期页先例在登记表登记为「不作为还原依据」，待 HTTP
// 契约交付并补稿后再按 21 号验收补充还原。

export default function StaffVerifyPage() {
  const { scope } = useWorkspace('real')
  const [notice, setNotice] = useState('')
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = { '--msv-status-top': `${platformInfo.statusBarHeight || 0}px`, '--msv-unit': `${unit}px` } as CSSProperties
  const [entered, setEntered] = useState(false)
  useDidShow(() => {
    setNotice('')
    const current = scope.current
    setEntered(!!current && current.workspace === 'merchant' && !!current.merchantId && !!current.storeId)
  })

  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack().catch(() => setNotice('返回失败，请重试'))
    else await Taro.redirectTo({ url: '/merchant/pages/staff-workbench/index' }).catch(() => setNotice('返回失败，请重试'))
  }
  function goWorkbench() {
    Taro.redirectTo({ url: '/merchant/pages/staff-workbench/index' }).catch(() => setNotice('页面跳转失败，请重试'))
  }

  return <View className='msv-page' style={style}>
    <View className='msv-status-area' />
    <View className='msv-header'>
      <Button id='msv-back' ariaLabel='返回' className='msv-back' onClick={() => void back()}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='msv-title'>订单核销</Text>
    </View>
    <ScrollView className='msv-body' scrollY enhanced showScrollbar={false}>
      {!entered && <View className='msv-state' role='status'>
        <Text>请从员工工作台进入核销。</Text>
        <Button id='msv-go-workbench' className='msv-state-action' onClick={goWorkbench}>去员工工作台</Button>
      </View>}
      {entered && <View className='msv-panel' role='status'>
        <Text className='msv-panel-title'>核销功能未开放</Text>
        <Text className='msv-panel-line'>核销通道（47/48 号契约的核销码读取与核销完成命令）尚未开通 HTTP 服务，员工核销开关 pet.verification.staff-identity.enabled 默认关闭。</Text>
        <Text className='msv-panel-line'>开放后，已获授权的核销员可在此对到店顾客的订单进行核销；核销人所属门店必须与订单门店一致（48 号 K1 D5），以员工身份落库可追溯。</Text>
        <Button id='msv-back-workbench' className='msv-back-workbench' onClick={goWorkbench}>返回员工工作台</Button>
      </View>}
      {notice && <Text id='msv-notice' className='msv-notice'>{notice}</Text>}
      <View className='msv-tail'><Text>本页为失败关闭面板：不提供任何可编辑核销表单，不展示虚构核销数据</Text></View>
    </ScrollView>
  </View>
}
