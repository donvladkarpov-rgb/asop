import apiClient from './client';
import type { Carrier } from '../types/reference';
import type { AcceptedResponse } from '../hooks/useCommand';

export const getCarriers = (regionId?: string) =>
  apiClient.get<Carrier[]>('/carriers', regionId ? { params: { regionId } } : undefined).then((r) => r.data);

export const getCarrier = (id: string) =>
  apiClient.get<Carrier>(`/carriers/${id}`).then((r) => r.data);

export const createCarrier = (data: Pick<Carrier, 'carrierName' | 'inn' | 'regionId'>) =>
  apiClient.post<AcceptedResponse>('/carriers', data).then((r) => r.data);

export const updateCarrier = (id: string, data: Partial<Carrier>) =>
  apiClient.put<Carrier>(`/carriers/${id}`, data).then((r) => r.data);

export const deleteCarrier = (id: string) =>
  apiClient.delete(`/carriers/${id}`);
