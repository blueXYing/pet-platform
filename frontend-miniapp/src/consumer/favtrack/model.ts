// 收藏+浏览足迹切片（32号裁决第二节批次，Contract 58 / CCR-C-FAVTRACK-001）。本文件是
// C 端收藏/足迹投影契约口径的唯一替换点：契约已定稿（docs/04-api/58-Favorite-Footprint-
// Contract-v0.1.md，10号 §3.16，11号 OpenAPI cListMyFavorites/cAddFavorite/cCancelFavorite/
// cGetFavoriteStatus/cListMyFootprints/cRecordFootprint/cDeleteFootprint，开关
// pet.favtrack.http.enabled 默认关闭）。字段事实：
// - targetType 恰为 SERVICE|STORE（V1 C 端可收藏/可浏览目标只有服务与门店两目录）；
// - 列表行：行标识 + targetType/targetId + 目录展示投影（targetAvailable/title/categoryLabel/
//   salePrice 两位小数串/storeId/coverUrl；目标下架行保留 targetAvailable=false 展示列 null，
//   取消/删除仍可用）+ 时间列（收藏 favoritedAt / 足迹 lastViewedAt，ISO 毫秒时间戳）；
// - 固定排序：收藏 created_at DESC, id DESC；足迹 last_viewed_at DESC, id DESC（重复浏览刷新
//   置顶，(user,target) 去重）；
// - 状态探针：未收藏 200 favoriteId=null（绝不 404）；跨会话只看本人（防枚举）；
// - 写命令：POST 加收藏/上报足迹（首建 201/重放与业务重复 200）、DELETE 取消/删足迹（缺席
//   仍 200），全部无业务体，X-Request-Id 终态 UUID 幂等（23号 §5）。
// 设计源（用户端 key bp2vpcjjA5vZbHvtKkA8wl 本机缓存 favtrack/）：128:3200 收藏列表、
// 129:3484 足迹列表（今天/昨天/更早分组行）、690:11109 足迹详情（=门店详情形态）。原稿的
// 服务人员卡与"动态"行不在 V1 目标范围（20号登记表 §4.13 登记偏差），不实现。

export type TrackTargetType = 'SERVICE' | 'STORE'
export const trackTargetTypes: readonly TrackTargetType[] = ['SERVICE', 'STORE']
export const isTrackTargetType = (value?: string): value is TrackTargetType =>
  (trackTargetTypes as readonly string[]).includes(value || '')
export const targetTypeLabels: Record<TrackTargetType, string> = { SERVICE: '服务', STORE: '商家' }

export type TrackTargetDisplay = Readonly<{
  targetAvailable: boolean
  title: string | null
  categoryLabel: string | null
  salePrice: string | null
  storeId: string | null
  coverUrl: string | null
}>

export type FavoriteRow = Readonly<{
  favoriteId: string
  targetType: TrackTargetType
  targetId: string
  favoritedAt: string
  display: TrackTargetDisplay
}>

export type FootprintRow = Readonly<{
  footprintId: string
  targetType: TrackTargetType
  targetId: string
  lastViewedAt: string
  display: TrackTargetDisplay
}>

export type FavoritePage = Readonly<{ items: readonly FavoriteRow[]; page: number; pageSize: number; total: number }>
export type FootprintPage = Readonly<{ items: readonly FootprintRow[]; page: number; pageSize: number; total: number }>

export type FavoriteStatusView = Readonly<{ favoriteId: string | null; favoritedAt: string | null }>
export type FavoriteToggleReceipt = Readonly<{ favoriteId: string; created: boolean }>
export type FavoriteCancelReceipt = Readonly<{ removed: boolean }>
export type FootprintRecordReceipt = Readonly<{ footprintId: string; created: boolean }>
export type FootprintDeleteReceipt = Readonly<{ deleted: boolean }>

