import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getVehicles, createVehicle, updateVehicle, deleteVehicle } from '../../api/routes';
import { getCarriers } from '../../api/reference';
import type { Vehicle } from '../../types/route';

export function VehiclesPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['vehicles'], queryFn: getVehicles });
  const [edit, setEdit] = useState<Partial<Vehicle> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const { data: carriers } = useQuery({ queryKey: ['carriers'], queryFn: getCarriers });

  const createMut = useMutation({
    mutationFn: createVehicle,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['vehicles'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<Vehicle> }) => updateVehicle(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['vehicles'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deleteVehicle,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['vehicles'] }),
  });

  if (isLoading) return <div className="loading">Загрузка...</div>;
  if (error) return <div className="form-error">Ошибка: {(error as any).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Транспортные средства</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>
          + Добавить
        </button>
      </div>

      {(showForm || edit) && (
        <div className="form-card">
          <h3>{edit?.id ? 'Редактировать' : 'Создать'} транспортное средство</h3>
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
                <label>Перевозчик</label>
                <select name="carrierId" defaultValue={(edit as any)?.carrierId || ''}>
                  <option value="">Без перевозчика</option>
                  {carriers?.map((c: any) => (
                    <option key={c.id} value={c.id}>{c.carrierName}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Тип ТС</label>
                <select name="vehicleTypeId" defaultValue={(edit as any)?.vehicleTypeId || ''} required>
                  <option value="">Выберите тип ТС</option>
                </select>
              </div>
              <div className="form-group">
                <label>Модель ТС</label>
                <select name="vehicleModelId" defaultValue={(edit as any)?.vehicleModelId || ''} required>
                  <option value="">Выберите модель ТС</option>
                </select>
              </div>
              <div className="form-group">
                <label>Госномер</label>
                <input name="vehicleNumber" defaultValue={(edit as any)?.vehicleNumber || ''} required />
              </div>
              <div className="form-group">
                <label>Название ТС</label>
                <input name="vehicleName" defaultValue={(edit as any)?.vehicleName || ''} />
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
            <th>Перевозчик</th>
            <th>Тип ТС</th>
            <th>Модель ТС</th>
            <th>Госномер</th>
            <th>Название ТС</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((item) => (
            <tr key={item.id}>
              <td>{carriers?.find((c: any) => c.id === item.carrierId)?.carrierName || item.carrierId || '—'}</td>
              <td>{item.vehicleTypeId}</td>
              <td>{item.vehicleModelId}</td>
              <td>{item.vehicleNumber}</td>
              <td>{item.vehicleName}</td>
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
