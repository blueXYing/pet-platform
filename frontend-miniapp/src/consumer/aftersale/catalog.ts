import { decodeOptions, type AfterSaleOption } from '../../shared/aftersale-api'

export type AfterSaleCatalog = { types: AfterSaleOption[]; demands: AfterSaleOption[] }
export type AfterSaleCatalogPort = () => Promise<AfterSaleCatalog | null>
export function validateCatalog(catalog: AfterSaleCatalog | null): AfterSaleCatalog {
  if (!catalog) throw new Error('AFTERSALE_OPTIONS_UNAVAILABLE')
  const options = decodeOptions({ typeOptions: catalog.types, demandOptions: catalog.demands })
  return { types: options.typeOptions, demands: options.demandOptions }
}
export function catalogSelectionErrors(catalog: AfterSaleCatalog | null, draft: { typeCode: string; demandCode: string }) {
  if (!catalog || !catalog.types.length || !catalog.demands.length) return { typeCode: '申请选项暂不可用，请稍后再试', demandCode: '申请选项暂不可用，请稍后再试' }
  return {
    ...(!catalog.types.some(item => item.code === draft.typeCode) ? { typeCode: '请重新选择当前可用的问题类型' } : {}),
    ...(!catalog.demands.some(item => item.code === draft.demandCode) ? { demandCode: '请重新选择当前可用的诉求' } : {}),
  }
}
