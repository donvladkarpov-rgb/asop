import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getRegions, getOrganizers } from '../../api/reference';
import { getCarriers } from '../../api/carriers';
import { getRoutes, getPaths, getVehicles, getAdminUsers, type Route, type Path, type Vehicle, type AdminUser } from '../../api/routes';
import { getTerminals } from '../../api/terminals';
import type { Terminal } from '../../types';
import { getShiftReport, type ShiftReportParams } from '../../api/reports-shifts';
import { useGlobalFilter } from '../../contexts/GlobalFilterContext';
import { formatDateTime, formatMoney } from './columns';

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
  const organizersQuery = useQuery({ queryKey: ['organizers', regionId], queryFn: () => getOrganizers(regionId || undefined), enabled: true });
  const carriersQuery = useQuery({ queryKey: ['carriers', regionId], queryFn: () => getCarriers(regionId || undefined) });
  const routesQuery = useQuery({ queryKey: ['routes', regionId], queryFn: () => getRoutes(regionId ? { regionId } : {}) });
  const pathsQuery = useQuery({ queryKey: ['paths', regionId], queryFn: () => getPaths(regionId ? { regionId } : {}) });
  const vehiclesQuery = useQuery({ queryKey: ['vehicles', regionId, carrierId], queryFn: () => getVehicles({ regionId: regionId || undefined, carrierId: carrierId || undefined }) });
  const terminalsQuery = useQuery({ queryKey: ['terminals', carrierId], queryFn: () => getTerminals(carrierId || undefined, regionId || undefined) });
  const usersQuery = useQuery({ queryKey: ['admin-users', regionId, carrierId], queryFn: () => getAdminUsers({ regionId: regionId || undefined, carrierId: carrierId || undefined }) });

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
    [dateFrom, dateTo, regionId, organizerId, carrierId, routeId, pathId, vehicleId, terminalId, driverId, applied]
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

  function pct(v: string | number): string {
    const n = typeof v === 'number' ? v : Number(v);
    if (Number.isNaN(n)) return String(v);
    return `${n.toFixed(2)}%`;
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Отчёт по сменам</h1>
      </div>

      <div className="filter-panel">
        <div className="filter-row">
          <div className="filter-field">
            <label>Дата с</label>
            <input type="date" value={dateFrom} onChange={(e) => { setDateFrom(e.target.value); setApplied(false); }} />
          </div>
          <div className="filter-field">
            <label>Дата по</label>
            <input type="date" value={dateTo} onChange={(e) => { setDateTo(e.target.value); setApplied(false); }} />
          </div>
        </div>

        <div className="filter-row">
          <div className="filter-field">
            <label>Регион</label>
            <select value={regionId} onChange={(e) => { setRegionId(e.target.value); setTouched((t) => ({ ...t, region: true })); setCarrierId(''); resetDependents(); setApplied(false); }}>
              <option value="">Все</option>
              {regionsQuery.data?.map((r: any) => (<option key={r.regionId} value={r.regionId}>{r.regionName}</option>))}
            </select>
          </div>
          <div className="filter-field">
            <label>Организатор</label>
            <select value={organizerId} onChange={(e) => { setOrganizerId(e.target.value); setApplied(false); }}>
              <option value="">Все</option>
              {organizersQuery.data?.map((o: any) => (<option key={o.organizerId} value={o.organizerId}>{o.organizerName}</option>))}
            </select>
          </div>
          <div className="filter-field">
            <label>Перевозчик</label>
            <select value={carrierId} onChange={(e) => { setCarrierId(e.target.value); setTouched((t) => ({ ...t, carrier: true })); resetDependents(); setApplied(false); }}>
              <option value="">Все</option>
              {carriersQuery.data?.map((c: any) => (<option key={c.carrierId} value={c.carrierId}>{c.carrierName}</option>))}
            </select>
          </div>
        </div>

        <div className="filter-row">
          <div className="filter-field">
            <label>Маршрут</label>
            <select value={routeId} onChange={(e) => { setRouteId(e.target.value); setPathId(''); setApplied(false); }}>
              <option value="">Все</option>
              {routesQuery.data?.items?.map((rt: Route) => (<option key={rt.id} value={rt.id}>{rt.routeNumber} {rt.routeName}</option>))}
            </select>
          </div>
          <div className="filter-field">
            <label>Путь</label>
            <select value={pathId} onChange={(e) => { setPathId(e.target.value); setVehicleId(''); setApplied(false); }}>
              <option value="">Все</option>
              {pathsQuery.data?.items?.map((p: Path) => (<option key={p.id} value={p.id}>{p.pathName}</option>))}
            </select>
          </div>
          <div className="filter-field">
            <label>Транспортное средство</label>
            <select value={vehicleId} onChange={(e) => { setVehicleId(e.target.value); setApplied(false); }}>
              <option value="">Все</option>
              {vehiclesQuery.data?.map((v: Vehicle) => (<option key={v.id} value={v.id}>{v.vehicleNumber} {v.vehicleName}</option>))}
            </select>
          </div>
        </div>

        <div className="filter-row">
          <div className="filter-field">
            <label>Терминал</label>
            <select value={terminalId} onChange={(e) => { setTerminalId(e.target.value); setApplied(false); }}>
              <option value="">Все</option>
              {terminalsQuery.data?.map((t: Terminal) => (<option key={t.id} value={t.id}>{t.terminalNumber || t.terminalSerial}</option>))}
            </select>
          </div>
          <div className="filter-field">
            <label>Водитель</label>
            <select value={driverId} onChange={(e) => { setDriverId(e.target.value); setApplied(false); }}>
              <option value="">Все</option>
              {usersQuery.data?.items?.map((u: AdminUser) => (<option key={u.id} value={u.id}>{u.firstName} {u.lastNameInitial}</option>))}
            </select>
          </div>
        </div>

        <div className="filter-actions">
          <button className="btn btn-primary" onClick={() => setApplied(true)}>Построить отчёт</button>
          <button className="btn btn-secondary" onClick={() => { setApplied(false); }}>Сбросить фильтры</button>
        </div>
      </div>

      {applied && reportQuery.isFetching && <p>Загрузка…</p>}
      {applied && reportQuery.isError && <div className="alert alert-error">Не удалось построить отчёт</div>}

      {applied && grouped.length > 0 && (
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
                <td className="num">{report?.grandTotalTransactions}</td>
                <td className="num">{formatMoney(report?.grandTotalCashlessAmount)}</td>
                <td className="num">{formatMoney(report?.grandTotalCashAmount)}</td>
              </tr>
            </tbody>
          </table>
        </details>
      )}

      {applied && rows.length > 0 && (
        <div className="table-scroll report-table-scroll">
          <table className="data-table report-table">
            <thead>
              <tr>
                <th>ID смены</th>
                <th>Перевозчик</th>
                <th>Маршрут</th>
                <th>Транспортное средство</th>
                <th>Серийный номер терминала</th>
                <th>Дата открытия смены</th>
                <th>Дата закрытия смены</th>
                <th className="num">Количество транзакций</th>
                <th className="num">Успешных по картам</th>
                <th className="num">Неуспешных по картам</th>
                <th className="num">Доля неуспешных</th>
                <th className="num">Сумма безнал</th>
                <th className="num">Сумма безнал (без скидки)</th>
                <th className="num">Количество безнал</th>
                <th className="num">Безнал, доля</th>
                <th className="num">Сумма наличными</th>
                <th className="num">Количество наличными</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.shiftId}>
                  <td>{r.shiftId}</td>
                  <td>{r.carrierName}</td>
                  <td>{r.routeNumber} {r.routeName}</td>
                  <td>{r.vehicleNumber} {r.vehicleName}</td>
                  <td>{r.terminalSerial || r.terminalNumber}</td>
                  <td>{formatDateTime(r.shiftStartedAt)}</td>
                  <td>{formatDateTime(r.shiftClosedAt)}</td>
                  <td className="num">{r.transactionsCount}</td>
                  <td className="num">{r.successfulCardTransactions}</td>
                  <td className="num">{r.failedCardTransactions}</td>
                  <td className="num">{pct(r.failedSharePct)}</td>
                  <td className="num">{formatMoney(r.cashlessAmount)}</td>
                  <td className="num">{formatMoney(r.cashlessAmountWithoutDiscount)}</td>
                  <td className="num">{r.cashlessCount}</td>
                  <td className="num">{pct(r.cashlessSharePct)}</td>
                  <td className="num">{formatMoney(r.cashAmount)}</td>
                  <td className="num">{r.cashCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {applied && rows.length === 0 && <p>За выбранный период операций не найдено</p>}
      {!applied && <p className="hint">Задайте период и фильтры, затем нажмите «Построить отчёт».</p>}
    </div>
  );
}
