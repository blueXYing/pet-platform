import { Button, Image, ScrollView, Text, Textarea, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { useEffect, useState } from 'react'
import { dateText, decisionText, opinionOptions, statusText } from '../../aftersale/model'
import { MerchantAfterSaleShell, MerchantAfterSaleState, useMerchantAfterSale } from './page'

/** Figma detail 40:1345 and reply sheet 40:1500; no merchant decision/refund command. */
export default function MerchantAfterSaleDetail() {
  const route = useRouter()
  const caseId = typeof route.params.afterSaleId === 'string' && /^[1-9][0-9]{0,18}$/.test(route.params.afterSaleId)
    && BigInt(route.params.afterSaleId) <= 9223372036854775807n ? route.params.afterSaleId : undefined
  const { controller, state, style } = useMerchantAfterSale(caseId, !!caseId)
  const [sheet, setSheet] = useState<'opinion' | 'evidence' | null>(null)
  const [pickerNotice, setPickerNotice] = useState('')
  // Remounted pending writes reopen their original action, with immutable input.
  useEffect(() => { if (state.pending) setSheet(state.pending.action) }, [state.pending])
  useEffect(() => { if (state.notice === '已提交，平台将结合双方证据处理。') setSheet(null) }, [state.notice])
  useEffect(() => { if (state.status === 'entry' || state.status === 'denied' || state.status === 'idle') { setSheet(null); setPickerNotice('') } }, [state.status])
  const detail = state.detail
  const replyAllowed = controller.canReply()
  const locked = state.busy || !!state.pending || state.uploadPending
  async function chooseImage() {
    if (locked || !replyAllowed) return
    setPickerNotice('')
    try {
      const choice = await Taro.chooseImage({ count: 1, sizeType: ['original'], sourceType: ['album', 'camera'] })
      if (choice.tempFilePaths[0]) await controller.upload(choice.tempFilePaths[0])
    } catch (error) {
      const message = error instanceof Error ? error.message : String((error as { errMsg?: string } | null)?.errMsg || '')
      if (!/cancel/i.test(message)) setPickerNotice('图片选择失败，请重试。')
    }
  }
  if (!caseId) return <MerchantAfterSaleShell title='售后详情' style={style}><View className='mas-state'>工单编号无效，请从售后列表进入。</View></MerchantAfterSaleShell>
  return <MerchantAfterSaleShell title='售后详情' style={style}>
    {state.status !== 'ready' || !detail ? <MerchantAfterSaleState controller={controller} status={state.status} text={state.notice} /> : <>
      <ScrollView className='mas-detail' scrollY enhanced showScrollbar={false}>
        {state.frozen && <View className='mas-alert'>商家或门店已冻结，当前只能查看工单和证据。</View>}
        <View className='mas-detail-card'>
          <View className='mas-line'><Text className='mas-tag' data-status={detail.status}>{statusText[detail.status]}</Text><Text className='mas-date'>工单 {detail.afterSaleId}</Text></View>
          <Text className='mas-heading'>订单 {detail.orderId}</Text>
          <Text className='mas-type'>申请于 {dateText(detail.createdAt)}</Text>
          <View className='mas-amount-row'><Text>用户诉求金额</Text><Text className='mas-amount'>{detail.requestedAmount === null ? '未填写' : `¥${detail.requestedAmount}`}</Text></View>
        </View>
        <View className='mas-detail-card'>
          <Text className='mas-section-title'>申请信息</Text>
          <View className='mas-field'><Text>问题类型</Text><Text>{detail.typeCode}</Text></View>
          <View className='mas-field'><Text>用户诉求</Text><Text>{detail.demandCode}</Text></View>
          <View className='mas-field'><Text>发起窗口截止</Text><Text>{dateText(detail.deadline)}</Text></View>
        </View>
        <View className='mas-detail-card'>
          <Text className='mas-section-title'>售后申请</Text>
          <Text className='mas-description'>{detail.description}</Text>
          {detail.newProblemStatement && <><Text className='mas-small-title'>新问题说明</Text><Text className='mas-description'>{detail.newProblemStatement}</Text></>}
          {detail.priorFinalCaseIds.length > 0 && <Text className='mas-help'>同单历史终局：{detail.priorFinalCaseIds.join('、')}</Text>}
        </View>
        {detail.supplementRequestId && <View className='mas-detail-card mas-supplement'>
          <Text className='mas-section-title'>当前补证要求 · {detail.supplementTarget === 'MERCHANT' ? '商家' : '用户'}</Text>
          <Text className='mas-description'>{detail.supplementReason}</Text>
          <Text className='mas-help'>截止 {dateText(detail.supplementDeadline)}（北京时间，须在截止前提交）</Text>
          {detail.supplementTarget === 'USER' && <Text className='mas-help'>商家可提交意见或证据；用户补证要求仍由平台处理。</Text>}
        </View>}
        <View className='mas-detail-card'>
          <Text className='mas-section-title'>双方证据与意见</Text>
          {detail.evidence.length === 0 && <Text className='mas-help'>暂无入卷证据</Text>}
          {detail.evidence.map((batch, index) => <View className='mas-evidence-batch' key={batch.batchId}>
            <View className='mas-evidence-marker'><Text>{index + 1}</Text></View>
            <View className='mas-evidence-content'>
              <Text className='mas-small-title'>{batch.submitterType === 'MERCHANT' ? '商家' : '用户'} · {dateText(batch.submittedAt)}</Text>
              {batch.opinionCode && <Text className='mas-help'>{opinionOptions.find(item => item.code === batch.opinionCode)?.label}</Text>}
              {batch.text && <Text className='mas-description'>{batch.text}</Text>}
              <View className='mas-evidence-assets'>{batch.assetIds.map((assetId, assetIndex) => <Button key={assetId} id={`mas-evidence-${batch.batchId}-${assetId}`}
                className='mas-private-image' disabled={state.busy} onClick={() => void controller.readEvidence(batch.batchId, assetId)}>查看证据{assetIndex + 1}</Button>)}</View>
            </View>
          </View>)}
        </View>
        {(detail.decisionType || detail.decisionReason) && <View className='mas-detail-card'>
          <Text className='mas-section-title'>平台处理结果</Text>
          {detail.decisionType && <Text className='mas-small-title'>{decisionText[detail.decisionType]}</Text>}
          <Text className='mas-description'>{detail.decisionReason}</Text>
        </View>}
        {!replyAllowed && !state.pending && <Text className='mas-help mas-readonly'>当前工单只读。补证期限已到时，请刷新查看平台更新后的状态。</Text>}
        {state.notice && <View className='mas-notice' role='status'>{state.notice}</View>}
        <Button id='mas-detail-refresh' className='mas-more' disabled={state.busy} onClick={() => void controller.load()}>刷新详情</Button>
        <View className='mas-bottom-space' />
      </ScrollView>
      <View className='mas-detail-footer'>
        <Button id='mas-open-evidence' className='mas-secondary' disabled={!replyAllowed || state.busy || !!state.pending && state.pending.action !== 'evidence'} onClick={() => setSheet('evidence')}>补充证据</Button>
        <Button id='mas-open-opinion' className='mas-primary' disabled={!replyAllowed || state.busy || !!state.pending && state.pending.action !== 'opinion'} onClick={() => setSheet('opinion')}>提交意见</Button>
      </View>
      {sheet && <View className='mas-overlay'>
        <Button className='mas-sheet-dismiss' ariaLabel='关闭填写面板' disabled={state.busy} onClick={() => setSheet(null)} />
        <View className='mas-sheet' role='dialog' ariaLabel={sheet === 'opinion' ? '商家意见' : '补充证据'}>
          <View className='mas-sheet-handle' />
          <Text className='mas-sheet-title'>{sheet === 'opinion' ? '商家意见' : '补充证据'}</Text>
          <Text className='mas-sheet-description'>{state.pending ? '上次提交结果待确认，按原内容重试。' : '说明相关事实，平台将结合双方意见和证据处理。'}</Text>
          {sheet === 'opinion' && <View className='mas-opinions'>{opinionOptions.map(option => <Button key={option.code} id={`mas-opinion-${option.code}`} disabled={locked}
            className='mas-opinion-choice' data-selected={state.draft.opinionCode === option.code ? 'true' : 'false'} onClick={() => controller.setDraft({ opinionCode: option.code })}>{option.label}</Button>)}</View>}
          <Textarea id='mas-reply-text' className='mas-textarea' value={state.draft.text} maxlength={500} disabled={locked} autoHeight={false}
            placeholder={sheet === 'opinion' ? '请填写意见说明（10至500字）' : '请填写补证说明（10至500字）或上传图片'} onInput={event => controller.setDraft({ text: event.detail.value })} />
          <Text className='mas-text-count'>{state.draft.text.length}/500</Text>
          <View className='mas-uploaded'>{state.draft.assetIds.map((assetId, index) => <View className='mas-upload-item' key={assetId}><Text>证据图片 {index + 1}</Text>
            <Button disabled={locked} ariaLabel={`移除证据图片${index + 1}`} onClick={() => controller.removeAsset(assetId)}>移除</Button></View>)}</View>
          {state.uploadPending ? <Button id='mas-upload-retry' className='mas-secondary' disabled={state.busy} onClick={() => void controller.upload()}>恢复或重试原图片</Button>
            : <Button id='mas-upload' className='mas-upload' disabled={locked || state.draft.assetIds.length >= 6 || !replyAllowed} onClick={() => void chooseImage()}>上传证据图片（最多6张）</Button>}
          <Text className='mas-help'>仅JPEG、PNG，每张不超过10MiB。商家意见由平台处理。</Text>
          {(state.notice || pickerNotice) && <View className='mas-notice' role='status'>{pickerNotice || state.notice}</View>}
          <Button id='mas-submit-reply' className='mas-primary mas-submit' disabled={state.busy || state.uploadPending || !replyAllowed && !state.pending}
            onClick={() => void controller.submit(sheet)}>{state.busy ? '正在处理…' : state.pending ? '重试原提交' : sheet === 'opinion' ? '提交意见' : '提交补证'}</Button>
          <Button className='mas-close-sheet' disabled={state.busy} onClick={() => setSheet(null)}>关闭</Button>
        </View>
      </View>}
      {state.image && <View className='mas-image-overlay' role='dialog' ariaLabel='入卷证据图片'>
        <Image className='mas-evidence-image' mode='aspectFit' src={state.image} />
        <Button id='mas-close-image' className='mas-primary' onClick={() => controller.closeImage()}>关闭证据</Button>
      </View>}
    </>}
  </MerchantAfterSaleShell>
}
