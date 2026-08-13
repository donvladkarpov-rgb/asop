import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import apiClient from '../api/client';

interface SessionRow {
  id: string;
  sessionTypeId?: string | null;
  parentSessionId?: string | null;
  terminalId?: string | null;
  pathId?: string | null;
  vehicleId?: string | null;
  status?: string;
  startedAt?: string | null;
  closedAt?: string | null;
}

const getSessions = (terminalId?: string) =>
  apiClient
    .get<SessionRow[]>('/sessions', { params: terminalId ? { terminalId } : {} })
    .then((r) => r.data);

const fmt = (ts?: string | null) =>
  ts ? new Date(ts).toLocaleString('ru-RU') : '—';

const short = (id?: string | null) => (id ? `${id.slice(0, 8)}…` : '—');

export function SessionsPage() {
  const [terminalId, setTerminalId] = useState('');
  const { data, isLoading, error } = useQuery({
    queryKey: ['sessions', terminalId || undefined],
    queryFn: () => getSessions(terminalId || undefined),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Смены</h1>
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
            <th>Статус</th>
            <th>Смена</th>
            <th>Родитель</th>
            <th>Терминал</th>
            <th>Маршрут</th>
            <th>ТС</th>
            <th>Открыта</th>
            <th>Закрыта</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((s) => (
            <tr key={s.id}>
              <td>{s.status ?? '—'}</td>
              <td>{short(s.id)}</td>
              <td>{short(s.parentSessionId)}</td>
              <td>{short(s.terminalId)}</td>
              <td>{short(s.pathId)}</td>
              <td>{short(s.vehicleId)}</td>
              <td>{fmt(s.startedAt)}</td>
              <td>{fmt(s.closedAt)}</td>
            </tr>
          ))}
          {data?.length === 0 && (
            <tr>
              <td colSpan={8} style={{ textAlign: 'center' }}>
                Смен нет
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
