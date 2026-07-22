import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUserCarriers, createUserCarrier, deleteUserCarrier } from '../../api/routes';

export function UserCarriersPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['user-carriers'], queryFn: () => getUserCarriers() });
  const [formError, setFormError] = useState<string | null>(null);
  const [userId, setUserId] = useState('');
  const [carrierId, setCarrierId] = useState('');

  const createMut = useMutation({
    mutationFn: (d: { userId: string; carrierId: string }) => createUserCarrier(d),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['user-carriers'] }); setUserId(''); setCarrierId(''); setFormError(null); },
    onError: (e) => setFormError(JSON.stringify((e as { response?: { data?: unknown } })?.response?.data ?? (e as Error).message)),
  });
  const deleteMut = useMutation({
    mutationFn: ({ userId, carrierId }: { userId: string; carrierId: string }) => deleteUserCarrier(userId, carrierId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['user-carriers'] }),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Перевозчики пользователей</h1>
      </div>

      <div className="form-card" style={{ marginBottom: 20 }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
          <label>User ID <input value={userId} onChange={(e) => setUserId(e.target.value)} /></label>
          <label>Carrier ID <input value={carrierId} onChange={(e) => setCarrierId(e.target.value)} /></label>
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
          <button className="btn-primary" onClick={() => createMut.mutate({ userId, carrierId })}>Назначить</button>
        </div>
        {formError && <div style={{ color: 'red', marginTop: 8 }}>{formError}</div>}
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>User ID</th>
            <th>Пользователь</th>
            <th>Carrier ID</th>
            <th>Перевозчик</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={`${r.userId}-${r.carrierId}`}>
              <td style={{ fontSize: '0.85em', opacity: 0.7 }}>{r.userId}</td>
              <td>{r.lastNameInitial ? `${r.lastNameInitial}. ${r.firstName}` : ''}</td>
              <td style={{ fontSize: '0.85em', opacity: 0.7 }}>{r.carrierId}</td>
              <td>{r.carrierName || ''}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate({ userId: r.userId, carrierId: r.carrierId }); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
