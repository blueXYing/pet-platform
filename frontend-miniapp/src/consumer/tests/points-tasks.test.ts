import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import {
  decodeBindingReceipt, decodeClaimReceipt, decodeSignInReceipt, decodeSignInStatus, decodeTasks,
  inviteShareText, rewardPointsLabel, weekCells, taskRowStatusLabels, rewardStatusLabels,
} from '../points-tasks/model'
import { RealPointsTaskRepository, isDefinitePointsTaskConflict, isPointsTaskUnauthorized } from '../points-tasks/repository'
import { ApiError } from '../../shared/request'
import { ConsumerApi, type LocalStore } from '../../shared/consumer-api'

// ---- 58 号契约解码器：全部按 model.ts 严格口径（exact-key 惯例，枚举/模式越界即失败关闭）。

const week = (signed: boolean[] = [false, false, false, false, false, false, true]) =>
  ['2026-10-07', '2026-10-08', '2026-10-09', '2026-10-10', '2026-10-11', '2026-10-12', '2026-10-13']
    .map((date, index) => ({ date, signed: signed[index] ?? false }))

const wireStatus = {
  signedToday: true, streak: 3, dailyPoints: '5', week: week(),
}

const wireTasks = {
  inviteCode: 'Pabc123def456ghi7',
  signIn: { signedToday: true, streak: 3, dailyPoints: '5' },
  invite: {
    perInviteePoints: '50', monthlyRewardCap: 3, rewardedThisMonth: 1, claimableCount: 2,
    milestoneRewarded: 1, milestoneStatus: 'CLAIMED', milestoneProgress: '1/3',
  },
  tasks: [
    { taskCode: 'INVITE_PER_HEAD', title: '邀请好友注册', description: 'd', points: '50', status: 'CLAIMABLE', progress: '1/3' },
    { taskCode: 'FIRST_ORDER', title: '完成首次下单', description: 'd', points: '100', status: 'CLAIMED', progress: '1/1' },
    { taskCode: 'SIGN_IN', title: '每日签到', description: 'd', points: '5', status: 'DONE_TODAY', progress: '1/1' },
    { taskCode: 'COMMUNITY_POST', title: '发表社区动态', description: 'd', points: '0', status: 'NOT_OPEN', progress: '-' },
  ],
  rewards: [
    { rewardCode: 'SIGN_IN_STREAK_7', title: '连续签到 7 天', description: 'd', points: '30', status: 'CLAIMABLE', progress: '3/7' },
    { rewardCode: 'INVITE_MILESTONE_3', title: '邀请 3 位好友', description: 'd', points: '100', status: 'CLAIMED', progress: '1/3' },
    { rewardCode: 'PROFILE_COMPLETE', title: '完善个人资料', description: 'd', points: '20', status: 'NOT_ACHIEVED', progress: '-' },
  ],
}

test('sign-in status decoder accepts the wire projection and keeps the 7-day week intact', () => {
  const decoded = decodeSignInStatus(JSON.parse(JSON.stringify(wireStatus)))
  assert.equal(decoded.signedToday, true)
  assert.equal(decoded.streak, 3)
  assert.equal(decoded.dailyPoints, '5')
  assert.equal(decoded.week.length, 7)
  assert.deepEqual(decoded.week[6], { date: '2026-10-13', signed: true })
  for (const mutate of [
    (v: Record<string, any>) => { delete v.signedToday },
    (v: Record<string, any>) => { v.streak = -1 },
    (v: Record<string, any>) => { v.streak = 1.5 },
    (v: Record<string, any>) => { v.dailyPoints = '05' },
    (v: Record<string, any>) => { v.dailyPoints = 5 },
    (v: Record<string, any>) => { v.week = week().slice(0, 6) },
    (v: Record<string, any>) => { v.week[0] = { date: '2026/10/07', signed: false } },
    (v: Record<string, any>) => { v.week[1] = { date: '2026-10-08', signed: 'yes' } },
    (v: Record<string, any>) => { v.extra = true },
  ]) {
    const value = JSON.parse(JSON.stringify(wireStatus))
    mutate(value)
    assert.throws(() => decodeSignInStatus(value), /INVALID_RESPONSE/, JSON.stringify(mutate))
  }
})

