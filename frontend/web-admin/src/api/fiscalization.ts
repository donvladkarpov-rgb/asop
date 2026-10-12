import apiClient from './client';
import type {
  CarrierFiscalizer,
  DistributorFiscalizer,
  Fiscalizer,
  FiscalizerToken,
} from '../types/reference';

export const getFiscalizers = () =>
  apiClient.get<Fiscalizer[]>('/fiscalizers').then((r) => r.data);

export const createFiscalizer = (data: Partial<Fiscalizer>) =>
  apiClient.post<Fiscalizer>('/fiscalizers', data).then((r) => r.data);

export const updateFiscalizer = (id: string, data: Partial<Fiscalizer>) =>
  apiClient.put<Fiscalizer>(`/fiscalizers/${id}`, data).then((r) => r.data);

export const deleteFiscalizer = (id: string) =>
  apiClient.delete(`/fiscalizers/${id}`);

export const getCarrierFiscalizers = (carrierId?: string) =>
  apiClient
    .get<CarrierFiscalizer[]>('/carrier-fiscalizers', carrierId ? { params: { carrierId } } : undefined)
    .then((r) => r.data);

export const createCarrierFiscalizer = (data: Partial<CarrierFiscalizer>) =>
  apiClient.post<CarrierFiscalizer>('/carrier-fiscalizers', data).then((r) => r.data);

export const updateCarrierFiscalizer = (id: string, data: Partial<CarrierFiscalizer>) =>
  apiClient.put<CarrierFiscalizer>(`/carrier-fiscalizers/${id}`, data).then((r) => r.data);

export const deleteCarrierFiscalizer = (id: string) =>
  apiClient.delete(`/carrier-fiscalizers/${id}`);

export const getDistributorFiscalizers = (cardsDistributorId?: string) =>
  apiClient
    .get<DistributorFiscalizer[]>('/distributor-fiscalizers', cardsDistributorId ? { params: { cardsDistributorId } } : undefined)
    .then((r) => r.data);

export const createDistributorFiscalizer = (data: Partial<DistributorFiscalizer>) =>
  apiClient.post<DistributorFiscalizer>('/distributor-fiscalizers', data).then((r) => r.data);

export const updateDistributorFiscalizer = (id: string, data: Partial<DistributorFiscalizer>) =>
  apiClient.put<DistributorFiscalizer>(`/distributor-fiscalizers/${id}`, data).then((r) => r.data);

export const deleteDistributorFiscalizer = (id: string) =>
  apiClient.delete(`/distributor-fiscalizers/${id}`);

export const getFiscalizerTokens = (params?: { carrierFiscalizerId?: string; distributorFiscalizerId?: string }) =>
  apiClient.get<FiscalizerToken[]>('/fiscalizer-tokens', { params }).then((r) => r.data);

export const createFiscalizerToken = (data: Record<string, unknown>) =>
  apiClient.post<FiscalizerToken>('/fiscalizer-tokens', data).then((r) => r.data);

export const updateFiscalizerToken = (id: string, data: Record<string, unknown>) =>
  apiClient.put<FiscalizerToken>(`/fiscalizer-tokens/${id}`, data).then((r) => r.data);

export const deleteFiscalizerToken = (id: string) =>
  apiClient.delete(`/fiscalizer-tokens/${id}`);
