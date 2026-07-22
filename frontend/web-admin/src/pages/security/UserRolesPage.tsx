import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUserRoles, createUserRole, deleteUserRole } from '../../api/routes';

export function UserRolesPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['user-roles'], queryFn: () => getUserRoles() });
  const [formError, setFormError] = useState<string | null>(null);
  const [userId, setUserId] = useState('');
  const [roleId, setRoleId] = useState('');

  const createMut = useMutation({
    mutationFn: (d: { userId: string; roleId: string }) => createUserRole(d),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['user-roles'] }); setUserId(''); setRoleId(''); setFormError(null); },
    onError: (e) => setFormError(JSON.stringify((e as { response?: { data?: unknown } })?.response?.data ?? (e as Error).message)),
  });
  const deleteMut = useMutation({
    mutationFn: ({ userId, roleId }: { userId: string; roleId: string }) => deleteUserRole(userId, roleId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['user-roles'] }),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Роли пользователей</h1>
      </div>

      <div className="form-card" style={{ marginBottom: 20 }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
          <label>User ID <input value={userId} onChange={(e) => setUserId(e.target.value)} /></label>
          <label>Role ID <input value={roleId} onChange={(e) => setRoleId(e.target.value)} /></label>
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
          <button className="btn-primary" onClick={() => createMut.mutate({ userId, roleId })}>Назначить</button>
        </div>
        {formError && <div style={{ color: 'red', marginTop: 8 }}>{formError}</div>}
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>User ID</th>
            <th>Пользователь</th>
            <th>Role ID</th>
            <th>Роль</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={`${r.userId}-${r.roleId}`}>
              <td style={{ fontSize: '0.85em', opacity: 0.7 }}>{r.userId}</td>
              <td>{r.lastNameInitial ? `${r.lastNameInitial}. ${r.firstName}` : ''}</td>
              <td style={{ fontSize: '0.85em', opacity: 0.7 }}>{r.roleId}</td>
              <td>{r.roleName || ''}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate({ userId: r.userId, roleId: r.roleId }); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
