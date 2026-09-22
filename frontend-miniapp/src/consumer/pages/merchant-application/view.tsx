import { Button, Image, Input, Text, Textarea, View } from '@tarojs/components'
import type { DraftInput, ApplicationResult } from '../../../shared/merchant-repositories'
import { merchantTypeOptions, editableApplication, type City, type FieldErrors, type MaterialKind } from '../../merchant-application/model'
import back from './assets/back.png'
import select from './assets/select.png'
import location from './assets/location.png'
import photo from './assets/photo.png'
import document from './assets/document.png'
import './index.scss'

export type ApplicationViewProps = {
  draft: DraftInput; result: ApplicationResult | null; errors: FieldErrors; notice: string; busy: boolean
  preview: boolean; loading: boolean; loggedIn: boolean; locked?: boolean; typeOpen: boolean; cities: City[] | null; cityName: string
  edit: (key: keyof DraftInput, value: DraftInput[keyof DraftInput]) => void
  onBack: () => void; onTypes: () => void; onCity: () => void; onCitySelect: (city: City) => void
  onLocation: () => void; onUpload: (kind: MaterialKind) => void; onRemove: (kind: MaterialKind, assetId?: string) => void
  onSave: () => void; onSubmit: () => void; onReload: () => void; onLogin: () => void; onSigning: () => void; opinion?: string | null
}
export function MerchantApplicationView(p: ApplicationViewProps) {
  const actionDisabled = p.busy || p.loading || (!p.locked && !editableApplication(p.result)) || (!p.preview && !p.loggedIn)
  const disabled = actionDisabled || !!p.locked
  const error = (key: keyof DraftInput) => p.errors[key] ? <View className='application-error' role='alert'>{p.errors[key]}</View> : null
  const input = (key: keyof DraftInput, label: string, placeholder: string, max: number, type: 'text' | 'number' = 'text') => <View className='application-field'>
    <Text className='application-label'>{label}</Text>
    <Input id={`application-${key}`} className='application-input' type={type} value={String(p.draft[key] || '')} placeholder={placeholder} maxlength={max} disabled={disabled} ariaLabel={label} onInput={event => p.edit(key, event.detail.value)} adjustPosition />{error(key)}
  </View>
  const upload = (kind: MaterialKind, label: string, wide = false) => {
    const uploaded = kind === 'storePhotoAssetIds' ? p.draft.storePhotoAssetIds || [] : p.draft[kind] ? [p.draft[kind] as string] : []
    return <View className={`application-upload-group${wide ? ' application-upload-wide' : ''}`}>
      {uploaded.map((assetId, index) => <View key={assetId} className='application-material'><Text>{kind === 'storePhotoAssetIds' ? `照片 ${index + 1}` : label}已上传</Text><Button disabled={disabled} ariaLabel={`移除${label}${index + 1}`} onClick={() => p.onRemove(kind, assetId)}>移除</Button></View>)}
      {uploaded.length < (kind === 'storePhotoAssetIds' ? 6 : 1) && <Button id={`application-upload-${kind}`} disabled={disabled} className='application-upload' ariaLabel={label} onClick={() => p.onUpload(kind)}><Image src={kind === 'storePhotoAssetIds' ? photo : document} mode='scaleToFill' /><Text>{label}</Text></Button>}
    </View>
  }
  return <View className='application-design' data-state={p.loading ? 'loading' : p.result?.status || 'empty'}>
    <View className='application-header'><Button id='application-back' ariaLabel='返回' disabled={p.busy} onClick={p.onBack}><Image src={back} mode='scaleToFill' /></Button><Text>成为商家</Text></View>
    <View className='application-content'>
      <Text className='application-intro'>加入宠灵工，让你的店铺被更多宠友看见，获取海量服务订单。填写以下信息即可提交入驻申请。</Text>
      {p.loading && <View className='application-state' role='status'>正在读取申请…</View>}
      {!p.preview && !p.loggedIn && <View className='application-state'><Text>请先登录后继续申请</Text><Button onClick={p.onLogin}>去登录</Button></View>}
      {p.result && p.result.status !== 'DRAFT' && <View className='application-state' role='status'><Text>{p.result.status === 'REVIEWING' ? '申请审核中，请耐心等待' : p.result.status === 'APPROVED' ? '申请已审核通过，请阅读并签署商家协议' : '申请需修改，请根据审核意见完善后重新提交'}</Text>{p.opinion && <Text className='application-opinion'>{p.opinion}</Text>}<Text className='application-opinion'>{p.result.applicationNo}</Text>
        {p.result.status === 'APPROVED' && p.result.reservedMerchantId && <Button id='application-signing-entry' className='application-signing-entry' disabled={p.busy || p.loading} onClick={p.onSigning}>去签署协议</Button>}</View>}
      <View className='application-form'>
        {input('merchantName', '商家名称 *', '请输入商家/店铺名称', 50)}
        <View className='application-row'>{input('contactName', '联系人 *', '姓名', 20)}{input('contactPhone', '联系电话 *', '手机号', 11, 'number')}</View>
        {input('email', '电子邮箱', '用于接收审核结果', 254)}
        <View className='application-row'>
          <View className='application-field application-type-field'><Text className='application-label'>商家类型 *</Text><Button id='application-type' className='application-select' disabled={disabled} onClick={p.onTypes} ariaLabel='选择商家类型'><Text>{merchantTypeOptions.find(([key]) => key === p.draft.merchantTypeCode)?.[1] || '请选择'}</Text><Image src={select} mode='scaleToFill' /></Button>
            {p.typeOpen && !disabled && <View className='application-options'>{merchantTypeOptions.map(([key, label]) => <Button key={key} id={`application-type-${key}`} onClick={() => p.edit('merchantTypeCode', key)}>{label}</Button>)}</View>}{error('merchantTypeCode')}</View>
          <View className='application-field'><Text className='application-label'>所在城市 *</Text><Button id='application-city' className={`application-select ${p.cityName ? '' : 'application-placeholder'}`} disabled={disabled} onClick={p.onCity}>{p.cityName || '如：上海'}</Button>{error('cityCode')}</View>
        </View>
        {p.cities && <View className='application-city-options'>{p.cities.length ? p.cities.map(city => <Button key={city.code} disabled={disabled} onClick={() => p.onCitySelect(city)}>{city.name}</Button>) : <Text>暂无可选择的已开通城市</Text>}</View>}
        <View className='application-field'><Text className='application-label'>店铺定位 *</Text><View className='application-location-row'><View className={`application-input application-address ${p.draft.address ? '' : 'application-placeholder'}`}>{p.draft.address || '请选择定位，方便用户找到你的店铺'}</View><Button id='application-location' disabled={disabled} onClick={p.onLocation}><Image src={location} mode='scaleToFill' /><Text>定位</Text></Button></View>{error('address')}</View>
        <View className='application-field'><Text className='application-label'>商家简介</Text><Textarea id='application-introduction' className='application-textarea' value={p.draft.introduction || ''} placeholder='简单介绍你的主营业务、特色服务等（500字以内）' maxlength={500} disabled={disabled} ariaLabel='商家简介' onInput={event => p.edit('introduction', event.detail.value)} adjustPosition showConfirmBar={false} />{error('introduction')}</View>
        <View className='application-field'><Text className='application-label'>门店照片（1–6张）*</Text>{upload('storePhotoAssetIds', '上传')}{error('storePhotoAssetIds')}</View>
        <View className='application-field'><Text className='application-label'>营业执照 *</Text><View className='application-material-row'>{upload('businessLicenseAssetId', '上传执照', true)}<Text className='application-hint'>请上传清晰的营业执照照片</Text></View>{error('businessLicenseAssetId')}</View>
        <View className='application-field'><Text className='application-label'>身份证正反面 *</Text><View className='application-row application-id-row'>{upload('idCardFrontAssetId', '人像面', true)}{upload('idCardBackAssetId', '国徽面', true)}</View><Text className='application-hint'>请上传清晰、完整的身份证正反面照片</Text>{error('idCardFrontAssetId')}{error('idCardBackAssetId')}</View>
        {p.draft.merchantTypeCode === 'PET_HOSPITAL' && <View className='application-field'><Text className='application-label'>行业许可证 *</Text>{upload('industryLicenseAssetId', '上传许可证', true)}{error('industryLicenseAssetId')}</View>}
        <Button id='application-submit' className='application-submit' disabled={actionDisabled} onClick={p.onSubmit}>{p.busy ? '处理中…' : p.locked ? '重试原操作' : p.result?.status === 'REJECTED' ? '重新提交申请' : '提交申请'}</Button>
      </View>
      {p.notice && <View id='application-notice' className='application-notice' role='status'>{p.notice}</View>}
      <View className='application-secondary'><Button id='application-save' disabled={disabled} onClick={p.onSave}>保存草稿</Button><Button id='application-reload' disabled={p.busy || p.loading} onClick={p.onReload}>重新读取申请</Button></View>
      {p.preview && <Text className='application-preview-label'>交互预览 · 不会保存或提交申请</Text>}
    </View>
  </View>
}
