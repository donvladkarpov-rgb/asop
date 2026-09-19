import apiClient from './client';

export interface BankPayment {
  paymentId: string;
  requestId: string | null;
  status: string; // PENDING | AUTHORIZED | DECLINED | FAILED | REVERSED | REFUNDED
  amount: number | string;
  currency: string;
  paymentType: string; // TOPUP | FARE | DEBT_RECOVERY
  provider: string;
  acquirerReference: string | null;
  rrn: string | null;
  authCode: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  panLast4: string | null;
  terminalId: string | null;
  sessionId: string | null;
  transactionId: string | null;
  occurredAt: string | null;
  createdAt: string;
  deletedAt: string | null;
}

export const getPayments = (params?: {
  status?: string;
  paymentType?: string;
  terminalId?: string;
  includeDeleted?: boolean;
  limit?: number;
  offset?: number;
}) => apiClient.get<BankPayment[]>('/payments', { params }).then((r) => r.data);