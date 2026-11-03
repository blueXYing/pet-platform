import { id, object, type ConsumerApi } from '../../shared/consumer-api'
import { apiOrigin } from '../../shared/consumer-runtime'
import defaultAvatar from '../assets/profile/avatar.png'

export type CommunityAuthor = Readonly<{ userId: string; nickname: string; avatarUrl: string }>
export type CommunityTopic = Readonly<{ topicCode: string; name: string; description: string | null; sortOrder: number }>
export type CommunityPost = Readonly<{
  postId: string; author: CommunityAuthor; title: string | null; content: string
  images: readonly string[]; topic: CommunityTopic | null
  likeCount: number; commentCount: number; favoriteCount: number; viewCount: number
  liked: boolean; favorited: boolean; followingAuthor: boolean; publishedAt: string | null
}>
export type CommunityComment = Readonly<{
  commentId: string; author: CommunityAuthor; content: string; createdAt: string
}>
export type CommunityPage<T> = Readonly<{ items: readonly T[]; page: number; pageSize: number; total: number }>
export type CommunityMedia = Readonly<{
  mediaId: string; url: string; contentType: 'image/png' | 'image/jpeg'
  bytes: number; width: number; height: number; sha256: string
}>
export type CommunityReceipt = Readonly<{ id: string; status: string; created: boolean; occurredAt: string }>
export type CommunityInteraction = Readonly<{ postId: string; active: boolean; count: number; changed: boolean }>

const fail = (): never => { throw new Error('INVALID_RESPONSE') }
const text = (value: unknown, max: number): string =>
  typeof value === 'string' && value.length > 0 && Array.from(value).length <= max ? value : fail()
const nullableText = (value: unknown, max: number): string | null =>
  value === null || value === undefined ? null
    : typeof value === 'string' && Array.from(value).length <= max ? value : fail()
const count = (value: unknown, max = 1_000_000_000): number =>
  typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= max ? value : fail()
const instant = (value: unknown): string | null => {
  if (value === null || value === undefined) return null
  if (typeof value !== 'string' || !Number.isFinite(Date.parse(value))) fail()
  return value as string
}
const bool = (value: unknown): boolean => value === true ? true : value === false ? false : fail()
const avatar = (value: string | null) => value ? assetUrl(value) : defaultAvatar

export function assetUrl(value: string): string {
  if (value.startsWith('/')) return apiOrigin ? apiOrigin + value : value
  return value
}

function authorOf(value: unknown): CommunityAuthor {
  const v = object(value)
  return {
    userId: id(v.userId),
    nickname: text(v.nickname, 64),
    avatarUrl: avatar(nullableText(v.avatarUrl, 1024)),
  }
}

function topicOf(value: unknown): CommunityTopic | null {
  if (value === null || value === undefined) return null
  const v = object(value)
  return {
    topicCode: text(v.topicCode, 32), name: text(v.name, 20),
    description: nullableText(v.description, 200), sortOrder: count(v.sortOrder, 9999),
  }
}

function postOf(value: unknown): CommunityPost {
  const v = object(value)
  if (!Array.isArray(v.images) || v.images.length > 9) fail()
  return {
    postId: id(v.postId), author: authorOf(v.author), title: nullableText(v.title, 60),
    content: text(v.content, 2000),
    images: v.images.map((image: unknown) => {
      const url = text(image, 1024)
      return assetUrl(url)
    }),
    topic: topicOf(v.topic), likeCount: count(v.likeCount), commentCount: count(v.commentCount),
    favoriteCount: count(v.favoriteCount), viewCount: count(v.viewCount),
    liked: bool(v.liked), favorited: bool(v.favorited), followingAuthor: bool(v.followingAuthor),
    publishedAt: instant(v.publishedAt),
  }
}

function commentOf(value: unknown): CommunityComment {
  const v = object(value)
  return {
    commentId: id(v.commentId), author: authorOf(v.author), content: text(v.content, 500),
    createdAt: text(v.createdAt, 64),
  }
}

function pageOf<T>(value: unknown, map: (item: unknown) => T, maxItems: number): CommunityPage<T> {
  const v = object(value)
  if (!Array.isArray(v.items) || v.items.length > maxItems) fail()
  return {
    items: v.items.map(map), page: count(v.page, 10000), pageSize: count(v.pageSize, 50),
    total: count(v.total),
  }
}

function receiptOf(value: unknown): CommunityReceipt {
  const v = object(value)
  return {
    id: id(v.id), status: text(v.status, 24), created: bool(v.created),
    occurredAt: text(v.occurredAt, 64),
  }
}

function interactionOf(value: unknown): CommunityInteraction {
  const v = object(value)
  return { postId: id(v.postId), active: bool(v.active), count: count(v.count), changed: bool(v.changed) }
}

