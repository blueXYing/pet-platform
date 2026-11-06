// 工作台/M 端「我的」视觉验收用的内部夹具（?preview=1）。只用于开发者工具截图，
// 不暴露为公共 DTO、不参与真实鉴权、不写任何业务数据。数字对齐 Figma 95:1899 主稿。

import type { WorkbenchDashboardDeps } from './dashboard'
import type {
  CustomerDetail, CustomerPage, CustomerRow, PetView, StatsOverview, StatsServiceRow,
  StatsUtilization, StoreProfileView, TrendBucket,
} from '../../shared/merchant-storefront-api'
import type { MerchantOrderPage, MerchantOrderSummary } from '../order/model'
import type { MerchantOrderListDeps } from '../order/repository'
import type { WorkspaceScope } from '../../shared/workspace'
import type {
  AppealReceipt, ReviewAggregate, ReviewDetail, ReviewPage, ReviewSummary,
} from '../../shared/review-appeal-api'
import type { RefundApplicationDetail, RefundApplicationPage } from '../../shared/merchant-refund-api'
import type { MerchantRefundDeps } from '../refund/repository'
import type { MerchantStorefrontDeps } from '../storefront/repository'
import type { MerchantReviewDeps } from '../review-appeal/repository'
import type {
  InvitationRow, InvitationsPage, MemberRow, MembersDeps, MembersPage,
} from '../members/model'
import { fixtureDeps } from '../admission'
import { consumerApi } from '../../shared/consumer-runtime'

const iso = (offsetMinutes: number) => new Date(Date.now() + offsetMinutes * 60_000).toISOString()
// CCR-M-ORDER-CARD-001 卡片样本：昵称/头像/服务名/宠物快照可空，覆盖空值降级形态。
const summary = (id: string, orderNo: string, amount: string, minutes: number,
  card: Pick<MerchantOrderSummary, 'customerNickname' | 'customerAvatarUrl' | 'serviceName' | 'petName' | 'petType' | 'petBreed'> = {
    customerNickname: null, customerAvatarUrl: null, serviceName: null, petName: null, petType: null, petBreed: null,
  }): MerchantOrderSummary => ({
  orderId: id,
  orderNo,
  displayStatus: 'PENDING_CONFIRM',
  payAmount: amount,
  appointmentStart: iso(minutes),
  appointmentEnd: iso(minutes + 60),
  paidAt: iso(-5),
  ...card,
})

const profile: StoreProfileView = {
  merchantId: '9007199254740995',
  storeId: '9007199254740996',
  readonlyFacts: {
    storeName: '小李宠物店', address: '测试路 1 号', cityCode: '310100', phoneMasked: '138****0000',
    longitude: '121.0', latitude: '31.0', merchantStatus: 'ACTIVE', storeStatus: 'ACTIVE',
    operatingStatus: 'OPERATING', applicationStatus: 'APPROVED',
  },
  profile: {
    intro: null, announcement: null, photoAssetIds: [], businessHours: [], promoLevel: null,
    promoTitle: null, promoText: null, avatarAssetId: null, businessStatus: 'OPEN', version: '1',
  },
}
const stats: StatsOverview = {
  range: 'TODAY', revenue: '3846.00', orderCount: 42, newCustomers: 2, avgTicket: '91.57',
  verifiedOrderCount: 10, verifyRate: '80.0', refundOrderCount: 1, refundRate: '2.0',
  repeatRate: '10.0', reviewAvg: '4.8', rejectRate: '1.0',
  deltas: { revenue: '12.5', orderCount: '3.2', newCustomers: null, avgTicket: '1.1' },
  generatedAt: new Date().toISOString(),
}
const pending: MerchantOrderPage = {
  items: [
    summary('9101', '202611040001', '268.00', 15, {
      customerNickname: '李小姐', customerAvatarUrl: null, serviceName: '猫咪洗澡+美容',
      petName: '豆豆', petType: 'CAT', petBreed: '英短',
    }),
    summary('9102', '202611040002', '450.00', 30, {
      customerNickname: null, customerAvatarUrl: null, serviceName: '狗狗美容造型',
      petName: '球球', petType: 'DOG', petBreed: null,
    }),
  ],
  page: 1, pageSize: 20, total: 8,
}
const service: MerchantOrderPage = { items: [], page: 1, pageSize: 20, total: 5 }
const refunds = { page: 1, pageSize: 20, total: 1, items: [] } as unknown as RefundApplicationPage

export function previewStoreProfile(): StoreProfileView {
  return profile
}

export function previewDashboardDeps(): WorkbenchDashboardDeps {
  return {
    profile: async () => profile,
    stats: async () => stats,
    orders: async status => status === 'PENDING_CONFIRM' ? pending : service,
    refunds: async () => refunds,
  }
}

