import apiClient from './client';
import type { Contract } from '../types/reference';

export const getContracts = () =>
  apiClient.get<Contract[]>('/contracts').then((r) => r.data);

export const getContract = (id: string) =>
  apiClient.get<Contract>(`/contracts/${id}`).then((r) => r.data);

export const createContract = (data: Partial<Contract>) =>
  apiClient.post<Contract>('/contracts', data).then((r) => r.data);

export const updateContract = (id: string, data: Record<string, unknown>) =>
  apiClient.put<Contract>(`/contracts/${id}`, data).then((r) => r.data);

export const deleteContract = (id: string) =>
  apiClient.delete(`/contracts/${id}`);
