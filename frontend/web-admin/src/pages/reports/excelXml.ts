import type { TripRegistrationReport, TripRegistrationReportRow } from '../../api/reports';
import type { ShiftReport } from '../../api/reports-shifts';
import type { BenefitReport } from '../../api/reports-benefit';
import {
  BENEFIT_REPORT_COLUMNS,
  REPORT_COLUMNS,
  SHIFT_REPORT_COLUMNS,
  formatPct,
  type BenefitReportColumn,
  type ShiftReportColumn,
} from './columns';

/**
 * SpreadsheetML 2003 (Excel XML) — открывается двойным кликом в Excel/LibreOffice.
 * Формат выбран вместо .xlsx, чтобы не тянуть в проект zip-библиотеку: 29 колонок
 * и статичные строки упаковывать в OOXML-пакет смысла нет.
 */

function esc(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;')
    // Управляющие символы XML 1.0 недопустимы — вырезаем.
    // oxlint-disable-next-line no-control-regex -- диапазон управляющих символов задан escape-последовательностями, а не литералами
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '');
}

function cell(value: unknown, styleId?: string): string {
  const style = styleId ? ` ss:StyleID="${styleId}"` : '';
  return `<Cell${style}><Data ss:Type="String">${esc(value)}</Data></Cell>`;
}

/** Денежные ячейки пишем числом — иначе Excel не может их суммировать. */
function cellNumber(value: unknown, styleId?: string): string {
  const style = styleId ? ` ss:StyleID="${styleId}"` : '';
  const n = typeof value === 'number' ? value : Number(value ?? 0);
  const safe = Number.isFinite(n) ? n : 0;
  return `<Cell${style}><Data ss:Type="Number">${safe}</Data></Cell>`;
}

/** Ячейка по типу колонки: money → Number, числа → Number, остальное → String. */
function typedCell(value: unknown, kind: string): string {
  if (kind === 'money' || kind === 'num') return cellNumber(value, 's-money');
  if (kind === 'percent') return cell(formatPct(value as string | number), 's-money');
  return cell(value, styleFor(kind));
}

/** 1-based индекс колонки «Оплаченная сумма» — в неё встаёт итоговая сумма. */
const MONEY_COLUMN_INDEX = REPORT_COLUMNS.findIndex((c) => c.header === 'Оплаченная сумма') + 1;

/** Стиль ячейки по типу колонки: даты и деньги выравниваем вправо и задаём формат. */
function styleFor(kind: string): string | undefined {
  if (kind === 'money') return 's-money';
  if (kind === 'datetime') return 's-datetime';
  if (kind === 'center') return 's-center';
  return undefined;
}

const STYLES = `  <Styles>
    <Style ss:ID="Default" ss:Name="Normal">
      <Alignment ss:Vertical="Center"/>
      <Font ss:FontName="Calibri" ss:Size="10"/>
    </Style>
    <Style ss:ID="s-title">
      <Font ss:FontName="Calibri" ss:Size="12" ss:Bold="1"/>
    </Style>
    <Style ss:ID="s-header">
      <Font ss:FontName="Calibri" ss:Size="10" ss:Bold="1"/>
      <Interior ss:Color="#DDEBF7" ss:Pattern="Solid"/>
      <Alignment ss:Horizontal="Center" ss:Vertical="Center" ss:WrapText="1"/>
      <Borders>
        <Border ss:Position="Bottom" ss:LineStyle="Continuous" ss:Weight="1"/>
      </Borders>
    </Style>
    <Style ss:ID="s-center">
      <Alignment ss:Horizontal="Center"/>
    </Style>
    <Style ss:ID="s-money" ss:Parent="Default">
      <NumberFormat ss:Format="#,##0.00"/>
      <Alignment ss:Horizontal="Right"/>
    </Style>
    <Style ss:ID="s-datetime" ss:Parent="Default">
      <Alignment ss:Horizontal="Center"/>
    </Style>
    <Style ss:ID="s-total">
      <Font ss:FontName="Calibri" ss:Size="10" ss:Bold="1"/>
      <Interior ss:Color="#FFF2CC" ss:Pattern="Solid"/>
    </Style>
    <Style ss:ID="s-total-money" ss:Parent="s-total">
      <NumberFormat ss:Format="#,##0.00"/>
      <Alignment ss:Horizontal="Right"/>
    </Style>
  </Styles>`;

function headerRow(): string {
  const cells = REPORT_COLUMNS.map((c) => cell(c.header, 's-header')).join('');
  const widths = REPORT_COLUMNS.map((c) => `<Column ss:Width="${c.width}"/>`).join('');
  return `${widths}\n   <Row ss:Height="32">${cells}</Row>`;
}

