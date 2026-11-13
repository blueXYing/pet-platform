import { ApiError } from '../../shared/request'
import { ConsumerApi } from '../../shared/consumer-api'
import { decodeManagedServicePage } from '../services/model'
import {
  batchGenerateProblems, bookingWindowFormProblems, capabilityProblems, composeTimestamp,
  decodeBatchCloseResult, decodeBatchGenerateResult, decodeBookingWindowReceipt,
  decodeBookingWindowView, decodeCapabilityView, decodeStaffWindowPage, decodeStaffWindowReceipt,
  decodeWeekTemplateView, decodeWindowPage, decodeWindowReceipt, reasonProblem,
  staffWindowFormProblems, weekTemplateFormProblems, windowFormProblems,
  type BatchCloseResult, type BatchGenerateInput, type BatchGenerateResult,
  type BookingWindowForm, type BookingWindowReceipt, type BookingWindowView,
  type CapabilityView, type ScheduleDeps, type ScheduleWindowPage,
  type ScheduleWindowReceipt, type StaffWindowFormInput, type StaffWindowReceipt,
  type StaffWindowPage, type WeekTemplateForm, type WeekTemplateReceipt,
  type WeekTemplateView, type WindowFormInput, type WindowPageQuery,
} from './model'

/**
 * Real wiring for the M-002 schedule-maintenance routes on top of the Schedule Write
 * Contract v0.1 (53号; MerchantScheduleController, switch pet.schedule.command.http.enabled
 * default OFF). Reads ride ConsumerApi.request (Bearer merchant workspace coordinates);
 * writes journal per-slot X-Request-Id commands through ConsumerApi.write so an explicit
 * retry replays the same requestId after a lost response (23号). A definitive 409 (CAS,
 * overlap, occupied-window, idempotency-key) proves nothing was written for that exact
 * payload, so the slot is retired and a corrected payload can use it again; 5xx keeps the
 * journal (outcome unknown) and the page tells the user to retry the original action.
 * The store-windows list read always consumes the §3.3 paged mode (#122): page/pageSize
 * go on every query and the decode accepts only the {storeId,items,page,pageSize,total}
 * envelope — the workbench summary counts ride the filter-matched total of one-row
 * status-filtered queries instead of a full fetch.
 */
export class RealScheduleRepository implements ScheduleDeps {
  constructor(private api: ConsumerApi, private merchantId: () => string, private storeId: () => string) {}

  private target(): { merchantId: string; storeId: string } {
    const merchantId = this.merchantId(), storeId = this.storeId()
    if (!merchantId || !storeId) throw new Error('WORKSPACE_PATH_MISMATCH')
    return { merchantId, storeId }
  }

  private async read<T>(path: string, data: Record<string, unknown>, decode: (value: unknown) => T): Promise<T> {
    return this.api.request({ method: 'GET', path, data }, decode)
  }

  private async write<T>(slot: string, path: string, method: 'POST' | 'PUT', data: Record<string, unknown>, decode: (value: unknown) => T): Promise<T> {
    try {
      return await this.api.write(slot, { method, path, data }, decode)
    } catch (error) {
      // 23号: replays return the original receipt, so a journaled command can only reach a
      // 409 as a definitive per-payload rejection — retire it (no-op if already retired).
      if (error instanceof ApiError && error.statusCode === 409) {
        try { this.api.retireRejectedCommand(slot, { method, path, data }) } catch { /* retired */ }
      }
      throw error
    }
  }

  private windowsParams(query: WindowPageQuery, merchantId: string): Record<string, unknown> {
    const data: Record<string, unknown> = { merchantId, page: query.page, pageSize: query.pageSize }
    // §3.1/§3.3: the controller accepts OPEN/CLOSED/SOLD_OUT as the server-side filter;
    // the workbench status chips and the summary count queries ride it (total is the
    // filter-matched count). Unified slot carries no serviceId/kind filter any more
    // (CCR-SCH-SLOT-001 §2: window identity is storeId+时间区间).
    if (query.status) data.status = query.status
    return data
  }

  async windows(query: WindowPageQuery): Promise<ScheduleWindowPage> {
    const { merchantId, storeId } = this.target()
    return this.read(`/api/v1/merchant/stores/${storeId}/availability-windows`,
      this.windowsParams(query, merchantId), decodeWindowPage)
  }