// 订单列表视觉验收样本（?preview=1）：覆盖待接单/待服务/已完成/已取消/退款中五个状态；
// 卡片字段按 CCR-M-ORDER-CARD-001 覆盖 有昵称+头像 / 无昵称 / 无宠物 快照 三种降级形态。
const previewOrders: readonly MerchantOrderSummary[] = [
  { ...summary('9201', '202611040011', '268.00', 15, {
    customerNickname: '李小姐', customerAvatarUrl: 'https://wx.qlogo.cn/sample-alice.png',
    serviceName: '猫咪洗澡+美容', petName: '豆豆', petType: 'CAT', petBreed: '英短',
  }), displayStatus: 'PENDING_CONFIRM' },
  { ...summary('9202', '202611040012', '450.00', 30, {
    customerNickname: null, customerAvatarUrl: null, serviceName: '狗狗美容造型',
    petName: '球球', petType: 'DOG', petBreed: null,
  }), displayStatus: 'PENDING_CONFIRM' },
  { ...summary('9203', '202611040013', '120.00', 50, {
    customerNickname: '王先生', customerAvatarUrl: null, serviceName: '宠物疫苗接种',
    petName: null, petType: null, petBreed: null,
  }), displayStatus: 'PENDING_CONFIRM' },
  { ...summary('9204', '202611040014', '380.00', 90, {
    customerNickname: '李小姐', customerAvatarUrl: null, serviceName: '狗狗寄养（每日）',
    petName: '年糕', petType: 'CAT', petBreed: '布偶',
  }), displayStatus: 'PENDING_SERVICE' },
  { ...summary('9205', '202611040015', '98.00', 180, {
    customerNickname: null, customerAvatarUrl: null, serviceName: null,
    petName: null, petType: null, petBreed: null,
  }), displayStatus: 'PENDING_SERVICE' },
  { ...summary('9206', '202611040016', '1280.00', -120, {
    customerNickname: '李小姐', customerAvatarUrl: null, serviceName: '宠物体检基础套餐',
    petName: '豆豆', petType: 'CAT', petBreed: '英短',
  }), displayStatus: 'COMPLETED' },
  { ...summary('9207', '202611040017', '200.00', -240, {
    customerNickname: '王先生', customerAvatarUrl: null, serviceName: '猫咪洗澡',
    petName: '小橘', petType: 'CAT', petBreed: '橘猫',
  }), displayStatus: 'CANCELED' },
  { ...summary('9208', '202611040018', '88.00', -60, {
    customerNickname: null, customerAvatarUrl: null, serviceName: '宠物疫苗接种',
    petName: '球球', petType: 'DOG', petBreed: '柯基',
  }), displayStatus: 'REFUND_PENDING_CONFIRM' },
]

export function previewOrderPage(displayStatus?: string): MerchantOrderPage {
  const items = displayStatus
    ? previewOrders.filter(item => item.displayStatus === displayStatus)
    : previewOrders
  return { items: [...items], page: 1, pageSize: 20, total: items.length }
}

export function previewOrderListDeps(scope: WorkspaceScope): MerchantOrderListDeps & { scope: WorkspaceScope } {
  return {
    scope,
    list: async query => previewOrderPage(query.displayStatus),
  }
}

// 退款列表/详情视觉验收样本（?preview=1）：57号商家读侧字段，无买家身份/申诉/服务时间线。
const refundNow = new Date().toISOString()
const previewRefundSummary = {
  applicationId: '9301',
  applicationNo: '202611040001',
  orderId: '9201',
  status: 'PENDING_MERCHANT',
  applicationVersion: '1',
  reasonCode: 'SERVICE_ISSUE',
  refundAmount: '268.00',
  createdAt: refundNow,
  merchantDeadline: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
}
const previewRefundDetail = {
  ...previewRefundSummary,
  decidedAt: null,
  decisionId: null,
  refundOrderId: null,
} as RefundApplicationDetail

export function previewRefundDeps(): MerchantRefundDeps {
  const admissionContext = {
    userId: '9007199254740993', workspace: 'merchant' as const,
    merchantId: '9007199254740995', storeId: '9007199254740996',
  }
  return {
    admission: () => fixtureDeps('allowed').admission(
      admissionContext, admissionContext.merchantId, admissionContext.storeId),
    list: async () => ({
      page: 1, pageSize: 20, total: 1, items: [previewRefundSummary],
    } as unknown as RefundApplicationPage),
    detail: async () => previewRefundDetail,
    approve: async () => { throw new Error('PREVIEW_ONLY') },
    reject: async () => { throw new Error('PREVIEW_ONLY') },
    pending: () => null,
    loadDraft: () => null,
    saveDraft: () => {},
  }
}

