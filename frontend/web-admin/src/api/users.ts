import apiClient from './client';

/** Синхронная смена пароля (proxy → user-service → Keycloak) */
export const changePassword = (currentPassword: string, newPassword: string) =>
  apiClient.post<void>('/users/password/change', { currentPassword, newPassword });
