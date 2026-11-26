import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useState, type CSSProperties } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { MerchantAdmissionRepository } from '../../../shared/merchant-repositories'
import {
  MerchantWalletClient, WITHDRAWAL_STATUS_LABELS, formatBeijingDateTime,
  merchantWalletErrorMessage, previewWithdrawals,
  type WithdrawalOrderView,
} from '../../../shared/merchant-wallet-api'
import './index.css'

// 商家提现记录页（Contract 80 §5，仅 OWNER 只读分页列表）。

const PAGE_SIZE = 20

const STATUS_FILTERS: readonly { value: string; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'SUCCEEDED', label: '已到账' },
  { value: 'FAILED', label: '提现失败' },
]

function statusBadgeClass(status: string): string {
  if (status === 'SUCCEEDED') return 'mwal-badge mwal-badge-ok'
  if (status === 'FAILED') return 'mwal-badge mwal-badge-fail'
  return 'mwal-badge mwal-badge-warn'
}

export default function MerchantWalletWithdrawalsPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = {
    '--mwal-status-top': `${info.statusBarHeight || 0}px`,
    '--mwal-unit': `${unit}px`,
  } as CSSProperties

  const [walletClient] = useState(() => new MerchantWalletClient(consumerApi))
  const [phase, setPhase] = useState<'loading' | 'entry' | 'forbidden' | 'error' | 'ready'>('loading')
  const [statusFilter, setStatusFilter] = useState('')
  const [items, setItems] = useState<readonly WithdrawalOrderView[]>([])
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState('')

  const loadPage = useCallback(async (nextPage: number, nextStatus: string, append: boolean) => {
    setError('')
    if (preview) {
      const sample = previewWithdrawals()
      const filtered = nextStatus ? sample.items.filter(item => item.status === nextStatus) : sample.items
      setItems(filtered)
      setPage(1)
      setTotal(filtered.length)
      setPhase('ready')
      return
    }
    const current = consumerApi.scope.current
    if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      setPhase('entry')
      return
    }
    if (append) setLoadingMore(true)
    else setPhase('loading')
    try {
      if (!append) {
        const admission = await new MerchantAdmissionRepository(consumerApi).admission(current.merchantId, current.storeId)
        if (admission.membershipKind !== 'OWNER') {
          setPhase('forbidden')
          return
        }
      }
      const data = await walletClient.listWithdrawals({
        status: nextStatus || undefined,
        page: nextPage,
        size: PAGE_SIZE,
      })
      setItems(prev => (append ? [...prev, ...data.items] : data.items))
      setPage(data.page)
      setTotal(data.total)
      setPhase('ready')
    } catch (caught) {
      setError(merchantWalletErrorMessage(caught, '提现记录读取失败'))
      if (!append) setPhase('error')
    } finally {
      setLoadingMore(false)
    }
  }, [preview, walletClient])

  useDidShow(() => { void loadPage(1, statusFilter, false) })

  function goBack() {
    void Taro.reLaunch({ url: `/merchant/pages/wallet/index${preview ? '?preview=1' : ''}` })
      .catch(() => { void Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) })
  }

  return <View className='mwal-page' style={style}>
    <View className='mwal-status-area' />
    <View className='mwal-header'>
      <Button id='mwal-wd-back' className='mwal-back-btn' onClick={goBack}>‹ 钱包</Button>
      <Text className='mwal-title'>提现记录</Text>
      <View className='mwal-header-right' />
    </View>

    <View className='mwal-body'>
      {phase === 'loading' && (
        <View className='mwal-card' role='status'><Text className='mwal-hint'>正在加载提现记录…</Text></View>
      )}

      {phase === 'entry' && (
        <View className='mwal-card' role='status'>
          <Text className='mwal-section-title'>请从商家工作台进入</Text>
          <Button className='mwal-primary-btn' onClick={goBack}>返回钱包</Button>
        </View>
      )}

      {phase === 'forbidden' && (
        <View className='mwal-card' role='status'>
          <Text className='mwal-section-title'>仅商家主账号可用</Text>
          <Text className='mwal-hint'>提现记录仅认证商家主账号（OWNER）可见。</Text>
          <Button className='mwal-primary-btn' onClick={goBack}>返回</Button>
        </View>
      )}

      {phase === 'error' && (
        <View className='mwal-card' role='status'>
          <Text className='mwal-alert'>{error || '加载失败'}</Text>
          <Button className='mwal-primary-btn' onClick={() => void loadPage(1, statusFilter, false)}>重新加载</Button>
        </View>
      )}

      {phase === 'ready' && <>
        <View className='mwal-chips'>
          {STATUS_FILTERS.map(filter => (
            <Button
              key={filter.value || 'ALL'}
              className={statusFilter === filter.value ? 'mwal-chip mwal-chip-active' : 'mwal-chip'}
              onClick={() => {
                setStatusFilter(filter.value)
                void loadPage(1, filter.value, false)
              }}>
              {filter.label}
            </Button>
          ))}
        </View>

        {error && <View className='mwal-alert' role='alert'><Text>{error}</Text></View>}

        {items.length === 0 ? (
          <View className='mwal-card'><Text className='mwal-empty'>暂无提现记录</Text></View>
        ) : (
          items.map(item => (
            <View key={item.withdrawalId} className='mwal-card'>
              <View className='mwal-wd-head'>
                <Text className='mwal-wd-amount'>¥{item.amount}</Text>
                <Text className={statusBadgeClass(item.status)}>
                  {WITHDRAWAL_STATUS_LABELS[item.status] ?? item.status}
                </Text>
              </View>
              <View className='mwal-fact-row'>
                <Text className='mwal-fact-label'>提现单号</Text>
                <Text className='mwal-fact-value'>{item.merOrderNo}</Text>
              </View>
              <View className='mwal-fact-row'>
                <Text className='mwal-fact-label'>渠道手续费 / 实到金额</Text>
                <Text className='mwal-fact-value'>
                  {item.feeAmount ? `¥${item.feeAmount}` : '—'} / {item.netAmount ? `¥${item.netAmount}` : '—'}
                </Text>
              </View>
              {item.drawJnl && (
                <View className='mwal-fact-row'>
                  <Text className='mwal-fact-label'>拉卡拉流水号</Text>
                  <Text className='mwal-fact-value'>{item.drawJnl}</Text>
                </View>
              )}
              <View className='mwal-fact-row'>
                <Text className='mwal-fact-label'>发起时间</Text>
                <Text className='mwal-fact-value'>{formatBeijingDateTime(item.requestedAt ?? item.createdAt)}</Text>
              </View>
              {item.finishedAt && (
                <View className='mwal-fact-row'>
                  <Text className='mwal-fact-label'>完成时间</Text>
                  <Text className='mwal-fact-value'>{formatBeijingDateTime(item.finishedAt)}</Text>
                </View>
              )}
              {item.failReason && (
                <View className='mwal-alert'><Text>失败原因：{item.failReason}</Text></View>
              )}
            </View>
          ))
        )}

        {items.length < total && (
          <Button
            id='mwal-wd-more'
            className='mwal-ghost-btn'
            disabled={loadingMore}
            onClick={() => void loadPage(page + 1, statusFilter, true)}>
            {loadingMore ? '正在加载更多…' : `加载更多（已显示 ${items.length} / ${total} 条）`}
          </Button>
        )}

        <Text className='mwal-preview-note'>
          {preview ? '只读预览：本地样例提现记录。' : `共 ${total} 条提现记录`}
        </Text>
      </>}
    </View>
  </View>
}
