import apiClient from './client';
import type { ThreeDesKey } from '../types/reference';

export const getThreeDesKeys = () =>
  apiClient.get<ThreeDesKey[]>('/three-des-keys').then((r) => r.data);

export const generateThreeDesKey = () =>
  apiClient.post<ThreeDesKey>('/three-des-keys').then((r) => r.data);

export const deleteThreeDesKey = (id: string) =>
  apiClient.delete(`/three-des-keys/${id}`);