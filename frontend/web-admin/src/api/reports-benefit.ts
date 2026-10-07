import apiClient from './client';

/** Строка сводного отчёта: зерно = (регион, льготная категория). */
export interface BenefitReportRow {
  regionId: string | null;
  regionName: string | null;
  benefitId: string | null;
  benefitCode: string | null;
  benefitName: string | null;
  tripsCount: number;
  compensation: string;
  /** Поездки без определённой ставки (нет активного тарифа или нет шагов льготы). */
  tripsWithoutRate: number;
}

export interface BenefitReportTotal {
  level: number;
  levelName: string;
  regionId: string | null;
  regionName: string | null;
  benefitId: string | null;
  benefitCode: string | null;
  benefitName: string | null;
  tripsCount: number;
  compensation: string;
  tripsWithoutRate: number;
}

export interface BenefitReport {
  title: string;
  dateFrom: string;
  dateTo: string;
  totalRows: number;
  limit: number;
  offset: number;
  totals: BenefitReportTotal[];
  grandTotalTrips: number;
  grandTotalCompensation: string;
  grandTripsWithoutRate: number;
  rows: BenefitReportRow[];
}

export interface BenefitReportParams {
  dateFrom: string;
  dateTo: string;
  regionId?: string;
  carrierId?: string;
  benefitId?: string;
  limit?: number;
  offset?: number;
}

export async function getBenefitReport(params: BenefitReportParams): Promise<BenefitReport> {
  const { data } = await apiClient.get<BenefitReport>('/reports/benefit-trips', { params });
  return data;
}
