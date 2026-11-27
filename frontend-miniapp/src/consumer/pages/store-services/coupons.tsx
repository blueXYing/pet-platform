import { Button, Text, View } from '@tarojs/components'
import type { CouponCampaignView } from '../../marketing/model'
import { formatSalePrice } from '../../service/model'

/**
 * 「店铺优惠券」区块（79 号契约 §5 的 C 端入口；2026-11-27 用户裁决：放在商家详情页，
 * 顾客逛到哪家店就领哪家的券）。没有 Figma 原稿：沿用 690:6660 的卡片规范（门店信息 /
 * 服务项目卡）与服务行的几何——左侧名称、门槛、领取截止，右侧面额与胶囊按钮。
 */

/** 最多展示 3 张；接口按 endAt 升序，最临近结束的在前。 */
export const STORE_COUPON_LIMIT = 3

// Card frame, same arithmetic as the service list: head 50, rows of 81.5 with a 12 gap,
// bottom pad 16. A "more" tail line adds 18 + its 8 margin.
export function storeCouponCardHeight(shown: number, total: number): number {
  if (shown <= 0) return 0
  return 50 + shown * 81.5 + (shown - 1) * 12 + 16 + (total > shown ? 26 : 0)
}

/** 面额：DECIMAL(18,2) 去掉多余的 0，与服务价格同一规则；无面额时退回券类型文案。 */
export function couponAmountText(coupon: CouponCampaignView): string {
  return coupon.amountOff === null ? (coupon.typeLabel ?? '店铺券') : `¥${formatSalePrice(coupon.amountOff)}`
}

/** 领取成功提示（72 号领券回执的 amountOff；为空时只报成功）。 */
export function claimSuccessTitle(amountOff: string | null): string {
  return amountOff === null ? '领取成功' : `领取成功 ¥${formatSalePrice(amountOff)}`
}

/** 使用门槛：商家券只有无门槛券与满减券（41 号 §5）；只可用于本商家的订单（79 号 §6.1）。 */
export function couponThresholdText(thresholdAmount: string | null): string {
  const rule = thresholdAmount === null || Number(thresholdAmount) <= 0 ? '无门槛' : `满 ¥${formatSalePrice(thresholdAmount)} 可用`
  return `${rule} · 仅限本商家门店使用`
}

/** 领取截止：按设备本地时区显示到日；无法解析时返回 null（不展示，不编造）。 */
export function couponDeadlineText(endAt: string): string | null {
  const time = Date.parse(endAt)
  if (!Number.isFinite(time)) return null
  const day = new Date(time)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `可领至 ${day.getFullYear()}-${pad(day.getMonth() + 1)}-${pad(day.getDate())}`
}

export type CouponButtonState = Readonly<{ label: string; disabled: boolean }>

/**
 * 按钮以服务端判定为准（79 号 §5）：`canClaim` 已含每人限领上限与预算/总量，
 * 未达上限可再次领取；`reason` 只决定不可领时的文案。
 */
export function couponButton(coupon: CouponCampaignView, claiming: boolean): CouponButtonState {
  if (claiming) return { label: '领取中…', disabled: true }
  if (coupon.canClaim) return { label: coupon.claimed ? '再领一张' : '领取', disabled: false }
  const label = coupon.reason === 'CLAIMED' ? '已领取'
    : coupon.reason === 'SOLD_OUT' || coupon.reason === 'BUDGET_EXHAUSTED' ? '已抢完'
      : '暂不可领'
  return { label, disabled: true }
}

export function StoreCouponCard({ coupons, claiming, onClaim }: {
  coupons: readonly CouponCampaignView[]
  /** The campaign whose claim is in flight. */
  claiming: string | null
  onClaim: (coupon: CouponCampaignView) => void
}) {
  const shown = coupons.slice(0, STORE_COUPON_LIMIT)
  return <View id='svc-coupon' className='svc-coupon-inner'>
    <Text className='svc-coupon-title'>店铺优惠券</Text>
    <View className='svc-coupon-rows'>
      {shown.map(coupon => {
        const button = couponButton(coupon, claiming === coupon.campaignId)
        const deadline = couponDeadlineText(coupon.endAt)
        const threshold = couponThresholdText(coupon.thresholdAmount)
        return <View key={coupon.campaignId} id={`svc-coupon-${coupon.campaignId}`} className='svc-coupon-row'
          ariaLabel={`${coupon.name}，${couponAmountText(coupon)}，${threshold}`}>
          <View className='svc-coupon-main'>
            <Text className='svc-coupon-name'>{coupon.name}</Text>
            <Text className='svc-coupon-desc'>{threshold}</Text>
            {deadline !== null ? <Text className='svc-coupon-sold'>{deadline}</Text> : null}
          </View>
          <View className='svc-coupon-side'>
            <Text className='svc-coupon-amount'>{couponAmountText(coupon)}</Text>
            <Button id={`svc-coupon-claim-${coupon.campaignId}`} className='svc-coupon-btn' disabled={button.disabled}
              onClick={() => onClaim(coupon)}><Text>{button.label}</Text></Button>
          </View>
        </View>
      })}
      {coupons.length > shown.length
        ? <Text className='svc-coupon-more'>仅展示前 {shown.length} 张，共 {coupons.length} 张</Text>
        : null}
    </View>
  </View>
}
