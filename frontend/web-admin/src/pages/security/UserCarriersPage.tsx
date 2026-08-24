import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUserCarriers, createUserCarrier, deleteUserCarrier } from '../../api/routes';
import { getAdminUsers } from '../../api/routes';
import { getCarriers } from '../../api/reference';
import { useGlobalFilter } from '../../contexts/GlobalFilterContext';

export function UserCarriersPage() {
  const qc = useQueryClient();
  const { carrierId: globalCarrierId, regionId: globalRegionId } = useGlobalFilter();
  const { data, isLoading, error } = useQuery({ queryKey: ['user-carriers', globalCarrierId], queryFn: () => getUserCarriers({ carrierId: globalCarrierId || undefined }) });
  const { data: users } = useQuery({ queryKey: ['admin-users', globalRegionId, globalCarrierId], queryFn: () => getAdminUsers({ regionId: globalRegionId || undefined, carrierId: globalCarrierId || undefined }) });
  const { data: carriers } = useQuery({ queryKey: ['carriers', globalRegionId], queryFn: () => getCarriers(globalRegionId || undefined) });
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

  const userName = (id: string) => users?.find((u) => u.id === id);
  const carrierName = (id: string) => carriers?.find((c) => c.id === id)?.carrierName || id;

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Перевозчики пользователей</h1>
      </div>

      <div className="form-card" style={{ marginBottom: 20 }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
          <label>Пользователь
            <select value={userId} onChange={(e) => setUserId(e.target.value)}>
              <option value="">— выберите —</option>
              {users?.map((u) => (
                <option key={u.id} value={u.id}>{u.lastNameInitial}. {u.firstName}</option>
              ))}
            </select>
          </label>
          <label>Перевозчик
            <select value={carrierId} onChange={(e) => setCarrierId(e.target.value)}>
              <option value="">— выберите —</option>
              {carriers?.map((c) => (
                <option key={c.id} value={c.id}>{c.carrierName}</option>
              ))}
            </select>
          </label>
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
          <button className="btn-primary" onClick={() => createMut.mutate({ userId, carrierId })} disabled={!userId || !carrierId}>Назначить</button>
        </div>
        {formError && <div style={{ color: 'red', marginTop: 8 }}>{formError}</div>}
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>Пользователь</th>
            <th>Перевозчик</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => {
            const u = userName(r.userId);
            return (
              <tr key={`${r.userId}-${r.carrierId}`}>
                <td>{u ? `${u.lastNameInitial}. ${u.firstName}` : r.userId}</td>
                <td>{carrierName(r.carrierId)}</td>
                <td>
                  <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate({ userId: r.userId, carrierId: r.carrierId }); }}>✕</button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
