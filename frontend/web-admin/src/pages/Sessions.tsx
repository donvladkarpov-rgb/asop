import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import apiClient from '../api/client';
import type { Session } from '../types';

const SESSION_TYPE_IDS: Record<string, string> = {
  '00000000-0000-0000-0000-000000000601': 'Смена',
  '00000000-0000-0000-0000-000000000602': 'Перерыв',
  '00000000-0000-0000-0000-000000000603': 'Рейс',
};

const getSessions = (terminalId?: string) =>
  apiClient
    .get<Session[]>('/sessions', { params: terminalId ? { terminalId } : {} })
    .then((r) => r.data);

const fmt = (ts?: string | null) =>
  ts ? new Date(ts).toLocaleString('ru-RU') : '—';

const short = (id?: string | null) => (id ? `${id.slice(0, 8)}…` : '—');

const typeLabel = (id?: string) =>
  id ? SESSION_TYPE_IDS[id] || short(id) : '—';

const statusClass = (status: string) =>
  status === 'IN_PROGRESS' ? 'status-ok' : status === 'CLOSED' ? 'status-dim' : '';

export function SessionsPage() {
  const [terminalId, setTerminalId] = useState('');
  const { data, isLoading, error } = useQuery({
    queryKey: ['sessions', terminalId || undefined],
    queryFn: () => getSessions(terminalId || undefined),
  });

  const [expandedId, setExpandedId] = useState<string | null>(null);

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  const shifts = data?.filter((s) => s.sessionTypeId?.endsWith('601')) ?? [];
  const trips = data?.filter((s) => s.sessionTypeId?.endsWith('603')) ?? [];

  return (
    <div>
      <div className="page-header">
        <h1>Смены и рейсы</h1>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          Фильтр по терминалу:
          <input
            value={terminalId}
            onChange={(e) => setTerminalId(e.target.value)}
            placeholder="UUID терминала"
            style={{ minWidth: 240 }}
          />
        </label>
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>Тип</th>
            <th>Статус</th>
            <th>ID</th>
            <th>Терминал</th>
            <th>Открыл</th>
            <th>ТС</th>
            <th>Путь</th>
            <th>Открыта</th>
            <th>Закрыта</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {shifts.map((s) => {
            const childTrips = trips.filter((t) => t.parentSessionId === s.id);
            return (
              <>
                <tr key={s.id} className={statusClass(s.status)}>
                  <td>{typeLabel(s.sessionTypeId)}</td>
                  <td>{s.status === 'IN_PROGRESS' ? 'Активна' : 'Закрыта'}</td>
                  <td title={s.id}>{short(s.id)}</td>
                  <td title={s.terminalId ?? undefined}>{short(s.terminalId)}</td>
                  <td title={s.openedByUserId ?? undefined}>{short(s.openedByUserId)}</td>
                  <td>—</td>
                  <td>—</td>
                  <td>{fmt(s.startedAt)}</td>
                  <td>{fmt(s.closedAt)}</td>
                  <td>
                    {childTrips.length > 0 && (
                      <button onClick={() => setExpandedId(expandedId === s.id ? null : s.id)}>
                        {expandedId === s.id ? '▲' : `▼ ${childTrips.length}`}
                      </button>
                    )}
                  </td>
                </tr>
                {expandedId === s.id &&
                  childTrips.map((t) => (
                    <tr key={t.id} className="child-row" style={{ background: '#f8f9fa' }}>
                      <td>— {typeLabel(t.sessionTypeId)}</td>
                      <td>{t.status === 'IN_PROGRESS' ? 'Активен' : 'Закрыт'}</td>
                      <td title={t.id}>{short(t.id)}</td>
                      <td title={t.terminalId ?? undefined}>{short(t.terminalId)}</td>
                      <td title={t.openedByUserId ?? undefined}>{short(t.openedByUserId)}</td>
                      <td title={t.vehicleId ?? undefined}>{short(t.vehicleId)}</td>
                      <td title={t.pathId ?? undefined}>{short(t.pathId)}</td>
                      <td>{fmt(t.startedAt)}</td>
                      <td>{fmt(t.closedAt)}</td>
                      <td></td>
                    </tr>
                  ))}
                {expandedId === s.id && childTrips.length === 0 && (
                  <tr key={`${s.id}-notrips`} className="child-row">
                    <td colSpan={10} style={{ textAlign: 'center', color: '#888' }}>
                      Нет рейсов в этой смене
                    </td>
                  </tr>
                )}
              </>
            );
          })}
          {shifts.length === 0 && (
            <tr>
              <td colSpan={10} style={{ textAlign: 'center' }}>
                Смен нет
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
