// 58 号切片（签到 + 拉新任务 + 奖励领取，32 号裁决第二节）。本文件是 C 端赚取面投影契约的
// 唯一替换点：契约 58-Points-Task-Contract-v0.1.md（docs/04-api），11 号 OpenAPI-Core
// cGetSignInStatus/cSignIn/cListPointsTasks/cClaimPointsReward/cBindReferralCode
// （IMPLEMENTED_DEFAULT_OFF，pet.points.task.http.enabled 另需 pet.auth.c.enabled）。
// 字段事实：
// - SignInStatusView：signedToday/streak(非负整数)/dailyPoints(非负整数 String)/week
//   （近 7 个自然日 {date "YYYY-MM-DD", signed}，UTC+8 口径）。
// - TasksView：inviteCode（P+13 位 base36+1 校验位，1..32 [A-Za-z0-9]）；signIn 同上摘要；
//   invite{perInviteePoints(String)/monthlyRewardCap/rewardedThisMonth/claimableCount/
//   milestoneRewarded/milestoneStatus/milestoneProgress}；tasks[] 四行（INVITE_PER_HEAD/
//   FIRST_ORDER/SIGN_IN/COMMUNITY_POST，COMMUNITY_POST 恒 NOT_OPEN——V1 无社区积分规则）；
//   rewards[] 三行（SIGN_IN_STREAK_7/INVITE_MILESTONE_3/PROFILE_COMPLETE）。
// - 状态词汇：tasks 行 CLAIMABLE|CLAIMED|NOT_ACHIEVED|NOT_OPEN|DONE_TODAY；rewards 行
//   NOT_ACHIEVED|CLAIMABLE|CLAIMED；milestoneStatus 同 rewards 三值。
// 产品硬规则不变：积分只赚取/扣回，不消费（AGENTS + 32 号裁决第二节第 1 条），本切片无任何
// 兑换/抵扣路径；除每日签到外一切奖励均显式领取。

export type TaskRowStatus = 'CLAIMABLE' | 'CLAIMED' | 'NOT_ACHIEVED' | 'NOT_OPEN' | 'DONE_TODAY'
export type RewardStatus = 'NOT_ACHIEVED' | 'CLAIMABLE' | 'CLAIMED'
export type ClaimType = 'INVITE_PER_HEAD' | 'FIRST_ORDER' | 'SIGN_IN_STREAK_7' | 'INVITE_MILESTONE_3' | 'PROFILE_COMPLETE'

export type SignInWeekDay = Readonly<{ date: string; signed: boolean }>

export type SignInStatusView = Readonly<{
  signedToday: boolean
  streak: number
  dailyPoints: string
  week: readonly SignInWeekDay[]
}>

/** /c/points/tasks 内嵌的签到摘要（11 号 PointsTaskSignInSummary：仅三字段，无 week）。 */
export type SignInSummary = Readonly<{
  signedToday: boolean
  streak: number
  dailyPoints: string
}>

export type InviteOverview = Readonly<{
  perInviteePoints: string
  monthlyRewardCap: number
  rewardedThisMonth: number
  claimableCount: number
  milestoneRewarded: number
  milestoneStatus: RewardStatus
  milestoneProgress: string
}>

export type TaskRowView = Readonly<{
  taskCode: 'INVITE_PER_HEAD' | 'FIRST_ORDER' | 'SIGN_IN' | 'COMMUNITY_POST'
  title: string
  description: string
  points: string
  status: TaskRowStatus
  progress: string
}>

export type RewardRowView = Readonly<{
  rewardCode: 'SIGN_IN_STREAK_7' | 'INVITE_MILESTONE_3' | 'PROFILE_COMPLETE'
  title: string
  description: string
  points: string
  status: RewardStatus
  progress: string
}>

export type TasksView = Readonly<{
  inviteCode: string
  signIn: SignInSummary
  invite: InviteOverview
  tasks: readonly TaskRowView[]
  rewards: readonly RewardRowView[]
}>

export type SignInReceipt = Readonly<{
  signedDate: string
  points: string
  streakAfter: number
  ledgerId: string
  balanceAfter: string
}>

export type AwardClaimReceipt = Readonly<{
  claimType: ClaimType
  pointsGranted: string
  ledgerIds: readonly string[]
  balanceAfter: string
}>

export type ReferralBindingReceipt = Readonly<{ bindingId: string; createdAt: string | null }>

const nonNegativeInteger = (value: string): boolean => /^(0|[1-9][0-9]{0,17})(?![\s\S])/.test(value)
const isoDate = (value: string): boolean => /^\d{4}-\d{2}-\d{2}(?![\s\S])/.test(value)
const isoMilli = (value: string): boolean => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z(?![\s\S])/.test(value)
const rewardStatuses: readonly RewardStatus[] = ['NOT_ACHIEVED', 'CLAIMABLE', 'CLAIMED']
const isRewardStatus = (value?: string): value is RewardStatus =>
  (rewardStatuses as readonly string[]).includes(value || '')
