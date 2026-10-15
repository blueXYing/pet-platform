import type { Transport } from '../request';
import {
  GovernanceFailure, ROOT, boundedPage, createGovernanceEnvelope, enumOf, exact,
  name, optional, pageOf, queryString, validId, validUtc,
} from './governanceHttp';

// Contract 64 staff governance face (HTTP10 §5.12, PRD 6.2.6): read-only supervision of the
// merchant-owned staff profile and 52/54号 binding facts. Phones arrive pre-masked from the
// kernel and member names come from the CONFIRMED invitation row; this client only renders
// server facts. SSOT §30 grants the operator no staff write action — no write method exists.
export const EMPLOYMENT_STATUSES = ['ACTIVE', 'INACTIVE'] as const;
export const MEMBER_STATUSES = ['ENABLED', 'DISABLED', 'REVOKED'] as const;
export const INVITATION_STATUSES = ['INVITED', 'CONFIRMED', 'CANCELED'] as const;
export type EmploymentStatus = typeof EMPLOYMENT_STATUSES[number];
export type MemberStatus = typeof MEMBER_STATUSES[number];
export type InvitationStatus = typeof INVITATION_STATUSES[number];

export type StaffProfileRow = {
  staffId: string; storeId: string; staffName: string; phoneMasked: string | null;
  employmentStatus: EmploymentStatus; serviceEnabled: boolean; version: string;
  createdAt: string; updatedAt: string;
};
export type StaffMemberRow = {
  memberId: string; storeId: string; status: MemberStatus; memberName: string | null;
  phoneMasked: string | null; grantedActions: string[]; grantRevoked: boolean;
  memberVersion: string; grantVersion: string | null; createdAt: string; updatedAt: string;
};
export type StaffInvitationRow = {
  invitationId: string; storeId: string; memberName: string; phoneMasked: string;
  status: InvitationStatus; grantedActions: string[]; invitedBy: string;
  invitedAt: string; updatedAt: string;
};
export type StaffFilters = {
  storeId?: string; employmentStatus?: EmploymentStatus; serviceEnabled?: boolean;
};
export type MemberFilters = { storeId?: string; status?: MemberStatus };
export type InvitationFilters = { storeId?: string; status?: InvitationStatus };

const employmentStatus = enumOf<EmploymentStatus>(EMPLOYMENT_STATUSES);
const memberStatus = enumOf<MemberStatus>(MEMBER_STATUSES);
const invitationStatus = enumOf<InvitationStatus>(INVITATION_STATUSES);
const maskedPhone = (v: unknown) => typeof v === 'string' && v.length >= 4 && v.length <= 11;
const actionCode = (v: unknown) => typeof v === 'string' && v.length >= 1 && v.length <= 100;
// Row/CAS versions start at 0 (35/54号 wire) — not Snowflake ids.
const versionOf = (v: unknown): v is string => typeof v === 'string' && /^(0|[1-9][0-9]{0,17})(?![\s\S])/.test(v);

