import apiClient from './client';
import type { User, PageResponse } from '../types';

// Для списка пользователей gateway проксирует на user-service
export const getUsers = (params?: Record<string, unknown>) =>
  apiClient.get<PageResponse<User>>('/users', { params }).then((r) => r.data);

export const getUser = (id: string) =>
  apiClient.get<User>(`/users/${id}`).then((r) => r.data);

export const createUser = (data: Omit<User, 'id' | 'createdAt'>) =>
  apiClient.post<User>('/users', data).then((r) => r.data);

export const updateUser = (id: string, data: Partial<User>) =>
  apiClient.put<User>(`/users/${id}`, data).then((r) => r.data);

export const deleteUser = (id: string) =>
  apiClient.delete(`/users/${id}`).then((r) => r.data);

/** Синхронная смена пароля (proxy → user-service → Keycloak) */
export const changePassword = (currentPassword: string, newPassword: string) =>
  apiClient.post<void>('/users/password/change', { currentPassword, newPassword });
