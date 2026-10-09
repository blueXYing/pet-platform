import test from 'node:test'
import assert from 'node:assert/strict'
import { createPhoneGuard, type PhoneGuardPorts } from '../phone-guard'

// 手机号前置守卫（用户 2026-10-08 裁决）：有已验证会话才放行；无会话先静默恢复一次，
// 仍无则引导去「我的」页授权（可取消）；动作绝不越权执行（失败关闭）。

function makePorts(overrides: (log: string[]) => Partial<PhoneGuardPorts> = () => ({})) {
  const log: string[] = []
  const ports: PhoneGuardPorts = {
    authenticated: () => false,
    ensureLogin: async () => { log.push('ensure') },
    guide: async () => { log.push('guide'); return true },
    goAuthorize: async () => { log.push('go') },
    ...overrides(log),
  }
  return { ports, log }
}

test('an authenticated session runs the action immediately (phone is implied by the grant)', async () => {
  const { ports, log } = makePorts(() => ({ authenticated: () => true }))
  const guard = createPhoneGuard(ports)
  const ran: string[] = []
  assert.equal(await guard(() => { ran.push('action') }), 'allowed')
  assert.deepEqual(ran, ['action'])
  assert.deepEqual(log, []) // 已认证不触发静默恢复/引导
})

test('an expired session is silently recovered once, then the action runs', async () => {
  let authenticated = false
  const { ports, log } = makePorts(log2 => ({
    authenticated: () => authenticated,
    ensureLogin: async () => { log2.push('ensure'); authenticated = true },
  }))
  const guard = createPhoneGuard(ports)
  assert.equal(await guard(() => 'done'), 'allowed')
  assert.deepEqual(log, ['ensure'])
})

test('a phone-less user is guided to authorize and the action never runs', async () => {
  const { ports, log } = makePorts()
  const guard = createPhoneGuard(ports)
  const ran: string[] = []
  assert.equal(await guard(() => { ran.push('action') }), 'guided')
  assert.deepEqual(ran, [])
  assert.deepEqual(log, ['ensure', 'guide', 'go'])
})

test('declining the guide stays on the page without side effects', async () => {
  const { ports, log } = makePorts(log2 => ({ guide: async () => { log2.push('decline'); return false } }))
  const guard = createPhoneGuard(ports)
  assert.equal(await guard(() => 'x'), 'guided')
  assert.deepEqual(log, ['ensure', 'decline']) // 未跳转
})
