// 设置枢纽（契约 66 / PRD 5.1.29；设计稿 131:18372，登记表 §4.9 偏差小节）。
// 纯视图模型 + 静态内容常量：注销前置校验/提交走 repository；通知/地址为真实子页入口；
// 隐私政策/用户协议为静态内容页（微信审核硬门槛；文案为 V1 基线版本文本）。
// 设计偏差（66号 §6）：修改密码行不渲染（V1 无密码体系，审计 D 类帧）；支付设置行不渲染
// （PRD 明示不做）；推送通知/消息提醒两开关行改为入口（承载既有通知偏好页）；新增注销入口
// （PRD 字段表明确要求，原稿无此元素）。

export type DeletionBlockerCode =
  | 'USER_ORDERS_UNSETTLED'
  | 'USER_AFTERSALES_OPEN'
  | 'OWNER_STORE_ORDERS_UNSETTLED'
  | 'OWNER_STORE_AFTERSALES_OPEN'

export type DeletionBlocker = Readonly<{
  code: DeletionBlockerCode
  label: string
  detail: string
}>

export type DeletionPrecheck = Readonly<{ blocked: boolean; blockers: readonly DeletionBlocker[] }>

export type DeletionReceipt = Readonly<{ userId: string; status: string; deletedAt: string }>

export const blockerFallbackLabels: Record<DeletionBlockerCode, string> = {
  USER_ORDERS_UNSETTLED: '存在未完结订单',
  USER_AFTERSALES_OPEN: '存在处理中售后工单',
  OWNER_STORE_ORDERS_UNSETTLED: '名下商家存在未完结订单',
  OWNER_STORE_AFTERSALES_OPEN: '名下商家存在处理中售后工单',
}

export function isBlockerCode(value: unknown): value is DeletionBlockerCode {
  return typeof value === 'string' && value in blockerFallbackLabels
}

export function decodePrecheck(value: unknown): DeletionPrecheck {
  if (typeof value !== 'object' || value === null) throw new Error('INVALID_RESPONSE')
  const v = value as Record<string, unknown>
  if (typeof v.blocked !== 'boolean' || !Array.isArray(v.blockers)) throw new Error('INVALID_RESPONSE')
  const blockers = v.blockers.map(item => {
    if (typeof item !== 'object' || item === null) throw new Error('INVALID_RESPONSE')
    const row = item as Record<string, unknown>
    if (!isBlockerCode(row.code) || typeof row.label !== 'string' || typeof row.detail !== 'string') {
      throw new Error('INVALID_RESPONSE')
    }
    return { code: row.code, label: row.label, detail: row.detail } as DeletionBlocker
  })
  if (v.blocked !== (blockers.length > 0)) throw new Error('INVALID_RESPONSE')
  return { blocked: v.blocked, blockers }
}

export function decodeDeletionReceipt(value: unknown): DeletionReceipt {
  if (typeof value !== 'object' || value === null) throw new Error('INVALID_RESPONSE')
  const v = value as Record<string, unknown>
  if (typeof v.userId !== 'string' || v.status !== 'CANCELED' || typeof v.deletedAt !== 'string') {
    throw new Error('INVALID_RESPONSE')
  }
  return { userId: v.userId, status: v.status, deletedAt: v.deletedAt }
}

// ---------------------------------------------------------------- 帮助与客服（67号 / PRD 5.1.25）

export type FaqItem = Readonly<{ question: string; answer: string }>
export type FaqGroup = Readonly<{ group: string; items: readonly FaqItem[] }>

// FAQ 答案按平台已批规则改写（CCR-CS-FEEDBACK-001 §4）：V1 无 C 端普通取消入口（10号 §4.3）、
// 服务前退款自动全额（AGENTS 硬规则）、核销后 30 天评价窗（PRD 5.1.27）、手机号=微信快捷验证
// 换绑（PR#137 登录口径）、积分只赚不花（32号）、优惠券=下单抵扣（61号）、服务者=平台审核入驻、
// 服务问题=联系客服/发起售后。设计稿「在线客服 7×24」按人工口径改写为客服热线。
export const faqGroups: readonly FaqGroup[] = [
  {
    group: '订单与服务',
    items: [
      {
        question: '如何取消订单？',
        answer: '订单暂不支持自行取消。未支付订单超时将自动关闭；已支付订单可在服务开始前在订单详情申请退款，服务开始前的退款自动全额原路退回。',
      },
      {
        question: '服务完成后如何评价？',
        answer: '订单核销完成后，可在「我的订单」的订单详情内对门店、服务和服务人员进行评价；评价入口自核销成功起保留 30 天，每笔订单仅可评价一次。',
      },
      {
        question: '如何申请退款？',
        answer: '服务开始前：在订单详情点「申请退款」，系统自动全额原路退回；服务开始后：先由商家确认处理，商家拒绝或已核销订单可在订单详情发起售后，由平台介入处理。',
      },
    ],
  },
  {
    group: '账户与支付',
    items: [
      {
        question: '如何修改手机号？',
        answer: '在「我的」页点击手机号卡片，通过微信手机号快捷验证完成换绑；换绑成功后账号联系方式即时更新。',
      },
      {
        question: '积分如何获得？',
        answer: '每日签到、完成拉新任务、订单完成后按实付金额获得奖励积分；积分仅可累计展示，V1 不支持消费抵扣。',
      },
      {
        question: '优惠券如何使用？',
        answer: '下单支付时选择可用优惠券，符合门槛即自动抵扣；每单仅可使用一张，退款时按平台规则处理。',
      },
    ],
  },
  {
    group: '服务者与安全',
    items: [
      {
        question: '服务者是否经过认证？',
        answer: '平台服务者与商家均需提交资质并通过平台入驻审核后才会对外展示；你可在商家详情页查看其评分与历史评价。',
      },
      {
        question: '服务过程中遇到问题怎么办？',
        answer: '可先拨打下方客服热线反馈；涉及服务质量、未履约、费用争议或宠物安全问题的，可在订单详情发起售后并上传凭证，平台将跟进处理。',
      },
    ],
  },
]