const isoTimestamp = (value: string): boolean => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z(?![\s\S])/.test(value)
const amount = (value: string): boolean => /^(?:0|[1-9][0-9]{0,15})\.[0-9]{2}(?![\s\S])/.test(value)

const invalid = (): never => { throw new Error('INVALID_RESPONSE') }
/** 严格 exact-key：只允许契约键集合；未知键直接失败关闭（沿 coupon-points 惯例）。 */
function exactObject(value: unknown, keys: readonly string[]): Record<string, any> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid()
  const record = value as Record<string, unknown>
  for (const key of Object.keys(record)) if (!keys.includes(key)) invalid()
  return value as Record<string, any>
}
function requiredField(record: Record<string, any>, key: string): any {
  if (!(key in record)) invalid()
  return record[key]
}
function nullableText(value: any, min: number, max: number): string | null {
  if (value === null || value === undefined) return null
  if (typeof value !== 'string' || [...value].length < min || [...value].length > max) invalid()
  return value
}
function nullablePattern(value: any, pattern: (value: string) => boolean): string | null {
  if (value === null || value === undefined) return null
  if (typeof value !== 'string' || !pattern(value)) invalid()
  return value
}
const isIdText = (value: any): string => {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}(?![\s\S])/.test(value) || BigInt(value) > 9223372036854775807n) invalid()
  return value
}
const nullableIdText = (value: any): string | null => value === null || value === undefined ? null : isIdText(value)
function decodePageNumbers(record: Record<string, any>): { page: number; pageSize: number; total: number } {
  const page = Number(record.page), pageSize = Number(record.pageSize), total = Number(record.total)
  if (!Number.isInteger(page) || page < 1 || page > 10000) invalid()
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50) invalid()
  if (!Number.isInteger(total) || total < 0) invalid()
  return { page, pageSize, total }
}
function decodeTargetType(value: any): TrackTargetType {
  if (!isTrackTargetType(typeof value === 'string' ? value : undefined)) invalid()
  return value
}
/** 行上的展示列解码（行级 exactObject 已校验键集，这里只解码字段，供扁平行共用）。 */
function decodeDisplayFields(v: Record<string, any>): TrackTargetDisplay {
  const targetAvailable = requiredField(v, 'targetAvailable')
  if (typeof targetAvailable !== 'boolean') invalid()
  return {
    targetAvailable,
    title: nullableText(v.title, 1, 128),
    categoryLabel: nullableText(v.categoryLabel, 1, 32),
    salePrice: nullablePattern(v.salePrice, amount),
    storeId: nullableIdText(v.storeId),
    coverUrl: nullableText(v.coverUrl, 1, 1024),
  }
}
function decodeTimestamp(value: any, nullable = false): string {
  if (nullable && (value === null || value === undefined)) return null as unknown as string
  if (typeof value !== 'string' || !isoTimestamp(value)) invalid()
  return value
}

const favoriteItemKeys = ['favoriteId', 'targetType', 'targetId', 'targetAvailable', 'title', 'categoryLabel', 'salePrice', 'storeId', 'coverUrl', 'favoritedAt']
const footprintItemKeys = ['footprintId', 'targetType', 'targetId', 'targetAvailable', 'title', 'categoryLabel', 'salePrice', 'storeId', 'coverUrl', 'lastViewedAt']

export function decodeFavoriteRow(value: unknown): FavoriteRow {
  const v = exactObject(value, favoriteItemKeys)
  return {
    favoriteId: isIdText(requiredField(v, 'favoriteId')),
    targetType: decodeTargetType(requiredField(v, 'targetType')),
    targetId: isIdText(requiredField(v, 'targetId')),
    favoritedAt: decodeTimestamp(requiredField(v, 'favoritedAt')),
    display: decodeDisplayFields(v),
  }
}

