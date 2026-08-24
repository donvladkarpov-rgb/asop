import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUserRegions, createUserRegion, deleteUserRegion } from '../../api/routes';
import { getAdminUsers } from '../../api/routes';
import { getRegions } from '../../api/reference';
import { useGlobalFilter } from '../../contexts/GlobalFilterContext';

export function UserRegionsPage() {
  const qc = useQueryClient();
  const { regionId: globalRegionId, carrierId: globalCarrierId } = useGlobalFilter();
  const { data, isLoading, error } = useQuery({ queryKey: ['user-regions', globalRegionId], queryFn: () => getUserRegions({ regionId: globalRegionId || undefined }) });
  const { data: users } = useQuery({ queryKey: ['admin-users', globalRegionId, globalCarrierId], queryFn: () => getAdminUsers({ regionId: globalRegionId || undefined, carrierId: globalCarrierId || undefined }) });
  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const [formError, setFormError] = useState<string | null>(null);
  const [userId, setUserId] = useState('');
  const [regionId, setRegionId] = useState('');

  const createMut = useMutation({
    mutationFn: (d: { userId: string; regionId: string }) => createUserRegion(d),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['user-regions'] }); setUserId(''); setRegionId(''); setFormError(null); },
    onError: (e) => setFormError(JSON.stringify((e as { response?: { data?: unknown } })?.response?.data ?? (e as Error).message)),
  });
  const deleteMut = useMutation({
    mutationFn: ({ userId, regionId }: { userId: string; regionId: string }) => deleteUserRegion(userId, regionId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['user-regions'] }),
  });

  const userName = (id: string) => users?.find((u) => u.id === id);
  const regionName = (id: string) => regions?.find((r) => r.id === id)?.municipalDivision || id;

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Регионы пользователей</h1>
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
          <label>Регион
            <select value={regionId} onChange={(e) => setRegionId(e.target.value)}>
              <option value="">— выберите —</option>
              {regions?.map((r) => (
                <option key={r.id} value={r.id}>{r.municipalDivision}</option>
              ))}
            </select>
          </label>
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
          <button className="btn-primary" onClick={() => createMut.mutate({ userId, regionId })} disabled={!userId || !regionId}>Назначить</button>
        </div>
        {formError && <div style={{ color: 'red', marginTop: 8 }}>{formError}</div>}
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>Пользователь</th>
            <th>Регион</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => {
            const u = userName(r.userId);
            return (
              <tr key={`${r.userId}-${r.regionId}`}>
                <td>{u ? `${u.lastNameInitial}. ${u.firstName}` : r.userId}</td>
                <td>{regionName(r.regionId)}</td>
                <td>
                  <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate({ userId: r.userId, regionId: r.regionId }); }}>✕</button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
