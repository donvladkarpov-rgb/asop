import apiClient from './client';
import type { Terminal, PageResponse } from '../types';

export const getTerminals = (params?: Record<string, unknown>) =>
  apiClient.get<PageResponse<Terminal>>('/terminals', { params }).then((r) => r.data);

export const getTerminal = (id: string) =>
  apiClient.get<Terminal>(`/terminals/${id}`).then((r) => r.data);

export const registerTerminal = (data: Omit<Terminal, 'id' | 'lastSeenAt' | 'certificateId'>) =>
  apiClient.post<Terminal>('/terminals/register', data).then((r) => r.data);

export const blockTerminal = (id: string) =>
  apiClient.post<Terminal>(`/terminals/${id}/block`).then((r) => r.data);
