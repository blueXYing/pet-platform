import { Button, Image, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useCallback, useState, type CSSProperties } from 'react'
import { consumerApi, consumerSession } from '../../../../shared/consumer-runtime'
import { RealServiceManageRepository } from '../../../services/repository'
import type { ManagedServiceItem } from '../../../services/model'
import { MerchantFlashPriceApi, type FlashPriceRecord } from '../../../marketing/flash-api'
import { beijingDateTimeText, flashCancellable, flashEditable, flashPriceMessage, flashQuotaLine, flashStatusPill } from '../../../marketing/flash-model'
import { MerchantTabBar } from '../../../components/tab-bar'
import navBack from '../../aftersale/assets/nav-back@3x.png'
import iconToolFlashSale from '../assets/icon-tool-flash-sale@3x.png'
import iconPlus from '../assets/icon-plus@3x.png'
import '../index.css'
import './index.css'

// 限时秒杀场次列表（Figma 18:9537，77号 §3.1 / HANDOFF §8.1）：按服务维度入口——顶部横向
// 服务 chips（本店 ACTIVE 服务，数据源=商家服务管理同源 GET /api/v1/merchant/services），
// 选中服务后拉取该服务场次卡（title/状态/窗口/折扣+闪价+划线原价/已售/参与人数/驳回原因）。
// 登记偏差（HANDOFF §10 + 77号 §9）：Figma 三统计卡的「转化率」无曝光数据不展示，
// 「本月秒杀/参与人数」改为当前所选服务的真实 API 计数；底部五 tab 为设计残留
// （M 端工作台导航）。所有数字来自 API，不伪造价格/名额/参与人数。
const api = new MerchantFlashPriceApi(consumerApi)
const servicesRepository = new RealServiceManageRepository(consumerApi,
  () => consumerApi.scope.current?.merchantId || '', () => consumerApi.scope.current?.storeId || '')

function useMerchant() {
  const context = consumerApi.scope.current
  if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) return null
  return { merchantId: context.merchantId, storeId: context.storeId }
}