  async createWindow(slot: string, input: WindowFormInput): Promise<ScheduleWindowReceipt> {
    const { merchantId, storeId } = this.target()
    if (windowFormProblems(input).length) throw new Error('WORKSPACE_FORM_INVALID')
    // Unified slot (CCR-SCH-SLOT-001 §2): the create body carries NO serviceId/windowKind —
    // the store-wide shared interval + capacity is the whole payload.
    return this.write(slot, `/api/v1/merchant/stores/${storeId}/availability-windows`, 'POST', {
      merchantId,
      startAt: composeTimestamp(input.startDate, input.startTime),
      endAt: composeTimestamp(input.endDate, input.endTime),
      configuredCapacity: input.configuredCapacity,
    }, decodeWindowReceipt)
  }

  async updateWindow(slot: string, windowId: string, expectedVersion: string, input: WindowFormInput): Promise<ScheduleWindowReceipt> {
    const { merchantId, storeId } = this.target()
    if (windowFormProblems(input).length) throw new Error('WORKSPACE_FORM_INVALID')
    return this.write(slot, `/api/v1/merchant/stores/${storeId}/availability-windows/${windowId}`, 'PUT', {
      merchantId, startAt: composeTimestamp(input.startDate, input.startTime),
      endAt: composeTimestamp(input.endDate, input.endTime),
      configuredCapacity: input.configuredCapacity, expectedVersion,
    }, value => {
      const receipt = decodeWindowReceipt(value)
      if (receipt.windowId !== windowId) throw new Error('INVALID_RESPONSE')
      return receipt
    })
  }

  async closeWindow(slot: string, windowId: string, expectedVersion: string, reason: string): Promise<ScheduleWindowReceipt> {
    const { merchantId, storeId } = this.target()
    if (reasonProblem(reason, true)) throw new Error('WORKSPACE_FORM_INVALID')
    return this.write(slot, `/api/v1/merchant/stores/${storeId}/availability-windows/${windowId}/close`, 'POST', {
      merchantId, expectedVersion, reason: reason.trim(),
    }, value => {
      const receipt = decodeWindowReceipt(value)
      if (receipt.windowId !== windowId || receipt.status !== 'CLOSED') throw new Error('INVALID_RESPONSE')
      return receipt
    })
  }

  async openWindow(slot: string, windowId: string, expectedVersion: string): Promise<ScheduleWindowReceipt> {
    const { merchantId, storeId } = this.target()
    return this.write(slot, `/api/v1/merchant/stores/${storeId}/availability-windows/${windowId}/open`, 'POST', {
      merchantId, expectedVersion,
    }, value => {
      const receipt = decodeWindowReceipt(value)
      // Reopen re-judges by occupancy: the system may answer SOLD_OUT directly (§3.1).
      if (receipt.windowId !== windowId || receipt.status === 'CLOSED') throw new Error('INVALID_RESPONSE')
      return receipt
    })
  }

  async batchClose(slot: string, fromDate: string, toDate: string, reason: string): Promise<BatchCloseResult> {
    const { merchantId, storeId } = this.target()
    if (reasonProblem(reason, true)) throw new Error('WORKSPACE_FORM_INVALID')
    return this.write(slot, `/api/v1/merchant/stores/${storeId}/availability-windows/batch-close`, 'POST', {
      merchantId, fromDate, toDate, reason: reason.trim(),
    }, decodeBatchCloseResult)
  }

  /** CCR-SCH-SLOT-002 §1（53号增补）batch-generate：覆盖重建统一槽。请求体严格 JSON
   *  {merchantId, fromDate, toDate, dailyStart, dailyEnd, intervalMinutes,
   *  configuredCapacity, reason}；响应 {storeId, createdWindows, closedWindows,
   *  skippedWindows:[{window,reasonCode}]}，部分成功允许、候选上限 200。覆盖范围 =
   *  生成区间（fromDate 起 dailyStart 至 toDate 止 dailyEnd）——多段多次调用按此自洽
   *  （按历日关闭会把同日前段调用刚建的窗再关掉），见 model.ts mock 同口径注记。 */
  async batchGenerate(slot: string, input: BatchGenerateInput): Promise<BatchGenerateResult> {
    const { merchantId, storeId } = this.target()
    if (batchGenerateProblems(input).length) throw new Error('WORKSPACE_FORM_INVALID')
    return this.write(slot, `/api/v1/merchant/stores/${storeId}/availability-windows/batch-generate`, 'POST', {
      merchantId,
      dates: [...input.dates],
      segments: input.segments.map(seg => ({ start: seg.start, end: seg.end })),
      configuredCapacity: input.configuredCapacity,
      reason: input.reason.trim(),
    }, decodeBatchGenerateResult)
  }