function detailRows(rows: TripRegistrationReportRow[]): string {
  return rows
    .map((row) => {
      const cells = REPORT_COLUMNS.map((c) => typedCell(c.get(row), c.kind)).join('');
      return `<Row>${cells}</Row>`;
    })
    .join('\n   ');
}

/** Лист «Итоги»: уровень → группа → поездок/сумма (5 уровней группировки). */
function totalsSheet(report: TripRegistrationReport): string {
  const head = `<Row>${cell('Уровень', 's-header')}${cell('Группировка', 's-header')}${cell('Поездок', 's-header')}${cell('Сумма оплат', 's-header')}</Row>`;
  const label = (t: TripRegistrationReport['totals'][number]): string => {
    switch (t.level) {
      case 1: return t.organizerName ?? '—';
      case 2: return `${t.organizerName ?? '—'} / ${t.carrierName ?? '—'}`;
      case 3: return `${t.carrierName ?? '—'} / ${t.routeLabel ?? '—'}`;
      case 4: return `Смена ${t.shiftId ?? '—'}`;
      default: return `Рейс ${t.tripId ?? '—'}`;
    }
  };
  const body = report.totals
    .map(
      (t) =>
        `<Row>${cell(t.levelName, 's-center')}${cell(label(t))}${cellNumber(t.tripsCount)}${cellNumber(t.amount, 's-total-money')}</Row>`,
    )
    .join('\n   ');
  const grand = `<Row>${cell('', 's-total')}${cell('ИТОГО', 's-total')}${cellNumber(report.grandTotalTrips, 's-total')}${cellNumber(report.grandTotalAmount, 's-total-money')}</Row>`;
  return ` <Worksheet ss:Name="Итоги">
  <Table>
   <Column ss:Width="90"/>
   <Column ss:Width="420"/>
   <Column ss:Width="90"/>
   <Column ss:Width="120"/>
   ${head}
   ${body}
   ${grand}
  </Table>
 </Worksheet>`;
}

export function buildExcelXml(report: TripRegistrationReport): string {
  const titleRow = `<Row>${cell(report.title, 's-title')}</Row>`;
  // ИТОГО: объединённая ячейка на все колонки до «Оплаченной суммы» + сама сумма.
  const grandRow = `<Row>` +
    `<Cell ss:MergeAcross="${MONEY_COLUMN_INDEX - 2}" ss:StyleID="s-total">` +
    `<Data ss:Type="String">${esc(`ИТОГО — поездок: ${report.grandTotalTrips}`)}</Data></Cell>` +
    `<Cell ss:Index="${MONEY_COLUMN_INDEX}" ss:StyleID="s-total-money">` +
    `<Data ss:Type="Number">${Number(report.grandTotalAmount) || 0}</Data></Cell>` +
    `</Row>`;

  return `<?xml version="1.0" encoding="UTF-8"?>
<?mso-application progid="Excel.Sheet"?>
<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"
 xmlns:o="urn:schemas-microsoft-com:office:office"
 xmlns:x="urn:schemas-microsoft-com:office:excel"
 xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet"
 xmlns:html="http://www.w3.org/TR/REC-html40">
${STYLES}
 <Worksheet ss:Name="Реестр">
  <Table>
   ${titleRow}
   ${headerRow()}
   ${detailRows(report.rows)}
   <Row ss:Height="6"/>
   ${grandRow}
  </Table>
 </Worksheet>
${totalsSheet(report)}
</Workbook>`;
}