function staffRow(v: unknown): v is StaffProfileRow {
  return exact(v, ['staffId', 'storeId', 'staffName', 'phoneMasked', 'employmentStatus', 'serviceEnabled', 'version', 'createdAt', 'updatedAt'])
    && validId(v.staffId) && validId(v.storeId) && name(v.staffName)
    && optional(v.phoneMasked, maskedPhone) && employmentStatus(v.employmentStatus)
    && typeof v.serviceEnabled === 'boolean' && versionOf(v.version)
    && validUtc(v.createdAt) && validUtc(v.updatedAt);
}
function memberRow(v: unknown): v is StaffMemberRow {
  return exact(v, ['memberId', 'storeId', 'status', 'memberName', 'phoneMasked', 'grantedActions', 'grantRevoked', 'memberVersion', 'grantVersion', 'createdAt', 'updatedAt'])
    && validId(v.memberId) && validId(v.storeId) && memberStatus(v.status)
    && optional(v.memberName, x => typeof x === 'string' && x.length >= 1 && x.length <= 64)
    && optional(v.phoneMasked, maskedPhone) && Array.isArray(v.grantedActions)
    && (v.grantedActions as unknown[]).every(actionCode)
    && typeof v.grantRevoked === 'boolean' && versionOf(v.memberVersion)
    && optional(v.grantVersion, versionOf) && validUtc(v.createdAt) && validUtc(v.updatedAt);
}
function invitationRow(v: unknown): v is StaffInvitationRow {
  return exact(v, ['invitationId', 'storeId', 'memberName', 'phoneMasked', 'status', 'grantedActions', 'invitedBy', 'invitedAt', 'updatedAt'])
    && validId(v.invitationId) && validId(v.storeId) && typeof v.memberName === 'string'
    && v.memberName.length >= 1 && v.memberName.length <= 64 && maskedPhone(v.phoneMasked)
    && invitationStatus(v.status) && Array.isArray(v.grantedActions)
    && (v.grantedActions as unknown[]).every(actionCode)
    && validId(v.invitedBy) && validUtc(v.invitedAt) && validUtc(v.updatedAt);
}

export function employmentLabel(value: string) {
  return ({ ACTIVE: '在职', INACTIVE: '停用' } as Record<string, string>)[value] ?? value;
}
export function memberStatusLabel(value: string) {
  return ({ ENABLED: '启用', DISABLED: '已停用', REVOKED: '已撤销' } as Record<string, string>)[value] ?? value;
}
export function invitationStatusLabel(value: string) {
  return ({ INVITED: '待确认', CONFIRMED: '已确认', CANCELED: '已撤销' } as Record<string, string>)[value] ?? value;
}

export function createStaffGovernanceClient(transport: Transport = fetch) {
  const http = createGovernanceEnvelope(transport);
  const scoped = (merchantId: string, suffix: string) => {
    if (!validId(merchantId)) throw new GovernanceFailure('INVALID_ARGUMENT', 0);
    return `${ROOT}/merchants/${merchantId}/${suffix}`;
  };
  return {
    resetContext: http.resetContext,
    async listStaff(merchantId: string, page: number, filters: StaffFilters = {}, pageSize = 20): Promise<StaffPage<StaffProfileRow>> {
      boundedPage(page, pageSize);
      const data = await http.envelope(`${scoped(merchantId, 'staff')}${queryString({
        page, pageSize,
        storeId: filters.storeId, employmentStatus: filters.employmentStatus,
        serviceEnabled: filters.serviceEnabled,
      })}`);
      const check = pageOf<StaffProfileRow>(staffRow, page, pageSize);
      if (!check(data)) throw new GovernanceFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async listMembers(merchantId: string, page: number, filters: MemberFilters = {}, pageSize = 20): Promise<StaffPage<StaffMemberRow>> {
      boundedPage(page, pageSize);
      const data = await http.envelope(`${scoped(merchantId, 'staff-members')}${queryString({
        page, pageSize, storeId: filters.storeId, status: filters.status,
      })}`);
      const check = pageOf<StaffMemberRow>(memberRow, page, pageSize);
      if (!check(data)) throw new GovernanceFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async listInvitations(merchantId: string, page: number, filters: InvitationFilters = {}, pageSize = 20): Promise<StaffPage<StaffInvitationRow>> {
      boundedPage(page, pageSize);
      const data = await http.envelope(`${scoped(merchantId, 'staff-members/invitations')}${queryString({
        page, pageSize, storeId: filters.storeId, status: filters.status,
      })}`);
      const check = pageOf<StaffInvitationRow>(invitationRow, page, pageSize);
      if (!check(data)) throw new GovernanceFailure('INVALID_RESPONSE', 200);
      return data;
    },
  };
}
export type StaffPage<T> = { page: number; pageSize: number; total: number; items: T[] };
export type StaffGovernanceClient = ReturnType<typeof createStaffGovernanceClient>;
