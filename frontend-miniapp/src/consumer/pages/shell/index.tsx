import { Button, Text, View } from '@tarojs/components'
import { useRef, useState } from 'react'
import { platform } from '../../../shared/platform'
import { consumerApi } from '../../../shared/consumer-runtime'
import { integrationMessage } from '../../../shared/consumer-api'
import { useWorkspace } from '../../../shared/workspace-react'
export default function Shell() {
  const [count, setCount] = useState(0)
  const { context } = useWorkspace('real')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const [step, setStep] = useState(consumerApi.authStep)
  const running = useRef(false)
  async function perform(action: () => Promise<unknown>) {
    if (running.current) return
    running.current = true; setBusy(true); setNotice('')
    try { await action() }
    catch (error) { setNotice(integrationMessage(error)) }
    finally { running.current = false; setBusy(false); setStep(consumerApi.authStep) }
  }
  return <View className='shell'>
    <View className='panel'><Text className='line'>C-001 工程示例</Text>
      <Text>非产品页面 · 预览入口与真实接口验证分别标注</Text></View>
    <View className='panel'>
      <Text className='line'>真实接口接入验证（沿用工程入口）</Text>
      <Text id='c-session-state' className='line'>{context ? `已登录 · ${consumerApi.currentSession?.phoneMasked || ''}` : '未登录'}</Text>
      {!context && <Button id='c-login' disabled={busy || step === 'retry'} onClick={() => void perform(() => consumerApi.startLogin(platform.login))}>微信登录</Button>}
      {!context && step === 'phone' && <Button id='c-phone' openType='getPhoneNumber' disabled={busy} onGetPhoneNumber={event => void perform(() => consumerApi.bindPhone(event.detail))}>授权手机号并完成登录</Button>}
      {step === 'retry' && <Button id='c-login-retry' disabled={busy} onClick={() => void perform(() => consumerApi.retryLogin())}>重试原登录请求</Button>}
      {!context && <Button id='c-login-cancel' onClick={() => { consumerApi.cancelLogin(); setStep('idle'); setNotice('已取消登录') }}>取消登录</Button>}
      <Button id='c-session-query' disabled={busy} onClick={() => void perform(() => consumerApi.restore())}>查询当前会话</Button>
      <Button id='c-profile' disabled={!context || busy} onClick={() => platform.navigate('/consumer/pages/profile-edit/index')}>编辑真实资料</Button>
      <Button id='c-pets' disabled={!context || busy} onClick={() => platform.navigate('/consumer/pages/pet-archive/index')}>管理真实宠物档案</Button>
      <Button id='c-merchant-application' onClick={() => platform.navigate('/consumer/pages/merchant-application/index')}>成为商家</Button>
      <Button id='c-logout' disabled={busy} onClick={() => void perform(async () => { await consumerApi.logout(); setNotice('已退出登录') })}>退出登录 / 重试退出</Button>
      {notice && <Text id='c-auth-notice' className='line'>{notice}</Text>}
    </View>
    <Text className='line'>局部计数：{count}</Text>
    <Button onClick={() => setCount(value => value + 1)}>增加计数</Button>
    <Button onClick={() => platform.navigate('/consumer/pages/diagnostics/index')}>打开隔离验证页</Button>
    <Button onClick={() => platform.navigate('/consumer/pages/profile-edit/index?preview=1')}>C-002 编辑资料视觉预览（仅本地数据）</Button>
    <Button id='c-merchant-application-preview' onClick={() => platform.navigate('/consumer/pages/merchant-application/index?preview=1')}>成为商家交互预览（不提交真实申请）</Button>
    <Button onClick={() => platform.navigate('/merchant/pages/workspace/index')}>进入商家工作区</Button>
      <Button id='c-messages' onClick={() => platform.navigate('/consumer/pages/messages/index')}>消息中心</Button>
  </View>
}
