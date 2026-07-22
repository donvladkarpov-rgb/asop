import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getVehicleTypes, createVehicleType, updateVehicleType, deleteVehicleType } from '../../api/routes';
import type { VehicleType } from '../../types/route';

export function VehicleTypesPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['vehicle-types'], queryFn: getVehicleTypes });
  const [edit, setEdit] = useState<Partial<VehicleType> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const createMut = useMutation({
    mutationFn: createVehicleType,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['vehicle-types'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<VehicleType> }) => updateVehicleType(id, data as any),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['vehicle-types'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deleteVehicleType,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['vehicle-types'] }),
  });

  if (isLoading) return <div className="loading">Загрузка...</div>;
  if (error) return <div className="form-error">Ошибка: {(error as any).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Типы ТС</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>
          + Добавить
        </button>
      </div>

      {(showForm || edit) && (
        <div className="form-card">
          <h3>{edit?.id ? 'Редактировать' : 'Создать'} тип ТС</h3>
          {formError && <div className="form-error">{formError}</div>}
          <form onSubmit={(e) => {
            e.preventDefault();
            const form = e.currentTarget;
            const data = Object.fromEntries(new FormData(form)) as any;
            if (edit?.id) updateMut.mutate({ id: edit.id, data });
            else createMut.mutate(data);
          }}>
            <div className="form-grid">
              <div className="form-group">
                <label>Название типа</label>
                <input name="typeName" defaultValue={(edit as any)?.typeName || ''} required />
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <button type="submit" className="btn-primary" disabled={createMut.isPending || updateMut.isPending}>
                Сохранить
              </button>
              <button type="button" className="btn-secondary" onClick={() => { setShowForm(false); setEdit(null); setFormError(null); }}>
                Отмена
              </button>
            </div>
          </form>
        </div>
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Название типа</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((item) => (
            <tr key={item.id}>
              <td>{item.typeName}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => { setEdit(item); setFormError(null); }}>✎</button>
                <button className="btn-danger btn-sm"
                  onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(item.id); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
