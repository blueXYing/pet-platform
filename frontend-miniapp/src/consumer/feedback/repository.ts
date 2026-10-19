import { consumerApi } from '../../shared/consumer-runtime'
import {
  decodeCreateReceipt, decodeTicketDetail, decodeTicketPage,
  type FeedbackPage, type FeedbackTicketDetail, type FeedbackTicketSummary, type FeedbackType,
} from './model'

// 意见反馈真实仓库（契约 67 via HTTP10 §3.18）。提交走 23号 X-Request-Id 幂等槽
// feedback:create（首次 201 / 同键重放 200 同回执）；列表/详情为登录态只读，详情防枚举 404。
// 后端装配开关 pet.feedback.http.enabled 默认关闭：关闭期请求走 404/503 常规错误路径，
// 页面失败关闭并引导稍后重试，不降级、不伪造成功。
export class RealFeedbackRepository {
  constructor(private api: typeof consumerApi) {}

  async create(type: FeedbackType, contact: string | null, content: string,
      attachmentAssetIds: readonly string[]): Promise<FeedbackTicketSummary> {
    const data: Record<string, unknown> = { type, content }
    if (contact !== null) data.contact = contact
    if (attachmentAssetIds.length > 0) data.attachmentAssetIds = [...attachmentAssetIds]
    return this.api.write('feedback:create', {
      method: 'POST', path: '/api/v1/c/feedback-tickets', data,
    }, decodeCreateReceipt)
  }

  async list(page: number, pageSize: number): Promise<FeedbackPage> {
    return this.api.request({
      method: 'GET', path: `/api/v1/c/feedback-tickets?page=${page}&pageSize=${pageSize}`,
    }, decodeTicketPage)
  }

  async get(ticketId: string): Promise<FeedbackTicketDetail> {
    return this.api.request({ method: 'GET', path: `/api/v1/c/feedback-tickets/${ticketId}` }, decodeTicketDetail)
  }
}
