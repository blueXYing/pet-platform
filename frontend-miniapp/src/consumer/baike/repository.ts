// 百科读侧仓库（契约 62）：真实链走匿名 GET（PRD「所有用户；未登录用户可浏览」，STR-D8
// 同款 anonymousRequest 通道），预览链走显式 PreviewBaikeRepository（无网络、无会话）。
import type { ConsumerApi } from '../../shared/consumer-api'
import {
  decodeBreedDetail, decodeBreedPage, decodeCategory,
  fixtureCards, fixtureCategories, fixtureDetail, normalizeKeyword,
  type BreedCard, type BreedDetail, type BreedPage, type CategoryCode, type EncyclopediaCategory,
} from './model'

export type BreedQuery = Readonly<{
  categoryCode: CategoryCode
  keyword?: string
  tag?: string | null
  page?: number
  pageSize?: number
}>

export class RealBaikeRepository {
  constructor(private api: ConsumerApi) {}

  categories(): Promise<EncyclopediaCategory[]> {
    return this.api.anonymousRequest({ method: 'GET', path: '/api/v1/c/encyclopedia/categories' }, value => {
      const envelope = value as { items: unknown }
      if (!envelope || typeof envelope !== 'object' || !Array.isArray(envelope.items)) throw new Error('INVALID_RESPONSE')
      return envelope.items.map(decodeCategory)
    })
  }

  async page(query: BreedQuery): Promise<BreedPage> {
    const data: Record<string, unknown> = {
      categoryCode: query.categoryCode,
      page: query.page ?? 1,
      pageSize: query.pageSize ?? 20,
    }
    const keyword = query.keyword ? normalizeKeyword(query.keyword) : ''
    if (keyword) data.keyword = keyword
    if (query.tag) data.tag = query.tag
    return this.api.anonymousRequest({ method: 'GET', path: '/api/v1/c/encyclopedia/breeds', data }, decodeBreedPage)
  }

  detail(breedId: string): Promise<BreedDetail> {
    // 契约 62：非契约形态 ID 直接拒绝（异步拒绝，调用方 await 收口）。
    if (!/^[1-9][0-9]{0,18}$/.test(breedId)) return Promise.reject(new Error('INVALID_RESPONSE'))
    return this.api.anonymousRequest(
      { method: 'GET', path: `/api/v1/c/encyclopedia/breeds/${breedId}` }, decodeBreedDetail)
  }
}

export type BaikeScenario = 'normal' | 'empty' | 'load-error' | 'missing'
export const isBaikeScenario = (value?: string): value is BaikeScenario =>
  ['normal', 'empty', 'load-error', 'missing'].includes(value || '')

// preview 仓库跨页共享但绝不跨工作区修订（同 address/store 模式）。
const previewStores = new WeakMap<object, { revision: number; repositories: Map<BaikeScenario, PreviewBaikeRepository> }>()
export function previewRepository(scope: { revision: number }, scenario: BaikeScenario): PreviewBaikeRepository {
  let entry = previewStores.get(scope)
  if (!entry || entry.revision !== scope.revision) {
    entry = { revision: scope.revision, repositories: new Map() }
    previewStores.set(scope, entry)
  }
  let repository = entry.repositories.get(scenario)
  if (!repository) {
    repository = new PreviewBaikeRepository(scenario === 'empty' ? [] : fixtureCards, scenario)
    entry.repositories.set(scenario, repository)
  }
  return repository
}

/** 显式 preview 仓库：纯内存种子，与真实链同语义（404 → missing，加载失败一次性）。 */
export class PreviewBaikeRepository {
  private breeds: BreedCard[]
  private failLoad: boolean
  constructor(breeds: BreedCard[] = fixtureCards, scenario: BaikeScenario = 'normal') {
    this.breeds = breeds.map(breed => ({ ...breed }))
    this.failLoad = scenario === 'load-error'
  }
  async categories(): Promise<EncyclopediaCategory[]> {
    return [...fixtureCategories]
  }
  async page(query: BreedQuery): Promise<BreedPage> {
    if (this.failLoad) { this.failLoad = false; throw new Error('PREVIEW_LOAD_FAILED') }
    const keyword = query.keyword ? normalizeKeyword(query.keyword).toLowerCase() : ''
    const items = this.breeds
      .filter(breed => breed.categoryCode === query.categoryCode)
      .filter(breed => !query.tag || breed.tags.includes(query.tag!))
      .filter(breed => !keyword
        || breed.name.toLowerCase().includes(keyword)
        || breed.aliases.some(alias => alias.toLowerCase().includes(keyword)))
    const page = query.page ?? 1
    const pageSize = query.pageSize ?? 20
    return {
      items: items.slice((page - 1) * pageSize, page * pageSize).map(breed => ({ ...breed })),
      page, pageSize, total: items.length,
    }
  }
  async detail(breedId: string): Promise<BreedDetail> {
    if (this.failLoad) { this.failLoad = false; throw new Error('PREVIEW_LOAD_FAILED') }
    const found = this.breeds.find(breed => breed.breedId === breedId)
    if (!found) throw new Error('BREED_NOT_FOUND')
    if (found.breedId !== fixtureDetail.breedId) return { ...fixtureDetail, ...found, related: [] }
    return { ...fixtureDetail, related: fixtureDetail.related.map(breed => ({ ...breed })) }
  }
}
