import badgeNewUser from '../assets/marketing/badge-new-user.svg'
import badgeBeauty from '../assets/marketing/badge-beauty.svg'

// Figma 61:599 的卡片模板只负责版式与徽标图形；插画 URL 由后台投放的 coverUrl 提供，
// 前端不再按活动/标签内置狗狗或猫咪插画。新素材只需后台上传，模板仍由代码提供。
export const ACTIVITY_TEMPLATES = {
  new_user: { badge: badgeNewUser },
  beauty: { badge: badgeBeauty },
  // 2026-11-23 用户裁决：整卡图模式——coverUrl 即完整卡片视觉（纸框+插画从 Figma 一比一导出），
  // 前端只叠加徽章和文字，不再组件拼装。需要更换视觉时后台上传新整卡图即可。
  full_card: { badge: badgeNewUser },
} as const

export type ActivityTemplateCode = keyof typeof ACTIVITY_TEMPLATES

export function activityTemplate(code: string | null | undefined) {
  const key = (code || '').toLowerCase()
  return key in ACTIVITY_TEMPLATES ? ACTIVITY_TEMPLATES[key as ActivityTemplateCode] : null
}

export function activityTemplateCode(value: string | null | undefined): ActivityTemplateCode | 'generic' {
  const key = (value || '').toLowerCase()
  return key in ACTIVITY_TEMPLATES ? key as ActivityTemplateCode : 'generic'
}
