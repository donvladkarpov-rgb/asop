import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUserRoles, createUserRole, deleteUserRole, getAdminUsers } from '../../api/routes';
import { getRoles } from '../../api/reference';
import { useGlobalFilter } from '../../contexts/GlobalFilterContext';

export function UserRolesPage() {
  const qc = useQueryClient();
  const { regionId: globalRegionId } = useGlobalFilter();
  const { data, isLoading, error } = useQuery({ queryKey: ['user-roles', globalRegionId], queryFn: () => getUserRoles({ regionId: globalRegionId || undefined }) });
  const { data: users } = useQuery({
    queryKey: ['admin-users', globalRegionId, ''],
    queryFn: () => getAdminUsers({ regionId: globalRegionId || undefined }),
  });
  const { data: roles } = useQuery({ queryKey: ['roles'], queryFn: getRoles });
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

  const userLabel = (id: string) => {
    const u = users?.find((x) => x.id === id);
    return u ? (u.lastNameInitial ? `${u.lastNameInitial}. ${u.firstName}` : u.firstName) : id;
  };

  return (
    <div>
      <div className="page-header">
        <h1>Роли пользователей</h1>
      </div>

      <div className="form-card" style={{ marginBottom: 20 }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
          <label>Пользователь
            <select value={userId} onChange={(e) => setUserId(e.target.value)}>
              <option value="">— выберите —</option>
              {users?.map((u) => (
                <option key={u.id} value={u.id}>{u.lastNameInitial ? `${u.lastNameInitial}. ${u.firstName}` : u.firstName}</option>
              ))}
            </select>
          </label>
          <label>Роль
            <select value={roleId} onChange={(e) => setRoleId(e.target.value)}>
              <option value="">— выберите —</option>
              {roles?.map((r) => (
                <option key={r.id} value={r.id}>{r.roleName}</option>
              ))}
            </select>
          </label>
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
          <button className="btn-primary" onClick={() => createMut.mutate({ userId, roleId })} disabled={!userId || !roleId}>Назначить</button>
        </div>
        {formError && <div style={{ color: 'red', marginTop: 8 }}>{formError}</div>}
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>Пользователь</th>
            <th>Роль</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r, i) => (
            <tr key={`${r.userId}-${r.roleId ?? 'none'}-${i}`}>
              <td>{userLabel(r.userId)}</td>
              <td>{r.roleName || '— без роли'}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                {r.roleId && (
                  <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate({ userId: r.userId, roleId: r.roleId! }); }}>✕</button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
