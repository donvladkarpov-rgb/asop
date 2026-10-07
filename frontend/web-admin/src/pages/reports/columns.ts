import type { TripRegistrationReportRow } from '../../api/reports';
import type { ShiftReportRow } from '../../api/reports-shifts';
import type { BenefitReportRow } from '../../api/reports-benefit';
import type { ShiftListRow } from '../../api/reports-shift-list';

export type CellKind = 'text' | 'center' | 'money' | 'datetime' | 'num' | 'percent';

export interface ReportColumn {
  /** Заголовок — ровно как в ТЗ, порядок фиксирован (29 колонок). */
  header: string;
  width: number;
  kind: CellKind;
  get: (row: TripRegistrationReportRow) => string | number | null;
}

export interface ShiftReportColumn {
  header: string;
  width: number;
  kind: CellKind;
  get: (row: ShiftReportRow) => string | number | null;
}

export interface BenefitReportColumn {
  header: string;
  width: number;
  kind: CellKind;
  /** `index` — номер строки (с 1), нужен колонке «№ п/п». */
  get: (row: BenefitReportRow, index: number) => string | number | null;
}

export interface ShiftListReportColumn {
  header: string;
  width: number;
  kind: CellKind;
  get: (row: ShiftListRow) => string | number | null;
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

/** failedSharePct / cashlessSharePct приходят уже в процентах (44.44). */
export function formatPct(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return `${n.toFixed(2)}%`;
}

function join(...parts: (string | null | undefined)[]): string {
  return parts.map((p) => (p ?? '').trim()).filter(Boolean).join(' ');
}

/** Колонки «Отчёт по сменам» (17 колонок, порядок фиксирован). */
export const SHIFT_REPORT_COLUMNS: ShiftReportColumn[] = [
  { header: 'ID смены', width: 260, kind: 'text', get: (r) => r.shiftId },
  { header: 'Организатор', width: 180, kind: 'text', get: (r) => r.organizerName },
  { header: 'Перевозчик', width: 180, kind: 'text', get: (r) => r.carrierName },
  { header: 'Маршрут', width: 190, kind: 'text', get: (r) => join(r.routeNumber, r.routeName) },
  { header: 'Транспортное средство', width: 190, kind: 'text', get: (r) => join(r.vehicleNumber, r.vehicleName) },
  { header: 'Тип ТС', width: 90, kind: 'text', get: (r) => r.vehicleTypeName },
  { header: 'Вид ТС', width: 110, kind: 'text', get: (r) => r.vehicleModelName },
  { header: 'Серийный номер терминала', width: 150, kind: 'text', get: (r) => r.terminalSerial ?? r.terminalNumber },
  { header: 'Дата открытия смены', width: 155, kind: 'datetime', get: (r) => formatDateTime(r.shiftStartedAt) },
  { header: 'Дата закрытия смены', width: 155, kind: 'datetime', get: (r) => formatDateTime(r.shiftClosedAt) },
  { header: 'Количество транзакций', width: 120, kind: 'num', get: (r) => r.transactionsCount },
  { header: 'Успешных по картам', width: 115, kind: 'num', get: (r) => r.successfulCardTransactions },
  { header: 'Неуспешных по картам', width: 115, kind: 'num', get: (r) => r.failedCardTransactions },
  { header: 'Доля неуспешных', width: 110, kind: 'percent', get: (r) => r.failedSharePct },
  { header: 'Сумма безнал', width: 120, kind: 'money', get: (r) => r.cashlessAmount },
  { header: 'Сумма безнал (без скидки)', width: 140, kind: 'money', get: (r) => r.cashlessAmountWithoutDiscount },
  { header: 'Количество безнал', width: 115, kind: 'num', get: (r) => r.cashlessCount },
  { header: 'Безнал, доля', width: 110, kind: 'percent', get: (r) => r.cashlessSharePct },
  { header: 'Сумма наличными', width: 120, kind: 'money', get: (r) => r.cashAmount },
  { header: 'Количество наличными', width: 120, kind: 'num', get: (r) => r.cashCount },
];

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

/** Колонки сводного отчёта «Сводный, льготники» (7 колонок, порядок фиксирован). */
export const BENEFIT_REPORT_COLUMNS: BenefitReportColumn[] = [
  { header: '№ п/п', width: 60, kind: 'num', get: (_r, index) => index + 1 },
  { header: 'Регион', width: 170, kind: 'text', get: (r) => r.regionName },
  { header: 'Код льготной категории', width: 150, kind: 'text', get: (r) => r.benefitCode },
  { header: 'Льготная категория', width: 340, kind: 'text', get: (r) => r.benefitName },
  { header: 'Фактическое количество поездок', width: 160, kind: 'num', get: (r) => r.tripsCount },
  { header: 'Поездок без ставки', width: 130, kind: 'num', get: (r) => r.tripsWithoutRate },
  // Сырое число: форматирование — при рендере и в Excel, чтобы ячейка уходила числом.
  { header: 'Возмещение на поездку', width: 140, kind: 'money', get: (r) => perTrip(r) },
  { header: 'Сумма возмещения', width: 140, kind: 'money', get: (r) => r.compensation },
];

/** Среднее возмещение на поездку (тариф × доля), 0 поездок → null. */
function perTrip(r: BenefitReportRow): string | null {
  if (!r.tripsCount) return null;
  const total = Number(r.compensation);
  if (Number.isNaN(total)) return null;
  return (total / r.tripsCount).toFixed(2);
}

/**
 * Колонки отчёта «Список смен» (25 колонок, порядок фиксирован):
 * 23 колонки внешнего CSV + ТК кол-во/ТК сумма (после МФК, перед Нал).
 */
export const SHIFT_LIST_REPORT_COLUMNS: ShiftListReportColumn[] = [
  { header: 'Перевозчик', width: 200, kind: 'text', get: (r) => r.carrierName },
  { header: 'ID смены', width: 260, kind: 'text', get: (r) => r.shiftId },
  { header: 'ФИО водителя', width: 190, kind: 'text', get: (r) => r.driverName },
  { header: 'Начало смены', width: 155, kind: 'datetime', get: (r) => formatDateTime(r.shiftStartedAt) },
  { header: 'Конец смены', width: 155, kind: 'datetime', get: (r) => formatDateTime(r.shiftClosedAt) },
  { header: 'Длительность', width: 100, kind: 'center', get: (r) => r.durationText },
  { header: 'SN терминала', width: 140, kind: 'text', get: (r) => r.terminalSerial },
  { header: 'ГРЗ', width: 110, kind: 'text', get: (r) => r.vehicleNumber },
  { header: 'Тип ТС', width: 100, kind: 'text', get: (r) => r.vehicleTypeName },
  { header: 'Маршрут', width: 220, kind: 'text', get: (r) => join(r.routeNumber, r.routeName) },
  { header: 'Организатор', width: 180, kind: 'text', get: (r) => r.organizerName },
  { header: 'Территория', width: 170, kind: 'text', get: (r) => r.territoryNames },
  { header: 'Тип маршрута', width: 110, kind: 'center', get: (r) => r.routeCategoryLabel ?? '—' },
  { header: 'Начало маршрута', width: 155, kind: 'datetime', get: (r) => formatDateTime(r.routeStartedAt) },
  { header: 'Конец маршрута', width: 155, kind: 'datetime', get: (r) => formatDateTime(r.routeEndedAt) },
  { header: 'БК кол-во', width: 95, kind: 'num', get: (r) => r.bkCount },
  { header: 'БК сумма', width: 110, kind: 'money', get: (r) => r.bkSum },
  { header: 'МФК кол-во', width: 100, kind: 'num', get: (r) => r.mfkCount },
  { header: 'МФК сумма', width: 110, kind: 'money', get: (r) => r.mfkSum },
  { header: 'ТК кол-во', width: 95, kind: 'num', get: (r) => r.tkCount },
  { header: 'ТК сумма', width: 110, kind: 'money', get: (r) => r.tkSum },
  { header: 'Нал. кол-во', width: 105, kind: 'num', get: (r) => r.cashCount },
  { header: 'Нал. Сумма', width: 110, kind: 'money', get: (r) => r.cashSum },
  { header: 'Итого кол-во', width: 110, kind: 'num', get: (r) => r.totalCount },
  { header: 'Итого сумма', width: 120, kind: 'money', get: (r) => r.totalSum },
];