// 数据统计视觉验收样本（70号 M 面，?preview=1）：只读聚合，不接真实接口。
const previewStatsTop: readonly StatsServiceRow[] = [
  { serviceId: '9401', serviceName: '猫咪洗澡+美容', orderCount: 12, revenue: '1680.00' },
  { serviceId: '9402', serviceName: '狗狗美容造型', orderCount: 8, revenue: '1584.00' },
  { serviceId: '9403', serviceName: '宠物疫苗接种', orderCount: 5, revenue: '600.00' },
  { serviceId: '9404', serviceName: '狗狗寄养（每日）', orderCount: 3, revenue: '450.00' },
  { serviceId: '9405', serviceName: '宠物体检基础套餐', orderCount: 2, revenue: '760.00' },
]
const previewTrend: readonly TrendBucket[] = [
  { date: '2026-10-29', revenue: '320.00', orderCount: 4 },
  { date: '2026-10-30', revenue: '480.00', orderCount: 6 },
  { date: '2026-10-31', revenue: '260.00', orderCount: 3 },
  { date: '2026-11-01', revenue: '720.00', orderCount: 9 },
  { date: '2026-11-02', revenue: '540.00', orderCount: 7 },
  { date: '2026-11-03', revenue: '860.00', orderCount: 11 },
  { date: '2026-11-04', revenue: '666.00', orderCount: 8 },
]
const previewUtilization: StatsUtilization = {
  range: 'LAST_7D', from: '2026-10-29', to: '2026-11-04',
  availableMinutes: 2400, bookedMinutes: 1680, utilizationRate: '70.0',
}

const previewCustomers: readonly CustomerRow[] = [
  {
    userId: '9601', nickname: '李小姐', phoneMasked: '138****0000', orderCount: 6,
    totalSpend: '1280.00', avgTicket: '213.33', lastVisitAt: iso(-2 * 24 * 60),
    topPet: { petName: '豆豆', petType: 'CAT', breedName: '英短' },
  },
  {
    userId: '9602', nickname: '王先生', phoneMasked: '139****0001', orderCount: 3,
    totalSpend: '760.00', avgTicket: '253.33', lastVisitAt: iso(-5 * 24 * 60),
    topPet: { petName: '球球', petType: 'DOG', breedName: '柯基' },
  },
]
const previewPets: readonly PetView[] = [
  { petName: '豆豆', petType: 'CAT', breedName: '英短' },
  { petName: '年糕', petType: 'CAT', breedName: '布偶' },
]
const previewCustomerDetail: CustomerDetail = {
  customer: previewCustomers[0]!,
  pets: previewPets,
  note: { content: '偏好下午到店；猫咪怕吹风机。', version: '1', updatedAt: iso(-24 * 60) },
}

export function previewStatsDeps(): MerchantStorefrontDeps {
  const admissionContext = {
    userId: '9007199254740993', workspace: 'merchant' as const,
    merchantId: '9007199254740995', storeId: '9007199254740996',
  }
  const impossible = async () => { throw new Error('PREVIEW_ONLY') }
  return {
    scope: consumerApi.scope,
    admission: () => fixtureDeps('allowed').admission(
      admissionContext, admissionContext.merchantId, admissionContext.storeId),
    storeProfile: async () => profile,
    saveStoreProfile: impossible,
    retireProfileConflict: () => {},
    uploadStorePhoto: impossible,
    // CCR-M-STORE-AVATAR-001：预览样本无头像（三 null → 首字占位，与真实降级路径一致）。
    storeAvatarUrl: async () => ({ assetId: null, url: null, expiresAt: null }),
    customers: async () => ({
      page: 1, pageSize: 20, total: previewCustomers.length, items: previewCustomers,
    } as CustomerPage),
    customerDetail: async () => previewCustomerDetail,
    saveCustomerNote: impossible,
    retireNoteConflict: () => {},
    statsOverview: async range => ({ ...stats, range }),
    statsServiceTop: async range => ({ range, items: previewStatsTop }),
    statsTrend: async range => ({ range, buckets: previewTrend }),
    statsServiceOrders: impossible,
    statsUtilization: async range => ({ ...previewUtilization, range }),
  }
}

// 成员管理视觉验收样本（54号）：3 名成员 + 1 个待确认邀请，覆盖启用/停用。
const previewMembers: readonly MemberRow[] = [
  { merchantId: '9007199254740995', storeId: '9007199254740996', memberId: '9501',
    memberName: '王小美', phoneMasked: '138****0001', memberStatus: 'ENABLED', grantStatus: 'ENABLED',
    grantedActions: ['merchant.order.verify'], memberVersion: '1', grantVersion: '1' },
  { merchantId: '9007199254740995', storeId: '9007199254740996', memberId: '9502',
    memberName: '王往', phoneMasked: '138****0002', memberStatus: 'ENABLED', grantStatus: 'ENABLED',
    grantedActions: ['merchant.order.verify'], memberVersion: '1', grantVersion: '1' },
  { merchantId: '9007199254740995', storeId: '9007199254740996', memberId: '9503',
    memberName: '李想', phoneMasked: '138****0003', memberStatus: 'DISABLED', grantStatus: 'ENABLED',
    grantedActions: ['merchant.order.verify'], memberVersion: '1', grantVersion: '1' },
]
const previewInvitations: readonly InvitationRow[] = [
  { merchantId: '9007199254740995', storeId: '9007199254740996', invitationId: '9551',
    memberName: '赵敏', phoneMasked: '138****0004', status: 'INVITED', version: '1' },
]

