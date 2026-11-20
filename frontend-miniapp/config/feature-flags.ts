export type FeatureFlagEnv = Readonly<Record<string, string | undefined>>

// 2026-11-19 用户裁决：商家入驻与材料上传默认启用，不再要求每次构建显式设置功能变量。
// 仅在没有后端 API Origin（请求无法接通）或部署方显式 PET_...=false 时关闭。
export function merchantApplicationEnabled(env: FeatureFlagEnv): boolean {
  return Boolean(env.PET_C_API_ORIGIN) && env.PET_MERCHANT_APPLICATION_ENABLED !== 'false'
}

export function privateMaterialUploadEnabled(env: FeatureFlagEnv): boolean {
  return Boolean(env.PET_C_API_ORIGIN) && env.PET_PRIVATE_MATERIAL_UPLOAD_ENABLED !== 'false'
}