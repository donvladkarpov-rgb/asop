import apiClient from './client';

/** Строка сводки Z-отчёта по смене — 4 категории оплат + итог «Билетов». */
export interface ZReportRow {
  carrierName: string | null;
  carrierInn: string | null;
  shiftId: string;
  driverName: string;
  shiftStartedAt: string | null;
  shiftClosedAt: string | null;
  durationText: string | null;
  terminalSerial: string | null;
  vehicleNumber: string | null;
  routeNumber: string | null;
  routeName: string | null;
  organizerName: string | null;
  cashCount: number;
  cashSum: string;
  bankCount: number;
  bankSum: string;
  mifareCount: number;
  mifareSum: string;
  benefitCount: number;
  benefitSum: string;
  totalCount: number;
  totalSum: string;
}

/** Строка детализации Z-отчёта по маршрутам (уровень рейса/сегмента). */
export interface ZReportSegment {
  shiftId: string;
  routeNumber: string | null;
  routeName: string | null;
  segmentStartedAt: string | null;
  segmentEndedAt: string | null;
  cashCount: number;
  cashSum: string;
  bankCount: number;
  bankSum: string;
  mifareCount: number;
  mifareSum: string;
  benefitCount: number;
  benefitSum: string;
  totalCount: number;
  totalSum: string;
}

/** Итоги по полному набору смен (не зависят от limit/offset). */
export interface ZReportTotals {
  shiftsCount: number;
  cashCount: number;
  cashSum: string;
  bankCount: number;
  bankSum: string;
  mifareCount: number;
  mifareSum: string;
  benefitCount: number;
  benefitSum: string;
  totalCount: number;
  totalSum: string;
}

export interface ZReport {
  title: string;
  dateFrom: string;
  dateTo: string;
  totalRows: number;
  limit: number;
  offset: number;
  totals: ZReportTotals;
  rows: ZReportRow[];
  segments: ZReportSegment[];
}

export interface ZReportParams {
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

export async function getZReport(params: ZReportParams): Promise<ZReport> {
  const { data } = await apiClient.get<ZReport>('/reports/z-report', { params });
  return data;
}
