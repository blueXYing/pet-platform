import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { useCallback, useState, type CSSProperties } from 'react'
import { consumerApi } from '../../../shared/consumer-runtime'
import { MerchantAdmissionRepository } from '../../../shared/merchant-repositories'
import {
  MerchantWalletClient, formatBeijingDateTime, merchantWalletErrorMessage,
  previewMerchantReceiver,
  type MerchantReceiverStatusView,
} from '../../../shared/merchant-wallet-api'
import './index.css'

// 商家结算账户（分账接收方）状态页（Contract 80 §5 / 40 号 §4.2 & §7 & §8）：
// - 仅认证商家主账号（OWNER）只读查询；
// - 严禁商家自助填写、编辑或提交收款银行卡/证件资料（全部由平台运营后台代填办理）。

function receiverBadgeClass(status: string, active: boolean): string {
  if (active || status === 'ACTIVE') return 'mwal-badge mwal-badge-ok'
  if (status === 'REJECTED') return 'mwal-badge mwal-badge-fail'
  return 'mwal-badge mwal-badge-warn'
}

export default function MerchantSettlementReceiverPage() {
  const route = useRouter()
  const preview = route.params.preview === '1'
  const fromWallet = route.params.from === 'wallet'
  const [info] = useState(() => Taro.getWindowInfo())
  const unit = info.windowWidth / 402
  const style = {
    '--mwal-status-top': `${info.statusBarHeight || 0}px`,
    '--mwal-unit': `${unit}px`,
  } as CSSProperties

  const [walletClient] = useState(() => new MerchantWalletClient(consumerApi))
  const [phase, setPhase] = useState<'loading' | 'entry' | 'forbidden' | 'error' | 'ready'>('loading')
  const [receiver, setReceiver] = useState<MerchantReceiverStatusView | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    setError('')
    if (preview) {
      setReceiver(previewMerchantReceiver())
      setPhase('ready')
      return
    }
    const current = consumerApi.scope.current
    if (!current || current.workspace !== 'merchant' || !current.merchantId || !current.storeId) {
      setPhase('entry')
      return
    }
    setPhase('loading')
    void new MerchantAdmissionRepository(consumerApi)
      .admission(current.merchantId, current.storeId)
      .then(async admission => {
        if (admission.membershipKind !== 'OWNER') {
          setPhase('forbidden')
          return
        }
        const view = await walletClient.getReceiver()
        setReceiver(view)
        setPhase('ready')
      })
      .catch(caught => {
        setError(merchantWalletErrorMessage(caught, '结算账户状态读取失败'))
        setPhase('error')
      })
  }, [preview, walletClient])

  useDidShow(load)

  function goBack() {
    const target = fromWallet ? '/merchant/pages/wallet/index' : '/merchant/pages/mine/index'
    void Taro.reLaunch({ url: `${target}${preview ? '?preview=1' : ''}` })
      .catch(() => { void Taro.showToast({ title: '返回失败，请重试', icon: 'none' }) })
  }

  return <View className='mwal-page' style={style}>
    <View className='mwal-status-area' />
    <View className='mwal-header'>
      <Button id='mwal-rcv-back' className='mwal-back-btn' onClick={goBack}>‹ 返回</Button>
      <Text className='mwal-title'>结算账户状态</Text>
      <View className='mwal-header-right' />
    </View>

    <View className='mwal-body'>
      {phase === 'loading' && (
        <View className='mwal-card' role='status'><Text className='mwal-hint'>正在查询结算账户状态…</Text></View>
      )}

      {phase === 'entry' && (
        <View className='mwal-card' role='status'>
          <Text className='mwal-section-title'>请从商家工作台进入</Text>
          <Button className='mwal-primary-btn' onClick={goBack}>返回</Button>
        </View>
      )}

      {phase === 'forbidden' && (
        <View className='mwal-card' role='status'>
          <Text className='mwal-section-title'>仅商家主账号可用</Text>
          <Text className='mwal-hint'>结算账户状态仅限认证商家主账号（OWNER）查看。</Text>
          <Button className='mwal-primary-btn' onClick={goBack}>返回</Button>
        </View>
      )}

      {phase === 'error' && (
        <View className='mwal-card' role='status'>
          <Text className='mwal-alert'>{error || '读取失败'}</Text>
          <Button className='mwal-primary-btn' onClick={load}>重新加载</Button>
        </View>
      )}

      {phase === 'ready' && receiver && <>
        <View className='mwal-card'>
          <View className='mwal-wd-head'>
            <Text className='mwal-section-title'>拉卡拉分账接收方</Text>
            <Text id='mwal-rcv-status' className={receiverBadgeClass(receiver.status, receiver.active)}>
              {receiver.statusLabel}
            </Text>
          </View>

          {receiver.status === 'UNCONFIGURED' && (
            <View className='mwal-info-box' role='status'>
              <Text>当前门店尚未开通分账接收方，请联系平台运营提交开户银行卡、证件及合作协议资料。</Text>
            </View>
          )}

          {receiver.rejectReason && (
            <View className='mwal-alert' role='alert'>
              <Text>渠道驳回原因：{receiver.rejectReason}</Text>
            </View>
          )}

          <View className='mwal-fact-row'>
            <Text className='mwal-fact-label'>生效状态</Text>
            <Text className='mwal-fact-value'>{receiver.active ? '已生效（可分账与提现）' : '未生效（到期分账将暂停等待）'}</Text>
          </View>
          <View className='mwal-fact-row'>
            <Text className='mwal-fact-label'>接收方编号</Text>
            <Text className='mwal-fact-value'>{receiver.receiverNo ?? '暂未生成'}</Text>
          </View>
          <View className='mwal-fact-row'>
            <Text className='mwal-fact-label'>收款户名（脱敏）</Text>
            <Text className='mwal-fact-value'>{receiver.acctNameMasked ?? '—'}</Text>
          </View>
          <View className='mwal-fact-row'>
            <Text className='mwal-fact-label'>收款银行卡尾号</Text>
            <Text className='mwal-fact-value'>{receiver.acctNoLast4 ? `**** ${receiver.acctNoLast4}` : '—'}</Text>
          </View>
          <View className='mwal-fact-row'>
            <Text className='mwal-fact-label'>开户银行</Text>
            <Text className='mwal-fact-value'>{receiver.bankName ?? '—'}</Text>
          </View>
          <View className='mwal-fact-row'>
            <Text className='mwal-fact-label'>提款模式</Text>
            <Text className='mwal-fact-value'>主动提款（商家手动发起）</Text>
          </View>
          <View className='mwal-fact-row'>
            <Text className='mwal-fact-label'>最近更新</Text>
            <Text className='mwal-fact-value'>{formatBeijingDateTime(receiver.updatedAt)}</Text>
          </View>
        </View>

        <View className='mwal-card'>
          <Text className='mwal-section-title'>资料变更说明（只读）</Text>
          <Text className='mwal-hint'>
            依据平台结算与合规规范，分账接收方的开通、银行卡/证件资料变更及分账关系绑定均由平台运营在后台统一代办。本页面仅提供只读状态查询，不支持商家自助填写或修改；如需变更收款账户，请联系平台运营协助处理。
          </Text>
        </View>

        <Button id='mwal-rcv-refresh' className='mwal-ghost-btn' onClick={load}>刷新状态</Button>

        <Text className='mwal-preview-note'>
          {preview ? '只读预览：本地样例结算账户状态。' : '数据来源：拉卡拉分账接收方台账（脱敏只读）。'}
        </Text>
      </>}
    </View>
  </View>
}
