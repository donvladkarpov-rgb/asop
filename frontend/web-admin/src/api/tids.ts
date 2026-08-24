import apiClient from './client';
import type { Tid } from '../types/reference';

/** GET /api/v1/tids — список TID (JOIN через банковские договоры, carrierId из договора). */
export const getTids = (carrierId?: string, regionId?: string) => {
  const params: Record<string, string> = {};
  if (carrierId) params.carrierId = carrierId;
  else if (regionId) params.regionId = regionId;
  return apiClient.get<Tid[]>('/tids', Object.keys(params).length ? { params } : undefined).then((r) => r.data);
}

export const getTidsByRegion = (regionId: string) =>
  apiClient.get<Tid[]>('/tids', { params: { regionId } }).then((r) => r.data);

export const getTid = (id: string) =>
  apiClient.get<Tid>(`/tids/${id}`).then((r) => r.data);

export const createTid = (data: Pick<Tid, 'contractId' | 'tidValue'>) =>
  apiClient.post<Tid>('/tids', data).then((r) => r.data);

export const updateTid = (id: string, data: Partial<Tid>) =>
  apiClient.put<Tid>(`/tids/${id}`, data).then((r) => r.data);

export const deleteTid = (id: string) =>
  apiClient.delete(`/tids/${id}`);
