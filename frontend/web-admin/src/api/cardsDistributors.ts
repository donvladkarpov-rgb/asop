import apiClient from './client';
import type { CardsDistributor } from '../types/reference';

export const getCardsDistributors = () =>
  apiClient.get<CardsDistributor[]>('/cards-distributors').then((r) => r.data);

export const getCardsDistributor = (id: string) =>
  apiClient.get<CardsDistributor>(`/cards-distributors/${id}`).then((r) => r.data);

export const createCardsDistributor = (data: Partial<CardsDistributor>) =>
  apiClient.post<CardsDistributor>('/cards-distributors', data).then((r) => r.data);

export const updateCardsDistributor = (id: string, data: Partial<CardsDistributor>) =>
  apiClient.put<CardsDistributor>(`/cards-distributors/${id}`, data).then((r) => r.data);

export const deleteCardsDistributor = (id: string) =>
  apiClient.delete(`/cards-distributors/${id}`);
