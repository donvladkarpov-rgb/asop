import apiClient from './client';
import type { ConfigParam } from '../types/reference';

export const getConfigParams = () =>
  apiClient.get<ConfigParam[]>('/config-params').then((r) => r.data);

export const getConfigParam = (id: string) =>
  apiClient.get<ConfigParam>(`/config-params/${id}`).then((r) => r.data);

export const createConfigParam = (data: Partial<ConfigParam>) =>
  apiClient.post<ConfigParam>('/config-params', data).then((r) => r.data);

export const updateConfigParam = (id: string, data: Partial<ConfigParam>) =>
  apiClient.put<ConfigParam>(`/config-params/${id}`, data).then((r) => r.data);

export const deleteConfigParam = (id: string) =>
  apiClient.delete(`/config-params/${id}`);