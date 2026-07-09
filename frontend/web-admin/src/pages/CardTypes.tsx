import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getCardTypes, createCardType, updateCardType, deleteCardType } from '../api/reference';
import type { CardType } from '../types/reference';

export function CardTypesPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['card-types'], queryFn: getCardTypes });
  const [edit, setEdit] = useState<Partial<CardType> | null>(null);
  const [showForm, setShowForm] = useState(false);

  const createMut = useMutation({ mutationFn: createCardType, onSuccess: () => { qc.invalidateQueries({ queryKey: ['card-types'] }); setShowForm(false); } });
  const updateMut = useMutation({ mutationFn: ({ id, data }: { id: string; data: Partial<CardType> }) => updateCardType(id, data), onSuccess: () => { qc.invalidateQueries({ queryKey: ['card-types'] }); setEdit(null); } });
  const deleteMut = useMutation({ mutationFn: deleteCardType, onSuccess: () => qc.invalidateQueries({ queryKey: ['card-types'] }) });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Типы карт</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <CardTypeForm
          initial={edit}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Название типа карты</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={r.id}>
              <td>{r.cardTypeName}</td>
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

function CardTypeForm({ initial, onSave, onCancel }: { initial?: Partial<CardType> | null; onSave: (d: Partial<CardType>) => void; onCancel: () => void }) {
  const [form, setForm] = useState<Partial<CardType>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <label>Название типа карты
        <input value={form.cardTypeName || ''} onChange={(e) => setForm({ ...form, cardTypeName: e.target.value })} />
      </label>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
