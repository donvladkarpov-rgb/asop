import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getSessionTypes, createSessionType, updateSessionType, deleteSessionType } from '../api/reference';
import type { SessionType } from '../types/reference';

export function SessionTypesPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['session-types'], queryFn: getSessionTypes });
  const [edit, setEdit] = useState<Partial<SessionType> | null>(null);
  const [showForm, setShowForm] = useState(false);

  const createMut = useMutation({ mutationFn: createSessionType, onSuccess: () => { qc.invalidateQueries({ queryKey: ['session-types'] }); setShowForm(false); } });
  const updateMut = useMutation({ mutationFn: ({ id, data }: { id: string; data: Partial<SessionType> }) => updateSessionType(id, data), onSuccess: () => { qc.invalidateQueries({ queryKey: ['session-types'] }); setEdit(null); } });
  const deleteMut = useMutation({ mutationFn: deleteSessionType, onSuccess: () => qc.invalidateQueries({ queryKey: ['session-types'] }) });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Типы смен</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <SessionTypeForm
          initial={edit}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Код типа смены</th>
            <th>Название типа смены</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={r.id}>
              <td>{r.sessionTypeCode}</td>
              <td>{r.sessionTypeName}</td>
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

function SessionTypeForm({ initial, onSave, onCancel }: { initial?: Partial<SessionType> | null; onSave: (d: Partial<SessionType>) => void; onCancel: () => void }) {
  const [form, setForm] = useState<Partial<SessionType>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Код типа смены <input value={form.sessionTypeCode || ''} onChange={(e) => setForm({ ...form, sessionTypeCode: e.target.value })} /></label>
        <label>Название типа смены <input value={form.sessionTypeName || ''} onChange={(e) => setForm({ ...form, sessionTypeName: e.target.value })} /></label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
