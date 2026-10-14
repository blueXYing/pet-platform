// 成为服务者入口模块（32号裁决 §2 七页批次）。设计源：129:14566（落地半屏面板）+
// 129:15547（服务类型下拉展开态），见登记表 §4.16。产品口径：这是 C 端转商家入驻的
// 入口页 + 服务类型选择页，选择结果按既有 merchant-application 契约的参数形态
// （merchantTypeCode）传入既有申请页；本模块不造第二套入驻后端、不新增任何接口。
// 服务类型选项 = 既有申请契约的 merchantTypeOptions 唯一来源（复用 import，不复制
// 字典），设计稿下拉选项为「请选择」占位文案——选项集以契约为准（登记偏差）。
import { merchantTypeOptions } from '../../merchant-application/model'

export type ProviderTypeCode = typeof merchantTypeOptions[number][0]

export const providerTypeChoices: readonly { code: ProviderTypeCode; label: string }[] =
  merchantTypeOptions.map(([code, label]) => ({ code, label }))

export function isProviderTypeCode(value: unknown): value is ProviderTypeCode {
  return typeof value === 'string' && merchantTypeOptions.some(([code]) => code === value)
}

export function providerTypeLabel(code: string): string {
  const found = merchantTypeOptions.find(([option]) => option === code)
  return found ? found[1] : ''
}

export function providerTypeHint(code: string | null): string {
  return code && providerTypeLabel(code) ? providerTypeLabel(code) : providerCopy.selectPlaceholder
}

// 既有申请页入口：merchantTypeCode 为既有 DraftInput 字段（非新契约）。
export function applicationEntryUrl(code: ProviderTypeCode): string {
  return `/consumer/pages/merchant-application/index?merchantTypeCode=${code}`
}

// 两页间选择回传：service-type 页选定后暂存并 navigateBack，落地页 useDidShow 取回
// （一次取回即清空；微信 page 间无返回参数通道，模块暂存为最小实现，node 测试直接驱动）。
let stagedType: ProviderTypeCode | null = null
export function stageProviderType(code: ProviderTypeCode): void {
  stagedType = code
}
export function takeStagedProviderType(): ProviderTypeCode | null {
  const value = stagedType
  stagedType = null
  return value
}
// 兜底：无落地页在栈内（URL 直入 service-type）时，选定即转既有申请页。
export function serviceTypePageUrl(current: ProviderTypeCode | null): string {
  return `/consumer/pages/become-provider/service-type${current ? `?current=${current}` : ''}`
}

// 文案基线：标题/简介/按钮沿 129:14566 原稿；说明行与主按钮动作为契约对齐口径
// （原稿「与开店商家是两种不同身份」的独立跑腿身份不在 V1 契约内，登记偏差）。
export const providerCopy = {
  title: '成为服务者',
  intro: '利用空余时间接单，为附近的宠友提供遛狗、上门喂养等服务，时间灵活、按单结算。',
  note: '入驻通过商家申请流程受理：填写资料并上传营业执照等材料，审核通过后在线签署。',
  selectLabel: '服务类型 *',
  selectPlaceholder: '请选择',
  primary: '进入入驻申请',
  toastPickType: '请先选择服务类型',
  typePageTitle: '服务类型',
} as const