export function downloadExcelXml(report: TripRegistrationReport): void {
  // BOM — иначе Excel ругается на кодировку при открытии .xml
  const xml = `\uFEFF${buildExcelXml(report)}`;
  const blob = new Blob([xml], { type: 'application/vnd.ms-excel;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `Отчет-реестр_${report.dateFrom}_${report.dateTo}.xml`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

// ===== «Отчёт по сменам» =====

/** 1-based индекс первой денежной колонки («Сумма безнал») — в неё встаёт итог. */
const SHIFT_MONEY_COLUMN_INDEX = SHIFT_REPORT_COLUMNS.findIndex((c) => c.header === 'Сумма безнал') + 1;

function shiftHeaderRow(): string {
  const cells = SHIFT_REPORT_COLUMNS.map((c) => cell(c.header, 's-header')).join('');
  const widths = SHIFT_REPORT_COLUMNS.map((c) => `<Column ss:Width="${c.width}"/>`).join('');
  return `${widths}\n   <Row ss:Height="32">${cells}</Row>`;
}

function shiftDetailRows(report: ShiftReport): string {
  return report.rows
    .map((row) => {
      const cells = SHIFT_REPORT_COLUMNS.map((c: ShiftReportColumn) => typedCell(c.get(row), c.kind)).join('');
      return `<Row>${cells}</Row>`;
    })
    .join('\n   ');
}

/** Лист «Итоги»: 4 уровня группировки (организатор → перевозчик → маршрут → смена). */
function shiftTotalsSheet(report: ShiftReport): string {
  const head = `<Row>${cell('Уровень', 's-header')}${cell('Группировка', 's-header')}${cell('Смен', 's-header')}${cell('Транзакций', 's-header')}${cell('Безнал', 's-header')}${cell('Наличными', 's-header')}</Row>`;
  const label = (t: ShiftReport['totals'][number]): string => {
    switch (t.level) {
      case 1: return t.organizerName ?? '—';
      case 2: return `${t.organizerName ?? '—'} / ${t.carrierName ?? '—'}`;
      case 3: return `${t.carrierName ?? '—'} / ${t.routeLabel ?? '—'}`;
      default: return `Смена ${t.shiftId ?? '—'}`;
    }
  };
  const body = report.totals
    .map(
      (t) =>
        `<Row>${cell(t.levelName, 's-center')}${cell(label(t))}${cellNumber(t.shiftsCount)}${cellNumber(t.transactionsCount)}${cellNumber(t.cashlessAmount, 's-total-money')}${cellNumber(t.cashAmount, 's-total-money')}</Row>`,
    )
    .join('\n   ');
  const grand =
    `<Row>${cell('', 's-total')}${cell('ИТОГО', 's-total')}` +
    `${cellNumber(report.rows.length, 's-total')}${cellNumber(report.grandTotalTransactions, 's-total')}` +
    `${cellNumber(report.grandTotalCashlessAmount, 's-total-money')}${cellNumber(report.grandTotalCashAmount, 's-total-money')}</Row>`;
  return ` <Worksheet ss:Name="Итоги">
  <Table>
   <Column ss:Width="90"/>
   <Column ss:Width="420"/>
   <Column ss:Width="70"/>
   <Column ss:Width="100"/>
   <Column ss:Width="110"/>
   <Column ss:Width="110"/>
   ${head}
   ${body}
   ${grand}
  </Table>
 </Worksheet>`;
}

export function buildShiftExcelXml(report: ShiftReport): string {
  const titleRow = `<Row>${cell(report.title, 's-title')}</Row>`;
  // ИТОГО: объединённая ячейка до «Сумма безнал» + сумма + количество смен/транзакций.
  const grandRow =
    `<Row>` +
    `<Cell ss:MergeAcross="${SHIFT_MONEY_COLUMN_INDEX - 2}" ss:StyleID="s-total">` +
    `<Data ss:Type="String">${esc(
      `ИТОГО — смен: ${report.totalRows}, транзакций: ${report.grandTotalTransactions}`,
    )}</Data></Cell>` +
    `<Cell ss:Index="${SHIFT_MONEY_COLUMN_INDEX}" ss:StyleID="s-total-money">` +
    `<Data ss:Type="Number">${Number(report.grandTotalCashlessAmount) || 0}</Data></Cell>` +
    `<Cell ss:StyleID="s-total-money">` +
    `<Data ss:Type="Number">${Number(report.grandTotalCashAmount) || 0}</Data></Cell>` +
    `</Row>`;

  return `<?xml version="1.0" encoding="UTF-8"?>
<?mso-application progid="Excel.Sheet"?>
<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"
 xmlns:o="urn:schemas-microsoft-com:office:office"
 xmlns:x="urn:schemas-microsoft-com:office:excel"
 xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet"
 xmlns:html="http://www.w3.org/TR/REC-html40">
${STYLES}
 <Worksheet ss:Name="Смены">
  <Table>
   ${titleRow}
   ${shiftHeaderRow()}
   ${shiftDetailRows(report)}
   <Row ss:Height="6"/>
   ${grandRow}
  </Table>
 </Worksheet>
${shiftTotalsSheet(report)}
</Workbook>`;
}

export function downloadShiftExcelXml(report: ShiftReport): void {
  const xml = `﻿${buildShiftExcelXml(report)}`;
  const blob = new Blob([xml], { type: 'application/vnd.ms-excel;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `Отчет-по-сменам_${report.dateFrom}_${report.dateTo}.xml`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

// ===== Сводный, льготники =====

/** 1-based индекс колонки «Сумма возмещения» — в неё встаёт итог. */
const BENEFIT_MONEY_COLUMN_INDEX =
  BENEFIT_REPORT_COLUMNS.findIndex((c) => c.header === 'Сумма возмещения') + 1;

function benefitHeaderRow(): string {
  const cells = BENEFIT_REPORT_COLUMNS.map((c) => cell(c.header, 's-header')).join('');
  const widths = BENEFIT_REPORT_COLUMNS.map((c) => `<Column ss:Width="${c.width}"/>`).join('');
  return `${widths}\n   <Row ss:Height="32">${cells}</Row>`;
}

function benefitDetailRows(report: BenefitReport): string {
  return report.rows
    .map((row, i) => {
      const index = report.offset + i + 1; // «№ п/п» продолжает нумерацию через offset
      const cells = BENEFIT_REPORT_COLUMNS.map((c: BenefitReportColumn) =>
        typedCell(c.get(row, index), c.kind),
      ).join('');
      return `<Row>${cells}</Row>`;
    })
    .join('\n   ');
}

/** Лист «Итоги»: 2 уровня (регион → категория). */
function benefitTotalsSheet(report: BenefitReport): string {
  const head =
    `<Row>${cell('Уровень', 's-header')}${cell('Группировка', 's-header')}` +
    `${cell('Поездок', 's-header')}${cell('Без ставки', 's-header')}${cell('Сумма возмещения', 's-header')}</Row>`;
  const label = (t: BenefitReport['totals'][number]): string => {
    if (t.level === 1) return t.regionName ?? '—';
    return `${t.regionName ?? '—'} / ${t.benefitName ?? '—'}`;
  };
  const body = report.totals
    .map(
      (t) =>
        `<Row>${cell(t.levelName, 's-center')}${cell(label(t))}${cellNumber(t.tripsCount)}` +
        `${cellNumber(t.tripsWithoutRate)}${cellNumber(t.compensation, 's-total-money')}</Row>`,
    )
    .join('\n   ');
  const grand =
    `<Row>${cell('', 's-total')}${cell('ИТОГО', 's-total')}` +
    `${cellNumber(report.grandTotalTrips, 's-total')}${cellNumber(report.grandTripsWithoutRate, 's-total')}` +
    `${cellNumber(report.grandTotalCompensation, 's-total-money')}</Row>`;
  return ` <Worksheet ss:Name="Итоги">
  <Table>
   <Column ss:Width="90"/>
   <Column ss:Width="420"/>
   <Column ss:Width="90"/>
   <Column ss:Width="110"/>
   <Column ss:Width="140"/>
   ${head}
   ${body}
   ${grand}
  </Table>
 </Worksheet>`;
}

export function buildBenefitExcelXml(report: BenefitReport): string {
  const titleRow = `<Row>${cell(report.title, 's-title')}</Row>`;
  // ИТОГО: объединённая ячейка на все колонки до «Сумма возмещения» + сама сумма.
  const grandRow =
    `<Row>` +
    `<Cell ss:MergeAcross="${BENEFIT_MONEY_COLUMN_INDEX - 2}" ss:StyleID="s-total">` +
    `<Data ss:Type="String">${esc(
      `ИТОГО — категорий: ${report.totalRows}, поездок: ${report.grandTotalTrips}, без ставки: ${report.grandTripsWithoutRate}`,
    )}</Data></Cell>` +
    `<Cell ss:Index="${BENEFIT_MONEY_COLUMN_INDEX}" ss:StyleID="s-total-money">` +
    `<Data ss:Type="Number">${Number(report.grandTotalCompensation) || 0}</Data></Cell>` +
    `</Row>`;

  return `<?xml version="1.0" encoding="UTF-8"?>
<?mso-application progid="Excel.Sheet"?>
<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"
 xmlns:o="urn:schemas-microsoft-com:office:office"
 xmlns:x="urn:schemas-microsoft-com:office:excel"
 xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet"
 xmlns:html="http://www.w3.org/TR/REC-html40">
${STYLES}
 <Worksheet ss:Name="Льготы">
  <Table>
   ${titleRow}
   ${benefitHeaderRow()}
   ${benefitDetailRows(report)}
   <Row ss:Height="6"/>
   ${grandRow}
  </Table>
 </Worksheet>
${benefitTotalsSheet(report)}
</Workbook>`;
}

export function downloadBenefitExcelXml(report: BenefitReport): void {
  const xml = `﻿${buildBenefitExcelXml(report)}`;
  const blob = new Blob([xml], { type: 'application/vnd.ms-excel;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `Сводный-льготники_${report.dateFrom}_${report.dateTo}.xml`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
