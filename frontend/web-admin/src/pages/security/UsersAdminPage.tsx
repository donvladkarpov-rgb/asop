import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getAdminUsers, createAdminUser, updateAdminUser, deleteAdminUser } from '../../api/routes';
import type { AdminUser, AdminUserCreate } from '../../api/routes';

export function UsersAdminPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['admin-users'], queryFn: getAdminUsers });
  const [edit, setEdit] = useState<Partial<AdminUser> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const createMut = useMutation({
    mutationFn: (d: AdminUserCreate) => createAdminUser(d),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['admin-users'] }); setShowForm(false); setFormError(null); },
    onError: (e) => setFormError(JSON.stringify((e as { response?: { data?: unknown } })?.response?.data ?? (e as Error).message)),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: AdminUserCreate }) => updateAdminUser(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['admin-users'] }); setEdit(null); },
  });
  const deleteMut = useMutation({
    mutationFn: deleteAdminUser,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-users'] }),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Пользователи</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <>
          <UserForm
            initial={edit}
            onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d as AdminUserCreate }) : createMut.mutate(d as AdminUserCreate)}
            onCancel={() => { setShowForm(false); setEdit(null); setFormError(null); }}
          />
          {formError && <div style={{ color: 'red', marginTop: 8 }}>{formError}</div>}
        </>
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Фамилия (инициал)</th>
            <th>Имя</th>
            <th>Отчество (инициал)</th>
            <th>Телефон</th>
            <th>Keycloak ID</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={r.id}>
              <td>{r.lastNameInitial}.</td>
              <td>{r.firstName}</td>
              <td>{r.patronymicInitial ? r.patronymicInitial + '.' : ''}</td>
              <td>{r.phone || ''}</td>
              <td style={{ fontSize: '0.85em', opacity: 0.7 }}>{r.keycloakId || ''}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => setEdit(r)}>✎</button>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(r.id); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function UserForm({ initial, onSave, onCancel }: { initial?: Partial<AdminUser> | null; onSave: (d: Partial<AdminUser>) => void; onCancel: () => void }) {
  const [form, setForm] = useState<Partial<AdminUser>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Имя <input value={form.firstName || ''} onChange={(e) => setForm({ ...form, firstName: e.target.value })} /></label>
        <label>Инициал фамилии <input value={form.lastNameInitial || ''} maxLength={1} onChange={(e) => setForm({ ...form, lastNameInitial: e.target.value })} /></label>
        <label>Инициал отчества <input value={form.patronymicInitial || ''} maxLength={1} onChange={(e) => setForm({ ...form, patronymicInitial: e.target.value })} /></label>
        <label>Телефон <input value={form.phone || ''} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