  /** §2（53号增补）GET booking-window：门店级可预约范围，缺行返回默认值（60/7）。
   *  GET → {merchantId, storeId, leadMinutes, maxDays, version, updatedAt}。 */
  async bookingWindow(): Promise<BookingWindowView> {
    const { merchantId, storeId } = this.target()
    return this.read(`/api/v1/merchant/stores/${storeId}/booking-window`, { merchantId }, decodeBookingWindowView)
  }

  /** §2 PUT booking-window：全替换 + expectedVersion 乐观锁（非减少可用性，无需 reason）。
   *  PUT {merchantId, leadMinutes, maxDays, expectedVersion} → receipt 无 updatedAt。 */
  async saveBookingWindow(slot: string, form: BookingWindowForm, expectedVersion: string): Promise<BookingWindowReceipt> {
    const { merchantId, storeId } = this.target()
    if (bookingWindowFormProblems(form).length) throw new Error('WORKSPACE_FORM_INVALID')
    return this.write(slot, `/api/v1/merchant/stores/${storeId}/booking-window`, 'PUT', {
      merchantId, leadMinutes: form.leadMinutes, maxDays: form.maxDays, expectedVersion,
    }, decodeBookingWindowReceipt)
  }

  /** CCR-SCH-SLOT-003 §3（53号 v0.3 增补）GET week-template：门店周循环模板，缺行=七天
   *  全休 version "0"（服务端物化默认）。GET → {days:[{enabled,segments:[{start,end,
   *  capacity}]}×7], version}；store 坐标只随路径，query 仅 merchantId（booking-window 同款）。 */
  async weekTemplate(): Promise<WeekTemplateView> {
    const { merchantId, storeId } = this.target()
    return this.read(`/api/v1/merchant/stores/${storeId}/week-template`, { merchantId }, decodeWeekTemplateView)
  }

  /** §3 PUT week-template：一次全替换 + expectedVersion 乐观锁（改模板非减少可用性动作，
   *  已物化窗口一律不动，无 reason）。PUT {merchantId, days, expectedVersion} → 回执=新头
   *  （同 view 线形态，无 updatedAt）。这是「保存并生效」的唯一模板写路径——CCR-003 取代
   *  CCR-002 的 batch-generate 铺排（该端点保留为手动批量入口）。 */
  async saveWeekTemplate(slot: string, form: WeekTemplateForm, expectedVersion: string): Promise<WeekTemplateReceipt> {
    const { merchantId, storeId } = this.target()
    if (weekTemplateFormProblems(form).length) throw new Error('WORKSPACE_FORM_INVALID')
    return this.write(slot, `/api/v1/merchant/stores/${storeId}/week-template`, 'PUT', {
      merchantId,
      days: form.days.map(day => ({
        enabled: day.enabled,
        segments: day.segments.map(segment => ({ start: segment.start, end: segment.end, capacity: segment.capacity })),
      })),
      expectedVersion,
    }, decodeWeekTemplateView)
  }

  async staffWindows(staffId: string): Promise<StaffWindowPage> {
    const { merchantId, storeId } = this.target()
    return this.read(`/api/v1/merchant/staff/${staffId}/availability-windows`, { merchantId, storeId }, value => {
      const page = decodeStaffWindowPage(value)
      if (page.staffId !== staffId) throw new Error('INVALID_RESPONSE')
      return page
    })
  }

  async createStaffWindow(slot: string, staffId: string, input: StaffWindowFormInput): Promise<StaffWindowReceipt> {
    const { merchantId, storeId } = this.target()
    if (staffWindowFormProblems(input).length) throw new Error('WORKSPACE_FORM_INVALID')
    return this.write(slot, `/api/v1/merchant/staff/${staffId}/availability-windows`, 'POST', {
      merchantId, storeId,
      startAt: composeTimestamp(input.startDate, input.startTime),
      endAt: composeTimestamp(input.endDate, input.endTime),
    }, decodeStaffWindowReceipt)
  }

