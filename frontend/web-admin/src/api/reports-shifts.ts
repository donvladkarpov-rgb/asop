import apiClient from './client';

export interface ShiftReportRow {
  organizerId: string | null;
  organizerName: string | null;
  carrierId: string | null;
  carrierName: string | null;
  routeId: string | null;
  routeNumber: string | null;
  routeName: string | null;
  shiftId: string;
  vehicleTypeName: string | null;
  vehicleModelName: string | null;
  vehicleNumber: string | null;
  vehicleName: string | null;
  terminalSerial: string | null;
  terminalNumber: string | null;
  shiftStartedAt: string | null;
  shiftClosedAt: string | null;
  transactionsCount: number;
  successfulCardTransactions: number;
  failedCardTransactions: number;
  failedSharePct: string;
  cashlessAmount: string;
  cashlessAmountWithoutDiscount: string;
  cashlessCount: number;
  cashlessSharePct: string;
  cashAmount: string;
  cashCount: number;
}

export interface ShiftReportTotal {
  level: number;
  levelName: string;
  organizerId: string | null;
  organizerName: string | null;
  carrierId: string | null;
  carrierName: string | null;
  routeId: string | null;
  routeLabel: string | null;
  shiftId: string | null;
  shiftsCount: number;
  transactionsCount: number;
  cashlessAmount: string;
  cashAmount: string;
}

export interface ShiftReport {
  title: string;
  dateFrom: string;
  dateTo: string;
  totalRows: number;
  limit: number;
  offset: number;
  totals: ShiftReportTotal[];
  grandTotalTransactions: number;
  grandTotalCashlessAmount: string;
  grandTotalCashAmount: string;
  rows: ShiftReportRow[];
}

export interface ShiftReportParams {
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

export async function getShiftReport(params: ShiftReportParams): Promise<ShiftReport> {
  const { data } = await apiClient.get<ShiftReport>('/reports/shifts', { params });
  return data;
}
