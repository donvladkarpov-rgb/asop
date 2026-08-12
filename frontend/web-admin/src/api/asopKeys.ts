import apiClient from './client';
import type { AsopKey } from '../types/reference';

export const getAsopKeys = () =>
  apiClient.get<AsopKey[]>('/asop-keys').then((r) => r.data);

export const generateAsopKey = () =>
  apiClient.post<AsopKey>('/asop-keys').then((r) => r.data);

export const deleteAsopKey = (id: string) =>
  apiClient.delete(`/asop-keys/${id}`);