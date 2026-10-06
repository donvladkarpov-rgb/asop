import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getRegions, getOrganizers } from '../../api/reference';
import { getCarriers } from '../../api/carriers';
import { getRoutes, getPaths, getVehicles, getAdminUsers, type Route, type Path, type Vehicle, type AdminUser } from '../../api/routes';
import { getTerminals } from '../../api/terminals';
import type { Terminal } from '../../types';
import { getShiftReport, type ShiftReportParams } from '../../api/reports-shifts';
import { useGlobalFilter } from '../../contexts/GlobalFilterContext';
import { SHIFT_REPORT_COLUMNS, formatDateTime, formatMoney, formatPct, type CellKind } from './columns';
import { downloadShiftExcelXml } from './excelXml';

function isoToday(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const PAGE_LIMIT = 1000;

export function ShiftReportPage() {
  const globalFilter = useGlobalFilter();

  const [dateFrom, setDateFrom] = useState(isoToday());
  const [dateTo, setDateTo] = useState(isoToday());
  const [regionId, setRegionId] = useState(globalFilter.regionId);
  const [organizerId, setOrganizerId] = useState('');
  const [carrierId, setCarrierId] = useState(globalFilter.carrierId);
  const [routeId, setRouteId] = useState('');
  const [pathId, setPathId] = useState('');
  const [vehicleId, setVehicleId] = useState('');
  const [terminalId, setTerminalId] = useState('');
  const [driverId, setDriverId] = useState('');
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
  const organizersQuery = useQuery({
    queryKey: ['organizers', regionId],
    queryFn: () => getOrganizers(regionId || undefined),
    enabled: true,
  });
  const carriersQuery = useQuery({
    queryKey: ['carriers', regionId],
    queryFn: () => getCarriers(regionId || undefined),
  });
  const routesQuery = useQuery({
    queryKey: ['routes', regionId],
    queryFn: () => getRoutes(regionId ? { regionId } : {}),
  });
  const pathsQuery = useQuery({
    queryKey: ['paths', regionId],
    queryFn: () => getPaths(regionId ? { regionId } : {}),
  });
  const vehiclesQuery = useQuery({
    queryKey: ['vehicles', regionId, carrierId],
    queryFn: () => getVehicles({ regionId: regionId || undefined, carrierId: carrierId || undefined }),
  });
  const terminalsQuery = useQuery({
    queryKey: ['terminals', carrierId],
    queryFn: () => getTerminals(carrierId || undefined, regionId || undefined),
  });
  const usersQuery = useQuery({
    queryKey: ['admin-users', regionId, carrierId],
    queryFn: () => getAdminUsers({ regionId: regionId || undefined, carrierId: carrierId || undefined }),
  });

  const params: ShiftReportParams = useMemo(
    () => ({
      dateFrom,
      dateTo,
      limit: PAGE_LIMIT,
      regionId: regionId || undefined,
      organizerId: organizerId || undefined,
      carrierId: carrierId || undefined,
      routeId: routeId || undefined,
      pathId: pathId || undefined,
      vehicleId: vehicleId || undefined,
      terminalId: terminalId || undefined,
      driverId: driverId || undefined,
    }),
    // applied — «заморозка» фильтров до нажатия «Построить отчёт»
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [dateFrom, dateTo, regionId, organizerId, carrierId, routeId, pathId, vehicleId, terminalId, driverId, applied],
  );

  const reportQuery = useQuery({
    queryKey: ['shift-report', params],
    queryFn: () => getShiftReport(params),
    enabled: applied,
  });

  const report = reportQuery.data;
  const rows = report?.rows ?? [];

  const grouped = useMemo(() => {
    const levels: { level: number; levelName: string; key: string; label: string; shifts: number; transactions: number; cashless: number; cash: number }[] = [];
    for (const t of report?.totals ?? []) {
      const key = [t.organizerId, t.carrierId, t.routeId, t.shiftId].map((v) => v ?? '').join('|');
      let label = '';
      if (t.level === 1) label = t.organizerName ?? '—';
      else if (t.level === 2) label = `${t.organizerName ?? '—'} / ${t.carrierName ?? '—'}`;
      else if (t.level === 3) label = `${t.carrierName ?? '—'} / ${t.routeLabel ?? '—'}`;
      else label = `Смена ${t.shiftId ?? '—'}`;
      levels.push({
        level: t.level,
        levelName: t.levelName,
        key,
        label,
        shifts: t.shiftsCount,
        transactions: t.transactionsCount,
        cashless: Number(t.cashlessAmount),
        cash: Number(t.cashAmount),
      });
    }
    return levels;
  }, [report]);

  function resetDependents() {
    setRouteId('');
    setPathId('');
    setVehicleId('');
  }

  function formatCell(kind: CellKind, value: string | number | null): string {
    if (value === null || value === undefined) return '';
    if (kind === 'money') return formatMoney(value);
    if (kind === 'datetime') return formatDateTime(String(value));
    if (kind === 'percent') return formatPct(value);
    return String(value);
  }

  function driverName(u: AdminUser): string {
    return `${u.lastNameInitial}.`;
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Отчёты</h1>
        <p className="page-subtitle">Отчёт по сменам: транзакции, безнал/наличные, итоги по группам</p>
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
                setOrganizerId('');
                setTouched((t) => ({ ...t, region: true }));
                resetDependents();
              }}
            >
              <option value="">Все</option>
              {(regionsQuery.data ?? []).map((r) => (
                <option key={r.id} value={r.id}>{r.municipalDivision}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Организатор</span>
            <select
              value={organizerId}
              onChange={(e) => {
                setOrganizerId(e.target.value);
                setRouteId('');
                setPathId('');
              }}
            >
              <option value="">Все</option>
              {(organizersQuery.data ?? []).map((o) => (
                <option key={o.id} value={o.id}>{o.organizerName}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Перевозчик</span>
            <select
              value={carrierId}
              onChange={(e) => {
                setCarrierId(e.target.value);
                setTerminalId('');
                setTouched((t) => ({ ...t, carrier: true }));
                resetDependents();
              }}
            >
              <option value="">Все</option>
              {(carriersQuery.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>{c.carrierName}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Маршрут</span>
            <select value={routeId} onChange={(e) => { setRouteId(e.target.value); setPathId(''); }}>
              <option value="">Все</option>
              {(routesQuery.data ?? [])
                .filter((r: Route) => !organizerId || r.organizerId === organizerId)
                .map((r: Route) => (
                  <option key={r.id} value={r.id}>
                    {r.routeNumber} {r.routeName}
                  </option>
                ))}
            </select>
          </label>
        </div>

        <div className="filters-row">
          <label className="field">
            <span className="field-label">Путь</span>
            <select value={pathId} onChange={(e) => setPathId(e.target.value)}>
              <option value="">Все</option>
              {(pathsQuery.data ?? [])
                .filter((p: Path) => !routeId || p.routeId === routeId)
                .map((p: Path) => (
                  <option key={p.id} value={p.id}>{p.pathName}</option>
                ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">ТС</span>
            <select value={vehicleId} onChange={(e) => setVehicleId(e.target.value)}>
              <option value="">Все</option>
              {(vehiclesQuery.data ?? []).map((v: Vehicle) => (
                <option key={v.id} value={v.id}>{v.vehicleNumber} {v.vehicleName}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Терминал</span>
            <select value={terminalId} onChange={(e) => setTerminalId(e.target.value)}>
              <option value="">Все</option>
              {(terminalsQuery.data ?? []).map((t: Terminal) => (
                <option key={t.id} value={t.id}>{t.terminalNumber ?? t.terminalSerial}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Водитель</span>
            <select value={driverId} onChange={(e) => setDriverId(e.target.value)}>
              <option value="">Все</option>
              {(usersQuery.data ?? []).map((u: AdminUser) => (
                <option key={u.id} value={u.id}>{u.firstName} {driverName(u)}</option>
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
              onClick={() => report && downloadShiftExcelXml(report)}
            >
              Выгрузить в Excel
            </button>
          </div>
        </div>

        <p className="hint">
          Группировка и сортировка: организатор → перевозчик → маршрут → смена. Итоги по каждой группе —
          в блоке «Итоги по группам» под сводкой.
        </p>
      </div>

      {applied && report && (
        <>
          <div className="report-summary">
            <h2 className="report-title">{report.title}</h2>
            <div className="report-stats">
              <span>Смен: <b>{report.totalRows}</b></span>
              <span>Транзакций: <b>{report.grandTotalTransactions}</b></span>
              <span>Безнал: <b>{formatMoney(report.grandTotalCashlessAmount)} ₽</b></span>
              <span>Наличными: <b>{formatMoney(report.grandTotalCashAmount)} ₽</b></span>
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

          {grouped.length > 0 && (
            <details className="report-totals">
              <summary>Итоги по группам ({grouped.length})</summary>
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Уровень</th>
                    <th>Группировка</th>
                    <th className="num">Смен</th>
                    <th className="num">Транзакций</th>
                    <th className="num">Сумма безнал</th>
                    <th className="num">Сумма наличными</th>
                  </tr>
                </thead>
                <tbody>
                  {grouped.map((g) => (
                    <tr key={`${g.level}-${g.key}`} className={`total-row total-level-${g.level}`}>
                      <td>{g.levelName}</td>
                      <td>{g.label}</td>
                      <td className="num">{g.shifts}</td>
                      <td className="num">{g.transactions}</td>
                      <td className="num">{formatMoney(g.cashless)}</td>
                      <td className="num">{formatMoney(g.cash)}</td>
                    </tr>
                  ))}
                  <tr className="total-row total-grand">
                    <td colSpan={3}>ИТОГО</td>
                    <td className="num">{report.grandTotalTransactions}</td>
                    <td className="num">{formatMoney(report.grandTotalCashlessAmount)}</td>
                    <td className="num">{formatMoney(report.grandTotalCashAmount)}</td>
                  </tr>
                </tbody>
              </table>
            </details>
          )}

          <div className="table-scroll report-table-scroll">
            <table className="data-table report-table">
              <thead>
                <tr>
                  {SHIFT_REPORT_COLUMNS.map((c) => (
                    <th
                      key={c.header}
                      style={{ minWidth: c.width }}
                      className={c.kind === 'money' || c.kind === 'num' || c.kind === 'percent' ? 'num' : undefined}
                    >
                      {c.header}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.shiftId}>
                    {SHIFT_REPORT_COLUMNS.map((c) => (
                      <td
                        key={c.header}
                        className={c.kind === 'money' || c.kind === 'num' || c.kind === 'percent' ? 'num' : undefined}
                      >
                        {formatCell(c.kind, c.get(r))}
                      </td>
                    ))}
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={SHIFT_REPORT_COLUMNS.length} className="empty-cell">
                      За выбранный период смен не найдено
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
