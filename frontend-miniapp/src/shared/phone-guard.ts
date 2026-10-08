// 可复用「手机号前置守卫」（用户 2026-10-08 裁决：首次进“我的”引导、可跳过、需要时拦截）。
// 纯工厂 + 端口注入（不 import Taro/consumer-runtime，node 测试直接驱动）；真实端口接线在
// phone-guard-runtime.ts。守卫失败关闭：仅服务端已验证的会话（wechat-login 有手机号才发
// SessionGrant）放行，不虚构“已授权”状态。

export type PhoneGuardPorts = {
  /** 服务端已验证的会话是否存在（currentSession 即含绑定手机号）。 */
  authenticated(): boolean
  /** 会话可能只是过期：先静默恢复一次（单飞、吞错）再判定。 */
  ensureLogin(): Promise<unknown>
  /** 弹出引导（确认去授权返回 true，暂不返回 false）。 */
  guide(): Promise<boolean>
  /** 用户同意后跳到授权入口（“我的”页手机号授权引导）。 */
  goAuthorize(): Promise<unknown>
}

export type PhoneGuardResult = 'allowed' | 'guided'

export function createPhoneGuard(ports: PhoneGuardPorts) {
  return async function requirePhone(action: () => unknown | Promise<unknown>): Promise<PhoneGuardResult> {
    if (!ports.authenticated()) await ports.ensureLogin()
    if (ports.authenticated()) {
      await action()
      return 'allowed'
    }
    // 无手机号（登录停在 VERIFY_PHONE / 未登录）：引导去授权，不执行动作。
    if (await ports.guide()) await ports.goAuthorize()
    return 'guided'
  }
}
