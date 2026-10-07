import apiClient from './client';

/** Строка отчёта «Список смен» — 25 колонок (23 из внешнего CSV + ТК кол-во/сумма). */
export interface ShiftListRow {
  carrierName: string | null;
  shiftId: string;
  driverName: string;
  shiftStartedAt: string | null;
  shiftClosedAt: string | null;
  durationText: string | null;
  terminalSerial: string | null;
  vehicleNumber: string | null;
  vehicleTypeName: string | null;
  routeNumber: string | null;
  routeName: string | null;
  organizerName: string | null;
  territoryNames: string | null;
  routeCategoryLabel: string | null;
  routeStartedAt: string | null;
  routeEndedAt: string | null;
  bkCount: number;
  bkSum: string;
  mfkCount: number;
  mfkSum: string;
  tkCount: number;
  tkSum: string;
  cashCount: number;
  cashSum: string;
  totalCount: number;
  totalSum: string;
}

/** Итоги по всему набору смен (не зависят от limit/offset). */
export interface ShiftListTotals {
  shiftsCount: number;
  bkCount: number;
  bkSum: string;
  mfkCount: number;
  mfkSum: string;
  tkCount: number;
  tkSum: string;
  cashCount: number;
  cashSum: string;
  totalCount: number;
  totalSum: string;
}

export interface ShiftListReport {
  title: string;
  dateFrom: string;
  dateTo: string;
  totalRows: number;
  limit: number;
  offset: number;
  totals: ShiftListTotals;
  rows: ShiftListRow[];
}

export interface ShiftListReportParams {
  dateFrom: string;
  dateTo: string;
  regionId?: string;
  organizerId?: string;
  carrierId?: string;
  routeId?: string;
  pathId?: string;
  vehicleId?: string;
  terminalId?: string;
  driverId?: string;
  limit?: number;
  offset?: number;
}

export async function getShiftListReport(params: ShiftListReportParams): Promise<ShiftListReport> {
  const { data } = await apiClient.get<ShiftListReport>('/reports/shift-list', { params });
  return data;
}
