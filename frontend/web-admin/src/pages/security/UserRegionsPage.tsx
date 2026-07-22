import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUserRegions, createUserRegion, deleteUserRegion } from '../../api/routes';

export function UserRegionsPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['user-regions'], queryFn: () => getUserRegions() });
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

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Регионы пользователей</h1>
      </div>

      <div className="form-card" style={{ marginBottom: 20 }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
          <label>User ID <input value={userId} onChange={(e) => setUserId(e.target.value)} /></label>
          <label>Region ID <input value={regionId} onChange={(e) => setRegionId(e.target.value)} /></label>
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
          <button className="btn-primary" onClick={() => createMut.mutate({ userId, regionId })}>Назначить</button>
        </div>
        {formError && <div style={{ color: 'red', marginTop: 8 }}>{formError}</div>}
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>User ID</th>
            <th>Пользователь</th>
            <th>Region ID</th>
            <th>Регион</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={`${r.userId}-${r.regionId}`}>
              <td style={{ fontSize: '0.85em', opacity: 0.7 }}>{r.userId}</td>
              <td>{r.lastNameInitial ? `${r.lastNameInitial}. ${r.firstName}` : ''}</td>
              <td style={{ fontSize: '0.85em', opacity: 0.7 }}>{r.regionId}</td>
              <td>{r.regionName || ''}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate({ userId: r.userId, regionId: r.regionId }); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
