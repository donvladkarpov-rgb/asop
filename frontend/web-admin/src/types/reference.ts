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