function mediaOf(value: unknown): CommunityMedia {
  const v = object(value)
  return {
    mediaId: id(v.mediaId), url: text(v.url, 1024),
    contentType: v.contentType === 'image/png' ? 'image/png' : v.contentType === 'image/jpeg' ? 'image/jpeg' : fail(),
    bytes: count(v.bytes, 2 * 1024 * 1024), width: count(v.width, 2000), height: count(v.height, 2000),
    sha256: text(v.sha256, 64),
  }
}

export function decodeTopics(value: unknown): readonly CommunityTopic[] {
  const v = object(value)
  if (!Array.isArray(v.items) || v.items.length > 100) fail()
  return v.items.map(topicOf).filter((topic: CommunityTopic | null): topic is CommunityTopic => topic !== null)
}

export class CommunityRepository {
  constructor(private api: ConsumerApi) {}

  private get authenticated() { return !!this.api.currentSession }

  feed(scope: 'LATEST' | 'HOT' | 'FOLLOWING' | 'MINE' | 'FAVORITES', topicCode?: string, page = 1, pageSize = 20) {
    const data: Record<string, unknown> = { scope, page, pageSize }
    if (topicCode) data.topicCode = topicCode
    const spec = { path: '/api/v1/c/community/posts', method: 'GET' as const, data }
    return this.authenticated
      ? this.api.request(spec, value => pageOf(value, postOf, 50))
      : this.api.anonymousRequest(spec, value => pageOf(value, postOf, 50))
  }

  hot(limit = 3) {
    return this.api.anonymousRequest({ path: '/api/v1/c/community/hot', method: 'GET', data: { limit } },
      (value): readonly CommunityPost[] => {
        const v = object(value)
        if (!Array.isArray(v.items) || v.items.length > 6) fail()
        return v.items.map(postOf)
      })
  }

  post(postId: string) {
    const spec = { path: `/api/v1/c/community/posts/${encodeURIComponent(postId)}`, method: 'GET' as const }
    return this.authenticated ? this.api.request(spec, postOf) : this.api.anonymousRequest(spec, postOf)
  }

  comments(postId: string, page = 1, pageSize = 20) {
    const spec = {
      path: `/api/v1/c/community/posts/${encodeURIComponent(postId)}/comments`, method: 'GET' as const,
      data: { page, pageSize },
    }
    return this.authenticated
      ? this.api.request(spec, value => pageOf(value, commentOf, 50))
      : this.api.anonymousRequest(spec, value => pageOf(value, commentOf, 50))
  }

  topics() { return this.api.anonymousRequest({ path: '/api/v1/c/community/topics', method: 'GET' }, decodeTopics) }

  async uploadMedia(filePath: string) { return this.api.uploadCommunityMedia(filePath) }

  async createPost(input: { content: string; title?: string; topicCode?: string; imageIds: readonly string[] }) {
    const requestId = await this.api.uuid()
    return this.api.request({
      path: '/api/v1/c/community/posts', method: 'POST', requestId,
      data: {
        content: input.content, title: input.title || undefined,
        topicCode: input.topicCode || undefined, imageIds: input.imageIds,
      },
    }, receiptOf)
  }

  async deletePost(postId: string) {
    const requestId = await this.api.uuid()
    return this.api.request({ path: `/api/v1/c/community/posts/${encodeURIComponent(postId)}`, method: 'DELETE', requestId }, receiptOf)
  }

  async like(postId: string) { return this.interaction(postId, 'likes', 'POST') }
  async unlike(postId: string) { return this.interaction(postId, 'likes', 'DELETE') }
  async favorite(postId: string) { return this.interaction(postId, 'favorites', 'POST') }
  async unfavorite(postId: string) { return this.interaction(postId, 'favorites', 'DELETE') }

  private async interaction(postId: string, kind: 'likes' | 'favorites', method: 'POST' | 'DELETE') {
    const requestId = await this.api.uuid()
    return this.api.request({
      path: `/api/v1/c/community/posts/${encodeURIComponent(postId)}/${kind}`, method, requestId,
    }, interactionOf)
  }

  async follow(userId: string, active: boolean) {
    const requestId = await this.api.uuid()
    return this.api.request({
      path: `/api/v1/c/community/users/${encodeURIComponent(userId)}/follow`,
      method: active ? 'POST' : 'DELETE', requestId,
    }, receiptOf)
  }

  async comment(postId: string, content: string) {
    const requestId = await this.api.uuid()
    return this.api.request({
      path: `/api/v1/c/community/posts/${encodeURIComponent(postId)}/comments`, method: 'POST', requestId,
      data: { content },
    }, receiptOf)
  }

  async report(input: { targetType: 'POST' | 'COMMENT'; targetId: string; reasonCode: string; reasonText?: string }) {
    const requestId = await this.api.uuid()
    return this.api.request({
      path: '/api/v1/c/community/reports', method: 'POST', requestId, data: input,
    }, receiptOf)
  }
}
