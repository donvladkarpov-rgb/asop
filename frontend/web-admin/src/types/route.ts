export interface FareZone {
  id: string;
  zoneCode: string;
  zoneName: string;
  description?: string | null;
  zonePolygon?: string | null;
  regionId?: string | null;
}

export interface TransportStop {
  id: string;
  stopCode: string;
  stopName: string;
  regionId?: string | null;
  fareZoneId?: string | null;
  zonePolygon?: string | null;
  stopAddress?: string | null;
  description?: string | null;
  isActive?: boolean | null;
}

export interface Route {
  id: string;
  routeNumber: string;
  routeName: string;
  routeCategory?: string | null;
  organizerId?: string | null;
  ministryRegistryNo?: string | null;
  regionId?: string | null;
}

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
}

export interface PathTransportStop {
  id: string;
  pathId: string;
  stopId: string;
  serialNumber: number;
  regionId?: string | null;
}

export interface Schedule {
  id: string;
  pathId: string;
  stopId: string;
  dayMask: number;
  arrivalTime: string;
  dwellTimeSec?: number | null;
  regionId?: string | null;
  isActive?: boolean | null;
}

export interface PathService {
  id: string;
  pathId: string;
  serviceId: string;
  carrierId?: string | null;
  vehicleId?: string | null;
  tariffTypeId?: string | null;
  price?: number | null;
  isActive?: boolean | null;
}

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
}

export interface PathBenefit {
  id: string;
  pathId: string;
  benefitId: string;
}

export interface Vehicle {
  id: string;
  carrierId?: string | null;
  vehicleTypeId: string;
  vehicleModelId: string;
  vehicleNumber: string;
  vehicleName?: string | null;
}

export interface VehicleType {
  id: string;
  typeName: string;
}

export interface VehicleModel {
  id: string;
  modelName: string;
}

export interface ContractRoute {
  contractId: string;
  routeId: string;
  routeNumber?: string | null;
  contractNumber?: string | null;
}
