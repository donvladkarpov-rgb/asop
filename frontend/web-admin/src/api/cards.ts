import apiClient from './client';
import type { Card } from '../types';

export interface CardsListParams {
  regionId?: string;
  carrierId?: string;
  userId?: string;
  includeDeleted?: boolean;
  limit?: number;
}

/** GET /api/v1/cards — список карт (JOIN ASOP_CARDS + ASOP_CARD_MIFARES), через gateway sync-proxy. */
export const getCards = (params?: CardsListParams) =>
  apiClient.get<Card[]>('/cards', { params }).then((r) => r.data);

export const getCard = (id: string) =>
  apiClient.get<Card>(`/cards/${id}`).then((r) => r.data);
