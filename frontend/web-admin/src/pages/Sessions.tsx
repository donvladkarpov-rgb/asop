import { useState, Fragment } from 'react';
import { useQuery } from '@tanstack/react-query';
import apiClient from '../api/client';
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

export function SessionsPage() {
  const [terminalId, setTerminalId] = useState('');
  const { data: sessions, isLoading, error } = useQuery({
    queryKey: ['sessions', terminalId || undefined],
    queryFn: () => getSessions(terminalId || undefined),
  });

  const [expandedTripId, setExpandedTripId] = useState<string | null>(null);
  const { data: txs } = useQuery({
    queryKey: ['tx', expandedTripId],
    queryFn: () => getTransactions(expandedTripId!),
    enabled: !!expandedTripId,
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  const shifts = sessions?.filter((s) => s.sessionTypeId?.endsWith('601')) ?? [];
  const trips = sessions?.filter((s) => s.sessionTypeId?.endsWith('603')) ?? [];

  return (
    <div>
      <div className="page-header">
        <h1>Смены и рейсы</h1>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          Фильтр по терминалу:
          <input value={terminalId} onChange={(e) => setTerminalId(e.target.value)} placeholder="UUID терминала" style={{ minWidth: 240 }} />
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
              <Fragment key={s.id}>
                <tr className={statusClass(s.status)}>
                  <td>{typeLabel(s.sessionTypeId)}</td>
                  <td>{s.status === 'IN_PROGRESS' ? 'Активна' : 'Закрыта'}</td>
                  <td title={s.id}>{short(s.id)}</td>
                  <td title={s.terminalId ?? undefined}>{short(s.terminalId)}</td>
                  <td title={s.openedByUserId ?? undefined}>{short(s.openedByUserId)}</td>
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
                      <td title={t.id}>{short(t.id)}</td>
                      <td title={t.terminalId ?? undefined}>{short(t.terminalId)}</td>
                      <td title={t.openedByUserId ?? undefined}>{short(t.openedByUserId)}</td>
                      <td title={t.vehicleId ?? undefined}>{short(t.vehicleId)}</td>
                      <td title={t.pathId ?? undefined}>{short(t.pathId)}</td>
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
                        <td colSpan={10} style={{ padding: '12px 24px', background: '#f0f4f8' }}>
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
                                    <th style={{ padding: '4px 8px', textAlign: 'left' }}>ID</th>
                                    <th style={{ padding: '4px 8px', textAlign: 'left' }}>Карта</th>
                                    <th style={{ padding: '4px 8px', textAlign: 'left' }}>Пользователь</th>
                                    <th style={{ padding: '4px 8px', textAlign: 'left' }}>Поездки</th>
                                    <th style={{ padding: '4px 8px', textAlign: 'left' }}>Льгота</th>
                                    <th style={{ padding: '4px 8px', textAlign: 'left' }}>Время</th>
                                  </tr>
                                </thead>
                                <tbody>
                                  {txs.map((tx) => {
                                    let tripsDebited: number | null = null;
                                    let benefitId: string | null = null;
                                    let declined = false;
                                    try {
                                      const m = JSON.parse(tx.metadata || '{}');
                                      tripsDebited = typeof m.tripsDebited === 'number' ? m.tripsDebited : null;
                                      benefitId = m.benefitId || null;
                                      declined = !!m.declined;
                                    } catch {}
                                    return (
                                      <tr key={tx.transactionId} style={{ borderBottom: '1px solid #e2e8f0' }}>
                                        <td style={{ padding: '4px 8px' }}>{short(tx.transactionId)}</td>
                                        <td style={{ padding: '4px 8px' }}>{short(tx.cardId)}</td>
                                        <td style={{ padding: '4px 8px' }} title={tx.userId ?? undefined}>{short(tx.userId)}</td>
                                        <td style={{ padding: '4px 8px' }}>
                                          {declined ? '⚠ отказ (0)' : tripsDebited === null ? '—' : tripsDebited}
                                        </td>
                                        <td style={{ padding: '4px 8px' }} title={benefitId ?? undefined}>
                                          {benefitId ? short(benefitId) : '—'}
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
                    <td colSpan={10} style={{ textAlign: 'center', color: '#888', paddingLeft: 32 }}>
                      Нет рейсов
                    </td>
                  </tr>
                )}
              </Fragment>
            );
          })}
          {shifts.length === 0 && (
            <tr><td colSpan={10} style={{ textAlign: 'center' }}>Смен нет</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
