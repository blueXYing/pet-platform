// M-002 排期工作台视图纯函数（P-11a 视觉层，2026-11-11）。设计源 Figma 12:6214「首页-排期
// 管理」：该帧原稿是周循环模板 + 固定 1 小时槽模型（registry §4 / pending-issues P-11b，CCR
// 红线），本轮只取其呈现 token（胶囊 DIV-65/72：白底 #FFFFFF、描边 #C0ECFF 0.96、圆角 7.68、
// 文字 Roboto 11.03 w700 #5BAAE8；日标题 12.47 w700 #1A1A2E；分隔线 #E5E7EB 1；分区卡白底
// r15.35）。分组维度按契约改为「北京历日」——窗口仍是分钟级半开区间（SSOT §12.1，无固定 60
// 分钟槽），时长/文案全部按真实分钟数呈现，禁止任何「对齐整点/按小时切」的渲染假设。
// 本文件只做展示层推导：不解码、不发请求、不触碰 model.ts/repository.ts 的契约语义。
import { splitBeijingParts, type ScheduleWindowItem, type WindowStatus } from './model'

/** 日期分组：date = 窗口 startAt 的北京历日（YYYY-MM-DD），items 保持服务端顺序。 */
export type WindowDayGroup = Readonly<{ date: string; weekday: string; items: readonly ScheduleWindowItem[] }>

const WEEKDAY_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'] as const
/** 北京历日的星期文案（model.ts 系把 YYYY-MM-DD 当 UTC 日处理，这里同口径取 getUTCDay）。 */
export function weekdayLabel(date: string): string {
  const parsed = new Date(`${date}T00:00:00.000Z`)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(parsed.getTime())) return ''
  return WEEKDAY_LABELS[parsed.getUTCDay()]
}

/** 分组头文案：`2026-11-12 周六`（跨年跨月都用完整日期，读列表不依赖上下文推断）。 */
export function dayGroupLabel(date: string): string {
  return weekdayLabel(date) ? `${date} ${weekdayLabel(date)}` : date
}

/** 真实分钟时长（分钟级半开区间的端点差；model 已保证 endAt > startAt）。 */
export function windowDurationMinutes(startAt: string, endAt: string): number {
  return Math.round((Date.parse(endAt) - Date.parse(startAt)) / 60000)
}

/** 时长文案按真实分钟数直读：45分钟 / 1小时 / 1小时30分钟——不做整点对齐或槽位换算。 */
export function durationMinutesText(minutes: number): string {
  if (!Number.isSafeInteger(minutes) || minutes <= 0) return ''
  if (minutes < 60) return `${minutes}分钟`
  const hours = Math.floor(minutes / 60), rest = minutes % 60
  return rest === 0 ? `${hours}小时` : `${hours}小时${rest}分钟`
}

/** 胶囊时间文案：同历日 `HH:mm - HH:mm`；跨历日用两端 `MM-dd HH:mm`（半开区间，分钟级）。 */
export function capsuleTimeText(startAt: string, endAt: string): string {
  const start = splitBeijingParts(startAt), end = splitBeijingParts(endAt)
  if (start.date === end.date) return `${start.time} - ${end.time}`
  return `${start.date.slice(5).replace('-', '.')} ${start.time} ~ ${end.date.slice(5).replace('-', '.')} ${end.time}`
}

/** 窗口按 startAt 的北京历日分组（服务端 §3.3 按 start_at 升序，分组保持到达顺序；
 *  跨日窗口归入起始日一组，胶囊文案展示完整跨日区间）。 */
export function groupWindowsByDate(items: readonly ScheduleWindowItem[]): readonly WindowDayGroup[] {
  const groups: { date: string; items: ScheduleWindowItem[] }[] = []
  const byDate = new Map<string, { date: string; items: ScheduleWindowItem[] }>()
  for (const item of items) {
    const date = splitBeijingParts(item.startAt).date
    let group = byDate.get(date)
    if (!group) { group = { date, items: [] }; byDate.set(date, group); groups.push(group) }
    group.items.push(item)
  }
  return groups.map(group => ({ date: group.date, weekday: weekdayLabel(group.date), items: group.items }))
}

// 状态胶囊变体沿用 model.ts 的类名约定（Taro 4.1.5 丢弃动态 data-*，变体必须是显式类名；
// 未知值回落基类，绝无 enum 拼接）。OPEN 取 Figma 原胶囊（白底/#C0ECFF 描边/#5BAAE8 文字），
// SOLD_OUT/CLOSED 用本页既有状态色系映射到同一胶囊形状。
export function windowCapsuleClass(status: WindowStatus): string {
  if (status === 'OPEN') return 'sch-cap sch-cap-open'
  if (status === 'SOLD_OUT') return 'sch-cap sch-cap-soldout'
  if (status === 'CLOSED') return 'sch-cap sch-cap-closed'
  return 'sch-cap'
}

/** 三态数字卡的数字色变体（同上：显式类名，未知回落基类）。 */
export function statNumberClass(status: WindowStatus): string {
  if (status === 'OPEN') return 'sch-v-stat-num sch-v-stat-num-open'
  if (status === 'SOLD_OUT') return 'sch-v-stat-num sch-v-stat-num-soldout'
  if (status === 'CLOSED') return 'sch-v-stat-num sch-v-stat-num-closed'
  return 'sch-v-stat-num'
}
