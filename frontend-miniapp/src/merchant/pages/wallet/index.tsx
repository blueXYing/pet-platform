import { Button, Input, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useState, type CSSProperties } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { MerchantAdmissionRepository } from '../../../shared/merchant-repositories'
import {
  MerchantWalletClient, RECEIVER_STATUS_LABELS, WITHDRAWAL_STATUS_LABELS,
  formatBeijingDateTime, merchantWalletErrorMessage, normalizeWithdrawalAmount,
  previewMerchantReceiver, previewMerchantWallet,
  type MerchantReceiverStatusView, type MerchantWalletView, type WithdrawalOrderView,
} from '../../../shared/merchant-wallet-api'
import './index.css'

// 商家钱包与手动提现页（Contract 80 §5 / 40 号 §5.5 & §7 & §9）：
// - 仅认证商家主账号（OWNER）可读可提现（失败关闭）；
// - 可提现余额以拉卡拉接收方账户余额查询（1269 curBalance）为准；
// - 严禁展示平台补贴待结算（仅运营后台报表可见）；
// - V1 不设短信验证码提现；提现写操作走 X-Request-Id 持久化幂等槽。

type Phase = 'loading' | 'entry' | 'forbidden' | 'error' | 'ready'

export default function MerchantWalletPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = {
    '--mwal-status-top': `${info.statusBarHeight || 0}px`,
    '--mwal-unit': `${unit}px`,
  } as CSSProperties

  const [walletClient] = useState(() => new MerchantWalletClient(consumerApi))
  const [phase, setPhase] = useState<Phase>('loading')
  const [wallet, setWallet] = useState<MerchantWalletView | null>(null)
  const [receiver, setReceiver] = useState<MerchantReceiverStatusView | null>(null)
  const [amountInput, setAmountInput] = useState('')
  const [pendingAmount, setPendingAmount] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [receipt, setReceipt] = useState<WithdrawalOrderView | null>(null)

  const load = useCallback(() => {
    setError('')
    if (preview) {
      setWallet(previewMerchantWallet())
      setReceiver(previewMerchantReceiver())
      setPendingAmount(null)
      setPhase('ready')
      return
    }
    const current = consumerApi.scope.current
    if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      setPhase('entry')
      return
    }
    setPhase('loading')
    const admissionRepo = new MerchantAdmissionRepository(consumerApi)
    void admissionRepo.admission(current.merchantId, current.storeId)
      .then(async admission => {
        if (admission.membershipKind !== 'OWNER') {
          setPhase('forbidden')
          return
        }
        const [walletView, receiverView] = await Promise.all([
          walletClient.getWallet(),
          walletClient.getReceiver().catch(() => null),
        ])
        const pending = walletClient.pendingWithdrawal()
        setWallet(walletView)
        setReceiver(receiverView)
        setPendingAmount(pending?.amount ?? null)
        if (pending?.amount) setAmountInput(pending.amount)
        setPhase('ready')
      })
      .catch(caught => {
        setError(merchantWalletErrorMessage(caught, '钱包数据读取失败'))
        setPhase('error')
      })
  }, [preview, walletClient])

  useDidShow(load)

  function goBack() {
    void Taro.reLaunch({ url: `/merchant/pages/mine/index${preview ? '?preview=1' : ''}` })
      .catch(() => { void Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) })
  }

  function openWithdrawals() {
    void Taro.navigateTo({ url: `/merchant/pages/wallet/withdrawals${preview ? '?preview=1' : ''}` })
      .catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) })
  }

  function openReceiver() {
    void Taro.navigateTo({ url: `/merchant/pages/wallet/receiver?from=wallet${preview ? '&preview=1' : ''}` })
      .catch(() => { void Taro.showToast({ title: '页面跳转失败，请重试', icon: 'none' }) })
  }

  async function submitWithdraw() {
    if (preview || submitting || !wallet) return
    if (!wallet.receiverActive) {
      setError('分账接收方尚未生效，暂不可发起提现，请联系平台运营协助办理。')
      return
    }
    if (!wallet.channelBalanceAvailable) {
      setError('拉卡拉渠道余额暂不可查询，请稍后刷新后再试。')
      return
    }
    const targetAmount = pendingAmount ?? amountInput
    const normalized = normalizeWithdrawalAmount(targetAmount)
    if (normalized === null) {
      setError('请输入大于 0.00 的合法提现金额（最多两位小数）。')
      return
    }
    setSubmitting(true)
    setError('')
    setReceipt(null)
    try {
      const order = await walletClient.withdraw(normalized)
      setReceipt(order)
      setPendingAmount(null)
      setAmountInput('')
      // 提现提交成功后主动刷新渠道余额与待结算视图
      const refreshed = await walletClient.getWallet().catch(() => null)
      if (refreshed) setWallet(refreshed)
    } catch (caught) {
      const pending = walletClient.pendingWithdrawal()
      setPendingAmount(pending?.amount ?? null)
      setError(merchantWalletErrorMessage(caught, '发起提现失败'))
    } finally {
      setSubmitting(false)
    }
  }

  const receiverLabel = receiver?.statusLabel
    ?? (wallet ? (RECEIVER_STATUS_LABELS[wallet.receiverStatus] ?? wallet.receiverStatus) : '—')

  return <View className='mwal-page' style={style}>
    <View className='mwal-status-area' />
    <View className='mwal-header'>
      <Button id='mwal-back' className='mwal-back-btn' onClick={goBack}>‹ 返回</Button>
      <Text className='mwal-title'>我的钱包</Text>
      <View className='mwal-header-right' />
    </View>

    <View className='mwal-body'>
      {phase === 'loading' && (
        <View className='mwal-card' role='status'><Text className='mwal-hint'>正在读取钱包与渠道余额…</Text></View>
      )}

      {phase === 'entry' && (
        <View className='mwal-card' role='status'>
          <Text className='mwal-section-title'>请从商家工作台进入</Text>
          <Text className='mwal-hint'>当前未处于商家工作区，请先返回商家“我的”页面重新进入。</Text>
          <Button className='mwal-primary-btn' onClick={goBack}>返回商家主页</Button>
        </View>
      )}

      {phase === 'forbidden' && (
        <View className='mwal-card' role='status'>
          <Text className='mwal-section-title'>仅商家主账号可用</Text>
          <Text className='mwal-hint'>钱包余额查询与手动提现仅限认证商家主账号（OWNER）操作，员工账号无权访问。</Text>
          <Button className='mwal-primary-btn' onClick={goBack}>返回</Button>
        </View>
      )}

      {phase === 'error' && (
        <View className='mwal-card' role='status'>
          <Text className='mwal-section-title'>钱包读取失败</Text>
          <Text className='mwal-alert'>{error || '请稍后重试。'}</Text>
          <Button className='mwal-primary-btn' onClick={load}>重新加载</Button>
        </View>
      )}

      {phase === 'ready' && wallet && <>
        {/* 结算账户（接收方）状态横幅 */}
        <Button
          id='mwal-receiver-banner'
          className={wallet.receiverActive ? 'mwal-banner' : 'mwal-banner mwal-banner-warn'}
          onClick={openReceiver}>
          <View className='mwal-banner-main'>
            <Text className='mwal-banner-title'>结算账户状态：{receiverLabel}</Text>
            <Text className='mwal-banner-sub'>
              {wallet.receiverActive
                ? (receiver?.bankName && receiver?.acctNoLast4
                  ? `${receiver.bankName}（尾号 ${receiver.acctNoLast4}） · 主动提款模式`
                  : '接收方已生效 · 主动提款模式，点击查看只读详情')
                : '接收方未生效前暂不可提现，请联系平台运营办理开通与绑定'}
            </Text>
          </View>
          <Text className='mwal-link-chevron'>›</Text>
        </Button>

        {/* 余额卡片（渠道实时余额 + 待结算金额与笔数；严禁展示平台补贴待结算） */}
        <View className='mwal-hero'>
          <Text className='mwal-hero-label'>可提现余额（{wallet.currency}）</Text>
          <Text id='mwal-available-balance' className='mwal-hero-amount'>
            {wallet.channelBalanceAvailable ? `¥${wallet.availableBalance}` : '暂不可查'}
          </Text>
          <Text className='mwal-hero-meta'>
            {wallet.channelBalanceAvailable
              ? `余额以拉卡拉接收方账户实时查询为准 · 更新于 ${formatBeijingDateTime(wallet.balanceAsOf)}`
              : '渠道余额查询暂不可用，请点击刷新重试'}
          </Text>
          <View className='mwal-hero-split'>
            <View className='mwal-hero-stat'>
              <Text className='mwal-hero-label'>待结算金额</Text>
              <Text id='mwal-pending-amount' className='mwal-hero-stat-val'>¥{wallet.pendingSettlementAmount}</Text>
            </View>
            <View className='mwal-hero-stat'>
              <Text className='mwal-hero-label'>待结算笔数</Text>
              <Text id='mwal-pending-count' className='mwal-hero-stat-val'>{wallet.pendingSettlementCount} 笔</Text>
            </View>
          </View>
        </View>

        {/* 手动提现表单（仅金额输入，无短信验证码，X-Request-Id 幂等） */}
        <View className='mwal-card'>
          <Text className='mwal-section-title'>手动提现（仅主账号）</Text>
          <Text className='mwal-hint'>提现至已绑定的拉卡拉接收方收款银行卡（D1 提现），提交后由渠道处理并异步回执。</Text>

          {pendingAmount && (
            <View className='mwal-info-box' role='status'>
              <Text>检测到上次提现请求（¥{pendingAmount}）结果尚未确认，点击下方按钮将沿用原请求编号重试，不会重复扣款。</Text>
            </View>
          )}

          <View className='mwal-input-row'>
            <Text className='mwal-yen'>¥</Text>
            <Input
              id='mwal-withdraw-amount'
              className='mwal-input'
              type='digit'
              value={pendingAmount ?? amountInput}
              disabled={submitting || preview || pendingAmount !== null || !wallet.receiverActive || !wallet.channelBalanceAvailable}
              placeholder='请输入提现金额'
              onInput={event => {
                setAmountInput(event.detail.value)
                setError('')
              }}
            />
            {pendingAmount === null && wallet.receiverActive && wallet.channelBalanceAvailable && !preview && (
              <Button
                id='mwal-withdraw-all'
                className='mwal-all-btn'
                onClick={() => {
                  setAmountInput(wallet.availableBalance)
                  setError('')
                }}>全部提现</Button>
            )}
          </View>

          {error && <View className='mwal-alert' role='alert'><Text>{error}</Text></View>}

          {receipt && (
            <View className='mwal-success-box' role='status'>
              <Text>
                提现申请已提交（单号 {receipt.merOrderNo} · 金额 ¥{receipt.amount} · 状态：
                {WITHDRAWAL_STATUS_LABELS[receipt.status] ?? receipt.status}
                {receipt.netAmount ? ` · 预计实到 ¥${receipt.netAmount}` : ''}）。
              </Text>
            </View>
          )}

          <Button
            id='mwal-withdraw-submit'
            className='mwal-primary-btn'
            disabled={submitting || preview || !wallet.receiverActive || !wallet.channelBalanceAvailable}
            onClick={() => void submitWithdraw()}>
            {submitting
              ? '正在提交提现…'
              : pendingAmount
                ? `重试原提现请求（¥${pendingAmount}）`
                : '确认提现'}
          </Button>
          <Button id='mwal-refresh' className='mwal-ghost-btn' disabled={submitting} onClick={load}>刷新余额</Button>
        </View>

        {/* 二级入口：提现记录 + 结算账户状态（只读） */}
        <View className='mwal-link-card'>
          <Button id='mwal-go-withdrawals' className='mwal-link-row' onClick={openWithdrawals}>
            <Text className='mwal-link-label'>提现记录</Text>
            <Text className='mwal-link-chevron'>›</Text>
          </Button>
          <Button id='mwal-go-receiver' className='mwal-link-row' onClick={openReceiver}>
            <Text className='mwal-link-label'>结算账户状态（只读）</Text>
            <Text className='mwal-link-chevron'>›</Text>
          </Button>
        </View>

        <Text className='mwal-preview-note'>
          {preview ? '只读预览：本地样例数据，不发起真实提现请求。' : '资金由拉卡拉分账接收方账户托管，可提现余额以渠道查询为准。'}
        </Text>
      </>}
    </View>
  </View>
}
