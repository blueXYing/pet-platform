// 百科读侧视图模型（契约 62 / PRD 5.1.4）：类型、严格解码、搜索关键词归一与高亮切分。
// 纯函数、无平台依赖；列表页与详情页共用，node 测试直接驱动本模块。
// 偏差登记（§4.16）：设计稿分类 tab 4 枚与 PRD 枚举冲突，按 PRD 原文五分类；
// 榜单胶囊为设计占位，落地为品种标签筛选（PRD 品种标签用于筛选）。
export type CategoryCode = 'DOG' | 'CAT' | 'REPTILE' | 'BIRD' | 'INSECT'
export const CATEGORY_CODES: readonly CategoryCode[] = ['DOG', 'CAT', 'REPTILE', 'BIRD', 'INSECT']
export const isCategoryCode = (value: unknown): value is CategoryCode =>
  CATEGORY_CODES.includes(value as CategoryCode)

export type EncyclopediaCategory = Readonly<{
  categoryCode: CategoryCode; categoryName: string; sortOrder: number
}>
export type BreedCard = Readonly<{
  breedId: string; categoryCode: CategoryCode; categoryName: string
  name: string; aliases: readonly string[]; tags: readonly string[]
  summary: string; coverUrl: string | null
}>
export type BreedRadar = Readonly<{
  companion: number; shedding: number; intelligence: number; easyCare: number; exercise: number
}>
export type BreedFact = Readonly<{ label: string; value: string }>
export type BreedDetail = BreedCard & Readonly<{
  bodyType: string | null
  facts: readonly BreedFact[]
  radar: BreedRadar | null
  descriptionHtml: string | null
  careAdviceHtml: string | null
  healthTipsHtml: string | null
  status: 'PUBLISHED'
  related: readonly BreedCard[]
}>
export type BreedPage = Readonly<{ items: BreedCard[]; page: number; pageSize: number; total: number }>
export type BaikePhase = 'loading' | 'ready' | 'load-error' | 'missing' | 'empty'

// PRD 5.1.4：搜索关键词 ≤40 字（去除首尾空格后按码点计）。
export const KEYWORD_LIMIT = 40
export const codePointLength = (value: string) => Array.from(value).length
export function normalizeKeyword(raw: string): string {
  const value = raw.trim()
  if (codePointLength(value) > KEYWORD_LIMIT) throw new Error('KEYWORD_TOO_LONG')
  return value
}

/** PRD：搜索结果高亮关键词——按关键词把文本切成 命中/未命中 片段序列。 */
export function highlightParts(text: string, keyword: string): Array<{ text: string; hit: boolean }> {
  const needle = keyword.trim().toLowerCase()
  if (!needle || !text.toLowerCase().includes(needle)) return [{ text, hit: false }]
  const parts: Array<{ text: string; hit: boolean }> = []
  const lower = text.toLowerCase()
  let cursor = 0
  while (cursor < text.length) {
    const hit = lower.indexOf(needle, cursor)
    if (hit < 0) { parts.push({ text: text.slice(cursor), hit: false }); break }
    if (hit > cursor) parts.push({ text: text.slice(cursor, hit), hit: false })
    parts.push({ text: text.slice(hit, hit + needle.length), hit: true })
    cursor = hit + needle.length
  }
  return parts
}

const invalid = (): never => { throw new Error('INVALID_RESPONSE') }
function exact(value: unknown, keys: readonly string[]): Record<string, any> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid()
  const record = value as Record<string, unknown>
  if (Object.keys(record).some(key => !keys.includes(key))) invalid()
  return value as Record<string, any>
}
const isId = (value: unknown): value is string =>
  typeof value === 'string' && /^[1-9][0-9]{0,18}(?![\s\S])/.test(value) && BigInt(value) <= 9223372036854775807n