  async updateStaffWindow(slot: string, staffId: string, windowId: string, expectedVersion: string, input: StaffWindowFormInput): Promise<StaffWindowReceipt> {
    const { merchantId, storeId } = this.target()
    if (staffWindowFormProblems(input).length) throw new Error('WORKSPACE_FORM_INVALID')
    return this.write(slot, `/api/v1/merchant/staff/${staffId}/availability-windows/${windowId}`, 'PUT', {
      merchantId, storeId,
      startAt: composeTimestamp(input.startDate, input.startTime),
      endAt: composeTimestamp(input.endDate, input.endTime),
      expectedVersion,
    }, value => {
      const receipt = decodeStaffWindowReceipt(value)
      if (receipt.windowId !== windowId || receipt.staffId !== staffId) throw new Error('INVALID_RESPONSE')
      return receipt
    })
  }

  async closeStaffWindow(slot: string, staffId: string, windowId: string, expectedVersion: string, reason: string): Promise<StaffWindowReceipt> {
    const { merchantId, storeId } = this.target()
    if (reasonProblem(reason, true)) throw new Error('WORKSPACE_FORM_INVALID')
    return this.write(slot, `/api/v1/merchant/staff/${staffId}/availability-windows/${windowId}/close`, 'POST', {
      merchantId, storeId, expectedVersion, reason: reason.trim(),
    }, value => {
      const receipt = decodeStaffWindowReceipt(value)
      if (receipt.windowId !== windowId || receipt.staffId !== staffId || receipt.status !== 'CLOSED') throw new Error('INVALID_RESPONSE')
      return receipt
    })
  }

  async openStaffWindow(slot: string, staffId: string, windowId: string, expectedVersion: string): Promise<StaffWindowReceipt> {
    const { merchantId, storeId } = this.target()
    return this.write(slot, `/api/v1/merchant/staff/${staffId}/availability-windows/${windowId}/open`, 'POST', {
      merchantId, storeId, expectedVersion,
    }, value => {
      const receipt = decodeStaffWindowReceipt(value)
      if (receipt.windowId !== windowId || receipt.staffId !== staffId || receipt.status !== 'AVAILABLE') throw new Error('INVALID_RESPONSE')
      return receipt
    })
  }

  async capabilities(staffId: string): Promise<CapabilityView> {
    const { merchantId, storeId } = this.target()
    return this.read(`/api/v1/merchant/staff/${staffId}/service-capabilities`, { merchantId, storeId }, value => {
      const view = decodeCapabilityView(value)
      if (view.staffId !== staffId) throw new Error('INVALID_RESPONSE')
      return view
    })
  }

  async replaceCapabilities(slot: string, staffId: string, serviceIds: readonly string[], expectedVersion: string, reason: string): Promise<CapabilityView> {
    const { merchantId, storeId } = this.target()
    if (capabilityProblems(serviceIds, [], reason).length) throw new Error('WORKSPACE_FORM_INVALID')
    return this.write(slot, `/api/v1/merchant/staff/${staffId}/service-capabilities`, 'PUT', {
      merchantId, storeId, serviceIds: [...serviceIds], expectedVersion, reason: reason.trim() || undefined,
    }, value => {
      const view = decodeCapabilityView(value)
      if (view.staffId !== staffId) throw new Error('INVALID_RESPONSE')
      return view
    })
  }
}

/** Best-effort service options from the existing service-management list (first 100 rows):
 *  id, display name and fulfillmentType. Used by the capabilities page (SCHC-2 service
 *  authorization is unchanged by CCR-SCH-SLOT-001); the store-slot pages no longer consume
 *  it. When the service module is not reachable the page falls back to raw ids —
 *  enrichment failure never blocks a page. */
export type ScheduleServiceOption = Readonly<{
  serviceId: string; serviceName: string; fulfillmentType: 'IN_STORE' | 'PICKUP_DELIVERY' | null
}>
export async function loadServiceOptions(api: ConsumerApi, merchantId: string, storeId: string): Promise<readonly ScheduleServiceOption[]> {
  try {
    const page = await api.request({ method: 'GET', path: '/api/v1/merchant/services',
      data: { merchantId, storeId, page: 1, pageSize: 100 } }, decodeManagedServicePage)
    return page.items.map(item => ({ serviceId: item.serviceId,
      serviceName: item.serviceName || `服务 ${item.serviceId}`, fulfillmentType: item.fulfillmentType }))
  } catch {
    return []
  }
}
