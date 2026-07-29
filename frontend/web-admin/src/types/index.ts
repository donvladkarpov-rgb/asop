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
  status: 'active' | 'blocked' | 'expired';
  holderName?: string;
  issuedAt: string;
  expiresAt?: string;
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
