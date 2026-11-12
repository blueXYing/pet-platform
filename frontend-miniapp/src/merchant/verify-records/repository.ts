import { ConsumerApi } from '../../shared/consumer-api'
import { decodeVerificationRecordPage, type VerificationRecordPage } from './model'

// ---------------------------------------------------------------------------
// 商家核销记录读侧（CCR-M-VERIFY-RECORD-001 §3）：GET /api/v1/merchant/verifications，
// 只读 GET，商家坐标（merchantId+storeId）由工作台票根携带并经 ConsumerApi 的
// WORKSPACE_PATH_MISMATCH 防线核对（与订单列表读侧同款），服务端仍在门店 guard 事务内
// 重验 OWNER 归属；开关 pet.verification.merchant-read.http.enabled 默认 false，未开时
// 按常规错误路径失败关闭。固定排序 verified_at DESC, id DESC（服务端）；PageResult 信封。
//
// 联调依赖（G1 并行）：shared ConsumerApi.send 的路径白名单目前不含本路由——本仓库按
// 契约写死路径，白名单放行需由 shared 侧（本任务禁改）补一条 GET 谓词后联调才通。
// ---------------------------------------------------------------------------

export interface VerificationRecordListDeps {
  list(query: { merchantId: string; storeId: string; page: number; pageSize: number }): Promise<VerificationRecordPage>
}

const VERIFICATION_RECORD_PAGE_SIZE = 20

export class RealVerificationRecordRepository implements VerificationRecordListDeps {
  constructor(private api: ConsumerApi) {}

  async list(query: { merchantId: string; storeId: string; page: number; pageSize: number }): Promise<VerificationRecordPage> {
    const coordinates = this.coordinates()
    if (query.merchantId !== coordinates.merchantId || query.storeId !== coordinates.storeId) {
      throw new Error('WORKSPACE_PATH_MISMATCH')
    }
    if (!Number.isInteger(query.page) || query.page < 1 || query.page > 10000
      || !Number.isInteger(query.pageSize) || query.pageSize < 1 || query.pageSize > 100
      || query.pageSize !== VERIFICATION_RECORD_PAGE_SIZE) throw new Error('INVALID_QUERY')
    const page = await this.api.request(
      { method: 'GET', path: '/api/v1/merchant/verifications', data: { ...query } },
      value => {
        const decoded = decodeVerificationRecordPage(value)
        if (decoded.page !== query.page || decoded.pageSize !== query.pageSize) throw new Error('INVALID_RESPONSE')
        return decoded
      })
    return page
  }

  private coordinates(): { merchantId: string; storeId: string } {
    const context = this.api.scope.capture().context
    if (!context || context.workspace !== 'merchant' || !context.merchantId || !context.storeId) {
      throw new Error('WORKSPACE_PATH_MISMATCH')
    }
    return { merchantId: context.merchantId, storeId: context.storeId }
  }
}
