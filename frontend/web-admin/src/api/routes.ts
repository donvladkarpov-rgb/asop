import apiClient from './client';

export type RouteEntity = Record<string, unknown> & { id: string };

// ===== VehicleType =====
export interface VehicleType {
  id: string;
  typeName: string;
}

// ===== VehicleModel =====
export interface VehicleModel {
  id: string;
  modelName: string;
}

// ===== ContractRoute =====
export interface ContractRoute {
  contractId: string;
  routeId: string;
  routeNumber?: string | null;
  contractNumber?: string | null;
}

// ===== FareZone =====
export interface FareZone {
  id: string;
  zoneCode: string;
  zoneName: string;
  description?: string | null;
  zonePolygon?: string | null;
  regionId?: string | null;
  createdAt?: string;
  updatedAt?: string;
}
export type FareZoneCreate = Omit<FareZone, 'id' | 'createdAt' | 'updatedAt'>;
export type FareZoneUpdate = Partial<FareZoneCreate>;

// ===== TransportStop =====
export interface TransportStop {
  id: string;
  stopCode: string;
  stopName: string;
  regionId?: string | null;
  fareZoneId?: string | null;
  stopAddress?: string | null;
  zonePolygon?: string | null;
  description?: string | null;
  isActive?: boolean | null;
  createdAt?: string;
  updatedAt?: string;
}
export type TransportStopCreate = Omit<TransportStop, 'id' | 'createdAt' | 'updatedAt'>;
export type TransportStopUpdate = Partial<TransportStopCreate>;

// ===== Route =====
export interface Route {
  id: string;
  routeNumber: string;
  routeName: string;
  routeCategory?: string | null;
  organizerId?: string | null;
  ministryRegistryNo?: string | null;
  regionId?: string | null;
  createdAt?: string;
  updatedAt?: string;
}
export type RouteCreate = Omit<Route, 'id' | 'createdAt' | 'updatedAt'>;
export type RouteUpdate = Partial<RouteCreate>;

// ===== Path =====
export interface Path {
  id: string;
  routeId: string;
  pathName: string;
  routeObject?: string | null;
  benefitPolicy?: string | null;
  startStopId?: string | null;
  endStopId?: string | null;
  pathStartDate?: string | null;
  pathEndDate?: string | null;
  description?: string | null;
  regionId?: string | null;
  createdAt?: string;
  updatedAt?: string;
}
export type PathCreate = Omit<Path, 'id' | 'createdAt' | 'updatedAt'>;
export type PathUpdate = Partial<PathCreate>;

// ===== PathTransportStop =====
export interface PathTransportStop {
  id: string;
  pathId: string;
  stopId: string;
  serialNumber: number;
  regionId?: string | null;
  createdAt?: string;
  updatedAt?: string;
}
export type PathTransportStopCreate = Omit<PathTransportStop, 'id' | 'createdAt' | 'updatedAt'>;
export type PathTransportStopUpdate = Partial<PathTransportStopCreate>;

// ===== Schedule =====
export interface Schedule {
  id: string;
  pathId: string;
  stopId: string;
  dayMask: number;
  arrivalTime: string;
  dwellTimeSec?: number | null;
  regionId?: string | null;
  isActive?: boolean | null;
  createdAt?: string;
  updatedAt?: string;
}
export type ScheduleCreate = Omit<Schedule, 'id' | 'createdAt' | 'updatedAt'>;
export type ScheduleUpdate = Partial<ScheduleCreate>;

// ===== PathService =====
export interface PathService {
  id: string;
  pathId: string;
  serviceId: string;
  carrierId?: string | null;
  vehicleId?: string | null;
  tariffTypeId?: string | null;
  price?: number | null;
  isActive?: boolean | null;
  createdAt?: string;
  updatedAt?: string;
}
export type PathServiceCreate = Omit<PathService, 'id' | 'createdAt' | 'updatedAt'>;
export type PathServiceUpdate = Partial<PathServiceCreate>;

// ===== PathDiscount =====
export interface PathDiscount {
  id: string;
  pathId: string;
  carrierId?: string | null;
  vehicleId?: string | null;
  tariffTypeId?: string | null;
  discountName: string;
  discountType: string;
  discountValue: number;
  validFrom?: string | null;
  validUntil?: string | null;
  isActive?: boolean | null;
  createdAt?: string;
  updatedAt?: string;
}
export type PathDiscountCreate = Omit<PathDiscount, 'id' | 'createdAt' | 'updatedAt'>;
export type PathDiscountUpdate = Partial<PathDiscountCreate>;

