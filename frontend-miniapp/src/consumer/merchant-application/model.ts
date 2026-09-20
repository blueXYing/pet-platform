import { decodeDraft, type ApplicationDetail, type ApplicationResult, type DraftInput, type MerchantApplicationRepository } from '../../shared/merchant-repositories'
import type { WorkspaceScope } from '../../shared/workspace'
import { ApiError } from '../../shared/request'

export const merchantTypeOptions = [
  ['PET_LIFE_STORE', '宠物生活馆'], ['PET_HOSPITAL', '宠物医院'], ['PET_GROOMING', '宠物美容院'],
  ['PET_BOARDING', '宠物寄养中心'], ['PET_TRAINING', '宠物训练机构'], ['OTHER', '其他'],
] as const
export type MaterialKind = 'storePhotoAssetIds' | 'businessLicenseAssetId' | 'idCardFrontAssetId' | 'idCardBackAssetId' | 'industryLicenseAssetId'
export type City = { code: string; name: string }
export type Location = { address: string; longitude: string; latitude: string }
export type ApplicationRepository = Pick<MerchantApplicationRepository, 'current' | 'create' | 'save' | 'submit'>
export type ApplicationDependencies = {
  application: ApplicationRepository
  cities(): Promise<City[]>
  location(): Promise<Location | null>
  upload(kind: MaterialKind): Promise<{ assetId: string } | null>
}
export function emptyDraft(): DraftInput { return { storePhotoAssetIds: [] } }
export type FieldErrors = Partial<Record<keyof DraftInput, string>>
export function validateApplication(draft: DraftInput): FieldErrors {
  const errors: FieldErrors = {}
  const length = (value?: string | null) => [...(value?.trim() || '')].length
  if (length(draft.merchantName) < 2 || length(draft.merchantName) > 50) errors.merchantName = '商家名称须为2–50字'
  if (!/^[\u3400-\u9fff]{2,20}$/.test(draft.contactName?.trim() || '')) errors.contactName = '请填写2–20字中文姓名'
  if (!/^1\d{10}$/.test(draft.contactPhone || '')) errors.contactPhone = '请填写有效的11位手机号'
  if (draft.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(draft.email)) errors.email = '请填写有效的电子邮箱'
  if (!merchantTypeOptions.some(([code]) => code === draft.merchantTypeCode)) errors.merchantTypeCode = '请选择商家类型'
  if (!draft.cityCode) errors.cityCode = '请选择已开通城市'
  if (!draft.address || draft.longitude == null || draft.latitude == null) errors.address = '请选择店铺位置'
  if (length(draft.introduction) > 500) errors.introduction = '商家简介最多500字'
  if (!draft.storePhotoAssetIds?.length || draft.storePhotoAssetIds.length > 6) errors.storePhotoAssetIds = '请上传1–6张门店照片'
  if (!draft.businessLicenseAssetId) errors.businessLicenseAssetId = '请上传营业执照'
  if (!draft.idCardFrontAssetId) errors.idCardFrontAssetId = '请上传身份证人像面'
  if (!draft.idCardBackAssetId) errors.idCardBackAssetId = '请上传身份证国徽面'
  if (draft.merchantTypeCode === 'PET_HOSPITAL' && !draft.industryLicenseAssetId) errors.industryLicenseAssetId = '宠物医院须上传行业许可证'
  return errors
}
// Typed seams stay closed until each real adapter has been delivered and approved.
export function unavailableDependencies(): ApplicationDependencies {
  const unavailable = async (): Promise<never> => { throw new Error('APPLICATION_NOT_CONNECTED') }
  return { application: { current: unavailable, create: unavailable, save: unavailable, submit: unavailable },
    cities: async () => { throw new Error('CITY_NOT_CONNECTED') },
    location: async () => { throw new Error('LOCATION_NOT_CONNECTED') },
    upload: async () => { throw new Error('UPLOAD_NOT_CONNECTED') } }
}
export function applicationMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.statusCode === 422 && error.code === 'PRIVATE_ASSET_REJECTED') return '该材料已被安全检查拒绝，请点击原材料上传入口重新选择图片。'
    const statusMessages: Record<number, string> = {
      401: '登录已失效，请重新登录。', 403: '当前账号没有此操作权限。',
      404: '申请记录或服务暂不可用，请重新读取申请。',
      409: '申请版本或状态已变化，当前操作未确认完成，请先核对原操作结果。',
      503: '服务暂不可用；如已发起写入，请重试原操作以确认结果。',
    }
    if (statusMessages[error.statusCode]) return statusMessages[error.statusCode]
  }
  const message = error instanceof Error ? error.message : ''
  const messages: Record<string, string> = {
    APPLICATION_NOT_CONNECTED: '申请服务暂未接通，当前不能恢复、保存或提交申请。请稍后重试。',
    CITY_NOT_CONNECTED: '城市列表暂不可用，请稍后重试。', LOCATION_NOT_CONNECTED: '店铺定位暂不可用，请稍后重试。',
    UPLOAD_NOT_CONNECTED: '材料上传暂不可用，请稍后重试；当前没有上传任何文件。',
    UPLOAD_PENDING: '有材料上传结果尚未确认，请点击原材料上传入口重试。原文件和请求编号会保留；取消选图不会取消已发出的上传。',
    UPLOAD_FILE_CHANGED: '待确认上传的本地文件已变化，不能使用原请求编号上传其他内容。请保留当前记录并联系支持。',
    UPLOAD_FILE_INVALID: '图片须为1字节至10MiB，请重新选择。',
    UPLOAD_JOURNAL_INVALID: '上传恢复记录无法校验，请保留记录并联系支持。',
    LOCATION_PERMISSION_DENIED: '未获得位置或隐私授权，请在小程序设置中检查授权后重试。',
    LOCATION_UNAVAILABLE: '地图选点暂不可用，请稍后重试；尚未更新店铺位置。',
    LOCATION_INVALID: '地图返回的位置无效，请重新选择店铺位置。',
    PENDING_WRITE_CHANGED: '上次操作结果尚未确认，请先重试原操作，勿重复提交。',
    NO_CONTEXT: '请先登录后继续申请。', COMMON_UNAUTHORIZED: '登录已失效，请重新登录。',
    WORKSPACE_PATH_MISMATCH: '请返回宠物主工作区后继续申请。',
  }
  return messages[message] || '操作未完成，请重试或稍后再试。未确认成功前不会显示申请已提交。'
}
/** All repository writes retain ConsumerApi request IDs; no replacement keys on errors. */
export class ApplicationCommands {
  private flight: Promise<ApplicationResult> | null = null
  private fingerprint = ''
  constructor(private repository: ApplicationRepository, private scope: WorkspaceScope) {}
  save(current: ApplicationResult | null, draft: DraftInput): Promise<ApplicationResult> {
    const fingerprint = JSON.stringify([current, draft])
    if (this.flight) return fingerprint === this.fingerprint ? this.flight : Promise.reject(new Error('PENDING_WRITE_CHANGED'))
    const ticket = this.scope.capture()
    if (ticket.context.workspace !== 'consumer') return Promise.reject(new Error('WORKSPACE_PATH_MISMATCH'))
    const snapshot = decodeDraft(JSON.parse(JSON.stringify(draft)))
    this.fingerprint = fingerprint
    this.flight = (async () => {
      const result = current ? await this.repository.save(current.applicationId, current.version, snapshot) : await this.repository.create(snapshot)
      ticket.assertCurrent(); return result
    })().finally(() => { this.flight = null })
    return this.flight
  }
  async submit(saved: ApplicationResult) {
    const ticket = this.scope.capture()
    if (ticket.context.workspace !== 'consumer') throw new Error('WORKSPACE_PATH_MISMATCH')
    const result = await this.repository.submit(saved.applicationId, saved.version, saved.currentRevisionId)
    ticket.assertCurrent(); return result
  }
}
export function editableApplication(detail: ApplicationResult | ApplicationDetail | null) {
  return !detail || detail.status === 'DRAFT' || detail.status === 'REJECTED'
}
