import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getEventTypes, createEventType, updateEventType, deleteEventType } from '../api/reference';
import type { EventType } from '../types/reference';

export function EventTypesPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['event-types'], queryFn: getEventTypes });
  const [edit, setEdit] = useState<Partial<EventType> | null>(null);
  const [showForm, setShowForm] = useState(false);

  const createMut = useMutation({ mutationFn: createEventType, onSuccess: () => { qc.invalidateQueries({ queryKey: ['event-types'] }); setShowForm(false); } });
  const updateMut = useMutation({ mutationFn: ({ id, data }: { id: string; data: Partial<EventType> }) => updateEventType(id, data), onSuccess: () => { qc.invalidateQueries({ queryKey: ['event-types'] }); setEdit(null); } });
  const deleteMut = useMutation({ mutationFn: deleteEventType, onSuccess: () => qc.invalidateQueries({ queryKey: ['event-types'] }) });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Типы событий</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <EventTypeForm
          initial={edit}
          onSave={(d) => edit?.eventType ? updateMut.mutate({ id: edit.eventType!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Код события</th>
            <th>Название</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={r.eventType}>
              <td><code>{r.eventType}</code></td>
              <td>{r.eventTypeName}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => setEdit(r)}>✎</button>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(r.eventType); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function EventTypeForm({ initial, onSave, onCancel }: { initial?: Partial<EventType> | null; onSave: (d: Partial<EventType>) => void; onCancel: () => void }) {
  const [form, setForm] = useState<Partial<EventType>>(initial || {});
  const isEdit = !!initial?.eventType;
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Код события
          <input value={form.eventType || ''} onChange={(e) => setForm({ ...form, eventType: e.target.value })} disabled={isEdit} />
        </label>
        <label>Название <input value={form.eventTypeName || ''} onChange={(e) => setForm({ ...form, eventTypeName: e.target.value })} /></label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
