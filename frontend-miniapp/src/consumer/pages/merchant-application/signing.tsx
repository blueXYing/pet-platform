import { useEffect, useState, type CSSProperties } from 'react'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { Button, Image, Text, View } from '@tarojs/components'
import { useSyncExternalStore } from 'react'
import { ConsumerPageLayout } from '../../components/page-layout'
import { navigationUnavailableMessage } from '../../components/navigation/model'
import { useWorkspace } from '../../../shared/workspace-react'
import { SigningController } from '../../merchant-application/signing'
import { signingRuntime } from '../../merchant-application/signing-runtime'
import back from './assets/back.png'
import './index.scss'
import './signing.scss'

// No Figma original exists for the agreement signing screen (design registry §4); this page
// reuses the delivered application-page language and is not a 1:1 restoration or VIS pass.
export default function MerchantSigningPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const { scope } = useWorkspace(preview ? 'preview' : 'real')
  return <SigningScreen preview={preview} merchantParam={route.params.merchantId} scope={scope} />
}
function SigningScreen({ preview, merchantParam, scope }: { preview: boolean; merchantParam: string | undefined; scope: ReturnType<typeof useWorkspace>['scope'] }) {
  const [controller] = useState(() => new SigningController(scope, signingRuntime(preview)))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  useDidShow(() => { if (preview) controller.enterPreview(merchantParam); else void controller.enter(merchantParam) })
  useEffect(() => () => controller.dispose(), [controller])
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = { '--application-unit': `${unit}px`, '--application-top': `${info.statusBarHeight || 0}px` } as CSSProperties
  async function leave() {
    controller.leave()
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.redirectTo({ url: '/consumer/pages/shell/index' })
  }
  const receiptVersion = state.receipt?.agreementVersion || ''
  const receiptTime = state.receipt?.acceptedAt || ''
  return <ConsumerPageLayout page='merchantApplication' unit={unit} className='application-page signing-page' style={style} navigation={{ idPrefix: 'signing', disabled: state.busy, onSelect: key => void Taro.showToast({ title: navigationUnavailableMessage(key), icon: 'none' }) }}>
    <View className='application-design' data-state={state.phase}>
      <View className='application-header'><Button id='signing-back' ariaLabel='返回' disabled={state.busy} onClick={() => void leave()}><Image src={back} mode='scaleToFill' /></Button><Text>商家协议签署</Text></View>
      <View className='application-content'>
        <Text className='application-intro'>请阅读以下商家协议全文。勾选同意并签署后，签署版本与时间将以服务端记录为准。</Text>
        {state.phase === 'switching' && <View className='application-state' role='status'>签署工作区已切换，请重新进入签署。</View>}
        {state.phase === 'loading' && <View className='application-state' role='status'>正在读取协议…</View>}
        {state.phase === 'invalid-merchant' && <View className='application-state' role='alert'>商家标识无效，请从申请页重新进入。</View>}
        {state.phase === 'unauthorized' && <View className='application-state'><Text>请先登录后继续签署</Text><Button id='signing-login' onClick={() => void Taro.redirectTo({ url: '/consumer/pages/shell/index' })}>去登录</Button></View>}
        {state.phase === 'denied' && <View className='application-state' role='alert'>当前账号无权查看或签署该商家协议。<Button id='signing-denied-reload' disabled={state.busy} onClick={() => controller.reread()}>重新读取</Button></View>}
        {state.phase === 'load-failed' && <View className='application-state' role='alert'>{state.notice || '协议读取失败。'}<Button id='signing-reload' disabled={state.busy} onClick={() => controller.reread()}>重新读取协议</Button></View>}
        {state.phase === 'recovering' && <View className='application-state' role='status'>
          <Text>上次签署结果尚未确认{state.pending ? `（版本 ${state.pending.agreementVersion}）` : ''}，重试将使用原请求编号确认结果，不会重复签署。</Text>
          <Button id='signing-retry' disabled={state.busy} onClick={() => void controller.retryPending()}>{state.busy ? '确认中…' : '重试原签署'}</Button>
        </View>}
        {state.phase === 'conflict' && <View className='application-state' role='status'>
          <Text>协议版本或内容已变化，本次签署未完成；请重新阅读新版本后再次确认。</Text>
          <Button id='signing-conflict-reload' disabled={state.busy} onClick={() => controller.reread()}>重新读取协议</Button>
        </View>}
        {(state.phase === 'unsigned' || state.phase === 'signed') && state.agreement && <View className='signing-agreement'>
          <View className='signing-agreement-meta'><Text>版本 {state.agreement.agreementVersion}</Text><Text className='signing-agreement-hash'>SHA-256 {state.agreement.contentSha256}</Text></View>
          <View className='signing-agreement-content' ariaLabel='协议正文'>{state.agreement.content}</View>
        </View>}
        {state.phase === 'unsigned' && !state.agreement && <View className='signing-agreement'><View className='signing-agreement-content'>协议内容以服务端为准。</View></View>}
        {state.phase === 'unsigned' && <View className='signing-check-row'>
          <Button id='signing-check' className={`signing-checkbox${state.checked ? ' signing-checkbox-checked' : ''}`} ariaLabel={state.checked ? '取消勾选同意' : '勾选同意协议'} disabled={state.busy || !state.agreement} onClick={() => controller.toggle()} aria-selected={state.checked}>{state.checked ? '✓' : ''}</Button>
          <Text className='signing-check-label' onClick={() => controller.toggle()}>我已阅读并同意上述协议全部条款</Text>
        </View>}
        {state.phase === 'unsigned' && <Button id='signing-submit' className='application-submit' disabled={state.busy || !state.checked || !state.agreement} onClick={() => void controller.submit()}>{state.busy ? '处理中…' : '同意并签署'}</Button>}
        {state.phase === 'signed' && <View className='application-state signing-signed' role='status'>
          <Text>协议已签署，原签署版本保持有效，无需重复签署。</Text>
          {receiptVersion && <Text className='application-opinion'>签署版本 {receiptVersion}</Text>}
          {receiptTime && <Text className='application-opinion'>签署时间 {receiptTime}</Text>}
        </View>}
        {state.notice && state.phase !== 'load-failed' && state.phase !== 'recovering' && <View id='signing-notice' className='application-notice' role='status'>{state.notice}</View>}
        <View className='application-secondary'><Button id='signing-reread' disabled={state.busy || !state.merchantId || state.phase === 'recovering'} onClick={() => controller.reread()}>重新读取协议</Button><Button id='signing-back-secondary' disabled={state.busy} onClick={() => void leave()}>返回申请页</Button></View>
        {preview && <Text className='application-preview-label'>交互预览 · 不会提交签署</Text>}
      </View>
    </View>
  </ConsumerPageLayout>
}
