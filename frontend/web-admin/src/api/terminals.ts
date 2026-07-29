import apiClient from './client';
import type { Terminal } from '../types';

export const getTerminals = (carrierId?: string, regionId?: string) => {
  const params: Record<string, string> = {};
  if (carrierId) params.carrierId = carrierId;
  if (regionId) params.regionId = regionId;
  return apiClient.get<Terminal[]>('/terminals', { params }).then((r) => r.data);
};

export const getTerminal = (id: string) =>
  apiClient.get<Terminal>(`/terminals/${id}`).then((r) => r.data);

export const blockTerminal = (id: string) =>
  apiClient.post<Terminal>(`/terminals/${id}/status`, { newStatus: 'BLOCKED' }).then((r) => r.data);
