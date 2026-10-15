import type { Transport } from '../request';
import {
  GovernanceFailure, ROOT, boundedPage, createGovernanceEnvelope, enumOf, exact, isoFilter,
  optional, pageOf, queryString, validId, validUtc,
} from './governanceHttp';

// Contract 64 schedule view face (HTTP10 §5.12, PRD 6.2.5): read-only supervision of
// merchant-owned schedule facts. Occupancy counts and states are server facts (53号 SOLD_OUT
// 口径); utilization renders from confirmedCount/configuredCapacity without further
// derivation. No write route exists in this family — schedule writes are the merchant face.
export const WINDOW_KINDS = ['GENERAL', 'PICKUP', 'RETURN'] as const;
export const WINDOW_STATUSES = ['OPEN', 'CLOSED', 'SOLD_OUT'] as const;
export const STAFF_WINDOW_STATUSES = ['AVAILABLE', 'CLOSED'] as const;
export const RESERVATION_STATUSES = ['TEMP_LOCKED', 'CONFIRMED', 'RELEASED', 'EXPIRED'] as const;
export type WindowKind = typeof WINDOW_KINDS[number];
export type WindowStatus = typeof WINDOW_STATUSES[number];
export type StaffWindowStatus = typeof STAFF_WINDOW_STATUSES[number];
export type ReservationStatus = typeof RESERVATION_STATUSES[number];

export type ScheduleWindowRow = {
  windowId: string; merchantId: string; storeId: string; serviceId: string;
  windowKind: WindowKind; startAt: string; endAt: string; configuredCapacity: number;
  status: WindowStatus; version: string; updatedAt: string;
  confirmedCount: number; tempLockedCount: number;
};
export type StaffWindowRow = {
  windowId: string; storeId: string; staffId: string; startAt: string; endAt: string;
  status: StaffWindowStatus; version: string; updatedAt: string;
};
export type ReservationRow = {
  reservationId: string; orderId: string; storeId: string; serviceId: string;
  fulfillmentType: string; startAt: string; endAt: string;
  pickupStartAt: string | null; returnStartAt: string | null; status: ReservationStatus;
  lockExpireAt: string | null; capacitySnapshot: number; qualifiedStaffCountSnapshot: number;
  createdAt: string;
};
export type WindowFilters = {
  merchantId?: string; storeId?: string; serviceId?: string; windowKind?: WindowKind;
  status?: WindowStatus; startFrom?: string; startTo?: string;
};
export type StaffWindowFilters = {
  storeId?: string; staffId?: string; status?: StaffWindowStatus;
  startFrom?: string; startTo?: string;
};
export type ReservationFilters = {
  merchantId?: string; storeId?: string; serviceId?: string; orderId?: string;
  status?: ReservationStatus; startFrom?: string; startTo?: string;
};

const idOf = validId;
const utc = validUtc;
const windowKind = enumOf<WindowKind>(WINDOW_KINDS);
const windowStatus = enumOf<WindowStatus>(WINDOW_STATUSES);
const staffWindowStatus = enumOf<StaffWindowStatus>(STAFF_WINDOW_STATUSES);
const reservationStatus = enumOf<ReservationStatus>(RESERVATION_STATUSES);
const nonNegative = (v: unknown) => typeof v === 'number' && Number.isInteger(v) && v >= 0;
// CAS versions start at 0 (53号 wire) — not Snowflake ids.
const versionOf = (v: unknown): v is string => typeof v === 'string' && /^(0|[1-9][0-9]{0,17})(?![\s\S])/.test(v);

