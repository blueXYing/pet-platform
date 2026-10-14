// 积分商城展示态模型（32号裁决补充第二节第 1 条：V1 积分只赚取/扣回、不消费——
// 商品可陈列，兑换动作不可用，不产生任何积分扣减，页面文案明示暂未开放）。
// 纯数据 + 纯投影，无 Taro 依赖，node 测试直接驱动本模块。
// V1 无商品后端：商品为设计源 128:2614 的前端设计样例（designSamples 先例，门店页/
// 服务详情页的做法），不虚构真实感数据、不发起任何商品请求；积分余额读现有 points
// 读侧（coupon-points 仓库，券/积分页既有），本页无任何写路径。

export type MallGoodView = Readonly<{
  /** 设计样例 id（非服务端实体 id，仅用于渲染 key 与 ariaLabel）。 */
  goodId: string
  name: string
  /** 所需积分（展示数字，无兑换语义）。 */
  points: string
  /** 库存文案（原稿逐字：如 "库存 12"，售罄卡为 "库存 0"）。 */
  stock: string
  /** 原稿仅首卡带「热销」角标。 */
  hot: boolean
  /** 动作文案（原稿："兑换"×5 / "已兑完"×1）；展示态一律渲染为禁用置灰。 */
  action: 'exchange' | 'sold-out'
  /** 对应切图（assets/manifest.json 登记的 2x 原切图）。 */
  image: string
}>

// 展示态口径文案（页面必须明示，32号裁决第二节第 1 条）。
export const mallPageCopy = {
  title: '积分商城',
  balanceLabel: '当前积分',
  earn: '赚积分',
  /** 未登录/读取失败时的余额占位。 */
  balanceUnavailable: '—',
  loginHint: '登录后可查看积分余额',
  /** 明示条：兑换暂未开放（32号裁决）。 */
  notice: '兑换暂未开放：以下商品仅供展示，V1 积分仅可通过赚取获得，不支持积分消费、兑换或抵现。',
  /** 卡片按钮的统一无障碍说明（兑换动作不可用）。 */
  actionDisabledHint: '兑换暂未开放',
  previewNote: '只读展示：商品为设计样例（无商品后端，不发起真实请求）；积分余额读本地夹具。兑换暂未开放。',
  realNote: '页面说明：商品为设计样例（V1 无商品后端）；积分余额为真实只读查询。兑换暂未开放，V1 积分只赚取、不消费。',
} as const

// 设计源 128:2614 六件样例商品（名称/积分/库存/角标逐字取自原稿；图片为一比一切图）。
// 售罄卡（猫咪玩具逗猫棒）动作原文案「已兑完」；展示态下全部动作与「兑换」一致置灰禁用。
export const mallGoods: readonly MallGoodView[] = [
  { goodId: '1', name: '宠物零食大礼包', points: '500', stock: '库存 12', hot: true, action: 'exchange', image: '1' },
  { goodId: '2', name: '宠物梳毛器套装', points: '300', stock: '库存 8', hot: false, action: 'exchange', image: '2' },
  { goodId: '3', name: '宠物牵引绳', points: '200', stock: '库存 20', hot: false, action: 'exchange', image: '3' },
  { goodId: '4', name: '猫咪玩具逗猫棒', points: '150', stock: '库存 0', hot: false, action: 'sold-out', image: '4' },
  { goodId: '5', name: '宠物洗澡毛巾', points: '100', stock: '库存 30', hot: false, action: 'exchange', image: '5' },
  { goodId: '6', name: '宠物饮水碗', points: '250', stock: '库存 15', hot: false, action: 'exchange', image: '6' },
]

// 展示态投影：动作一律禁用置灰（不区分「兑换/已兑完」，均无交互）；文案保留原稿差异。
export function mallActionLabel(good: MallGoodView): string {
  return good.action === 'sold-out' ? '已兑完' : '兑换'
}
