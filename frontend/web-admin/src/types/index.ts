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
  cardTypeId: string;
  userId?: string;
  carrierId?: string;
  isClassic: boolean;
  isPrimary: boolean;
  /** VCM1 bitmask (промпт 008) для CLASSIC карт. Decoded из ASOP_CARD_MIFARES.IDENTITY_JSON. */
  bitmask?: number;
  registeredAt?: string;
  createdAt: string;
  updatedAt: string;
  /** UID NFC-карты (hex). null если карта не MIFARE. */
  uid?: string;
  /** Технология NFC-карты (промпт 007): "DESFIRE" | "CLASSIC". */
  cardTech?: 'DESFIRE' | 'CLASSIC' | string;
  /** ASOP_CARD_MIFARES.CARD_ROLE (14 ролей АСОП). */
  cardRole?: string;
  /** Остаток поездок на карте (ASOP_CARD_MIFARES.TRIPS_LEFT) — из терминальных транзакций. */
  tripsLeft?: number | null;
  /** ASOP_CARD_TYPES.CARD_TYPE_NAME. */
  cardTypeName?: string;
  /** ФИО владельца из ASOP_USERS. */
  holderName?: string;
  validUntil?: string;
  revokedAt?: string;
  /** Производный статус: active | blocked | expired | deleted. */
  status?: 'active' | 'blocked' | 'expired' | 'deleted';
}

export interface Transaction {
  transactionId: string;
  sessionId: string | null;
  transactionTypeId: string;
  transactionResultId: string;
  amount: number;
  metadata: string | null;
  startedAt: string;
  completedAt: string | null;
  cardId: string | null;
  userId: string | null;
}

export interface Session {
  id: string;
  sessionTypeId: string;
  parentSessionId: string | null;
  terminalId: string | null;
  tidId: string | null;
  openedByUserId: string | null;
  closedByUserId: string | null;
  cardId: string | null;
  pathId: string | null;
  vehicleId: string | null;
  status: string;
  startedAt: string;
  closedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export type PageResponse<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
};