// ===== PathBenefit =====
export interface PathBenefit {
  id: string;
  pathId: string;
  benefitId: string;
  createdAt?: string;
  updatedAt?: string;
}
export type PathBenefitCreate = Omit<PathBenefit, 'id' | 'createdAt' | 'updatedAt'>;
export type PathBenefitUpdate = Partial<PathBenefitCreate>;

// ===== Vehicle =====
export interface Vehicle {
  id: string;
  carrierId?: string | null;
  vehicleTypeId: string;
  vehicleModelId: string;
  vehicleNumber: string;
  vehicleName?: string | null;
  createdAt?: string;
  updatedAt?: string;
}
export type VehicleCreate = Omit<Vehicle, 'id' | 'createdAt' | 'updatedAt'>;
export type VehicleUpdate = Partial<VehicleCreate>;

// ===== Specific CRUD (typed) =====
const stripId = (entity: Record<string, unknown>): Record<string, unknown> => {
  const { id, createdAt, updatedAt, ...rest } = entity as Record<string, unknown>;
  return rest;
};

const toIsoUtc = (value: unknown): unknown => {
  if (typeof value !== 'string') return value;
  if (!value) return value;
  if (value.endsWith('Z') || value.includes('+') || /-\d{2}:\d{2}$/.test(value)) return value;
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value)) return value + ':00Z';
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value + 'T00:00:00Z';
  return value;
};

const normalize = (data: Record<string, unknown>): Record<string, unknown> => {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(data)) {
    out[k] = k === 'createdAt' || k === 'updatedAt' ? toIsoUtc(v) : v;
  }
  return out;
};

// FareZone
export const getFareZones = (): Promise<FareZone[]> =>
  apiClient.get<FareZone[]>('/fare-zones').then((r) => r.data ?? []);
export const createFareZone = (data: FareZoneCreate) =>
  apiClient.post<FareZone>('/fare-zones', data).then((r) => r.data);
export const updateFareZone = (id: string, data: FareZoneUpdate) =>
  apiClient.put<FareZone>(`/fare-zones/${id}`, data).then((r) => r.data);
export const deleteFareZone = (id: string) => apiClient.delete(`/fare-zones/${id}`);

// TransportStop
export const getTransportStops = (): Promise<TransportStop[]> =>
  apiClient.get<TransportStop[]>('/transport-stops').then((r) => r.data ?? []);
export const createTransportStop = (data: TransportStopCreate) =>
  apiClient.post<TransportStop>('/transport-stops', data).then((r) => r.data);
export const updateTransportStop = (id: string, data: TransportStopUpdate) =>
  apiClient.put<TransportStop>(`/transport-stops/${id}`, data).then((r) => r.data);
export const deleteTransportStop = (id: string) => apiClient.delete(`/transport-stops/${id}`);

// Route
export const getRoutes = (): Promise<Route[]> =>
  apiClient.get<Route[]>('/routes').then((r) => r.data ?? []);
export const createRoute = (data: RouteCreate) =>
  apiClient.post<Route>('/routes', data).then((r) => r.data);
export const updateRoute = (id: string, data: RouteUpdate) =>
  apiClient.put<Route>(`/routes/${id}`, data).then((r) => r.data);
export const deleteRoute = (id: string) => apiClient.delete(`/routes/${id}`);

// Path
export const getPaths = (): Promise<Path[]> =>
  apiClient.get<Path[]>('/paths').then((r) => r.data ?? []);
export const createPath = (data: PathCreate) =>
  apiClient.post<Path>('/paths', {
    ...data,
    pathStartDate: data.pathStartDate ? toIsoUtc(data.pathStartDate) : undefined,
    pathEndDate: data.pathEndDate ? toIsoUtc(data.pathEndDate) : undefined,
  }).then((r) => r.data);
export const updatePath = (id: string, data: PathUpdate) =>
  apiClient.put<Path>(`/paths/${id}`, {
    ...data,
    pathStartDate: data.pathStartDate ? toIsoUtc(data.pathStartDate) : undefined,
    pathEndDate: data.pathEndDate ? toIsoUtc(data.pathEndDate) : undefined,
  }).then((r) => r.data);
export const deletePath = (id: string) => apiClient.delete(`/paths/${id}`);

// PathTransportStop
export const getPathTransportStops = (): Promise<PathTransportStop[]> =>
  apiClient.get<PathTransportStop[]>('/path-transport-stops').then((r) => r.data ?? []);
export const createPathTransportStop = (data: PathTransportStopCreate) =>
  apiClient.post<PathTransportStop>('/path-transport-stops', data).then((r) => r.data);
