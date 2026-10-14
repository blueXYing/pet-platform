import { ApiError } from '../../shared/request'
import { ConsumerApi } from '../../shared/consumer-api'
import {
  decodeBindingReceipt, decodeClaimReceipt, decodeSignInReceipt, decodeSignInStatus, decodeTasks,
  type AwardClaimReceipt, type ClaimType, type ReferralBindingReceipt, type SignInReceipt,
  type SignInStatusView, type TasksView,
} from './model'

// 58 号契约（32 号裁决第二节）：签到 + 拉新 + 奖励领取的 C 端接线。会话用户身份作用域的
// /api/v1/c/points/** 路由全部落在 ConsumerApi.request 既有 /^\/api\/v1\/c\// 白名单内，
// 不改 consumer-api.ts。读不带 query；三条写命令走 api.write 幂等槽（X-Request-Id 终端
// UUID 由 ConsumerApi 分配，同槽同参重试复用原命令）。开关未开放期间失败关闭：路由未
// 挂载 404 / 会话缺失 401 走常规错误路径，页面不虚构任何签到/奖励状态。
export function isPointsTaskUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 401
}

/** 当日已签 / 终局冲突 / 无可领取等确定性 409（重试换新 requestId 也不该重放原命令）。 */
export function isDefinitePointsTaskConflict(error: unknown): boolean {
  return error instanceof ApiError && error.statusCode === 409
    && error.code !== 'IDEMPOTENCY_KEY_CONFLICT'
}

export class RealPointsTaskRepository {
  constructor(private api: ConsumerApi) {}

  signInStatus(): Promise<SignInStatusView> {
    return this.api.request(
      { method: 'GET', path: '/api/v1/c/points/sign-in/status' }, decodeSignInStatus)
  }

  tasks(): Promise<TasksView> {
    return this.api.request({ method: 'GET', path: '/api/v1/c/points/tasks' }, decodeTasks)
  }

  signIn(): Promise<SignInReceipt> {
    return this.api.write(
      'points-sign-in:USER_SELF',
      { method: 'POST', path: '/api/v1/c/points/sign-in', data: {} },
      decodeSignInReceipt)
  }

  claimReward(claimType: ClaimType): Promise<AwardClaimReceipt> {
    return this.api.write(
      `points-claim:${claimType}`,
      { method: 'POST', path: '/api/v1/c/points/claims', data: { claimType } },
      decodeClaimReceipt)
  }

  bindReferral(inviteCode: string): Promise<ReferralBindingReceipt> {
    if (!/^[A-Za-z0-9]{1,32}$/.test(inviteCode)) {
      throw new ApiError('COMMON_INVALID_ARGUMENT', 400)
    }
    return this.api.write(
      'points-referral:USER_SELF',
      { method: 'POST', path: '/api/v1/c/points/referral-bindings', data: { inviteCode } },
      decodeBindingReceipt)
  }
}
