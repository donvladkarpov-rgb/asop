import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUserDistributors, createUserDistributor, deleteUserDistributor, getAdminUsers } from '../../api/routes';
import { getCardsDistributors } from '../../api/cardsDistributors';
import { useGlobalFilter } from '../../contexts/GlobalFilterContext';

export function UserDistributorsPage() {
  const qc = useQueryClient();
  const { cardsDistributorId: globalDistributorId } = useGlobalFilter();
  const { data, isLoading, error } = useQuery({ queryKey: ['user-distributors', globalDistributorId], queryFn: () => getUserDistributors({ cardsDistributorId: globalDistributorId || undefined }) });
  const { data: users } = useQuery({ queryKey: ['admin-users', '', '', globalDistributorId], queryFn: () => getAdminUsers({ cardsDistributorId: globalDistributorId || undefined }) });
  const { data: distributors } = useQuery({ queryKey: ['cards-distributors'], queryFn: getCardsDistributors });
  const [formError, setFormError] = useState<string | null>(null);
  const [userId, setUserId] = useState('');
  const [distributorId, setDistributorId] = useState('');

  const createMut = useMutation({
    mutationFn: (d: { userId: string; cardsDistributorId: string }) => createUserDistributor(d),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['user-distributors'] }); setUserId(''); setDistributorId(''); setFormError(null); },
    onError: (e) => setFormError(JSON.stringify((e as { response?: { data?: unknown } })?.response?.data ?? (e as Error).message)),
  });
  const deleteMut = useMutation({
    mutationFn: ({ userId, cardsDistributorId }: { userId: string; cardsDistributorId: string }) => deleteUserDistributor(userId, cardsDistributorId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['user-distributors'] }),
  });

  const userName = (id: string) => {
    const u = users?.find((x) => x.id === id);
    return u ? `${u.lastNameInitial}. ${u.firstName}` : id;
  };
  const distributorName = (id: string) => distributors?.find((d) => d.id === id)?.distributorName || id;

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Дистрибьюторы пользователей</h1>
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
          <label>Дистрибьютор карт
            <select value={distributorId} onChange={(e) => setDistributorId(e.target.value)}>
              <option value="">— выберите —</option>
              {(distributors || [])
                .filter((d) => !globalDistributorId || d.id === globalDistributorId)
                .map((d) => (
                  <option key={d.id} value={d.id}>{d.distributorName}</option>
                ))}
            </select>
          </label>
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
          <button className="btn-primary" onClick={() => createMut.mutate({ userId, cardsDistributorId: distributorId })} disabled={!userId || !distributorId}>Назначить</button>
        </div>
        {formError && <div style={{ color: 'red', marginTop: 8 }}>{formError}</div>}
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>Пользователь</th>
            <th>Дистрибьютор карт</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={`${r.userId}-${r.cardsDistributorId}`}>
              <td>{userName(r.userId)}</td>
              <td>{r.distributorName || distributorName(r.cardsDistributorId)}</td>
              <td>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate({ userId: r.userId, cardsDistributorId: r.cardsDistributorId }); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
