import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getPayments, type BankPayment } from '../api/payments';

const STATUSES = ['PENDING', 'AUTHORIZED', 'DECLINED', 'FAILED', 'REVERSED', 'REFUNDED'];
const TYPES = ['TOPUP', 'FARE', 'DEBT_RECOVERY'];

function statusClass(s: string): string {
  switch (s) {
    case 'AUTHORIZED': return 'status-green';
    case 'PENDING': return 'status-orange';
    case 'REVERSED': case 'REFUNDED': return 'status-gray';
    default: return 'status-red';
  }
}

export function PaymentsPage() {
  const [status, setStatus] = useState('');
  const [paymentType, setPaymentType] = useState('');
  const [includeDeleted, setIncludeDeleted] = useState(false);

  const { data, isLoading, error } = useQuery({
    queryKey: ['payments', status, paymentType, includeDeleted],
    queryFn: () =>
      getPayments({
        status: status || undefined,
        paymentType: paymentType || undefined,
        includeDeleted: includeDeleted || undefined,
        limit: 200,
      }),
    refetchInterval: 15_000,
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  const fmt = (iso?: string | null) => (iso ? new Date(iso).toLocaleString() : '—');

  return (
    <div>
      <div className="page-header">
        <h1>Банковские платежи</h1>
      </div>

      <div style={{ display: 'flex', gap: 8, marginBottom: 12, alignItems: 'center' }}>
        <select value={status} onChange={(e) => setStatus(e.target.value)} style={{ padding: '6px 10px', borderRadius: 6, border: '1px solid #d1d5db' }}>
          <option value="">Все статусы</option>
          {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <select value={paymentType} onChange={(e) => setPaymentType(e.target.value)} style={{ padding: '6px 10px', borderRadius: 6, border: '1px solid #d1d5db' }}>
          <option value="">Все типы</option>
          {TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
        <label style={{ fontSize: 13, userSelect: 'none' }}>
          <input type="checkbox" checked={includeDeleted} onChange={(e) => setIncludeDeleted(e.target.checked)} style={{ marginRight: 4 }} />
          Показывать удалённые
        </label>
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>Время</th>
            <th>Сумма</th>
            <th>Тип</th>
            <th>Статус</th>
            <th>Эквайер</th>
            <th>PAN **</th>
            <th>RRN</th>
            <th>Ref</th>
            <th>Terminal</th>
            <th>Ошибка</th>
          </tr>
        </thead>
        <tbody>
          {(data?.length || 0) === 0 && (
            <tr><td colSpan={10} style={{ textAlign: 'center', color: '#9ca3af' }}>Платежей нет</td></tr>
          )}
          {data?.map((p: BankPayment) => (
            <tr key={p.paymentId} style={p.deletedAt ? { opacity: 0.5 } : undefined}>
              <td>{fmt(p.occurredAt || p.createdAt)}</td>
              <td>{Number(p.amount).toFixed(2)} {p.currency}</td>
              <td>{p.paymentType}</td>
              <td><span className={`status-badge ${statusClass(p.status)}`}>{p.status}</span></td>
              <td>{p.provider}</td>
              <td>**{p.panLast4 || '—'}</td>
              <td>{p.rrn || '—'}</td>
              <td>{p.acquirerReference ? <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{p.acquirerReference}</span> : '—'}</td>
              <td>{p.terminalId ? <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{p.terminalId.substring(0, 8)}</span> : '—'}</td>
              <td style={{ color: p.errorMessage ? '#dc2626' : undefined }}>
                {p.errorMessage || (p.errorCode ? p.errorCode : '—')}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}