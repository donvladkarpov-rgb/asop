import apiClient from './client';

/** Строка «Z отчёта (дистрибьюторы)» — один фискальный чек дистрибьютора карт. */
export interface DistributorZReportRow {
  distributorName: string | null;
  distributorInn: string | null;
  receiptId: string;
  receiptNumber: string | null;
  fiscalSign: string | null;
  createdAt: string;
  amount: string | null;
  paymentMethod: string;
  status: string | null;
}

/** Итоги по полному набору чеков: кол-во + разбивка суммы наличные/банковская карта. */
export interface DistributorZReportTotals {
  receiptsCount: number;
  cashCount: number;
  cashSum: string;
  bankCount: number;
  bankSum: string;
  totalSum: string;
}

export interface DistributorZReport {
  title: string;
  dateFrom: string;
  dateTo: string;
  totalRows: number;
  limit: number;
  offset: number;
  totals: DistributorZReportTotals;
  rows: DistributorZReportRow[];
}

export interface DistributorZReportParams {
  dateFrom: string;
  dateTo: string;
  cardsDistributorId?: string;
  limit?: number;
  offset?: number;
}

export async function getDistributorZReport(params: DistributorZReportParams): Promise<DistributorZReport> {
  const { data } = await apiClient.get<DistributorZReport>('/reports/distributor-z-report', { params });
  return data;
}
