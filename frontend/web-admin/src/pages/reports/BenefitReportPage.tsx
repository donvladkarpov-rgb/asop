import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getRegions, getBenefits } from '../../api/reference';
import { getCarriers } from '../../api/carriers';
import { getBenefitReport, type BenefitReportParams } from '../../api/reports-benefit';
import { useGlobalFilter } from '../../contexts/GlobalFilterContext';
import { BENEFIT_REPORT_COLUMNS, formatMoney, type CellKind } from './columns';
import { downloadBenefitExcelXml } from './excelXml';

function isoToday(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const PAGE_LIMIT = 1000;

export function BenefitReportPage() {
  const globalFilter = useGlobalFilter();

  const [dateFrom, setDateFrom] = useState(isoToday());
  const [dateTo, setDateTo] = useState(isoToday());
  const [regionId, setRegionId] = useState(globalFilter.regionId);
  const [carrierId, setCarrierId] = useState(globalFilter.carrierId);
  const [benefitId, setBenefitId] = useState('');
  const [applied, setApplied] = useState(false);

  // Дефолты берём из правой панели глобального фильтра (регион/перевозчик),
  // но только пока пользователь не выбрал свои значения.
  const [touched, setTouched] = useState({ region: false, carrier: false });
  useEffect(() => {
    if (!touched.region && globalFilter.regionId) {
      setRegionId(globalFilter.regionId);
      setCarrierId('');
      setTouched((t) => ({ ...t, region: true }));
    }
  }, [globalFilter.regionId, touched.region]);
  useEffect(() => {
    if (!touched.carrier && globalFilter.carrierId) {
      setCarrierId(globalFilter.carrierId);
      setTouched((t) => ({ ...t, carrier: true }));
    }
  }, [globalFilter.carrierId, touched.carrier]);

  const regionsQuery = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const carriersQuery = useQuery({
    queryKey: ['carriers', regionId],
    queryFn: () => getCarriers(regionId || undefined),
  });
  const benefitsQuery = useQuery({
    queryKey: ['benefits', regionId],
    queryFn: () => getBenefits(regionId || undefined),
  });

  const params: BenefitReportParams = useMemo(
    () => ({
      dateFrom,
      dateTo,
      limit: PAGE_LIMIT,
      regionId: regionId || undefined,
      carrierId: carrierId || undefined,
      benefitId: benefitId || undefined,
    }),
    // applied — «заморозка» фильтров до нажатия «Построить отчёт»
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [dateFrom, dateTo, regionId, carrierId, benefitId, applied],
  );

  const reportQuery = useQuery({
    queryKey: ['benefit-report', params],
    queryFn: () => getBenefitReport(params),
    enabled: applied,
  });

  const report = reportQuery.data;
  const rows = report?.rows ?? [];

  const grouped = useMemo(() => {
    return (report?.totals ?? []).map((t) => ({
      level: t.level,
      levelName: t.levelName,
      key: `${t.regionId ?? ''}|${t.benefitId ?? ''}`,
      label:
        t.level === 1
          ? (t.regionName ?? '—')
          : `${t.regionName ?? '—'} / ${t.benefitCode ?? '—'} ${t.benefitName ?? ''}`.trim(),
      trips: t.tripsCount,
      withoutRate: t.tripsWithoutRate,
      compensation: Number(t.compensation),
    }));
  }, [report]);

  function formatCell(kind: CellKind, value: string | number | null): string {
    if (value === null || value === undefined) return '';
    if (kind === 'money') return formatMoney(value);
    return String(value);
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Отчёты</h1>
        <p className="page-subtitle">
          Сводный, льготники: льготные поездки и сумма возмещения по категориям
        </p>
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
            <span className="field-label">Регион</span>
            <select
              value={regionId}
              onChange={(e) => {
                setRegionId(e.target.value);
                setCarrierId('');
                setBenefitId('');
                setTouched((t) => ({ ...t, region: true }));
              }}
            >
              <option value="">Все</option>
              {(regionsQuery.data ?? []).map((r) => (
                <option key={r.id} value={r.id}>{r.municipalDivision}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Перевозчик</span>
            <select
              value={carrierId}
              onChange={(e) => {
                setCarrierId(e.target.value);
                setTouched((t) => ({ ...t, carrier: true }));
              }}
            >
              <option value="">Все</option>
              {(carriersQuery.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>{c.carrierName}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Категория льготы</span>
            <select value={benefitId} onChange={(e) => setBenefitId(e.target.value)}>
              <option value="">Все</option>
              {(benefitsQuery.data ?? []).map((b) => (
                <option key={b.id} value={b.id}>
                  {b.benefitCode} {b.benefitName}
                </option>
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
              onClick={() => report && downloadBenefitExcelXml(report)}
            >
              Выгрузить в Excel
            </button>
          </div>
        </div>

        <p className="hint">
          Возмещение = тариф × доля скидки на дату поездки. Группировка и сортировка: регион → категория.
          Итоги — в блоке «Итоги по группам» под сводкой.
        </p>
      </div>

      {applied && reportQuery.isError && (
        <div className="alert alert-error">
          Не удалось построить отчёт: {(reportQuery.error as Error)?.message}
        </div>
      )}

      {applied && report && (
        <>
          <div className="report-summary">
            <h2 className="report-title">{report.title}</h2>
            <div className="report-stats">
              <span>Категорий: <b>{report.totalRows}</b></span>
              <span>Поездок: <b>{report.grandTotalTrips}</b></span>
              <span>Возмещение: <b>{formatMoney(report.grandTotalCompensation)} ₽</b></span>
              <span>Без ставки: <b>{report.grandTripsWithoutRate}</b></span>
              {report.totalRows > report.rows.length && (
                <span className="hint">
                  Показана первая {report.rows.length} строк из {report.totalRows} — сузьте период или фильтры
                </span>
              )}
            </div>
          </div>

          {grouped.length > 0 && (
            <details className="report-totals">
              <summary>Итоги по группам ({grouped.length})</summary>
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Уровень</th>
                    <th>Группировка</th>
                    <th className="num">Поездок</th>
                    <th className="num">Без ставки</th>
                    <th className="num">Сумма возмещения</th>
                  </tr>
                </thead>
                <tbody>
                  {grouped.map((g) => (
                    <tr key={`${g.level}-${g.key}`} className={`total-row total-level-${g.level}`}>
                      <td>{g.levelName}</td>
                      <td>{g.label}</td>
                      <td className="num">{g.trips}</td>
                      <td className="num">{g.withoutRate}</td>
                      <td className="num">{formatMoney(g.compensation)}</td>
                    </tr>
                  ))}
                  <tr className="total-row total-grand">
                    <td colSpan={2}>ИТОГО</td>
                    <td className="num">{report.grandTotalTrips}</td>
                    <td className="num">{report.grandTripsWithoutRate}</td>
                    <td className="num">{formatMoney(report.grandTotalCompensation)}</td>
                  </tr>
                </tbody>
              </table>
            </details>
          )}

          <div className="table-scroll report-table-scroll">
            <table className="data-table report-table">
              <thead>
                <tr>
                  {BENEFIT_REPORT_COLUMNS.map((c) => (
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
                {rows.map((r, i) => (
                  <tr key={`${r.regionId ?? ''}|${r.benefitId ?? ''}`}>
                    {BENEFIT_REPORT_COLUMNS.map((c) => (
                      <td
                        key={c.header}
                        className={c.kind === 'money' || c.kind === 'num' ? 'num' : undefined}
                      >
                        {formatCell(c.kind, c.get(r, report.offset + i + 1))}
                      </td>
                    ))}
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={BENEFIT_REPORT_COLUMNS.length} className="empty-cell">
                      За выбранный период льготных поездок не найдено
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