const taskRowStatuses: readonly TaskRowStatus[] =
  ['CLAIMABLE', 'CLAIMED', 'NOT_ACHIEVED', 'NOT_OPEN', 'DONE_TODAY']
const isTaskRowStatus = (value?: string): value is TaskRowStatus =>
  (taskRowStatuses as readonly string[]).includes(value || '')

function text(value: unknown, pattern?: (raw: string) => boolean): string {
  if (typeof value !== 'string' || value.length === 0) throw new Error('INVALID_RESPONSE')
  if (pattern && !pattern(value)) throw new Error('INVALID_RESPONSE')
  return value
}

function count(value: unknown, min: number, max: number): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min || value > max) {
    throw new Error('INVALID_RESPONSE')
  }
  return value
}

function flag(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new Error('INVALID_RESPONSE')
  return value
}

function rows(value: unknown, size: number): readonly unknown[] {
  if (!Array.isArray(value) || value.length !== size) throw new Error('INVALID_RESPONSE')
  return value
}

export function decodeSignInStatus(value: unknown): SignInStatusView {
  if (value === null || typeof value !== 'object') throw new Error('INVALID_RESPONSE')
  const raw = value as Record<string, unknown>
  if (Object.keys(raw).length !== 4) throw new Error('INVALID_RESPONSE')
  const week = rows(raw.week, 7).map(day => {
    if (day === null || typeof day !== 'object') throw new Error('INVALID_RESPONSE')
    const cell = day as Record<string, unknown>
    return { date: text(cell.date, isoDate), signed: flag(cell.signed) } satisfies SignInWeekDay
  })
  return {
    signedToday: flag(raw.signedToday),
    streak: count(raw.streak, 0, 100_000),
    dailyPoints: text(raw.dailyPoints, nonNegativeInteger),
    week,
  }
}

/** tasks 视图内嵌摘要：严格三键（additionalProperties: false，11 号 PointsTaskSignInSummary）。 */
export function decodeSignInSummary(value: unknown): SignInSummary {
  if (value === null || typeof value !== 'object') throw new Error('INVALID_RESPONSE')
  const raw = value as Record<string, unknown>
  if (Object.keys(raw).length !== 3) throw new Error('INVALID_RESPONSE')
  return {
    signedToday: flag(raw.signedToday),
    streak: count(raw.streak, 0, 100_000),
    dailyPoints: text(raw.dailyPoints, nonNegativeInteger),
  }
}

export function decodeTasks(value: unknown): TasksView {
  if (value === null || typeof value !== 'object') throw new Error('INVALID_RESPONSE')
  const raw = value as Record<string, unknown>
  if (raw.signIn === null || typeof raw.signIn !== 'object'
    || raw.invite === null || typeof raw.invite !== 'object') throw new Error('INVALID_RESPONSE')
  const signIn = decodeSignInSummary(raw.signIn)
  const invite = raw.invite as Record<string, unknown>
  if (!isRewardStatus(String(invite.milestoneStatus))) throw new Error('INVALID_RESPONSE')
  return {
    inviteCode: text(raw.inviteCode, value => /^[A-Za-z0-9]{1,32}$/.test(value)),
    signIn,
    invite: {
      perInviteePoints: text(invite.perInviteePoints, nonNegativeInteger),
      monthlyRewardCap: count(invite.monthlyRewardCap, 0, 100),
      rewardedThisMonth: count(invite.rewardedThisMonth, 0, 100),
      claimableCount: count(invite.claimableCount, 0, 100),
      milestoneRewarded: count(invite.milestoneRewarded, 0, 100_000),
      milestoneStatus: invite.milestoneStatus as RewardStatus,
      milestoneProgress: text(invite.milestoneProgress, value => /^\d{1,3}\/3$/.test(value)),
    },
    tasks: rows(raw.tasks, 4).map(row => {
      if (row === null || typeof row !== 'object') throw new Error('INVALID_RESPONSE')
      const cell = row as Record<string, unknown>
      const taskCode = cell.taskCode
      if (taskCode !== 'INVITE_PER_HEAD' && taskCode !== 'FIRST_ORDER'
        && taskCode !== 'SIGN_IN' && taskCode !== 'COMMUNITY_POST') throw new Error('INVALID_RESPONSE')
      if (!isTaskRowStatus(String(cell.status))) throw new Error('INVALID_RESPONSE')
      return {
        taskCode, title: text(cell.title), description: text(cell.description, () => true),
        points: text(cell.points, nonNegativeInteger), status: cell.status as TaskRowStatus,
        progress: text(cell.progress),
      } satisfies TaskRowView
    }),
    rewards: rows(raw.rewards, 3).map(row => {
      if (row === null || typeof row !== 'object') throw new Error('INVALID_RESPONSE')
      const cell = row as Record<string, unknown>
      const rewardCode = cell.rewardCode
      if (rewardCode !== 'SIGN_IN_STREAK_7' && rewardCode !== 'INVITE_MILESTONE_3'
        && rewardCode !== 'PROFILE_COMPLETE') throw new Error('INVALID_RESPONSE')
      if (!isRewardStatus(String(cell.status))) throw new Error('INVALID_RESPONSE')
      return {
        rewardCode, title: text(cell.title), description: text(cell.description, () => true),
        points: text(cell.points, nonNegativeInteger), status: cell.status as RewardStatus,
        progress: text(cell.progress),
      } satisfies RewardRowView
    }),
  }
}

