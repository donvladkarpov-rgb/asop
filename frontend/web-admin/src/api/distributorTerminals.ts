import apiClient from './client';
import type { DistributorTerminal } from '../types/reference';

export const getDistributorTerminals = (cardsDistributorId?: string) => {
  const params: Record<string, string> = {};
  if (cardsDistributorId) params.cardsDistributorId = cardsDistributorId;
  return apiClient.get<DistributorTerminal[]>('/distributor-terminals', { params }).then((r) => r.data);
};

export const getDistributorTerminal = (id: string) =>
  apiClient.get<DistributorTerminal>(`/distributor-terminals/${id}`).then((r) => r.data);

export const createDistributorTerminal = (data: Partial<DistributorTerminal>) =>
  apiClient.post<DistributorTerminal>('/distributor-terminals', data).then((r) => r.data);

export const updateDistributorTerminal = (id: string, data: Partial<DistributorTerminal>) =>
  apiClient.put<DistributorTerminal>(`/distributor-terminals/${id}`, data).then((r) => r.data);

export const deleteDistributorTerminal = (id: string) =>
  apiClient.delete(`/distributor-terminals/${id}`);