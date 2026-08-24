import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUserKrs, createUserKrs, deleteUserKrs, getAdminUsers, getAuditServices } from '../../api/routes';

export function UserKrsPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['user-krs'], queryFn: () => getUserKrs() });
  const { data: users } = useQuery({ queryKey: ['admin-users', '', ''], queryFn: () => getAdminUsers() });
  const { data: auditServices } = useQuery({ queryKey: ['audit-services'], queryFn: getAuditServices });
  const [formError, setFormError] = useState<string | null>(null);
  const [userId, setUserId] = useState('');
  const [krsId, setKrsId] = useState('');

  const createMut = useMutation({
    mutationFn: (d: { userId: string; auditServiceId: string }) => createUserKrs(d),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['user-krs'] }); setUserId(''); setKrsId(''); setFormError(null); },
    onError: (e) => setFormError(JSON.stringify((e as { response?: { data?: unknown } })?.response?.data ?? (e as Error).message)),
  });
  const deleteMut = useMutation({
    mutationFn: ({ userId, auditServiceId }: { userId: string; auditServiceId: string }) => deleteUserKrs(userId, auditServiceId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['user-krs'] }),
  });

  const userName = (id: string) => {
    const u = users?.find((x) => x.id === id);
    return u ? `${u.lastNameInitial}. ${u.firstName}` : id;
  };
  const krsName = (id: string) => auditServices?.find((s) => s.auditServiceId === id)?.serviceName || id;

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>КРС пользователей</h1>
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
          <label>КРС
            <select value={krsId} onChange={(e) => setKrsId(e.target.value)}>
              <option value="">— выберите —</option>
              {(auditServices || []).filter((s) => s.isActive !== false).map((s) => (
                <option key={s.auditServiceId} value={s.auditServiceId}>{s.serviceName}</option>
              ))}
            </select>
          </label>
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
          <button className="btn-primary" onClick={() => createMut.mutate({ userId, auditServiceId: krsId })} disabled={!userId || !krsId}>Назначить</button>
        </div>
        {formError && <div style={{ color: 'red', marginTop: 8 }}>{formError}</div>}
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>Пользователь</th>
            <th>КРС</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={`${r.userId}-${r.auditServiceId}`}>
              <td>{userName(r.userId)}</td>
              <td>{r.serviceName || krsName(r.auditServiceId)}</td>
              <td>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate({ userId: r.userId, auditServiceId: r.auditServiceId }); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
