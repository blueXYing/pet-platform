import type { OrderDetailView } from '../../orders/model'

// 确认支付半屏的展示文案（43 号 §2，Figma 690:4837）。纯函数、不依赖 Taro，供单测直接引用。

/** 小程序唯一支付方式：HTTP 契约 10「C 端小程序 V1.0 不展示支付方式选择」，channel 固定 WECHAT_MINI_PROGRAM。 */
export const PAY_METHOD_WECHAT = '微信支付' as const

/** 摘要卡首行：下单时快照的店铺名 / 服务名（§3.7 名称增补）；订单无快照值时退回通用文案。 */
export function paySummaryLabels(
  detail: Pick<OrderDetailView, 'storeName' | 'serviceName'>,
): Readonly<{ store: string; service: string }> {
  return { store: detail.storeName ?? '预约服务订单', service: detail.serviceName ?? '预约服务' }
}