// 客服热线常量（CCR-CS-FEEDBACK-001 §2：号码待运营配置，V1 前端常量承载；无 IM/无 AI，
// 人工客服承载=联系方式展示 + 电话唤起 wx.makePhoneCall）。
export const SERVICE_HOTLINE = '400-000-0000'
export const SERVICE_HOTLINE_NOTE = '人工客服热线（服务时间 9:00-21:00）'

// ---------------------------------------------------------------- 静态协议内容（微信审核硬门槛）

export type PolicyDoc = Readonly<{ title: string; version: string; updatedAt: string; sections: readonly { heading: string; body: string }[] }>

export const privacyPolicyDoc: PolicyDoc = {
  title: '隐私政策',
  version: 'V1.0',
  updatedAt: '2026-10-19',
  sections: [
    { heading: '1. 我们收集的信息', body: '为提供宠物生活服务，我们收集：你的微信登录标识（OpenID）、经你授权的手机号、你填写的昵称头像、宠物档案信息（品种、性别、年龄、体重、健康备注、疫苗/驱虫记录）、订单与预约信息、地址簿信息、你主动提交的评价、反馈与售后凭证。' },
    { heading: '2. 信息的使用', body: '上述信息仅用于：账号识别与登录、预约与订单履约、商家提供服务时必要的宠物信息展示（以你的授权为前提）、售后与客服处理、平台安全与风控。我们不会将你的个人信息出售给任何第三方。' },
    { heading: '3. 信息的共享', body: '仅在为你完成服务所必需的范围内向接单商家/服务者展示必要的宠物档案与健康字段；你可随时在宠物档案中撤回对商家的授权，撤回后商家端不再展示任何健康字段。' },
    { heading: '4. 信息的存储', body: '你的个人信息存储于中华人民共和国境内的服务器；订单、售后、评价等交易相关记录按法律法规要求保留；账号注销后账号状态置为注销、登录立即失效，相关数据按平台保留策略处理。' },
    { heading: '5. 你的权利', body: '你可以：在「我的-编辑资料」维护昵称头像；在宠物档案中维护或停用宠物信息；在地址簿中增删地址；在通知偏好中关闭普通互动提醒与外部推送（订单/退款/核销/售后/审核类站内消息不可关闭）；注销账号（存在未完结订单/售后时需先完结）。' },
    { heading: '6. 未成年人保护', body: '我们的服务面向成年人。若你是未满 14 周岁的未成年人，请在监护人陪同下阅读本政策，并在取得监护人同意后使用我们的服务。' },
    { heading: '7. 联系我们', body: '如对本政策有任何疑问、意见或投诉，可通过「帮助与客服」页面的客服热线与我们联系，我们将在 15 个工作日内答复。' },
  ],
}

export const userAgreementDoc: PolicyDoc = {
  title: '用户协议',
  version: 'V1.0',
  updatedAt: '2026-10-19',
  sections: [
    { heading: '1. 协议范围', body: '本协议是你与本平台之间关于使用宠物生活服务小程序（含预约、支付、评价、售后、积分等功能）的约定。使用本服务即视为你已阅读并同意本协议。' },
    { heading: '2. 账号与使用', body: '你通过微信授权登录并完成手机号验证后使用完整功能。账号仅限本人使用；你应保证提交的信息真实、合法，不得利用平台从事违法违规活动。' },
    { heading: '3. 预约与服务', body: '平台服务均需提前预约，服务时间以商家确认与排期为准。订单展示金额以支付页为准，支付渠道为微信支付。' },
    { heading: '4. 退款与售后', body: '服务开始前申请退款，系统自动全额原路退回；服务开始后退款需商家确认；已核销或商家拒绝退款的订单可在约定期限内发起售后，由平台按规则处理，平台首次裁决即终局。' },
    { heading: '5. 评价规范', body: '你应对真实完成的服务进行评价，不得发布违法违规、侵权或虚假内容；违规评价将不予展示。' },
    { heading: '6. 积分说明', body: '积分通过签到、任务与订单奖励获得，仅用于累计展示与平台后续活动，不可转让、不可消费抵扣。' },
    { heading: '7. 协议变更与终止', body: '平台可根据业务发展与法规要求修订本协议并在小程序内公示。你可随时停止使用服务并注销账号；注销前需完结全部未完成订单与售后。' },
  ],
}
