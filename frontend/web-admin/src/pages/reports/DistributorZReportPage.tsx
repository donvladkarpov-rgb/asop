import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getCardsDistributors } from '../../api/cardsDistributors';
import { getDistributorZReport, type DistributorZReportParams } from '../../api/reports-distributor-z';
import { DISTRIBUTOR_Z_REPORT_COLUMNS, formatDateTime, formatMoney, type CellKind } from './columns';
import { downloadDistributorZReportExcelXml } from './excelXml';

function isoToday(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const PAGE_LIMIT = 1000;

export function DistributorZReportPage() {
  const [dateFrom, setDateFrom] = useState(isoToday());
  const [dateTo, setDateTo] = useState(isoToday());
  const [cardsDistributorId, setCardsDistributorId] = useState('');
  const [applied, setApplied] = useState(false);

  const distributorsQuery = useQuery({ queryKey: ['cards-distributors'], queryFn: getCardsDistributors });

  const params: DistributorZReportParams = useMemo(
    () => ({
      dateFrom,
      dateTo,
      limit: PAGE_LIMIT,
      cardsDistributorId: cardsDistributorId || undefined,
    }),
    // applied — «заморозка» фильтров до нажатия «Построить отчёт»
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [dateFrom, dateTo, cardsDistributorId, applied],
  );

  const reportQuery = useQuery({
    queryKey: ['distributor-z-report', params],
    queryFn: () => getDistributorZReport(params),
    enabled: applied,
  });

  const report = reportQuery.data;
  const rows = report?.rows ?? [];

  function formatCell(kind: CellKind, value: string | number | null): string {
    if (value === null || value === undefined) return '';
    if (kind === 'money') return formatMoney(value);
    if (kind === 'datetime') return formatDateTime(String(value));
    return String(value);
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Отчёты</h1>
        <p className="page-subtitle">Z отчёт (дистрибьюторы): реестр фискальных чеков — наличные/банковская карта</p>
      </div>

      <div className="filters-panel">
        <div className="filters-row">
          <label className="field">
            <span className="field-label">Период с</span>
            <input type="date" value={dateFrom} max={dateTo} onChange={(e) => setDateFrom(e.target.value)} />
          </label>
          <label className="field">
            <span className="field-label">по</span>
            <input type="date" value={dateTo} min={dateFrom} onChange={(e) => setDateTo(e.target.value)} />
          </label>
          <label className="field">
            <span className="field-label">Дистрибьютор</span>
            <select value={cardsDistributorId} onChange={(e) => setCardsDistributorId(e.target.value)}>
              <option value="">Все</option>
              {(distributorsQuery.data ?? []).map((d) => (
                <option key={d.id} value={d.id}>{d.distributorName}</option>
              ))}
            </select>
          </label>
          <div className="field field-actions">
            <button className="btn btn-primary" onClick={() => setApplied(true)} disabled={reportQuery.isFetching}>
              {reportQuery.isFetching ? 'Загрузка…' : 'Построить отчёт'}
            </button>
            <button
              className="btn"
              disabled={!report || rows.length === 0}
              onClick={() => report && downloadDistributorZReportExcelXml(report)}
            >
              Выгрузить в Excel
            </button>
          </div>
        </div>

        <p className="hint">
          Строка — фискальный чек дистрибьютора (пополнение/выдача карты). Способ оплаты — по acqReference: наличные/банковская карта.
        </p>
      </div>

      {applied && report && (
        <>
          <div className="report-summary">
            <h2 className="report-title">{report.title}</h2>
            <div className="report-stats">
              <span>Чеков: <b>{report.totalRows}</b></span>
              <span>Наличные: <b>{report.totals.cashCount} / {formatMoney(report.totals.cashSum)} ₽</b></span>
              <span>Банк. карта: <b>{report.totals.bankCount} / {formatMoney(report.totals.bankSum)} ₽</b></span>
              <span>Итого: <b>{formatMoney(report.totals.totalSum)} ₽</b></span>
              {report.totalRows > report.rows.length && (
                <span className="hint">
                  Показана первая {report.rows.length} строк из {report.totalRows} — сузьте период или фильтры
                </span>
              )}
            </div>
          </div>

          {reportQuery.isError && (
            <div className="alert alert-error">
              Не удалось построить отчёт: {(reportQuery.error as Error)?.message}
            </div>
          )}

          <div className="table-scroll report-table-scroll">
            <table className="data-table report-table">
              <thead>
                <tr>
                  {DISTRIBUTOR_Z_REPORT_COLUMNS.map((c) => (
                    <th
                      key={c.header}
                      style={{ minWidth: c.width }}
                      className={c.kind === 'money' || c.kind === 'num' ? 'num' : undefined}
                    >
                      {c.header}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.receiptId}>
                    {DISTRIBUTOR_Z_REPORT_COLUMNS.map((c) => (
                      <td
                        key={c.header}
                        className={c.kind === 'money' || c.kind === 'num' ? 'num' : undefined}
                      >
                        {formatCell(c.kind, c.get(r))}
                      </td>
                    ))}
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={DISTRIBUTOR_Z_REPORT_COLUMNS.length} className="empty-cell">
                      За выбранный период чеков не найдено
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}

      {!applied && (
        <p className="hint">Задайте период и фильтры, затем нажмите «Построить отчёт».</p>
      )}
    </div>
  );
}
