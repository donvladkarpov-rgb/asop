import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getFareZones, createFareZone, updateFareZone, deleteFareZone } from '../../api/routes';
import { getRegions } from '../../api/reference';
import type { FareZone } from '../../types/route';


export function FareZonesPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['fare-zones'], queryFn: getFareZones });
  const [edit, setEdit] = useState<Partial<FareZone> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });

  const createMut = useMutation({
    mutationFn: createFareZone,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['fare-zones'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<FareZone> }) => updateFareZone(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['fare-zones'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deleteFareZone,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['fare-zones'] }),
  });

  if (isLoading) return <div className="loading">Загрузка...</div>;
  if (error) return <div className="form-error">Ошибка: {(error as any).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Тарифные зоны</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>
          + Добавить
        </button>
      </div>

      {(showForm || edit) && (
        <div className="form-card">
          <h3>{edit?.id ? 'Редактировать' : 'Создать'} тарифную зону</h3>
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
                <label>Код зоны</label>
                <input name="zoneCode" defaultValue={(edit as any)?.zoneCode || ''} required />
              </div>
              <div className="form-group">
                <label>Название зоны</label>
                <input name="zoneName" defaultValue={(edit as any)?.zoneName || ''} required />
              </div>
              <div className="form-group">
                <label>Регион</label>
                <select name="regionId" defaultValue={(edit as any)?.regionId || ''} required>
                  <option value="">Выберите регион</option>
                  {regions?.map((r: any) => (
                    <option key={r.id} value={r.id}>{r.municipalDivision}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>GeoJSON полигон</label>
                <textarea name="zonePolygon" defaultValue={(edit as any)?.zonePolygon || ''} rows={3} />
              </div>
              <div className="form-group">
                <label>Описание</label>
                <textarea name="description" defaultValue={(edit as any)?.description || ''} rows={2} />
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
            <th>Код зоны</th>
            <th>Название зоны</th>
            <th>Регион</th>
            <th>Описание</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((item) => (
            <tr key={item.id}>
              <td>{item.zoneCode}</td>
              <td>{item.zoneName}</td>
              <td>{regions?.find((r: any) => r.id === item.regionId)?.municipalDivision || item.regionId}</td>
              <td>{item.description}</td>
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