function windowRow(v: unknown): v is ScheduleWindowRow {
  return exact(v, ['windowId', 'merchantId', 'storeId', 'serviceId', 'windowKind', 'startAt', 'endAt', 'configuredCapacity', 'status', 'version', 'updatedAt', 'confirmedCount', 'tempLockedCount'])
    && idOf(v.windowId) && idOf(v.merchantId) && idOf(v.storeId) && idOf(v.serviceId)
    && windowKind(v.windowKind) && utc(v.startAt) && utc(v.endAt)
    && nonNegative(v.configuredCapacity) && windowStatus(v.status) && versionOf(v.version)
    && utc(v.updatedAt) && nonNegative(v.confirmedCount) && nonNegative(v.tempLockedCount);
}
function staffWindowRow(v: unknown): v is StaffWindowRow {
  return exact(v, ['windowId', 'storeId', 'staffId', 'startAt', 'endAt', 'status', 'version', 'updatedAt'])
    && idOf(v.windowId) && idOf(v.storeId) && idOf(v.staffId) && utc(v.startAt) && utc(v.endAt)
    && staffWindowStatus(v.status) && versionOf(v.version) && utc(v.updatedAt);
}
function reservationRow(v: unknown): v is ReservationRow {
  return exact(v, ['reservationId', 'orderId', 'storeId', 'serviceId', 'fulfillmentType', 'startAt', 'endAt', 'pickupStartAt', 'returnStartAt', 'status', 'lockExpireAt', 'capacitySnapshot', 'qualifiedStaffCountSnapshot', 'createdAt'])
    && idOf(v.reservationId) && idOf(v.orderId) && idOf(v.storeId) && idOf(v.serviceId)
    && typeof v.fulfillmentType === 'string' && v.fulfillmentType.length >= 1
    && utc(v.startAt) && utc(v.endAt)
    && optional(v.pickupStartAt, utc) && optional(v.returnStartAt, utc)
    && reservationStatus(v.status) && optional(v.lockExpireAt, utc)
    && nonNegative(v.capacitySnapshot) && nonNegative(v.qualifiedStaffCountSnapshot)
    && utc(v.createdAt);
}

export function windowStatusLabel(value: string) {
  return ({ OPEN: '开放', CLOSED: '已关闭', SOLD_OUT: '已约满' } as Record<string, string>)[value] ?? value;
}
export function reservationStatusLabel(value: string) {
  return ({
    TEMP_LOCKED: '临时锁定', CONFIRMED: '已确认', RELEASED: '已释放', EXPIRED: '锁定超时释放',
  } as Record<string, string>)[value] ?? value;
}
export function windowKindLabel(value: string) {
  return ({ GENERAL: '到店', PICKUP: '上门', RETURN: '送回' } as Record<string, string>)[value] ?? value;
}

export function createScheduleViewClient(transport: Transport = fetch) {
  const http = createGovernanceEnvelope(transport);
  return {
    resetContext: http.resetContext,
    async listWindows(page: number, filters: WindowFilters = {}, pageSize = 20): Promise<SchedulePage<ScheduleWindowRow>> {
      boundedPage(page, pageSize);
      const data = await http.envelope(`${ROOT}/schedule/windows${queryString({
        page, pageSize,
        merchantId: filters.merchantId, storeId: filters.storeId, serviceId: filters.serviceId,
        windowKind: filters.windowKind, status: filters.status,
        startFrom: isoFilter(filters.startFrom), startTo: isoFilter(filters.startTo),
      })}`);
      const check = pageOf<ScheduleWindowRow>(windowRow, page, pageSize);
      if (!check(data)) throw new GovernanceFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async listStaffWindows(page: number, filters: StaffWindowFilters = {}, pageSize = 20): Promise<SchedulePage<StaffWindowRow>> {
      boundedPage(page, pageSize);
      const data = await http.envelope(`${ROOT}/schedule/staff-windows${queryString({
        page, pageSize,
        storeId: filters.storeId, staffId: filters.staffId, status: filters.status,
        startFrom: isoFilter(filters.startFrom), startTo: isoFilter(filters.startTo),
      })}`);
      const check = pageOf<StaffWindowRow>(staffWindowRow, page, pageSize);
      if (!check(data)) throw new GovernanceFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async listReservations(page: number, filters: ReservationFilters = {}, pageSize = 20): Promise<SchedulePage<ReservationRow>> {
      boundedPage(page, pageSize);
      const data = await http.envelope(`${ROOT}/schedule/reservations${queryString({
        page, pageSize,
        merchantId: filters.merchantId, storeId: filters.storeId, serviceId: filters.serviceId,
        orderId: filters.orderId, status: filters.status,
        startFrom: isoFilter(filters.startFrom), startTo: isoFilter(filters.startTo),
      })}`);
      const check = pageOf<ReservationRow>(reservationRow, page, pageSize);
      if (!check(data)) throw new GovernanceFailure('INVALID_RESPONSE', 200);
      return data;
    },
  };
}
export type SchedulePage<T> = { page: number; pageSize: number; total: number; items: T[] };
export type ScheduleViewClient = ReturnType<typeof createScheduleViewClient>;
