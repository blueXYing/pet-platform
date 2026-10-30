import { PLACEMENT_POSITION_HOT_SERVICE_TOP, type PlacementCard } from '../marketing/model'

// 热门服务二级页纯函数与契约 Mock（74 §3.7 / 71 v0.3 §3.7/§10，F-C-03）。本模块无 Taro 依赖，
// node 单测可直接驱动；页面接线在 pages/store-services/hot-services.tsx。

/** 74 §3.7：banner 按 `sortWeight ASC, placementId ASC` 取第一条（服务端已按该序返回；
 *  空数组 = 无启用投放位 → 页面隐藏 banner，不伪造）。 */
export function pickHotServiceBanner(items: readonly PlacementCard[]): PlacementCard | null {
  return items.length > 0 ? items[0]! : null
}

const idPattern = /^[1-9][0-9]{0,18}$/
// 74 §2.1 categoryCode 词法（与 stores 页 isCategoryCode 同口径：词法非法的落地参数不跳转）。
const categoryCodePattern = /^[A-Z][A-Z0-9_]{1,31}$/

function objectParam(payload: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(payload)
    return value && typeof value === 'object' && !Array.isArray(value)
      ? value as Record<string, unknown> : null
  } catch { return null }
}

function idParam(payload: string, key: string): string | null {
  const field = objectParam(payload)?.[key]
  return typeof field === 'string' && idPattern.test(field) ? field : null
}

function categoryParam(payload: string): string | null {
  const field = objectParam(payload)?.categoryCode
  return typeof field === 'string' && categoryCodePattern.test(field) ? field : null
}

/**
 * 74 §3.7 banner 点击跳转——投放位既有跳转规则的 C 端承接子集：
 * CAMPAIGN/STORE/SERVICE/SERVICE_CATEGORY 四类落既有路由；COMMUNITY/MINIAPP_PATH 等在 C 端
 * 无既有承接路由的类型 V1 不跳转（不新增承接面、不自行扩展白名单）。jumpParams 解析失败、
 * 键缺失或词法非法一律返回 null（点击不跳转，fail-closed，不猜参）。
 */
export function placementJumpUrl(placement: Readonly<{ jumpType: string; jumpParams: string }>): string | null {
  switch (placement.jumpType) {
    case 'CAMPAIGN': {
      const campaignId = idParam(placement.jumpParams, 'campaignId')
      return campaignId && `/consumer/pages/marketing/campaign?campaignId=${encodeURIComponent(campaignId)}`
    }
    case 'STORE': {
      const storeId = idParam(placement.jumpParams, 'storeId')
      return storeId && `/consumer/pages/store-services/index?preview=0&storeId=${encodeURIComponent(storeId)}`
    }
    case 'SERVICE': {
      const serviceId = idParam(placement.jumpParams, 'serviceId')
      return serviceId && `/consumer/pages/store-services/service-detail?preview=0&serviceId=${encodeURIComponent(serviceId)}`
    }
    case 'SERVICE_CATEGORY': {
      const categoryCode = categoryParam(placement.jumpParams)
      return categoryCode && `/consumer/pages/store-services/stores?preview=0&categoryCode=${encodeURIComponent(categoryCode)}`
    }
    default: return null
  }
}

// CONTRACT MOCK（preview=1 设计验收通道，74 §3.7 版式）：文案取 97:7825 设计稿样板，
// coverUrl=null → 页面渲染中性底版（不伪造图片素材）；跳转 NONE → 点击不跳。
// 真实数据一律走 C 端 placements（匿名读，71 §3.1）。
export const previewHotServiceBanner: PlacementCard = {
  placementId: '880001', positionCode: PLACEMENT_POSITION_HOT_SERVICE_TOP, campaignId: '880000',
  title: '优惠活动商家', subtitle: '精选优质商家优惠，点击查看详情',
  tagCode: 'HOT', tagName: '限时优惠 · 先到先得',
  coverUrl: null, templateCode: null,
  jumpType: 'NONE', jumpParams: '{}',
  startAt: '2026-10-30T00:00:00Z', endAt: '2026-11-30T00:00:00Z',
}