const text = (value: any, max: number): string => {
  if (typeof value !== 'string' || [...value].length < 1 || [...value].length > max) invalid()
  return value
}
const textOrNull = (value: unknown, max: number): string | null => value === null ? null : text(value, max)
const stringArray = (value: unknown): readonly string[] => {
  if (!Array.isArray(value)) invalid()
  return (value as unknown[]).map((raw): string => {
    if (typeof raw !== 'string' || [...raw].length < 1 || [...raw].length > 50) invalid()
    return raw as string
  })
}

const categoryKeys = ['categoryCode', 'categoryName', 'sortOrder'] as const
export function decodeCategory(value: unknown): EncyclopediaCategory {
  const v = exact(value, categoryKeys)
  if (!isCategoryCode(v.categoryCode) || typeof v.sortOrder !== 'number' || !Number.isInteger(v.sortOrder)) invalid()
  return { categoryCode: v.categoryCode, categoryName: text(v.categoryName, 16), sortOrder: v.sortOrder }
}

const cardKeys = ['breedId', 'categoryCode', 'categoryName', 'name', 'aliases', 'tags', 'summary', 'coverUrl'] as const
export function decodeBreedCard(value: unknown): BreedCard {
  const v = exact(value, cardKeys)
  if (!isCategoryCode(v.categoryCode)) invalid()
  return {
    breedId: isId(v.breedId) ? v.breedId : invalid(),
    categoryCode: v.categoryCode,
    categoryName: text(v.categoryName, 16),
    name: text(v.name, 30),
    aliases: stringArray(v.aliases),
    tags: stringArray(v.tags),
    summary: text(v.summary, 120),
    coverUrl: textOrNull(v.coverUrl, 512),
  }
}

const detailKeys = [...cardKeys, 'bodyType', 'facts', 'radar', 'descriptionHtml', 'careAdviceHtml', 'healthTipsHtml', 'status', 'related'] as const
export function decodeBreedDetail(value: unknown): BreedDetail {
  const v = exact(value, detailKeys)
  const card = decodeBreedCard({ breedId: v.breedId, categoryCode: v.categoryCode, categoryName: v.categoryName, name: v.name, aliases: v.aliases, tags: v.tags, summary: v.summary, coverUrl: v.coverUrl })
  if (v.status !== 'PUBLISHED') invalid()
  if (!Array.isArray(v.facts)) invalid()
  const facts = (v.facts as unknown[]).map((fact: unknown) => {
    const f = exact(fact, ['label', 'value'])
    return { label: text(f.label, 16), value: text(f.value, 32) }
  })
  let radar: BreedRadar | null = null
  if (v.radar !== null) {
    const r = exact(v.radar, ['companion', 'shedding', 'intelligence', 'easyCare', 'exercise'])
    const axis = (raw: unknown): number => {
      if (typeof raw !== 'number' || !Number.isInteger(raw) || raw < 1 || raw > 5) invalid()
      return raw as number
    }
    radar = {
      companion: axis(r.companion), shedding: axis(r.shedding), intelligence: axis(r.intelligence),
      easyCare: axis(r.easyCare), exercise: axis(r.exercise),
    }
  }
  if (!Array.isArray(v.related)) invalid()
  return {
    ...card,
    bodyType: textOrNull(v.bodyType, 32),
    facts,
    radar,
    descriptionHtml: textOrNull(v.descriptionHtml, 6000),
    careAdviceHtml: textOrNull(v.careAdviceHtml, 4000),
    healthTipsHtml: textOrNull(v.healthTipsHtml, 4000),
    status: 'PUBLISHED',
    related: (v.related as unknown[]).map(decodeBreedCard),
  }
}

export function decodeBreedPage(value: unknown): BreedPage {
  const v = exact(value, ['items', 'page', 'pageSize', 'total'])
  if (!Array.isArray(v.items) || typeof v.page !== 'number' || typeof v.pageSize !== 'number'
    || typeof v.total !== 'number') invalid()
  return { items: v.items.map(decodeBreedCard), page: v.page, pageSize: v.pageSize, total: v.total }
}

/** 详情富文本安全白名单（契约 62）：仅 h3/h4/p/ul/li/strong 进渲染；其余剔除。 */
export function sanitizeRichText(html: string | null): string {
  if (!html) return ''
  const allowed = html.replace(/<(?!\/?(h3|h4|p|ul|li|strong)\b)[^>]*>/gi, '')
  return allowed
}

