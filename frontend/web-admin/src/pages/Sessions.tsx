import { useState, Fragment, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import apiClient from '../api/client';
import { getCarriers } from '../api/carriers';
import { getTerminals } from '../api/terminals';
import { getBenefits } from '../api/reference';
import { getRoutes, getPaths, getVehicles, getAdminUsers } from '../api/routes';
import { useGlobalFilter } from '../contexts/GlobalFilterContext';
import type { Session, Transaction } from '../types';

const SESSION_TYPE_IDS: Record<string, string> = {
  '00000000-0000-0000-0000-000000000601': 'Смена',
  '00000000-0000-0000-0000-000000000602': 'Перерыв',
  '00000000-0000-0000-0000-000000000603': 'Рейс',
};

const getSessions = (terminalId?: string) =>
  apiClient.get<Session[]>('/sessions', { params: terminalId ? { terminalId } : {} }).then((r) => r.data);

const getTransactions = (sessionId: string) =>
  apiClient.get<Transaction[]>('/transactions', { params: { sessionId } }).then((r) => r.data);

const fmt = (ts?: string | null) => (ts ? new Date(ts).toLocaleString('ru-RU') : '—');
const short = (id?: string | null) => (id ? `${id.slice(0, 8)}…` : '—');
const typeLabel = (id?: string) => (id ? SESSION_TYPE_IDS[id] || short(id) : '—');
const statusClass = (status: string) =>
  status === 'IN_PROGRESS' ? 'status-ok' : status === 'CLOSED' ? 'status-dim' : '';

/** YYYY-MM-DD по локальной дате. */
const dayKey = (ts?: string | null) =>
  ts ? new Date(ts).toLocaleDateString('sv-SE') : '';
const byTimeDesc = (a: { startedAt: string }, b: { startedAt: string }) =>
  new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime();

const inputStyle: React.CSSProperties = { padding: '4px 8px', borderRadius: 4, border: '1px solid #ccc' };

export function SessionsPage() {
  // Глобальный фильтр (правая панель): регион/перевозчик
  const { regionId: globalRegionId, carrierId: globalCarrierId } = useGlobalFilter();

  // Верхний фильтр
  const [terminalId, setTerminalId] = useState('');
  const [day, setDay] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [carrierId, setCarrierId] = useState('');
  const [routeId, setRouteId] = useState('');
  const [pathId, setPathId] = useState('');
  const [vehicleId, setVehicleId] = useState('');

  /** Дефолтное окно, если диапазон не задан: последние 3 месяца (квартал). */
  const quarterCutoffKey = useMemo(() => {
    const d = new Date();
    d.setMonth(d.getMonth() - 3);
    return d.toLocaleDateString('sv-SE');
  }, []);

  const { data: sessions, isLoading, error } = useQuery({
    queryKey: ['sessions', terminalId || undefined],
    queryFn: () => getSessions(terminalId || undefined),
  });

  const { data: terminals } = useQuery({ queryKey: ['terminals'], queryFn: () => getTerminals() });
  const { data: carriers } = useQuery({ queryKey: ['carriers'], queryFn: () => getCarriers() });
  const { data: routes } = useQuery({ queryKey: ['routes'], queryFn: () => getRoutes() });
  const { data: paths } = useQuery({ queryKey: ['paths'], queryFn: () => getPaths() });
  const { data: vehicles } = useQuery({ queryKey: ['vehicles'], queryFn: () => getVehicles() });
  const { data: benefits } = useQuery({ queryKey: ['benefits'], queryFn: () => getBenefits() });
  const { data: adminUsers } = useQuery({ queryKey: ['adminUsers'], queryFn: () => getAdminUsers() });

  const [expandedTripId, setExpandedTripId] = useState<string | null>(null);
  const { data: txs } = useQuery({
    queryKey: ['tx', expandedTripId],
    queryFn: () => getTransactions(expandedTripId!),
    enabled: !!expandedTripId,
  });

  // Справочники-мапы
  const terminalCarrier = useMemo(() => {
    const m = new Map<string, string>();
    (terminals || []).forEach((t) => t.carrierId && m.set(t.id, t.carrierId));
    return m;
  }, [terminals]);

  const carrierName = (id?: string | null) => {
    if (!id) return '—';
    return carriers?.find((c) => c.id === id)?.carrierName || short(id);
  };
  const sessionCarrierId = (s: Session) => terminalCarrier.get(s.terminalId || '') || null;
  const sessionRegionOk = (s: Session) =>
    !globalRegionId ||
    carriers?.find((c) => c.id === sessionCarrierId(s))?.regionId === globalRegionId;

  const routeLabel = (id?: string | null) => {
    if (!id) return '—';
    const r = routes?.find((x) => x.id === id);
    return r ? (r.routeName ? `${r.routeNumber} — ${r.routeName}` : r.routeNumber) : short(id);
  };
  const pathLabel = (id?: string | null) => {
    if (!id) return '—';
    const p = paths?.find((x) => x.id === id);
    if (!p) return short(id);
    const r = routes?.find((x) => x.id === p.routeId);
    return r ? `${r.routeNumber} / ${p.pathName}` : p.pathName;
  };
  const vehicleLabel = (id?: string | null) => {
    if (!id) return '—';
    const v = vehicles?.find((x) => x.id === id);
    if (!v) return short(id);
    return v.vehicleName ? `${v.vehicleNumber} ${v.vehicleName}` : v.vehicleNumber;
  };
  const benefitLabel = (id?: string | null) => {
    if (!id) return null;
    const b = benefits?.find((x) => x.id === id);
    return b ? b.benefitName : short(id);
  };
  /** Владелец карты: «Мария М» (firstName + lastNameInitial); fallback — короткий UUID. */
  const userLabel = (id?: string | null) => {
    if (!id) return '—';
    const u = adminUsers?.find((x) => x.id === id);
    if (!u) return short(id);
    const initial = u.lastNameInitial ? ` ${u.lastNameInitial}` : '';
    return `${u.firstName}${initial}`;
  };

  // Фильтрация + сортировка (свежие сверху)
  const { shifts, tripsByParent } = useMemo(() => {
    const all = sessions || [];
    const allTrips = all
      .filter((s) => s.sessionTypeId?.endsWith('603'))
      .sort(byTimeDesc);
    const allShifts = all
      .filter((s) => s.sessionTypeId?.endsWith('601'))
      .sort(byTimeDesc);

    const tripMatch = (t: Session) =>
      (!pathId || t.pathId === pathId) &&
      (!vehicleId || t.vehicleId === vehicleId) &&
      (!routeId || paths?.find((p) => p.id === t.pathId)?.routeId === routeId);

    const keepShift = (s: Session) => {
      const cid = sessionCarrierId(s);
      if (globalCarrierId && cid !== globalCarrierId) return false;
      if (carrierId && cid !== carrierId) return false;
      if (!sessionRegionOk(s)) return false;
      if (day) return dayKey(s.startedAt) === day;
      // Диапазон с—по; если оба пусты — окно по умолчанию: последние 3 месяца
      const from = dateFrom || (!dateTo ? quarterCutoffKey : '');
      const kd = dayKey(s.startedAt);
      if (from && kd < from) return false;
      if (dateTo && kd > dateTo) return false;
      return true;
    };

    const keptTrips = allTrips.filter(tripMatch);
    const byParent = new Map<string, Session[]>();
    keptTrips.forEach((t) => {
      if (!t.parentSessionId) return;
      const list = byParent.get(t.parentSessionId) || [];
      list.push(t);
      byParent.set(t.parentSessionId, list);
    });

    const keptShifts = allShifts.filter(keepShift).filter((s) => byParent.has(s.id));
    // Если фильтров рейса нет — показываем смены и без рейсов
    if (!pathId && !vehicleId && !routeId) {
      return {
        shifts: allShifts.filter(keepShift),
        tripsByParent: byParent,
      };
    }
    return { shifts: keptShifts, tripsByParent: byParent };
  }, [sessions, terminals, carriers, paths, globalRegionId, globalCarrierId, carrierId, day, dateFrom, dateTo, quarterCutoffKey, routeId, pathId, vehicleId]);

  const pathOptions = routeId ? (paths || []).filter((p) => p.routeId === routeId) : paths || [];

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  const resetFilters = () => {
    setTerminalId(''); setDay(''); setDateFrom(''); setDateTo(''); setCarrierId('');
    setRouteId(''); setPathId(''); setVehicleId('');
  };

  const isFiltered = !!(terminalId || day || dateFrom || dateTo || carrierId || routeId || pathId || vehicleId || globalCarrierId || globalRegionId);

  return (
    <div>
      <div className="page-header">
        <h1>Смены и рейсы</h1>
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, marginBottom: 16, alignItems: 'flex-end' }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span>День</span>
          <input type="date" value={day} onChange={(e) => { setDay(e.target.value); setDateFrom(''); setDateTo(''); }} style={inputStyle} />
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span title="Если пусто — последние 3 месяца">Период с</span>
          <input type="date" value={dateFrom} onChange={(e) => { setDateFrom(e.target.value); setDay(''); }} style={inputStyle} />
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span>по</span>
          <input type="date" value={dateTo} onChange={(e) => { setDateTo(e.target.value); setDay(''); }} style={inputStyle} />
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span>Перевозчик</span>
          <select value={carrierId} onChange={(e) => setCarrierId(e.target.value)} style={inputStyle}>
            <option value="">Все перевозчики</option>
            {(carriers || []).map((c) => (
              <option key={c.id} value={c.id}>{c.carrierName}</option>
            ))}
          </select>
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span>Маршрут</span>
          <select value={routeId} onChange={(e) => { setRouteId(e.target.value); setPathId(''); }} style={inputStyle}>
            <option value="">Все маршруты</option>
            {(routes || []).map((r) => (
              <option key={r.id} value={r.id}>{r.routeNumber}{r.routeName ? ` — ${r.routeName}` : ''}</option>
            ))}
          </select>
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span>Путь</span>
          <select value={pathId} onChange={(e) => setPathId(e.target.value)} style={inputStyle}>
            <option value="">Все пути</option>
            {pathOptions.map((p) => (
              <option key={p.id} value={p.id}>{p.pathName}</option>
            ))}
          </select>
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span>Транспортное средство</span>
          <select value={vehicleId} onChange={(e) => setVehicleId(e.target.value)} style={inputStyle}>
            <option value="">Все ТС</option>
            {(vehicles || []).map((v) => (
              <option key={v.id} value={v.id}>{v.vehicleNumber}{v.vehicleName ? ` ${v.vehicleName}` : ''}</option>
            ))}
          </select>
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span>Терминал</span>
          <input value={terminalId} onChange={(e) => setTerminalId(e.target.value)} placeholder="UUID терминала" style={{ ...inputStyle, minWidth: 200 }} />
        </label>
        {isFiltered && (
          <button onClick={resetFilters} style={{ padding: '6px 12px', borderRadius: 4, border: '1px solid #ccc', background: '#fff', cursor: 'pointer' }}>
            Сбросить
          </button>
        )}
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>Тип</th>
            <th>Статус</th>
            <th>Перевозчик</th>
            <th>Терминал</th>
            <th>Открыл</th>
            <th>ТС</th>
            <th>Маршрут</th>
            <th>Путь</th>
            <th>Открыта</th>
            <th>Закрыта</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {shifts.map((s) => {
            const childTrips = tripsByParent.get(s.id) || [];
            return (
              <Fragment key={s.id}>
                <tr className={statusClass(s.status)}>
                  <td>{typeLabel(s.sessionTypeId)}</td>
                  <td>{s.status === 'IN_PROGRESS' ? 'Активна' : 'Закрыта'}</td>
                  <td title={s.terminalId ?? undefined}>{carrierName(sessionCarrierId(s))}</td>
                  <td title={s.terminalId ?? undefined}>{short(s.terminalId)}</td>
                  <td title={s.openedByUserId ?? undefined}>{userLabel(s.openedByUserId)}</td>
                  <td>—</td>
                  <td>—</td>
                  <td>—</td>
                  <td>{fmt(s.startedAt)}</td>
                  <td>{fmt(s.closedAt)}</td>
                  <td></td>
                </tr>
                {childTrips.map((t) => (
                  <Fragment key={t.id}>
                    <tr className="child-row" style={{ background: '#f8f9fa' }}>
                      <td style={{ paddingLeft: 32 }}>— {typeLabel(t.sessionTypeId)}</td>
                      <td>{t.status === 'IN_PROGRESS' ? 'Активен' : 'Закрыт'}</td>
                      <td>{carrierName(sessionCarrierId(t))}</td>
                      <td title={t.terminalId ?? undefined}>{short(t.terminalId)}</td>
                      <td title={t.openedByUserId ?? undefined}>{userLabel(t.openedByUserId)}</td>
                      <td title={t.vehicleId ?? undefined}>{vehicleLabel(t.vehicleId)}</td>
                      <td>{routeLabel(paths?.find((p) => p.id === t.pathId)?.routeId)}</td>
                      <td title={t.pathId ?? undefined}>{pathLabel(t.pathId)}</td>
                      <td>{fmt(t.startedAt)}</td>
                      <td>{fmt(t.closedAt)}</td>
                      <td style={{ textAlign: 'center' }}>
                        <button
                          onClick={() => setExpandedTripId(expandedTripId === t.id ? null : t.id)}
                          style={{ cursor: 'pointer', fontSize: '1.1em', padding: '4px 10px', border: '1px solid #ccc', borderRadius: 4, background: '#fff' }}
                        >
                          {expandedTripId === t.id ? '▲' : '▼'}
                        </button>
                      </td>
                    </tr>
                    {expandedTripId === t.id && (
                      <tr className="child-row">
                        <td colSpan={11} style={{ padding: '12px 24px', background: '#f0f4f8' }}>
                          {!txs ? (
                            <span style={{ color: '#888' }}>Загрузка валидаций...</span>
                          ) : txs.length === 0 ? (
                            <span style={{ color: '#888' }}>Нет валидаций</span>
                          ) : (
                            <div>
                              <strong style={{ fontSize: '1.1em' }}>Валидации: {txs.length}</strong>
                              <table style={{ width: '100%', marginTop: 8, borderCollapse: 'collapse' }}>
                                <thead>
                                  <tr style={{ background: '#e2e8f0' }}>
                                    <th style={{ padding: '4px 8px', textAlign: 'left' }}>Карта</th>
                                    <th style={{ padding: '4px 8px', textAlign: 'left' }}>Пользователь</th>
                                    <th style={{ padding: '4px 8px', textAlign: 'left' }}>Поездки</th>
                                    <th style={{ padding: '4px 8px', textAlign: 'left' }}>Льгота</th>
                                    <th style={{ padding: '4px 8px', textAlign: 'left' }}>Время</th>
                                  </tr>
                                </thead>
                                <tbody>
                                  {[...txs].sort(byTimeDesc).map((tx) => {
                                    let tripsDebited: number | null = null;
                                    let benefitId: string | null = null;
                                    let declined = false;
                                    let writeFailed = false;
                                    try {
                                      const m = JSON.parse(tx.metadata || '{}');
                                      tripsDebited = typeof m.tripsDebited === 'number' ? m.tripsDebited : null;
                                      benefitId = m.benefitId || null;
                                      declined = !!m.declined;
                                      writeFailed = !!m.writeFailed;
                                    } catch {}
                                    return (
                                      <tr key={tx.transactionId} style={{ borderBottom: '1px solid #e2e8f0' }}>
                                        <td style={{ padding: '4px 8px' }} title={tx.cardId ?? undefined}>{short(tx.cardId)}</td>
                                        <td style={{ padding: '4px 8px' }} title={tx.userId ?? undefined}>{userLabel(tx.userId)}</td>
                                        <td style={{ padding: '4px 8px' }}>
                                          {declined
                                            ? writeFailed ? '⚠ отказ (ошибка записи)' : '⚠ отказ (нет поездок)'
                                            : tripsDebited === 1 ? 'списана 1'
                                            : benefitId ? 'по льготе (0)'
                                            : '—'}
                                        </td>
                                        <td style={{ padding: '4px 8px' }} title={benefitId ?? undefined}>
                                          {benefitId ? benefitLabel(benefitId) : '—'}
                                        </td>
                                        <td style={{ padding: '4px 8px' }}>{fmt(tx.startedAt)}</td>
                                      </tr>
                                    );
                                  })}
                                </tbody>
                              </table>
                            </div>
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
                {childTrips.length === 0 && (
                  <tr className="child-row">
                    <td colSpan={11} style={{ textAlign: 'center', color: '#888', paddingLeft: 32 }}>
                      Нет рейсов
                    </td>
                  </tr>
                )}
              </Fragment>
            );
          })}
          {shifts.length === 0 && (
            <tr><td colSpan={11} style={{ textAlign: 'center' }}>Смен нет</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
