import { ConsumerApi } from '../../shared/consumer-api'
import {
  decodeStaffConfirmReceipt, decodeStaffInvitationDetail, decodeStaffInvitationPage,
  type StaffInvitationDeps, type StaffInvitationListDeps,
} from './model'

/**
 * Real wiring for the contract-54 employee-side invitation routes (CStaffInvitationController,
 * switch pet.merchant.staff-member.enabled + pet.auth.c.enabled default OFF — until the platform
 * enables them the requests fail closed through the normal error paths and the page renders the
 * non-interactive panel; it never pretends a real integration succeeded). Both routes are
 * identity-scoped: the server resolves the employee from the MINIAPP session and compares it
 * with the invitation phone inside the read/confirm kernel, so the requests carry no
 * merchant/store coordinates at all. Confirm is a journaled per-slot X-Request-Id command via
 * ConsumerApi.write so retries replay the same requestId (54 §2 idempotency).
 */
export class RealStaffInvitationRepository implements StaffInvitationDeps, StaffInvitationListDeps {
  constructor(private api: ConsumerApi) {}

  /** GET with no query bytes — the controller rejects any request parameter. */
  read(invitationId: string): Promise<ReturnType<typeof decodeStaffInvitationDetail>> {
    return this.api.request(
      { method: 'GET', path: `/api/v1/c/staff/invitations/${invitationId}` },
      decodeStaffInvitationDetail)
  }

  confirm(slot: string, invitationId: string) {
    return this.api.write(slot, {
      method: 'POST', path: `/api/v1/c/staff/invitations/${invitationId}/confirm`, data: {},
    }, decodeStaffConfirmReceipt)
  }

  /** §7 list: identity-scoped paging only (server-side phone match, fixed id DESC order). */
  list(page: number, pageSize: number) {
    return this.api.request(
      { method: 'GET', path: '/api/v1/c/staff/invitations', data: { page, pageSize } },
      decodeStaffInvitationPage)
  }
}