/** 适养能力图谱五维渲染序（原稿顺时针：顶 陪伴需求，右 掉毛程度，右下 聪明度…）。 */
export const radarAxes: ReadonlyArray<{ key: keyof BreedRadar; label: string }> = [
  { key: 'companion', label: '陪伴需求' },
  { key: 'shedding', label: '掉毛程度' },
  { key: 'intelligence', label: '聪明度' },
  { key: 'easyCare', label: '易养度' },
  { key: 'exercise', label: '运动需求' },
]

/** 详情 404 判定（契约 62：不存在/未发布/已下线统一 BREED_NOT_FOUND）。 */
export function isBreedNotFound(error: unknown): boolean {
  return typeof error === 'object' && error !== null
    && (error as { code?: unknown }).code === 'BREED_NOT_FOUND'
}

// 设计采样（245:5375 列表首屏）样例：五分类字典 + 狗狗陈列序首屏，preview 通道专用。
export const fixtureCategories: EncyclopediaCategory[] = [
  { categoryCode: 'DOG', categoryName: '狗狗', sortOrder: 1 },
  { categoryCode: 'CAT', categoryName: '猫咪', sortOrder: 2 },
  { categoryCode: 'REPTILE', categoryName: '爬宠', sortOrder: 3 },
  { categoryCode: 'BIRD', categoryName: '鸟类', sortOrder: 4 },
  { categoryCode: 'INSECT', categoryName: '昆虫', sortOrder: 5 },
]
export const fixtureCards: BreedCard[] = [
  {
    breedId: '711001', categoryCode: 'DOG', categoryName: '狗狗', name: '贵宾犬',
    aliases: ['泰迪', '迷你/玩具贵宾'], tags: ['聪明', '亲人', '少掉毛', '需美容'],
    summary: '很多人说的泰迪，通常是贵宾犬的一种常见造型。贵宾犬聪明伶俐、活泼亲人，是极佳的家庭伴侣犬。',
    coverUrl: 'https://cdn.petplatform.example/encyclopedia/dog-poodle.jpg',
  },
  {
    breedId: '711002', categoryCode: 'DOG', categoryName: '狗狗', name: '金毛寻回犬',
    aliases: ['金毛', '大暖男', '金毛毛'], tags: ['亲人', '温和', '大型犬', '掉毛多'],
    summary: '金毛是很多人心里的家庭犬代表，性格温和友善，对孩子极有耐心，是理想的家庭伴侣。',
    coverUrl: 'https://cdn.petplatform.example/encyclopedia/dog-golden.jpg',
  },
  {
    breedId: '711003', categoryCode: 'DOG', categoryName: '狗狗', name: '比熊犬',
    aliases: ['比熊', '棉花糖狗'], tags: ['亲人', '爱撒娇', '少掉毛', '需美容'],
    summary: '比熊看起来像棉花糖，性格通常亲人、爱撒娇，是非常受欢迎的伴侣犬品种。',
    coverUrl: 'https://cdn.petplatform.example/encyclopedia/dog-bichon.jpg',
  },
]
export const fixtureDetail: BreedDetail = {
  ...fixtureCards[0],
  bodyType: '小型/中型/大型',
  facts: [
    { label: '原产地', value: '法国/德国' },
    { label: '平均寿命', value: '12~15 年' },
    { label: '参考体重', value: '3~6 kg' },
  ],
  radar: { companion: 5, shedding: 1, intelligence: 5, easyCare: 3, exercise: 4 },
  descriptionHtml: '<h3>起源历史</h3><p>贵宾犬起源于欧洲水猎犬。</p>',
  careAdviceHtml: '<p>规律美容修剪与正向训练。</p>',
  healthTipsHtml: '<h4>常见健康问题</h4><p>注意膝关节脱位。</p>',
  status: 'PUBLISHED',
  related: [fixtureCards[1], fixtureCards[2]],
}
