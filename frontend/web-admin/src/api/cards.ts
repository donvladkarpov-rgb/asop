import apiClient from './client';
import type { Card, PageResponse } from '../types';

export const getCards = (params?: Record<string, unknown>) =>
  apiClient.get<PageResponse<Card>>('/cards', { params }).then((r) => r.data);

export const getCard = (id: string) =>
  apiClient.get<Card>(`/cards/${id}`).then((r) => r.data);

export const issueCard = (data: Omit<Card, 'id' | 'issuedAt'>) =>
  apiClient.post<Card>('/cards/issue', data).then((r) => r.data);

export const blockCard = (id: string) =>
  apiClient.post<Card>(`/cards/${id}/block`).then((r) => r.data);
