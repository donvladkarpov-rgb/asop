import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getRoles, createRole, updateRole, deleteRole } from '../api/reference';
import type { Role } from '../types/reference';

export function RolesPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['roles'], queryFn: getRoles });
  const [edit, setEdit] = useState<Partial<Role> | null>(null);
  const [showForm, setShowForm] = useState(false);

  const createMut = useMutation({ mutationFn: createRole, onSuccess: () => { qc.invalidateQueries({ queryKey: ['roles'] }); setShowForm(false); } });
  const updateMut = useMutation({ mutationFn: ({ id, data }: { id: string; data: Partial<Role> }) => updateRole(id, data), onSuccess: () => { qc.invalidateQueries({ queryKey: ['roles'] }); setEdit(null); } });
  const deleteMut = useMutation({ mutationFn: deleteRole, onSuccess: () => qc.invalidateQueries({ queryKey: ['roles'] }) });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Роли</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <RoleForm
          initial={edit}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Название роли</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={r.id}>
              <td>{r.roleName}</td>
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

function RoleForm({ initial, onSave, onCancel }: { initial?: Partial<Role> | null; onSave: (d: Partial<Role>) => void; onCancel: () => void }) {
  const [form, setForm] = useState<Partial<Role>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <label>Название роли
        <input value={form.roleName || ''} onChange={(e) => setForm({ ...form, roleName: e.target.value })} />
      </label>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
