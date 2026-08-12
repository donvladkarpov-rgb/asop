export interface User {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  roles: string[];
  enabled: boolean;
  createdAt: string;
}

export interface Terminal {
  id: string;
  terminalSerial: string;
  terminalNumber?: string;
  terminalModel?: string;
  carrierId?: string;
  timezone?: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface Card {
  id: string;
  uid: string;
  type: 'MIFARE' | 'bank';
  /** Технология NFC-карты (промпт 007): "DESFIRE" (по умолчанию) или "CLASSIC". */
  cardTech?: 'DESFIRE' | 'CLASSIC';
  status: 'active' | 'blocked' | 'expired';
  holderName?: string;
  issuedAt: string;
  expiresAt?: string;
  /**
   * VCM1 bitmask (промпт 008) для CLASSIC карт. Decoded из
   * ASOP_CARD_MIFARES.IDENTITY_JSON (новый формат `format: VCM1`).
   * Bit-0 = SUPER_ADMIN, Bit-1 = REGION_ADMIN, etc.
   */
  bitmask?: number;
}

export interface Session {
  id: string;
  terminalId: string;
  operatorId: string;
  openedAt: string;
  closedAt?: string;
  status: 'open' | 'closed';
}

export type PageResponse<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
};
