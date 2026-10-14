import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useWorkspace } from '../../../shared/workspace-react'
import { consumerApi, consumerSession } from '../../../shared/consumer-runtime'
import { integrationMessage } from '../../../shared/consumer-api'
import { RealAddressRepository } from '../../address/repository'
import { isAddressScenario, maskPhone, previewRepository, type AddressPhase, type AddressScenario, type AddressView } from '../../address/model'
import backIcon from '../../assets/profile/back.png'
import './index.css'

// 地址管理列表页（设计稿 129:13141，登记表 §4.14）。契约 59 六路由中的读侧 + 行内
// 设默认/删除入口；新增/编辑走子页弹层（129:13731/129:13533 同构，编辑页共用表单）。
// 偏差登记：原稿头带标题残留「编辑资料」→按语义用「地址管理」；底部 tab 原稿为
// navigateTo 子页不带；微信 chooseAddress/chooseLocation 未按原稿形态接入（披露于 §4.14）。
// 官方色值：#5baae8 主蓝 / #c0ecff 浅蓝 / #f0fbff 页底；列表手机号按设计稿 138****5678
// 展示层脱敏（读侧契约返回明文，编辑表单回填明文）。
type Scenario = AddressScenario

export default function AddressListPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const scenario: Scenario = preview && isAddressScenario(route.params.scenario) ? route.params.scenario : 'normal'
  const { scope, revision, context } = useWorkspace(preview ? 'preview' : 'real')
  const [phase, setPhase] = useState<AddressPhase>('loading')
  const [addresses, setAddresses] = useState<readonly AddressView[]>([])
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const mounted = useRef(true)
  const sequence = useRef(0)
  const acting = useRef(false)
  const previousRevision = useRef(revision)
  const repository = useRef(preview ? previewRepository(scope, scenario) : new RealAddressRepository(consumerApi))
  const [platformInfo] = useState(() => Taro.getWindowInfo())
  const unit = platformInfo.windowWidth / 402
  const style = {
    '--addr-top': `${platformInfo.statusBarHeight || 0}px`,
    '--addr-unit': `${unit}px`,
  } as CSSProperties

  const load = useCallback(async () => {
    const currentRevision = scope.revision
    const current = ++sequence.current
    setPhase('loading'); setNotice('')
    if ((preview && scenario === 'expired') || !scope.current || scope.current.workspace !== 'consumer') { setPhase('expired'); return }
    try {
      const value = await scope.run(undefined, () => repository.current.list())
      if (!mounted.current || current !== sequence.current || currentRevision !== scope.revision) return
      setAddresses(value); setPhase('ready')
    } catch {
      if (mounted.current && current === sequence.current && currentRevision === scope.revision) setPhase('load-error')
    }
  }, [preview, scenario, scope])
  useDidShow(() => { void load() })
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; sequence.current++ }
  }, [load])
  useEffect(() => {
    if (previousRevision.current !== revision || !context || context.workspace !== 'consumer') {
      previousRevision.current = revision
      sequence.current++
      setAddresses([]); setNotice(''); setPhase('expired')
      if (!preview && context?.workspace === 'consumer') void load()
    }
  }, [revision, context, preview, load])

  function canInteract() { return phase === 'ready' && !acting.current && !busy }

  async function setDefault(address: AddressView) {
    if (!canInteract() || address.isDefault) return
    acting.current = true; setBusy(true); setNotice('')
    const currentRevision = scope.revision
    try {
      await scope.run(undefined, () => repository.current.setDefault(address.addressId, `preview-default-${address.addressId}-${Date.now()}`))
      if (!mounted.current || currentRevision !== scope.revision) return
      await load()
    } catch (error) {
      if (mounted.current && currentRevision === scope.revision) setNotice(preview ? '设置失败，请重试' : integrationMessage(error))
    } finally { acting.current = false; setBusy(false) }
  }
  async function remove(address: AddressView) {
    if (!canInteract()) return
    const currentRevision = scope.revision
    acting.current = true; setBusy(true)
    try {
      const result = await Taro.showModal({
        title: '删除地址？', content: `确定删除「${address.contactName}」的这条地址吗？`, confirmText: '删除', cancelText: '取消',
      })
      if (!result.confirm || !mounted.current || currentRevision !== scope.revision) return
      await scope.run(undefined, () => repository.current.remove(address.addressId, `preview-delete-${address.addressId}-${Date.now()}`))
      if (!mounted.current || currentRevision !== scope.revision) return
      setNotice(preview ? '预览数据已更新' : '已删除')
      await load()
    } catch (error) {
      if (mounted.current && currentRevision === scope.revision) setNotice(preview ? '删除失败，请重试' : integrationMessage(error))
    } finally { acting.current = false; setBusy(false) }
  }
  function openForm(mode: 'new' | 'edit', address?: AddressView) {
    if (!canInteract()) return
    const query = preview ? 'preview=1' : ''
    const suffix = `${query ? '&' : ''}${address ? `addressId=${encodeURIComponent(address.addressId)}` : ''}`
    Taro.navigateTo({ url: `/consumer/pages/address/${mode}${query ? `?${query}${suffix ? `&${suffix}` : ''}` : suffix ? `?${suffix}` : ''}` })
      .catch(() => setNotice('页面打开失败，请重试'))
  }
  async function back() {
    if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
    else await Taro.switchTab({ url: '/consumer/pages/mine/index' })
  }
  async function login() { await consumerSession.ensure(); await load() }

  const ready = phase === 'ready'
  return <View className='addr-page' style={style}>
    <View className='addr-status-area' />
    <View className='addr-header'>
      <Button id='addr-back' className='addr-back' ariaLabel='返回' hoverClass='none' onClick={() => void back()}>
        <Image className='addr-back-icon' src={backIcon} mode='scaleToFill' />
      </Button>
      <Text className='addr-title'>地址管理</Text>
    </View>
    <ScrollView className='addr-body' scrollY enhanced showScrollbar={false}>
      <View className='addr-scroll'>
        {phase === 'loading' && <View className='addr-state' role='status'><Text>正在加载地址…</Text></View>}
        {phase === 'expired' && <View className='addr-state' role='status'>
          <Text id='addr-expired'>登录后可管理收货地址。</Text>
          {!preview && <View className='addr-state-action'><Button id='addr-login' className='addr-state-btn' hoverClass='none' onClick={() => void login()}>去登录</Button></View>}
        </View>}
        {phase === 'load-error' && <View className='addr-state' role='status'>
          <Text id='addr-error'>地址读取失败，请稍后重试。</Text>
          <View className='addr-state-action'><Button id='addr-retry' className='addr-state-btn' hoverClass='none' onClick={() => void load()}>重新加载</Button></View>
        </View>}
        {ready && addresses.length === 0 && <View className='addr-state' role='status'>
          <Text id='addr-empty'>还没有地址，点击下方「+ 新增地址」添加。</Text>
        </View>}
        {ready && addresses.length > 0 && <View className='addr-cards'>
          {addresses.map(address => <View key={address.addressId} id={`addr-card-${address.addressId}`} className='addr-card' ariaLabel={`${address.contactName} ${address.region}${address.detail}`}>
            <View className='addr-card-main'>
              <View className='addr-card-head'>
                <Text className='addr-card-name'>{address.contactName}</Text>
                <Text className='addr-card-phone'>{maskPhone(address.contactPhone)}</Text>
                {address.isDefault && <View className='addr-card-pill'><Text>默认</Text></View>}
              </View>
              <Text className='addr-card-region'>{address.region}</Text>
              <Text className='addr-card-detail'>{address.detail}</Text>
            </View>
            <View className='addr-card-actions'>
              {!address.isDefault && <Button id={`addr-default-${address.addressId}`} className='addr-card-act' hoverClass='none' disabled={busy} onClick={() => void setDefault(address)}>设为默认</Button>}
              <Button id={`addr-edit-${address.addressId}`} className='addr-card-act' hoverClass='none' disabled={busy} onClick={() => openForm('edit', address)}>编辑</Button>
              <Button id={`addr-delete-${address.addressId}`} className='addr-card-act addr-card-act-danger' hoverClass='none' disabled={busy} onClick={() => void remove(address)}>删除</Button>
            </View>
          </View>)}
        </View>}
        {notice && <Text id='addr-notice' className='addr-notice'>{notice}</Text>}
      </View>
    </ScrollView>
    <View className='addr-foot'>
      <Button id='addr-new' className='addr-new' hoverClass='none' disabled={!ready || busy}
        ariaLabel='新增地址' onClick={() => openForm('new')}>+ 新增地址</Button>
    </View>
  </View>
}