export const updatePathTransportStop = (id: string, data: PathTransportStopUpdate) =>
  apiClient.put<PathTransportStop>(`/path-transport-stops/${id}`, data).then((r) => r.data);
export const deletePathTransportStop = (id: string) => apiClient.delete(`/path-transport-stops/${id}`);

// Schedule
export const getSchedules = (): Promise<Schedule[]> =>
  apiClient.get<Schedule[]>('/schedule').then((r) => r.data ?? []);
export const createSchedule = (data: ScheduleCreate) =>
  apiClient.post<Schedule>('/schedule', data).then((r) => r.data);
export const updateSchedule = (id: string, data: ScheduleUpdate) =>
  apiClient.put<Schedule>(`/schedule/${id}`, data).then((r) => r.data);
export const deleteSchedule = (id: string) => apiClient.delete(`/schedule/${id}`);

// PathService
export const getPathServices = (): Promise<PathService[]> =>
  apiClient.get<PathService[]>('/path-services').then((r) => r.data ?? []);
export const createPathService = (data: PathServiceCreate) =>
  apiClient.post<PathService>('/path-services', data).then((r) => r.data);
export const updatePathService = (id: string, data: PathServiceUpdate) =>
  apiClient.put<PathService>(`/path-services/${id}`, data).then((r) => r.data);
export const deletePathService = (id: string) => apiClient.delete(`/path-services/${id}`);

// PathDiscount
export const getPathDiscounts = (): Promise<PathDiscount[]> =>
  apiClient.get<PathDiscount[]>('/path-discounts').then((r) => r.data ?? []);
export const createPathDiscount = (data: PathDiscountCreate) =>
  apiClient.post<PathDiscount>('/path-discounts', {
    ...data,
    validFrom: data.validFrom ? toIsoUtc(data.validFrom) : undefined,
    validUntil: data.validUntil ? toIsoUtc(data.validUntil) : undefined,
  }).then((r) => r.data);
export const updatePathDiscount = (id: string, data: PathDiscountUpdate) =>
  apiClient.put<PathDiscount>(`/path-discounts/${id}`, {
    ...data,
    validFrom: data.validFrom ? toIsoUtc(data.validFrom) : undefined,
    validUntil: data.validUntil ? toIsoUtc(data.validUntil) : undefined,
  }).then((r) => r.data);
export const deletePathDiscount = (id: string) => apiClient.delete(`/path-discounts/${id}`);

// PathBenefit
export const getPathBenefits = (): Promise<PathBenefit[]> =>
  apiClient.get<PathBenefit[]>('/path-benefits').then((r) => r.data ?? []);
export const createPathBenefit = (data: PathBenefitCreate) =>
  apiClient.post<PathBenefit>('/path-benefits', data).then((r) => r.data);
export const updatePathBenefit = (id: string, data: PathBenefitUpdate) =>
  apiClient.put<PathBenefit>(`/path-benefits/${id}`, data).then((r) => r.data);
export const deletePathBenefit = (id: string) => apiClient.delete(`/path-benefits/${id}`);

// Vehicle
export const getVehicles = (): Promise<Vehicle[]> =>
  apiClient.get<Vehicle[]>('/vehicles').then((r) => r.data ?? []);
export const createVehicle = (data: VehicleCreate) =>
  apiClient.post<Vehicle>('/vehicles', data).then((r) => r.data);
export const updateVehicle = (id: string, data: VehicleUpdate) =>
  apiClient.put<Vehicle>(`/vehicles/${id}`, data).then((r) => r.data);
export const deleteVehicle = (id: string) => apiClient.delete(`/vehicles/${id}`);

// VehicleType
export const getVehicleTypes = (): Promise<VehicleType[]> =>
  apiClient.get<VehicleType[]>('/vehicle-types').then((r) => r.data ?? []);
export const createVehicleType = (data: { typeName: string }) =>
  apiClient.post<VehicleType>('/vehicle-types', data).then((r) => r.data);
export const updateVehicleType = (id: string, data: { typeName: string }) =>
  apiClient.put<VehicleType>(`/vehicle-types/${id}`, data).then((r) => r.data);
export const deleteVehicleType = (id: string) => apiClient.delete(`/vehicle-types/${id}`);

// VehicleModel
export const getVehicleModels = (): Promise<VehicleModel[]> =>
  apiClient.get<VehicleModel[]>('/vehicle-models').then((r) => r.data ?? []);
export const createVehicleModel = (data: { modelName: string }) =>
  apiClient.post<VehicleModel>('/vehicle-models', data).then((r) => r.data);
