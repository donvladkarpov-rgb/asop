import apiClient from './client';
import type { Region, Territory, Organizer, OrganizerTerritory } from '../types/reference';

export const getRegions = () =>
  apiClient.get<Region[]>('/regions').then((r) => r.data);

export const createRegion = (data: Partial<Region>) =>
  apiClient.post<Region>('/regions', data).then((r) => r.data);

export const updateRegion = (id: string, data: Partial<Region>) =>
  apiClient.put<Region>(`/regions/${id}`, data).then((r) => r.data);

export const deleteRegion = (id: string) =>
  apiClient.delete(`/regions/${id}`);

export const getTerritories = (regionId?: string) =>
  apiClient.get<Territory[]>('/territories', { params: { regionId } }).then((r) => r.data);

export const createTerritory = (data: Partial<Territory>) =>
  apiClient.post<Territory>('/territories', data).then((r) => r.data);

export const updateTerritory = (id: string, data: Partial<Territory>) =>
  apiClient.put<Territory>(`/territories/${id}`, data).then((r) => r.data);

export const deleteTerritory = (id: string) =>
  apiClient.delete(`/territories/${id}`);

export const getOrganizers = () =>
  apiClient.get<Organizer[]>('/organizers').then((r) => r.data);

export const createOrganizer = (data: Partial<Organizer>) =>
  apiClient.post<Organizer>('/organizers', data).then((r) => r.data);

export const updateOrganizer = (id: string, data: Partial<Organizer>) =>
  apiClient.put<Organizer>(`/organizers/${id}`, data).then((r) => r.data);

export const deleteOrganizer = (id: string) =>
  apiClient.delete(`/organizers/${id}`);

export const getOrganizerTerritories = (organizerId: string) =>
  apiClient.get<OrganizerTerritory[]>(`/organizers/${organizerId}/territories`).then((r) => r.data);

export const assignTerritory = (organizerId: string, territoryId: string) =>
  apiClient.post(`/organizers/${organizerId}/territories`, { organizerId, territoryId });

export const unassignTerritory = (organizerId: string, territoryId: string) =>
  apiClient.delete(`/organizers/${organizerId}/territories/${territoryId}`);
