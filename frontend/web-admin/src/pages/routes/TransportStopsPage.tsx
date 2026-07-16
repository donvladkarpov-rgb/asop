import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getTransportStops, createTransportStop, updateTransportStop, deleteTransportStop, getFareZones } from '../../api/routes';
import { getRegions } from '../../api/reference';
import type { TransportStop } from '../../types/route';


export function TransportStopsPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['transport-stops'], queryFn: getTransportStops });
  const [edit, setEdit] = useState<Partial<TransportStop> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const { data: fareZones } = useQuery({ queryKey: ['fare-zones'], queryFn: getFareZones });

  const createMut = useMutation({
    mutationFn: createTransportStop,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['transport-stops'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<TransportStop> }) => updateTransportStop(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['transport-stops'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deleteTransportStop,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['transport-stops'] }),
  });

  if (isLoading) return <div className="loading">Загрузка...</div>;
  if (error) return <div className="form-error">Ошибка: {(error as any).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Остановки</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>
          + Добавить
        </button>
      </div>

      {(showForm || edit) && (
        <div className="form-card">
          <h3>{edit?.id ? 'Редактировать' : 'Создать'} остановку</h3>
          {formError && <div className="form-error">{formError}</div>}
          <form onSubmit={(e) => {
            e.preventDefault();
            const form = e.currentTarget;
            const data = Object.fromEntries(new FormData(form)) as any;
            data.isActive = form.querySelector<HTMLInputElement>('input[name="isActive"]')?.checked || false;
            if (edit?.id) updateMut.mutate({ id: edit.id, data });
            else createMut.mutate(data);
          }}>
            <div className="form-grid">
              <div className="form-group">
                <label>Код остановки</label>
                <input name="stopCode" defaultValue={(edit as any)?.stopCode || ''} required />
              </div>
              <div className="form-group">
                <label>Название остановки</label>
                <input name="stopName" defaultValue={(edit as any)?.stopName || ''} required />
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
                <label>Тарифная зона</label>
                <select name="fareZoneId" defaultValue={(edit as any)?.fareZoneId || ''}>
                  <option value="">Без зоны</option>
                  {fareZones?.map((z: any) => (
                    <option key={z.id} value={z.id}>{z.zoneName}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>GeoJSON полигон</label>
                <textarea name="zonePolygon" defaultValue={(edit as any)?.zonePolygon || ''} rows={3} />
              </div>
              <div className="form-group">
                <label>Адрес</label>
                <input name="stopAddress" defaultValue={(edit as any)?.stopAddress || ''} />
              </div>
              <div className="form-group">
                <label>Описание</label>
                <textarea name="description" defaultValue={(edit as any)?.description || ''} rows={2} />
              </div>
              <div className="form-group">
                <label>
                  <input type="checkbox" name="isActive" defaultChecked={(edit as any)?.isActive ?? true} />
                  {' '}Активна
                </label>
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
            <th>Код остановки</th>
            <th>Название остановки</th>
            <th>Регион</th>
            <th>Тарифная зона</th>
            <th>Адрес</th>
            <th>Активна</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((item) => (
            <tr key={item.id}>
              <td>{item.stopCode}</td>
              <td>{item.stopName}</td>
              <td>{regions?.find((r: any) => r.id === item.regionId)?.municipalDivision || item.regionId}</td>
              <td>{fareZones?.find((z: any) => z.id === item.fareZoneId)?.zoneName || item.fareZoneId || '—'}</td>
              <td>{item.stopAddress}</td>
              <td>{item.isActive ? 'Да' : 'Нет'}</td>
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
