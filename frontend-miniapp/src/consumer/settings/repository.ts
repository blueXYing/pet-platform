import { consumerApi } from '../../shared/consumer-runtime'
import { decodeDeletionReceipt, decodePrecheck, type DeletionPrecheck, type DeletionReceipt } from './model'

// 设置枢纽真实仓库（契约 66 via HTTP10 §3.17）。注销前置校验为登录态只读；注销提交走
// 23号 X-Request-Id 幂等槽 settings:deletion（首次 201 / 同键重放 200 同回执）。
// 后端装配开关 pet.usersettings.http.enabled 默认关闭：关闭期请求走 404/503 常规错误路径，
// 页面失败关闭并引导稍后重试，不降级、不伪造成功。
export class RealSettingsRepository {
  constructor(private api: typeof consumerApi) {}

  async precheck(): Promise<DeletionPrecheck> {
    return this.api.request({ method: 'GET', path: '/api/v1/c/account/deletion-precheck' }, decodePrecheck)
  }

  async deleteAccount(_previewId?: string): Promise<DeletionReceipt> {
    return this.api.write('settings:deletion', {
      method: 'POST',
      path: '/api/v1/c/account/deletion',
      data: { acknowledged: true },
    }, decodeDeletionReceipt)
  }
}