export function decodeSignInReceipt(value: unknown): SignInReceipt {
  if (value === null || typeof value !== 'object') throw new Error('INVALID_RESPONSE')
  const raw = value as Record<string, unknown>
  return {
    signedDate: text(raw.signedDate, isoDate),
    points: text(raw.points, nonNegativeInteger),
    streakAfter: count(raw.streakAfter, 1, 100_000),
    ledgerId: text(raw.ledgerId, nonNegativeInteger),
    balanceAfter: text(raw.balanceAfter, nonNegativeInteger),
  }
}

export function decodeClaimReceipt(value: unknown): AwardClaimReceipt {
  if (value === null || typeof value !== 'object') throw new Error('INVALID_RESPONSE')
  const raw = value as Record<string, unknown>
  const claimType = raw.claimType
  if (claimType !== 'INVITE_PER_HEAD' && claimType !== 'FIRST_ORDER'
    && claimType !== 'SIGN_IN_STREAK_7' && claimType !== 'INVITE_MILESTONE_3'
    && claimType !== 'PROFILE_COMPLETE') throw new Error('INVALID_RESPONSE')
  const ledgerIds = raw.ledgerIds
  if (!Array.isArray(ledgerIds) || ledgerIds.length === 0) throw new Error('INVALID_RESPONSE')
  return {
    claimType, pointsGranted: text(raw.pointsGranted, nonNegativeInteger),
    ledgerIds: ledgerIds.map(id => text(id, nonNegativeInteger)),
    balanceAfter: text(raw.balanceAfter, nonNegativeInteger),
  }
}

export function decodeBindingReceipt(value: unknown): ReferralBindingReceipt {
  if (value === null || typeof value !== 'object') throw new Error('INVALID_RESPONSE')
  const raw = value as Record<string, unknown>
  if (raw.createdAt !== null && typeof raw.createdAt !== 'string') throw new Error('INVALID_RESPONSE')
  if (typeof raw.createdAt === 'string' && !isoMilli(raw.createdAt)) throw new Error('INVALID_RESPONSE')
  return { bindingId: text(raw.bindingId, nonNegativeInteger), createdAt: raw.createdAt as string | null }
}

// ---------------------------------------------------------------- 展示层纯函数

const weekdayLabels = ['日', '一', '二', '三', '四', '五', '六'] as const

/** 周条格子：服务端 week（近 7 个自然日）→ 星期字 + 日号 + 已签标记。 */
export function weekCells(week: readonly SignInWeekDay[]): readonly { label: string; day: string; signed: boolean }[] {
  return week.map(cell => {
    const date = new Date(`${cell.date}T00:00:00Z`)
    return {
      label: weekdayLabels[date.getUTCDay()],
      day: cell.date.slice(8, 10),
      signed: cell.signed,
    }
  })
}

export const taskRowStatusLabels: Record<TaskRowStatus, string> = {
  CLAIMABLE: '去领取', CLAIMED: '已完成', NOT_ACHIEVED: '未达成', NOT_OPEN: '未开放', DONE_TODAY: '已完成',
}

export const rewardStatusLabels: Record<RewardStatus, string> = {
  NOT_ACHIEVED: '未达成', CLAIMABLE: '领取', CLAIMED: '已领取',
}

/** rewards 面板副文案（+N 积分按契约串渲染，不改写）。 */
export function rewardPointsLabel(points: string): string {
  return `+${points} 积分`
}

/** 拉新分享口令（V1 无邀请落地页，仅复制文本；文案不虚构奖励数字）。 */
export function inviteShareText(inviteCode: string, perInviteePoints: string): string {
  return `来宠物生活服务平台一起养宠！输入我的邀请码 ${inviteCode} 完成注册并下单，你我都能得积分（当前每邀请 1 位得 ${perInviteePoints} 积分）。`
}
