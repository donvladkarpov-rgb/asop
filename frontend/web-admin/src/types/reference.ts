export interface Region {
  id: string;
  municipalDivision: string;
  adminDivision?: string;
  federalDistrict?: string;
  ifnsFlCode?: string;
  ifnsUlCode?: string;
  okatoCode?: string;
  oktmoCode?: string;
  oktmoBudgetCode?: string;
  fiasId?: string;
  registryRecordId?: string;
}

export interface Territory {
  id: string;
  regionId: string;
  municipalDivision: string;
  adminDivision?: string;
  federalDistrict?: string;
  ifnsFlCode?: string;
  ifnsUlCode?: string;
  okatoCode?: string;
  oktmoCode?: string;
  oktmoBudgetCode?: string;
  fiasId?: string;
  registryRecordId?: string;
}

export interface Organizer {
  id: string;
  organizerName: string;
}

export interface OrganizerTerritory {
  organizerId: string;
  territoryId: string;
  territoryName?: string;
  regionName?: string;
}

export interface Role {
  id: string;
  roleName: string;
}

export interface CardType {
  id: string;
  cardTypeName: string;
}

export interface TariffType {
  id: string;
  code: string;
  name: string;
  description?: string;
}

export interface SessionType {
  id: string;
  sessionTypeCode: string;
  sessionTypeName: string;
}

export interface EventType {
  eventType: string;
  eventTypeName: string;
}

export interface TransactionType {
  id: string;
  transactionTypeName: string;
}

export interface TransactionResult {
  id: string;
  transactionResultName: string;
}

export interface Service {
  id: string;
  serviceName: string;
  description?: string;
  priority: number;
  regionId: string;
}

export interface Benefit {
  id: string;
  benefitCode: string;
  benefitName: string;
  regionId: string;
  description?: string;
  isActive: boolean;
}

export interface Carrier {
  id: string;
  carrierName: string;
  inn: string;
  regionId: string;
}

export interface CardsDistributor {
  id: string;
  distributorName: string;
  inn: string;
  kpp?: string;
  legalAddress?: string;
  contactPhone?: string;
  contactEmail?: string;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
  contracts?: Contract[];
}

export interface Contract {
  id: string;
  contractorType?: string;
  carrierId?: string;
  cardsDistributorId?: string;
  contractNumber: string;
  startDate: string;
  endDate?: string;
  status: string;
  commissionPercent?: number;
  attributes?: string;
  createdAt: string;
  updatedAt: string;
}

export interface Tid {
  id: string;
  carrierId: string;
  terminalId?: string;
  tidValue: string;
  status: 'UNUSED' | 'ASSIGNED' | 'REVOKED';
  assignedAt?: string;
  unassignedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface BenefitStep {
  id: string;
  benefitId: string;
  stepOrder: number;
  tripThresholdFrom: number;
  tripThresholdTo?: number;
  discountShare: number;
  periodType: string;
}

export interface ThreeDesKey {
  keyId: string;
  keyMaterial: string;
  createdAt: string;
  updatedAt: string;
  deletedAt?: string;
  version?: number;
}

export interface ConfigParam {
  paramId: string;
  regionId?: string | null;
  organizerId?: string | null;
  carrierId?: string | null;
  cardsDistributorId?: string | null;
  krsId?: string | null;
  params: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
  deletedAt?: string | null;
  version?: number;
}
