import apiClient from './client';
import type { Region, Territory, Organizer, OrganizerTerritory, Role, CardType, TariffType, SessionType, EventType, TransactionType, TransactionResult, Service, Benefit, Carrier, BenefitStep } from '../types/reference';

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

// Roles
export const getRoles = () =>
  apiClient.get<Role[]>('/roles').then((r) => r.data);
export const createRole = (data: Partial<Role>) =>
  apiClient.post<Role>('/roles', data).then((r) => r.data);
export const updateRole = (id: string, data: Partial<Role>) =>
  apiClient.put<Role>(`/roles/${id}`, data).then((r) => r.data);
export const deleteRole = (id: string) =>
  apiClient.delete(`/roles/${id}`);

// Card Types
export const getCardTypes = () =>
  apiClient.get<CardType[]>('/card-types').then((r) => r.data);
export const createCardType = (data: Partial<CardType>) =>
  apiClient.post<CardType>('/card-types', data).then((r) => r.data);
export const updateCardType = (id: string, data: Partial<CardType>) =>
  apiClient.put<CardType>(`/card-types/${id}`, data).then((r) => r.data);
export const deleteCardType = (id: string) =>
  apiClient.delete(`/card-types/${id}`);

// Tariff Types
export const getTariffTypes = () =>
  apiClient.get<TariffType[]>('/tariff-types').then((r) => r.data);
export const createTariffType = (data: Partial<TariffType>) =>
  apiClient.post<TariffType>('/tariff-types', data).then((r) => r.data);
export const updateTariffType = (id: string, data: Partial<TariffType>) =>
  apiClient.put<TariffType>(`/tariff-types/${id}`, data).then((r) => r.data);
export const deleteTariffType = (id: string) =>
  apiClient.delete(`/tariff-types/${id}`);

// Session Types
export const getSessionTypes = () =>
  apiClient.get<SessionType[]>('/session-types').then((r) => r.data);
export const createSessionType = (data: Partial<SessionType>) =>
  apiClient.post<SessionType>('/session-types', data).then((r) => r.data);
export const updateSessionType = (id: string, data: Partial<SessionType>) =>
  apiClient.put<SessionType>(`/session-types/${id}`, data).then((r) => r.data);
export const deleteSessionType = (id: string) =>
  apiClient.delete(`/session-types/${id}`);

// Event Types
export const getEventTypes = () =>
  apiClient.get<EventType[]>('/event-types').then((r) => r.data);
export const getEventType = (eventType: string) =>
  apiClient.get<EventType>(`/event-types/${eventType}`).then((r) => r.data);
export const createEventType = (data: Partial<EventType>) =>
  apiClient.post<EventType>('/event-types', data).then((r) => r.data);
export const updateEventType = (eventType: string, data: Partial<EventType>) =>
  apiClient.put<EventType>(`/event-types/${eventType}`, data).then((r) => r.data);
export const deleteEventType = (eventType: string) =>
  apiClient.delete(`/event-types/${eventType}`);

// Transaction Types
export const getTransactionTypes = () =>
  apiClient.get<TransactionType[]>('/transaction-types').then((r) => r.data);
export const createTransactionType = (data: Partial<TransactionType>) =>
  apiClient.post<TransactionType>('/transaction-types', data).then((r) => r.data);
export const updateTransactionType = (id: string, data: Partial<TransactionType>) =>
  apiClient.put<TransactionType>(`/transaction-types/${id}`, data).then((r) => r.data);
export const deleteTransactionType = (id: string) =>
  apiClient.delete(`/transaction-types/${id}`);

// Transaction Results
export const getTransactionResults = () =>
  apiClient.get<TransactionResult[]>('/transaction-results').then((r) => r.data);
export const createTransactionResult = (data: Partial<TransactionResult>) =>
  apiClient.post<TransactionResult>('/transaction-results', data).then((r) => r.data);
export const updateTransactionResult = (id: string, data: Partial<TransactionResult>) =>
  apiClient.put<TransactionResult>(`/transaction-results/${id}`, data).then((r) => r.data);
export const deleteTransactionResult = (id: string) =>
  apiClient.delete(`/transaction-results/${id}`);

// Services
export const getServices = (regionId?: string) =>
  apiClient.get<Service[]>('/services', { params: { regionId } }).then((r) => r.data);
export const createService = (data: Partial<Service>) =>
  apiClient.post<Service>('/services', data).then((r) => r.data);
export const updateService = (id: string, data: Partial<Service>) =>
  apiClient.put<Service>(`/services/${id}`, data).then((r) => r.data);
export const deleteService = (id: string) =>
  apiClient.delete(`/services/${id}`);

// Benefits
export const getBenefits = (regionId?: string) =>
  apiClient.get<Benefit[]>('/benefits', { params: { regionId } }).then((r) => r.data);
export const createBenefit = (data: Partial<Benefit>) =>
  apiClient.post<Benefit>('/benefits', data).then((r) => r.data);
export const updateBenefit = (id: string, data: Partial<Benefit>) =>
  apiClient.put<Benefit>(`/benefits/${id}`, data).then((r) => r.data);
export const deleteBenefit = (id: string) =>
  apiClient.delete(`/benefits/${id}`);

// Carriers
export const getCarriers = () =>
  apiClient.get<Carrier[]>('/carriers').then((r) => r.data);

// Vehicles — moved to api/routes.ts (per-table CRUD for route-service)

// Benefit Steps
export const getBenefitSteps = (benefitId?: string) =>
  apiClient.get<BenefitStep[]>('/benefit-steps', { params: { benefitId } }).then((r) => r.data);
export const createBenefitStep = (data: Partial<BenefitStep>) =>
  apiClient.post<BenefitStep>('/benefit-steps', data).then((r) => r.data);
export const updateBenefitStep = (id: string, data: Partial<BenefitStep>) =>
  apiClient.put<BenefitStep>(`/benefit-steps/${id}`, data).then((r) => r.data);
export const deleteBenefitStep = (id: string) =>
  apiClient.delete(`/benefit-steps/${id}`);
