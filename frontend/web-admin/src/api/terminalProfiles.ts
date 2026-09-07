import apiClient from './client';
import type { TerminalProfile } from '../types/reference';

export const getTerminalProfiles = () =>
  apiClient.get<TerminalProfile[]>('/terminal-profiles').then((r) => r.data);

export const getTerminalProfile = (id: string) =>
  apiClient.get<TerminalProfile>(`/terminal-profiles/${id}`).then((r) => r.data);

export const createTerminalProfile = (data: Partial<TerminalProfile>) =>
  apiClient.post<TerminalProfile>('/terminal-profiles', data).then((r) => r.data);

export const updateTerminalProfile = (id: string, data: Partial<TerminalProfile>) =>
  apiClient.put<TerminalProfile>(`/terminal-profiles/${id}`, data).then((r) => r.data);

export const deleteTerminalProfile = (id: string) =>
  apiClient.delete(`/terminal-profiles/${id}`);