export function decodeFavoritePage(value: unknown): FavoritePage {
  const v = exactObject(value, ['items', 'page', 'pageSize', 'total'])
  if (!Array.isArray(v.items) || v.items.length > 50) invalid()
  const numbers = decodePageNumbers(v)
  const items = v.items.map(decodeFavoriteRow)
  // 固定排序 created_at DESC, id DESC（§3.16）：毫秒时间戳同长可字典序比较；同毫秒按 id 数值降序。
  for (let index = 1; index < items.length; index++) {
    const newer = items[index - 1]!, older = items[index]!
    if (newer.favoritedAt < older.favoritedAt) invalid()
    if (newer.favoritedAt === older.favoritedAt && !(BigInt(newer.favoriteId) > BigInt(older.favoriteId))) invalid()
  }
  return { items, ...numbers }
}

export function decodeFootprintRow(value: unknown): FootprintRow {
  const v = exactObject(value, footprintItemKeys)
  return {
    footprintId: isIdText(requiredField(v, 'footprintId')),
    targetType: decodeTargetType(requiredField(v, 'targetType')),
    targetId: isIdText(requiredField(v, 'targetId')),
    lastViewedAt: decodeTimestamp(requiredField(v, 'lastViewedAt')),
    display: decodeDisplayFields(v),
  }
}

export function decodeFootprintPage(value: unknown): FootprintPage {
  const v = exactObject(value, ['items', 'page', 'pageSize', 'total'])
  if (!Array.isArray(v.items) || v.items.length > 50) invalid()
  const numbers = decodePageNumbers(v)
  const items = v.items.map(decodeFootprintRow)
  // 固定排序 last_viewed_at DESC, id DESC（重复浏览刷新置顶后的服务端序）。
  for (let index = 1; index < items.length; index++) {
    const newer = items[index - 1]!, older = items[index]!
    if (newer.lastViewedAt < older.lastViewedAt) invalid()
    if (newer.lastViewedAt === older.lastViewedAt && !(BigInt(newer.footprintId) > BigInt(older.footprintId))) invalid()
  }
  return { items, ...numbers }
}

export function decodeFavoriteStatus(value: unknown): FavoriteStatusView {
  const v = exactObject(value, ['targetType', 'targetId', 'favoriteId', 'favoritedAt'])
  decodeTargetType(requiredField(v, 'targetType'))
  isIdText(requiredField(v, 'targetId'))
  const favoriteId = nullableIdText(v.favoriteId)
  const favoritedAt = v.favoritedAt === null || v.favoritedAt === undefined ? null : decodeTimestamp(v.favoritedAt)
  if ((favoriteId !== null) !== (favoritedAt !== null)) invalid()
  return { favoriteId, favoritedAt }
}

export function decodeFavoriteAddReceipt(value: unknown): FavoriteToggleReceipt {
  const v = exactObject(value, ['favoriteId', 'targetType', 'targetId', 'favoritedAt', 'favorited', 'created'])
  isIdText(requiredField(v, 'favoriteId'))
  decodeTargetType(requiredField(v, 'targetType'))
  isIdText(requiredField(v, 'targetId'))
  decodeTimestamp(requiredField(v, 'favoritedAt'))
  if (requiredField(v, 'favorited') !== true) invalid()
  const created = requiredField(v, 'created')
  if (typeof created !== 'boolean') invalid()
  return { favoriteId: v.favoriteId, created }
}

export function decodeFavoriteCancelReceipt(value: unknown): FavoriteCancelReceipt {
  const v = exactObject(value, ['targetType', 'targetId', 'removed'])
  decodeTargetType(requiredField(v, 'targetType'))
  isIdText(requiredField(v, 'targetId'))
  const removed = requiredField(v, 'removed')
  if (typeof removed !== 'boolean') invalid()
  return { removed }
}

