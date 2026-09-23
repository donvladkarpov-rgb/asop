import apiClient from './client';

export interface BlacklistEntry {
  cardId: string;
  blockType: string; // NEGATIVE_BALANCE | PERMANENT
  blockedAt: string;
  relatedDebtId: string | null;
  autoUnblockOnRecovery: boolean;
  createdAt: string;
  updatedAt: string;
  deletedAt: string | null;
  version: number | null;
}

export interface BlacklistBlockRequest {
  cardId: string;
  blockType: string;
  relatedDebtId?: string | null;
  autoUnblockOnRecovery?: boolean;
}

export const getBlacklists = (params?: { blockType?: string; includeDeleted?: boolean; limit?: number }) =>
  apiClient.get<BlacklistEntry[]>('/blacklists', { params }).then((r) => r.data);

export const blockCard = (data: BlacklistBlockRequest) =>
  apiClient.post<BlacklistEntry>('/blacklists', data).then((r) => r.data);

export const unblockCard = (cardId: string) =>
  apiClient.delete(`/blacklists/${cardId}`);