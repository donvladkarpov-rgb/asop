import apiClient from './client';
import type { Tid } from '../types/reference';

export const getTids = (carrierId?: string) =>
  apiClient.get<Tid[]>('/tids', carrierId ? { params: { carrierId } } : undefined).then((r) => r.data);

export const getTid = (id: string) =>
  apiClient.get<Tid>(`/tids/${id}`).then((r) => r.data);

export const createTid = (data: Pick<Tid, 'carrierId' | 'tidValue'>) =>
  apiClient.post<Tid>('/tids', data).then((r) => r.data);

export const updateTid = (id: string, data: Partial<Tid>) =>
  apiClient.put<Tid>(`/tids/${id}`, data).then((r) => r.data);

export const deleteTid = (id: string) =>
  apiClient.delete(`/tids/${id}`);