export function decodeFootprintRecordReceipt(value: unknown): FootprintRecordReceipt {
  const v = exactObject(value, ['footprintId', 'targetType', 'targetId', 'lastViewedAt', 'created'])
  isIdText(requiredField(v, 'footprintId'))
  decodeTargetType(requiredField(v, 'targetType'))
  isIdText(requiredField(v, 'targetId'))
  decodeTimestamp(requiredField(v, 'lastViewedAt'))
  const created = requiredField(v, 'created')
  if (typeof created !== 'boolean') invalid()
  return { footprintId: v.footprintId, created }
}

export function decodeFootprintDeleteReceipt(value: unknown): FootprintDeleteReceipt {
  const v = exactObject(value, ['footprintId', 'deleted'])
  isIdText(requiredField(v, 'footprintId'))
  const deleted = requiredField(v, 'deleted')
  if (typeof deleted !== 'boolean') invalid()
  return { deleted }
}

const pad = (value: number): string => String(value).padStart(2, '0')

/** 足迹时间呈现（D6 同积分流水口径，UTC 日历日）：今天/昨天 → "HH:mm"，更早 → "M月D日 HH:mm"。 */
export function formatTrackClock(now: Date, timestamp: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(timestamp)
  if (!match) return ''
  const created = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]), Number(match[4]), Number(match[5]), 0))
  if (Number.isNaN(created.getTime())) return ''
  const utc = (date: Date): number => Math.floor(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()) / 86400000)
  const diffDays = utc(now) - utc(created)
  const clock = `${pad(created.getUTCHours())}:${pad(created.getUTCMinutes())}`
  if (diffDays === 0) return clock
  if (diffDays === 1) return `昨天 ${clock}`
  const monthDay = `${created.getUTCMonth() + 1}月${created.getUTCDate()}日`
  return created.getUTCFullYear() === now.getUTCFullYear() ? `${monthDay} ${clock}` : `${created.getUTCFullYear()}年${monthDay} ${clock}`
}

export type FootprintGroup = Readonly<{ key: 'today' | 'yesterday' | 'earlier'; label: string; rows: readonly FootprintRow[] }>

/** 足迹列表按 今天/昨天/更早 分组（129:3484 原稿形态；UTC 日历日，同 D6 口径）。 */
export function groupFootprints(now: Date, rows: readonly FootprintRow[]): FootprintGroup[] {
  const utc = (date: Date): number => Math.floor(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()) / 86400000)
  const today = utc(now)
  const groups: { key: FootprintGroup['key']; label: string; rows: FootprintRow[] }[] = [
    { key: 'today', label: '今天', rows: [] },
    { key: 'yesterday', label: '昨天', rows: [] },
    { key: 'earlier', label: '更早', rows: [] },
  ]
  for (const row of rows) {
    const match = /^(\d{4})-(\d{2})-(\d{2})T/.exec(row.lastViewedAt)
    if (!match) continue
    const day = utc(new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))))
    const bucket = today - day <= 0 ? 0 : today - day === 1 ? 1 : 2
    groups[bucket]!.rows.push(row)
  }
  return groups.filter(group => group.rows.length > 0)
}

/** 行主文案：目标不可用时按类型给中性占位（行保留，取消/删除仍可用）。 */
export function rowTitle(row: { targetType: TrackTargetType; display: TrackTargetDisplay }): string {
  if (row.display.title !== null) return row.display.title
  return row.targetType === 'SERVICE' ? '服务已下架' : '门店暂不可访问'
}

/** 行副文案：服务行 "类目 · ¥xx 起"，门店行 "门店"（目录投影无评分字段，不虚构原稿评分）。 */
export function rowSubtitle(row: { targetType: TrackTargetType; display: TrackTargetDisplay }): string {
  if (!row.display.targetAvailable) return '内容已不可用'
  if (row.targetType === 'SERVICE') {
    const parts: string[] = []
    if (row.display.categoryLabel !== null) parts.push(row.display.categoryLabel)
    if (row.display.salePrice !== null) parts.push(`¥${row.display.salePrice} 起`)
    return parts.length > 0 ? parts.join(' · ') : '宠物服务'
  }
  return '门店'
}