export const updateVehicleModel = (id: string, data: { modelName: string }) =>
  apiClient.put<VehicleModel>(`/vehicle-models/${id}`, data).then((r) => r.data);
export const deleteVehicleModel = (id: string) => apiClient.delete(`/vehicle-models/${id}`);

// ContractRoute
export const getContractRoutes = (params?: { contractId?: string; routeId?: string }): Promise<ContractRoute[]> =>
  apiClient.get<ContractRoute[]>('/contract-routes', { params }).then((r) => r.data ?? []);
export const createContractRoute = (data: { contractId: string; routeId: string }) =>
  apiClient.post<ContractRoute>('/contract-routes', data).then((r) => r.data);
export const deleteContractRoute = (contractId: string, routeId: string) =>
  apiClient.delete('/contract-routes', { params: { contractId, routeId } });

// ===== Admin User =====
export interface AdminUser {
  id: string;
  firstName: string;
  lastNameInitial: string;
  patronymicInitial?: string | null;
  phone?: string | null;
  keycloakId?: string | null;
}
export type AdminUserCreate = Omit<AdminUser, 'id' | 'keycloakId'>;

export const getAdminUsers = (): Promise<AdminUser[]> =>
  apiClient.get<AdminUser[]>('/admin-users').then((r) => r.data ?? []);
export const createAdminUser = (data: AdminUserCreate) =>
  apiClient.post<AdminUser>('/admin-users', data).then((r) => r.data);
export const updateAdminUser = (id: string, data: AdminUserCreate) =>
  apiClient.put<AdminUser>(`/admin-users/${id}`, data).then((r) => r.data);
export const deleteAdminUser = (id: string) => apiClient.delete(`/admin-users/${id}`);

// ===== UserRole =====
export interface UserRole {
  userId: string;
  roleId: string;
  roleName?: string | null;
  firstName?: string | null;
  lastNameInitial?: string | null;
}

export const getUserRoles = (params?: { userId?: string; roleId?: string }): Promise<UserRole[]> =>
  apiClient.get<UserRole[]>('/user-roles', { params }).then((r) => r.data ?? []);
export const createUserRole = (data: { userId: string; roleId: string }) =>
  apiClient.post<UserRole>('/user-roles', data).then((r) => r.data);
export const deleteUserRole = (userId: string, roleId: string) =>
  apiClient.delete('/user-roles', { params: { userId, roleId } });

// ===== UserCarrier =====
export interface UserCarrier {
  userId: string;
  carrierId: string;
  carrierName?: string | null;
  firstName?: string | null;
  lastNameInitial?: string | null;
}

export const getUserCarriers = (params?: { userId?: string; carrierId?: string }): Promise<UserCarrier[]> =>
  apiClient.get<UserCarrier[]>('/user-carriers', { params }).then((r) => r.data ?? []);
export const createUserCarrier = (data: { userId: string; carrierId: string }) =>
  apiClient.post<UserCarrier>('/user-carriers', data).then((r) => r.data);
export const deleteUserCarrier = (userId: string, carrierId: string) =>
  apiClient.delete('/user-carriers', { params: { userId, carrierId } });

// ===== UserRegion =====
export interface UserRegion {
  userId: string;
  regionId: string;
  regionName?: string | null;
  firstName?: string | null;
  lastNameInitial?: string | null;
}

export const getUserRegions = (params?: { userId?: string; regionId?: string }): Promise<UserRegion[]> =>
  apiClient.get<UserRegion[]>('/user-regions', { params }).then((r) => r.data ?? []);
export const createUserRegion = (data: { userId: string; regionId: string }) =>
  apiClient.post<UserRegion>('/user-regions', data).then((r) => r.data);
export const deleteUserRegion = (userId: string, regionId: string) =>
  apiClient.delete('/user-regions', { params: { userId, regionId } });

// ===== UserBenefit =====
export interface UserBenefit {
  assignmentId: string;
  userId: string;
  benefitId: string;
  validFrom: string;
  validUntil?: string | null;
}

export const getUserBenefits = (params?: { userId?: string; benefitId?: string }): Promise<UserBenefit[]> =>
  apiClient.get<UserBenefit[]>('/user-benefits', { params }).then((r) => r.data ?? []);
export const createUserBenefit = (data: { userId: string; benefitId: string; validFrom?: string; validUntil?: string }) =>
  apiClient.post<UserBenefit>('/user-benefits', data).then((r) => r.data);
export const deleteUserBenefit = (assignmentId: string) =>
  apiClient.delete(`/user-benefits/${assignmentId}`);

// Suppress unused warning for helper
export { stripId, normalize };
