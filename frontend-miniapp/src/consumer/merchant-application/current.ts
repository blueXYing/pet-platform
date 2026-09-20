import { ApiError } from '../../shared/request'
import type { ApplicationRepository } from './model'

export const noCurrentApplicationNotice = '尚未创建入驻申请，可填写后保存草稿。'

/** This exception belongs exclusively to GET current; never use it for writes or other reads. */
export async function readCurrentApplication(repository: Pick<ApplicationRepository, 'current'>, canApply: () => boolean, canApplyEmpty: () => boolean = canApply) {
  try {
    const current = await repository.current()
    return canApply() ? { kind: 'current' as const, current } : { kind: 'ignored' as const }
  } catch (error) {
    if (!(error instanceof ApiError) || error.statusCode !== 404 || error.code !== 'COMMON_NOT_FOUND') throw error
    return canApply() && canApplyEmpty() ? { kind: 'empty' as const } : { kind: 'ignored' as const }
  }
}
