import type { TripRegistrationReport, TripRegistrationReportRow } from '../../api/reports';
import { REPORT_COLUMNS } from './columns';

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

/** Ячейка по типу колонки: money → Number, остальное → String. */
function typedCell(value: unknown, kind: string): string {
  return kind === 'money' ? cellNumber(value, 's-money') : cell(value, styleFor(kind));
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