export default function MerchantFlashIndexPage() {
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = {
    '--mmk-unit': `${info.windowWidth / 402}px`,
    '--mmk-status-top': `${info.statusBarHeight || 0}px`,
  } as CSSProperties
  const [merchant] = useState(() => useMerchant())
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [services, setServices] = useState<readonly ManagedServiceItem[]>([])
  const [selectedServiceId, setSelectedServiceId] = useState('')
  const [records, setRecords] = useState<readonly FlashPriceRecord[]>([])
  const [listPhase, setListPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)

  /** 拉全本店服务并取 ACTIVE 子集；返回生效的所选服务 ID（保持已选，否则取第一个）。 */
  const loadServices = useCallback(async (): Promise<string> => {
    if (!merchant) { setPhase('error'); return '' }
    setPhase('loading'); setNotice('')
    try {
      // 列表契约单页 ≤50；翻到 total 为止（硬上限兜底），客户端只保留 ACTIVE 且有价格的服务。
      const collected: ManagedServiceItem[] = []
      let page = 1
      for (;;) {
        const result = await servicesRepository.list(page, 50)
        collected.push(...result.items)
        if (collected.length >= result.total || page >= 200) break
        page += 1
      }
      const active = collected.filter(item => item.status === 'ACTIVE' && item.price !== null)
      setServices(active)
      const next = selectedServiceId && active.some(item => item.serviceId === selectedServiceId)
        ? selectedServiceId
        : active[0]?.serviceId || ''
      setSelectedServiceId(next)
      setPhase('ready')
      return next
    } catch {
      setServices([]); setSelectedServiceId(''); setPhase('error')
      return ''
    }
  }, [merchant, selectedServiceId])

  const loadRecords = useCallback(async (serviceId: string) => {
    if (!serviceId) { setRecords([]); setListPhase('ready'); return }
    setListPhase('loading')
    try {
      const items = await api.list(serviceId, undefined, 1, 50)
      setRecords(items); setListPhase('ready')
    } catch {
      setRecords([]); setListPhase('error')
    }
  }, [])

  useDidShow(() => {
    // 进入/返回本页都重读：从编辑页回来后立即呈现最新场次（数字全部来自 API）。
    void (async () => {
      try { await consumerSession.launch() } catch { /* load renders the error state */ }
      const serviceId = await loadServices()
      await loadRecords(serviceId)
    })()
  })

  function chooseService(serviceId: string) {
    if (serviceId === selectedServiceId || busy) return
    setSelectedServiceId(serviceId)
    setNotice('')
    void loadRecords(serviceId)
  }

  function createFlash() {
    if (!selectedServiceId) return
    Taro.navigateTo({ url: `/merchant/pages/marketing/flash/edit?serviceId=${selectedServiceId}` })
      .catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) })
  }
  function editFlash(record: FlashPriceRecord) {
    Taro.navigateTo({ url: `/merchant/pages/marketing/flash/edit?serviceId=${record.serviceId}&id=${record.flashPriceId}` })
      .catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) })
  }
  function back() {
    // P-03 追缉（2026-11-12）：商家域返回不用 navigateBack（隐藏页树可能已被 dispose）。
    Taro.reLaunch({ url: '/merchant/pages/marketing/index' }).catch(() => {})
  }

  async function submitFlash(record: FlashPriceRecord) {
    if (busy || !flashEditable(record.status)) return
    const confirmed = await Taro.showModal({ title: '提交审核', content: '提交后由运营审核，通过后按有效期生效；商家不可直接发布。', confirmText: '提交审核', cancelText: '再想想' })
    if (!confirmed.confirm) return
    setBusy(true); setNotice('')
    try {
      await api.submit(record.serviceId, record.flashPriceId, record.version)
      await Taro.showToast({ title: '已提交审核', icon: 'none' })
      await loadRecords(record.serviceId)
    } catch (error) { setNotice(flashPriceMessage(error)) } finally { setBusy(false) }
  }

  async function cancelFlash(record: FlashPriceRecord) {
    if (busy || !flashCancellable(record.status)) return
    const confirmed = await Taro.showModal({ title: '确认取消秒杀', content: '取消后该场次不再生效；已生效场次不可由商家取消。', confirmText: '确认取消', cancelText: '再想想' })
    if (!confirmed.confirm) return
    setBusy(true); setNotice('')
    try {
      await api.cancel(record.serviceId, record.flashPriceId, record.version)
      await Taro.showToast({ title: '已取消', icon: 'none' })
      await loadRecords(record.serviceId)
    } catch (error) { setNotice(flashPriceMessage(error)) } finally { setBusy(false) }
  }

  const selected = services.find(item => item.serviceId === selectedServiceId) || null
  const participantTotal = records.reduce((sum, record) => sum + record.participantCount, 0)

  return <View className='mmk-page' style={style}>
    <View className='mmk-status' />
    <View className='mmk-header'>
      <Button className='mmk-back' ariaLabel='返回' onClick={back}><Image src={navBack} mode='scaleToFill' /></Button>
      <Text className='mmk-title'>限时秒杀</Text>
    </View>
    {!merchant && <View className='mmk-empty'><Text>请从商家工作台进入营销工具</Text></View>}
    {merchant && phase === 'loading' && <View className='mmk-empty'><Text>正在加载服务与场次…</Text></View>}
    {merchant && phase === 'error' && <View className='mmk-empty'>
      <Text>服务加载失败</Text><Button className='mmk-primary' onClick={() => { void (async () => { await loadRecords(await loadServices()) })() }}>重新加载</Button>
    </View>}
    {merchant && phase === 'ready' && <View className='mmk-campaigns'>
      <View className='mmk-banner'>
        <View className='mmk-banner-icon'><Image src={iconToolFlashSale} mode='aspectFit' /></View>
        <View className='mmk-banner-main'>
          <Text className='mmk-banner-title'>限时秒杀</Text>
          <Text className='mmk-banner-sub'>爆款服务限时抢购，制造下单紧迫感</Text>
        </View>
      </View>
      {services.length === 0 && <View className='mmk-empty' role='status'>
        <Text>暂无可参与的 ACTIVE 服务，请先在服务管理完成服务上架。</Text>
      </View>}
      {services.length > 0 && <>
        <View className='mmf-service-head'>
          <Text className='mmf-service-label'>选择服务</Text>
          <Text className='mmf-service-count'>共 {services.length} 个可参与服务</Text>
        </View>
        <ScrollView scrollX className='mmf-service-scroll' showScrollbar={false}>
          <View className='mmf-service-chips'>
            {services.map(item => <View key={item.serviceId} id={`mmf-service-${item.serviceId}`}
              className={item.serviceId === selectedServiceId ? 'mmf-service-chip mmf-service-chip-active' : 'mmf-service-chip'}
              ariaLabel={`服务 ${item.serviceName} 价格${item.price ?? ''}元`}
              onClick={() => chooseService(item.serviceId)}>
              <Text className='mmf-service-chip-name'>{item.serviceName}</Text>
              <Text className='mmf-service-chip-price'>¥{item.price}</Text>
            </View>)}
          </View>
        </ScrollView>
        <View className='mmk-stats'>
          <View className='mmk-stat'><Text className='mmk-stat-value'>{records.length}</Text><Text className='mmk-stat-label'>秒杀场次</Text></View>
          <View className='mmk-stat'><Text className='mmk-stat-value'>{participantTotal}</Text><Text className='mmk-stat-label'>参与人数</Text></View>
        </View>
        <View className='mmk-listbar'>
          <Text className='mmk-listbar-count'>{selected ? `${selected.serviceName} · 共 ${records.length} 个场次` : '共 0 个场次'}</Text>
          <View className='mmk-listbar-create' id='mmf-create-flash' onClick={createFlash}>
            <Image className='mmk-listbar-plus' src={iconPlus} mode='aspectFit' />
            <Text>创建秒杀</Text>
          </View>
        </View>
        {listPhase === 'loading' && <View className='mmk-empty'><Text>正在加载场次…</Text></View>}
        {listPhase === 'error' && <View className='mmk-empty' role='status'>
          <Text>场次加载失败</Text>
          <Button className='mmk-primary' onClick={() => void loadRecords(selectedServiceId)}>重新加载</Button>
        </View>}
        {listPhase === 'ready' && records.length === 0 && <View className='mmk-empty'>
          <Text>该服务还没有秒杀场次，点击「创建秒杀」发起第一场</Text>
        </View>}
        {listPhase === 'ready' && records.length > 0 && <View className='mmk-cards'>
          {records.map(record => {
            const pill = flashStatusPill(record.status, Date.now(), record.startAt, record.endAt)
            const editable = flashEditable(record.status)
            const cancellable = flashCancellable(record.status)
            return <View key={record.flashPriceId} id={`mmf-flash-${record.flashPriceId}`} className='mmk-campaign-card mmf-card'>
              <View className='mmk-campaign-cover'>
                <Image className='mmf-card-icon' src={iconToolFlashSale} mode='aspectFit' />
              </View>
              <View className='mmk-campaign-main'>
                <View className='mmk-campaign-head'>
                  <Text className='mmk-campaign-title'>{record.title}</Text>
                  <Text className={`mmk-pill mmk-pill-${pill.tone}`}>{pill.text}</Text>
                </View>
                <View className='mmf-card-price'>
                  <Text className='mmf-card-rate'>{record.discountRate}折</Text>
                  <Text className='mmf-card-flash'>限时价 ¥{record.flashPrice}</Text>
                  <Text className='mmf-card-list'>¥{record.listPriceSnapshot}</Text>
                </View>
                <Text className='mmf-card-window'>{beijingDateTimeText(record.startAt)} 至 {beijingDateTimeText(record.endAt)}</Text>
                <Text className='mmf-card-meta'>{flashQuotaLine(record)} · 参与人数 {record.participantCount}</Text>
                {record.perUserLimit !== null ? <Text className='mmf-card-meta'>每人限领 {record.perUserLimit}</Text> : null}
                {record.rejectReason ? <Text className='mmk-reject'>驳回原因：{record.rejectReason}</Text> : null}
                {(editable || cancellable) && <View className='mmf-card-actions'>
                  {editable && <View className='mmf-action' id={`mmf-edit-${record.flashPriceId}`} onClick={() => editFlash(record)}><Text className='mmf-action-text'>{record.status === 'REJECTED' ? '修改重提' : '编辑'}</Text></View>}
                  {editable && <View className='mmf-action mmf-action-primary' id={`mmf-submit-${record.flashPriceId}`} onClick={() => void submitFlash(record)}><Text className='mmf-action-text mmf-action-text-primary'>{busy ? '处理中…' : '提交审核'}</Text></View>}
                  {cancellable && <View className='mmf-action' id={`mmf-cancel-${record.flashPriceId}`} onClick={() => void cancelFlash(record)}><Text className='mmf-action-text'>取消</Text></View>}
                </View>}
              </View>
            </View>
          })}
        </View>}
        {notice ? <View className='mmk-notice'><Text>{notice}</Text></View> : null}
        <Text className='mmk-hub-tail'>提交后由运营审核；参与人数为该服务各场次已下单去重用户数，全部来自平台接口。</Text>
      </>}
    </View>}
    <View className='mmk-tab-space' />
    <MerchantTabBar active='home' unit={unit} />
  </View>
}