test('tasks decoder pins the 4+3 rows, milestone shape and the invite-code alphabet', () => {
  const decoded = decodeTasks(JSON.parse(JSON.stringify(wireTasks)))
  assert.equal(decoded.inviteCode, 'Pabc123def456ghi7')
  assert.equal(decoded.tasks.length, 4)
  assert.equal(decoded.tasks[3].taskCode, 'COMMUNITY_POST')
  assert.equal(decoded.tasks[3].status, 'NOT_OPEN')
  assert.equal(decoded.rewards.length, 3)
  assert.equal(decoded.invite.milestoneStatus, 'CLAIMED')
  assert.equal(decoded.invite.milestoneProgress, '1/3')
  for (const mutate of [
    (v: Record<string, any>) => { v.inviteCode = '带空格 p' },
    (v: Record<string, any>) => { v.inviteCode = '' },
    (v: Record<string, any>) => { delete v.invite },
    (v: Record<string, any>) => { v.invite.monthlyRewardCap = 101 },
    (v: Record<string, any>) => { v.invite.milestoneStatus = 'DONE' },
    (v: Record<string, any>) => { v.invite.milestoneProgress = '1-of-3' },
    // 内嵌 signIn 是三字段摘要（11 号 PointsTaskSignInSummary）：带 week 即违规。
    (v: Record<string, any>) => { v.signIn = { ...v.signIn, week: [] } },
    (v: Record<string, any>) => { delete v.signIn.dailyPoints },
    (v: Record<string, any>) => { v.tasks = v.tasks.slice(0, 3) },
    (v: Record<string, any>) => { v.tasks[0].taskCode = 'REDEEM' },
    (v: Record<string, any>) => { v.tasks[0].status = 'PENDING' },
    (v: Record<string, any>) => { v.tasks[1].points = '-50' },
    (v: Record<string, any>) => { v.tasks[2].progress = '' },
    (v: Record<string, any>) => { v.rewards = v.rewards.slice(0, 2) },
    (v: Record<string, any>) => { v.rewards[0].rewardCode = 'SHARE_POST' },
    (v: Record<string, any>) => { v.rewards[0].status = 'NOT_OPEN' },
  ]) {
    const value = JSON.parse(JSON.stringify(wireTasks))
    mutate(value)
    assert.throws(() => decodeTasks(value), /INVALID_RESPONSE/, JSON.stringify(mutate))
  }
})

test('receipt decoders enforce non-negative string ids and the five claim types', () => {
  const sign = decodeSignInReceipt({
    signedDate: '2026-10-13', points: '5', streakAfter: 4, ledgerId: '741', balanceAfter: '55',
  })
  assert.equal(sign.streakAfter, 4)
  assert.throws(() => decodeSignInReceipt({ signedDate: '2026-10-13', points: '5', streakAfter: 0, ledgerId: '1', balanceAfter: '1' }), /INVALID_RESPONSE/)
  assert.throws(() => decodeSignInReceipt({ signedDate: '2026-10-13', points: '5', streakAfter: 1, ledgerId: '01', balanceAfter: '1' }), /INVALID_RESPONSE/)

  const claim = decodeClaimReceipt({
    claimType: 'INVITE_PER_HEAD', pointsGranted: '100', ledgerIds: ['742', '743'], balanceAfter: '155',
  })
  assert.equal(claim.ledgerIds.length, 2)
  for (const mutate of [
    () => decodeClaimReceipt({ claimType: 'REDEEM', pointsGranted: '1', ledgerIds: ['1'], balanceAfter: '1' }),
    () => decodeClaimReceipt({ claimType: 'FIRST_ORDER', pointsGranted: '1', ledgerIds: [], balanceAfter: '1' }),
    () => decodeClaimReceipt({ claimType: 'FIRST_ORDER', pointsGranted: '1', ledgerIds: ['x'], balanceAfter: '1' }),
  ]) assert.throws(mutate, /INVALID_RESPONSE/)

  assert.deepEqual(decodeBindingReceipt({ bindingId: '9', createdAt: null }), { bindingId: '9', createdAt: null })
  assert.equal(decodeBindingReceipt({ bindingId: '9', createdAt: '2026-10-13T08:00:00.000Z' }).createdAt, '2026-10-13T08:00:00.000Z')
  for (const mutate of [
    () => decodeBindingReceipt({ bindingId: '9', createdAt: '2026-10-13T08:00:00Z' }),
    () => decodeBindingReceipt({ bindingId: '9', createdAt: 5 }),
    () => decodeBindingReceipt({ bindingId: '0' }),
  ]) assert.throws(mutate, /INVALID_RESPONSE/)
})

test('week cells map the server week to weekday labels and day-of-month digits', () => {
  const cells = weekCells(week([false, false, false, false, false, true, true]))
  // 2026-10-13 是周二；近 7 日横跨周三~周二。
  assert.deepEqual(cells.map(cell => cell.label), ['三', '四', '五', '六', '日', '一', '二'])
  assert.deepEqual(cells.map(cell => cell.day), ['07', '08', '09', '10', '11', '12', '13'])
  assert.deepEqual(cells.map(cell => cell.signed), [false, false, false, false, false, true, true])
})

