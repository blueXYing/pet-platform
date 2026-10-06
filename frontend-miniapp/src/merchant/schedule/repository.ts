import { ApiError } from '../../shared/request'
import { ConsumerApi } from '../../shared/consumer-api'
import { decodeManagedServicePage } from '../services/model'
import {
  capabilityProblems, composeTimestamp, decodeBatchCloseResult, decodeCapabilityView,
  decodeStaffWindowPage, decodeStaffWindowReceipt, decodeWindowPage, decodeWindowReceipt,
  reasonProblem, staffWindowFormProblems, windowFormProblems,
  type BatchCloseResult, type CapabilityView, type ScheduleDeps, type ScheduleWindowPage,
  type ScheduleWindowReceipt, type StaffWindowFormInput, type StaffWindowReceipt,
  type StaffWindowPage, type WindowFilter, type WindowFormInput,
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

  private receiptsFilter(filter: WindowFilter, merchantId: string): Record<string, unknown> {
    const data: Record<string, unknown> = { merchantId }
    if (filter.serviceId) data.serviceId = filter.serviceId
    // The page fetches unfiltered on purpose: the workbench summary needs all three status
    // counts (OPEN/SOLD_OUT/CLOSED), so tabs group client-side. Contract §3.1 allows
    // status=SOLD_OUT server-side filtering and the controller now accepts all three
    // values (fixed alongside this slice).
    return data
  }

  async windows(filter: WindowFilter): Promise<ScheduleWindowPage> {
    const { merchantId, storeId } = this.target()
    return this.read(`/api/v1/merchant/stores/${storeId}/availability-windows`,
      this.receiptsFilter(filter, merchantId), decodeWindowPage)
  }

  async createWindow(slot: string, input: WindowFormInput): Promise<ScheduleWindowReceipt> {
    const { merchantId, storeId } = this.target()
    if (windowFormProblems(input).length) throw new Error('WORKSPACE_FORM_INVALID')
    return this.write(slot, `/api/v1/merchant/stores/${storeId}/availability-windows`, 'POST', {
      merchantId, serviceId: input.serviceId, windowKind: input.windowKind,
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
 *  id, display name and fulfillmentType (the page narrows window kinds per PRD29 guidance).
 *  The schedule contract carries serviceId only; when the service module is not reachable
 *  the pages fall back to raw ids — enrichment failure never blocks a page. */
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
