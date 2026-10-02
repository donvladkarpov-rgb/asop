import type { TripRegistrationReportRow } from '../../api/reports';

export type CellKind = 'text' | 'center' | 'money' | 'datetime';

export interface ReportColumn {
  /** Заголовок — ровно как в ТЗ, порядок фиксирован (29 колонок). */
  header: string;
  width: number;
  kind: CellKind;
  get: (row: TripRegistrationReportRow) => string | number | null;
}

const pad = (n: number) => n.toString().padStart(2, '0');

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export function formatMoney(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return n.toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** DISCOUNT_SHARE хранится долей (0.50) — показываем процентом. */
export function formatShare(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return `${(n * 100).toFixed(2)}%`;
}

function join(...parts: (string | null | undefined)[]): string {
  return parts.map((p) => (p ?? '').trim()).filter(Boolean).join(' ');
}

export const REPORT_COLUMNS: ReportColumn[] = [
  { header: 'Реализатор', width: 180, kind: 'text', get: (r) => r.organizerName },
  { header: 'Идентификатор перевозчика', width: 180, kind: 'text', get: (r) => r.carrierName },
  { header: 'Тип ТС', width: 90, kind: 'text', get: (r) => r.vehicleTypeName },
  { header: 'Вид ТС', width: 110, kind: 'text', get: (r) => r.vehicleModelName },
  { header: 'Транспортное средство', width: 190, kind: 'text', get: (r) => join(r.vehicleNumber, r.vehicleName) },
  { header: 'Маршрут', width: 190, kind: 'text', get: (r) => join(r.routeNumber, r.routeName) },
  { header: '№ рейса', width: 260, kind: 'text', get: (r) => r.tripId },
  { header: 'Время смены рейса (начало)', width: 150, kind: 'datetime', get: (r) => formatDateTime(r.shiftStartedAt) },
  { header: 'Номер терминала', width: 110, kind: 'text', get: (r) => r.terminalNumber ?? r.terminalSerial },
  { header: 'ФИО водителя', width: 190, kind: 'text', get: (r) => r.driverName },
  { header: 'Номер обслуженной карты', width: 150, kind: 'text', get: (r) => r.cardNumber },
  { header: 'ID транзакции', width: 260, kind: 'text', get: (r) => r.transactionId },
  { header: 'Дата и время совершения поездки', width: 155, kind: 'datetime', get: (r) => formatDateTime(r.tripAt) },
  { header: 'Дата и время обработки на сервере', width: 155, kind: 'datetime', get: (r) => formatDateTime(r.processedAt) },
  { header: 'Статус', width: 140, kind: 'text', get: (r) => r.status },
  { header: 'Категория пассажира', width: 150, kind: 'text', get: (r) => r.passengerCategory },
  { header: 'Форма оплаты проезда', width: 130, kind: 'text', get: (r) => r.paymentForm },
  { header: 'Услуга', width: 90, kind: 'text', get: (r) => r.serviceName },
  { header: 'Тип тарифа', width: 130, kind: 'text', get: (r) => r.tariffTypeName },
  { header: 'Применённый тариф', width: 140, kind: 'text', get: (r) => r.appliedTariff },
  { header: 'Регулируемый тариф', width: 140, kind: 'text', get: (r) => r.regulatedTariff },
  { header: 'Льгота', width: 90, kind: 'center', get: (r) => formatShare(r.benefitShare) },
  // Сырое число: форматирование применяется при рендере таблицы и при выгрузке в Excel,
  // чтобы в .xml ячейка уходила числом (а не строкой «1 234,50») и суммировалась в Excel.
  { header: 'Оплаченная сумма', width: 120, kind: 'money', get: (r) => r.amount },
  { header: 'Пункт отправления', width: 160, kind: 'text', get: (r) => r.originStopName },
  { header: 'Пункт назначения', width: 160, kind: 'text', get: (r) => r.destinationStopName },
  { header: 'Признак фискализации', width: 140, kind: 'text', get: (r) => r.fiscalStatus },
  { header: 'Дата фискализации', width: 150, kind: 'datetime', get: (r) => formatDateTime(r.fiscalCreatedAt) },
  { header: 'Дата факта фискализации', width: 160, kind: 'datetime', get: (r) => formatDateTime(r.fiscalConfirmedAt) },
  { header: 'RRN', width: 180, kind: 'text', get: (r) => r.rrn },
];