export function previewMembersDeps(): MembersDeps {
  return {
    listMembers: async () => ({
      items: previewMembers, page: 1, pageSize: 50, total: previewMembers.length,
    } as MembersPage),
    listInvitations: async () => ({
      items: previewInvitations, page: 1, pageSize: 50, total: previewInvitations.length,
    } as InvitationsPage),
    invite: async (_slot, phone, memberName) => ({
      merchantId: '9007199254740995', storeId: '9007199254740996', invitationId: '9552',
      memberName, phoneMasked: phone.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2'),
      status: 'INVITED', version: '1',
    }),
    cancelInvitation: async (_slot, invitationId) => ({
      ...previewInvitations[0]!, invitationId, status: 'CANCELED', version: '2',
    }),
    disableMember: async (_slot, memberId) => ({
      ...previewMembers[0]!, memberId, memberStatus: 'DISABLED', memberVersion: '2',
    }),
    enableMember: async (_slot, memberId) => ({
      ...previewMembers[0]!, memberId, memberStatus: 'ENABLED', memberVersion: '2',
    }),
  }
}

// 店铺评价视觉验收样本（56号）：三条评价覆盖未申诉/申诉中/低分；56号 §7 买家身份覆盖
// 昵称+头像外链 / 仅昵称（首字占位）/ 无昵称（匿名用户）三态。
const previewReviews: readonly ReviewSummary[] = [
  { reviewId: '9701', orderId: '9201', storeScore: '5.0', serviceScore: '5.0', staffScore: '4.0',
    compositeScore: '4.8', scoreIncluded: true, visibilityStatus: 'PUBLISHED',
    content: '服务很细致，猫咪洗完很蓬松，店员还提醒了下次驱虫时间。', createdAt: iso(-24 * 60),
    appealStatus: null, appealId: null,
    reviewerNickname: '林晓雨', reviewerAvatarUrl: 'https://thirdwx.qlogo.cn/mmopen/preview-avatar/132' },
  { reviewId: '9702', orderId: '9202', storeScore: '4.0', serviceScore: '4.0', staffScore: '4.0',
    compositeScore: '4.0', scoreIncluded: true, visibilityStatus: 'PUBLISHED',
    content: '预约准时，环境干净，狗狗剪完造型很可爱。', createdAt: iso(-3 * 24 * 60),
    appealStatus: 'PROCESSING', appealId: '9802',
    reviewerNickname: '张伟', reviewerAvatarUrl: null },
  { reviewId: '9703', orderId: '9203', storeScore: '2.0', serviceScore: '3.0', staffScore: '2.0',
    compositeScore: '2.4', scoreIncluded: true, visibilityStatus: 'PUBLISHED',
    content: '等待时间有点久，希望下次能更准时。', createdAt: iso(-7 * 24 * 60),
    appealStatus: null, appealId: null,
    reviewerNickname: null, reviewerAvatarUrl: null },
]
const previewReviewAggregate: ReviewAggregate = {
  totalReviews: 3, scoredCount: 3, averageScore: '3.7',
  scoreCounts: { '1': 0, '2': 1, '3': 0, '4': 1, '5': 1 },
}
const previewReviewDetail: ReviewDetail = {
  ...previewReviews[0]!, appealReason: null, appealCreatedAt: null,
  decisionReason: null, decidedAt: null,
}

export function previewReviewDeps(): MerchantReviewDeps {
  const admissionContext = {
    userId: '9007199254740993', workspace: 'merchant' as const,
    merchantId: '9007199254740995', storeId: '9007199254740996',
  }
  return {
    scope: consumerApi.scope,
    admission: () => fixtureDeps('allowed').admission(
      admissionContext, admissionContext.merchantId, admissionContext.storeId),
    list: async () => ({
      page: 1, pageSize: 20, total: previewReviews.length, items: previewReviews,
    } as ReviewPage),
    summary: async () => previewReviewAggregate,
    detail: async () => previewReviewDetail,
    appeal: async (reviewId): Promise<AppealReceipt> => ({
      appealId: '9801', reviewId, status: 'SUBMITTED', createdAt: iso(0),
    }),
    pendingAppeal: () => null,
    retireConflict: () => {},
    saveDraft: () => {},
    loadDraft: () => '',
    clearDraft: () => {},
  }
}