test('status labels and share text never offer a spend/exchange wording (V1 hard rule)', () => {
  assert.equal(taskRowStatusLabels.NOT_OPEN, '未开放')
  assert.equal(taskRowStatusLabels.DONE_TODAY, '已完成')
  assert.equal(rewardStatusLabels.CLAIMABLE, '领取')
  assert.equal(rewardPointsLabel('30'), '+30 积分')
  const share = inviteShareText('Pabc123def456ghi7', '50')
  assert.ok(share.includes('Pabc123def456ghi7'))
  assert.ok(share.includes('50'))
  assert.ok(!/兑换|抵扣|消费/.test(share))
  assert.ok(!/兑换|抵扣|消费/.test(Object.values(taskRowStatusLabels).join()))
})

// ---- repository：五条路由均落在 ConsumerApi 既有 C 白名单；写命令走幂等槽。

const sessionView = { userId: '957001', sessionId: '957101', audience: 'MINIAPP', expiresAt: '2099-01-01T00:00:00.000Z' }
const ok = (data: unknown) => ({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data, traceId: 'test' } })

function authenticatedApi(transport: (request: { method: string; path: string; data?: Record<string, unknown>; headers: Record<string, string> }) => Promise<{ statusCode: number; data: unknown }>) {
  const values = new Map<string, unknown>()
  const store: LocalStore = { get: key => values.get(key), set: (key, value) => values.set(key, value), remove: key => { values.delete(key) } }
  store.set('pet.c.session.v1', { ...sessionView, accessToken: 'test-only', tokenType: 'Bearer' })
  const api = new ConsumerApi(transport, store, async () => randomUUID())
  return { api, restore: () => api.restore() }
}

test('real repository speaks the five contract routes with idempotent write slots', async () => {
  const seen: { method: string; path: string; data?: Record<string, unknown>; headers: Record<string, string> }[] = []
  const { api, restore } = authenticatedApi(async request => {
    seen.push({ method: request.method, path: request.path, data: request.data, headers: request.headers })
    if (request.path === '/api/v1/c/auth/session') return ok(sessionView)
    if (request.path === '/api/v1/c/points/sign-in/status') return ok(wireStatus)
    if (request.path === '/api/v1/c/points/tasks') return ok(wireTasks)
    if (request.path === '/api/v1/c/points/sign-in') return ok({ signedDate: '2026-10-13', points: '5', streakAfter: 4, ledgerId: '741', balanceAfter: '55' })
    if (request.path === '/api/v1/c/points/claims') return ok({ claimType: 'INVITE_PER_HEAD', pointsGranted: '100', ledgerIds: ['742'], balanceAfter: '155' })
    if (request.path === '/api/v1/c/points/referral-bindings') return ok({ bindingId: '9', createdAt: null })
    throw new Error('unexpected path ' + request.path)
  })
  await restore()
  const repository = new RealPointsTaskRepository(api)
  assert.equal((await repository.signInStatus()).streak, 3)
  assert.equal((await repository.tasks()).inviteCode, 'Pabc123def456ghi7')
  assert.equal((await repository.signIn()).streakAfter, 4)
  assert.equal((await repository.claimReward('INVITE_PER_HEAD')).pointsGranted, '100')
  assert.equal((await repository.bindReferral('Pabc123def456ghi7')).bindingId, '9')
  const writes = seen.filter(call => call.method === 'POST')
  assert.deepEqual(writes.map(call => call.path), [
    '/api/v1/c/points/sign-in', '/api/v1/c/points/claims', '/api/v1/c/points/referral-bindings'])
  // 三条写命令都带终端 X-Request-Id（幂等键），读不带。
  for (const call of seen) {
    const hasId = Boolean(call.headers['X-Request-Id'])
    assert.equal(hasId, call.method === 'POST', call.path)
  }
  assert.deepEqual(seen.find(call => call.path === '/api/v1/c/points/claims')?.data, { claimType: 'INVITE_PER_HEAD' })
  // 无效邀请码在客户端即拒（400 口径），不发请求。
  assert.throws(() => repository.bindReferral('not valid!'), /COMMON_INVALID_ARGUMENT/)
})

test('unauthorized and definite-conflict guards classify ApiError by code', () => {
  assert.equal(isPointsTaskUnauthorized(new ApiError('COMMON_UNAUTHORIZED', 401)), true)
  assert.equal(isPointsTaskUnauthorized(new ApiError('COMMON_CONFLICT', 409)), false)
  // 409 且非幂等键冲突 = 当日已签/终局冲突/无可领取（换新 requestId 也不该重放）。
  assert.equal(isDefinitePointsTaskConflict(new ApiError('COMMON_CONFLICT', 409)), true)
  assert.equal(isDefinitePointsTaskConflict(new ApiError('IDEMPOTENCY_KEY_CONFLICT', 409)), false)
  assert.equal(isDefinitePointsTaskConflict(new ApiError('COMMON_INVALID_ARGUMENT', 400)), false)
})
