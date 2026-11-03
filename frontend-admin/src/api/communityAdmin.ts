import { createWebClient, type Transport } from '../request';

const ROOT = '/api/v1/admin/community';

export type CommunityAuthor = { userId: string; nickname: string; avatarUrl: string | null };
export type CommunityTopic = { topicCode: string; name: string; description: string | null; sortOrder: number };
export type CommunityAdminPost = {
  postId: string; author: CommunityAuthor; title: string | null; content: string;
  images: string[]; topic: CommunityTopic | null;
  likeCount: number; commentCount: number; favoriteCount: number; viewCount: number;
  visibility: string; status: string; version: string;
  publishedAt: string | null; createdAt: string; updatedAt: string;
};
export type CommunityAdminReport = {
  reportId: string; targetType: string; targetId: string;
  reporterUserId: string; reporterNickname: string;
  reasonCode: string; reasonText: string | null; status: string;
  targetPreview: string | null; createdAt: string; handledAt: string | null; handlingNote: string | null;
};
export type Page<T> = { page: number; pageSize: number; total: number; items: T[] };
export type CommunityReceipt = { id: string; status: string; created: boolean; occurredAt: string };

const query = (params: Record<string, string | number | undefined>) => {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) if (value !== undefined && value !== '') search.set(key, String(value));
  return search.toString();
};

export function createCommunityAdminClient(transport?: Transport) {
  const client = createWebClient(transport);
  const request = <T>(path: string, options: { method?: 'GET' | 'POST'; body?: unknown; requestId?: string } = {}) =>
    client.request<T>(path, options);
  return {
    resetContext(token?: string) { client.resetContext(token); },
    listPosts(filters: { status?: string; keyword?: string } = {}, page = 1, pageSize = 50) {
      return request<Page<CommunityAdminPost>>(`${ROOT}/posts?${query({ ...filters, page, pageSize })}`);
    },
    moderatePost(postId: string, decision: 'APPROVE' | 'OFFLINE' | 'DELETE', reason: string | undefined, expectedVersion: string) {
      return request<CommunityReceipt>(`${ROOT}/posts/${postId}/decision`, {
        method: 'POST', body: { decision, reason, expectedVersion },
      });
    },
    listReports(filters: { status?: string } = {}, page = 1, pageSize = 50) {
      return request<Page<CommunityAdminReport>>(`${ROOT}/reports?${query({ ...filters, page, pageSize })}`);
    },
    resolveReport(reportId: string, decision: 'RESOLVE' | 'DISMISS', note: string) {
      return request<CommunityReceipt>(`${ROOT}/reports/${reportId}/resolve`, {
        method: 'POST', body: { decision, note },
      });
    },
  };
}

export type CommunityAdminClient = ReturnType<typeof createCommunityAdminClient>;
