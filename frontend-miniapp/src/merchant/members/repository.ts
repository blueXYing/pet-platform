import { ConsumerApi } from '../../shared/consumer-api'
import {
  decodeInvitationRow, decodeInvitationsPage, decodeMemberRow, decodeMembersPage,
  type InvitationRow, type InvitationsPage, type MemberRow, type MembersDeps, type MembersPage,
} from './model'

/**
 * Real wiring for the contract-54 member-management routes (OWNER side only; the employee
 * confirm channel lives on the C-side invitation page delivered with the workbench staff
 * entry). Until the backend switch is on, requests fail closed through the normal error
 * paths — the page never pretends a real integration succeeded. Writes journal per-slot
 * X-Request-Id commands through ConsumerApi.write so retries replay the same requestId.
 */
export class RealMembersRepository implements MembersDeps {
  constructor(private api: ConsumerApi, private merchantId: () => string, private storeId: () => string) {}

  private target(): { merchantId: string; storeId: string } {
    const merchantId = this.merchantId(), storeId = this.storeId()
    if (!merchantId || !storeId) throw new Error('WORKSPACE_PATH_MISMATCH')
    return { merchantId, storeId }
  }

  listMembers(page = 1, pageSize = 20): Promise<MembersPage> {
    const { merchantId, storeId } = this.target()
    return this.api.request(
      { method: 'GET', path: '/api/v1/merchant/staff-members', data: { merchantId, storeId, page, pageSize } },
      decodeMembersPage)
  }

  listInvitations(page = 1, pageSize = 20): Promise<InvitationsPage> {
    const { merchantId, storeId } = this.target()
    return this.api.request(
      { method: 'GET', path: '/api/v1/merchant/staff-members/invitations', data: { merchantId, storeId, page, pageSize } },
      decodeInvitationsPage)
  }

  async invite(slot: string, phone: string, memberName: string): Promise<InvitationRow> {
    const { merchantId, storeId } = this.target()
    return this.api.write(slot, {
      method: 'POST', path: '/api/v1/merchant/staff-members/invitations',
      data: { merchantId, storeId, phone, memberName, actions: ['merchant.order.verify'] },
    }, decodeInvitationRow)
  }

  async cancelInvitation(slot: string, invitationId: string, expectedVersion: string): Promise<InvitationRow> {
    const { merchantId, storeId } = this.target()
    return this.api.write(slot, {
      method: 'POST', path: `/api/v1/merchant/staff-members/invitations/${invitationId}/cancel`,
      data: { merchantId, storeId, expectedVersion },
    }, decodeInvitationRow)
  }

  async disableMember(slot: string, memberId: string, expectedVersion: string): Promise<MemberRow> {
    const { merchantId, storeId } = this.target()
    return this.api.write(slot, {
      method: 'POST', path: `/api/v1/merchant/staff-members/${memberId}/disable`,
      data: { merchantId, storeId, expectedVersion },
    }, decodeMemberRow)
  }

  async enableMember(slot: string, memberId: string, expectedVersion: string): Promise<MemberRow> {
    const { merchantId, storeId } = this.target()
    return this.api.write(slot, {
      method: 'POST', path: `/api/v1/merchant/staff-members/${memberId}/enable`,
      data: { merchantId, storeId, expectedVersion },
    }, decodeMemberRow)
  }
}
