import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getTariffTypes, createTariffType, updateTariffType, deleteTariffType } from '../api/reference';
import type { TariffType } from '../types/reference';

export function TariffTypesPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['tariff-types'], queryFn: getTariffTypes });
  const [edit, setEdit] = useState<Partial<TariffType> | null>(null);
  const [showForm, setShowForm] = useState(false);

  const createMut = useMutation({ mutationFn: createTariffType, onSuccess: () => { qc.invalidateQueries({ queryKey: ['tariff-types'] }); setShowForm(false); } });
  const updateMut = useMutation({ mutationFn: ({ id, data }: { id: string; data: Partial<TariffType> }) => updateTariffType(id, data), onSuccess: () => { qc.invalidateQueries({ queryKey: ['tariff-types'] }); setEdit(null); } });
  const deleteMut = useMutation({ mutationFn: deleteTariffType, onSuccess: () => qc.invalidateQueries({ queryKey: ['tariff-types'] }) });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Типы тарифов</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <TariffTypeForm
          initial={edit}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Код</th>
            <th>Название</th>
            <th>Описание</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={r.id}>
              <td>{r.code}</td>
              <td>{r.name}</td>
              <td>{r.description || '—'}</td>
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

function TariffTypeForm({ initial, onSave, onCancel }: { initial?: Partial<TariffType> | null; onSave: (d: Partial<TariffType>) => void; onCancel: () => void }) {
  const [form, setForm] = useState<Partial<TariffType>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Код <input value={form.code || ''} onChange={(e) => setForm({ ...form, code: e.target.value })} /></label>
        <label>Название <input value={form.name || ''} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
        <label style={{ gridColumn: '1 / -1' }}>Описание <input value={form.description || ''} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
