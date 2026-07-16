import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getPathTransportStops, createPathTransportStop, updatePathTransportStop, deletePathTransportStop, getPaths, getTransportStops } from '../../api/routes';
import { getRegions } from '../../api/reference';
import type { PathTransportStop } from '../../types/route';


export function PathTransportStopsPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['path-transport-stops'], queryFn: getPathTransportStops });
  const [edit, setEdit] = useState<Partial<PathTransportStop> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const { data: paths } = useQuery({ queryKey: ['paths'], queryFn: getPaths });
  const { data: stops } = useQuery({ queryKey: ['transport-stops'], queryFn: getTransportStops });

  const createMut = useMutation({
    mutationFn: createPathTransportStop,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['path-transport-stops'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<PathTransportStop> }) => updatePathTransportStop(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['path-transport-stops'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deletePathTransportStop,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['path-transport-stops'] }),
  });

  if (isLoading) return <div className="loading">Загрузка...</div>;
  if (error) return <div className="form-error">Ошибка: {(error as any).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Остановки путей</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>
          + Добавить
        </button>
      </div>

      {(showForm || edit) && (
        <div className="form-card">
          <h3>{edit?.id ? 'Редактировать' : 'Создать'} остановку пути</h3>
          {formError && <div className="form-error">{formError}</div>}
          <form onSubmit={(e) => {
            e.preventDefault();
            const form = e.currentTarget;
            const data = Object.fromEntries(new FormData(form)) as any;
            data.serialNumber = Number(data.serialNumber);
            if (edit?.id) updateMut.mutate({ id: edit.id, data });
            else createMut.mutate(data);
          }}>
            <div className="form-grid">
              <div className="form-group">
                <label>Путь</label>
                <select name="pathId" defaultValue={(edit as any)?.pathId || ''} required>
                  <option value="">Выберите путь</option>
                  {paths?.map((p: any) => (
                    <option key={p.id} value={p.id}>{p.pathName}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Остановка</label>
                <select name="stopId" defaultValue={(edit as any)?.stopId || ''} required>
                  <option value="">Выберите остановку</option>
                  {stops?.map((s: any) => (
                    <option key={s.id} value={s.id}>{s.stopName}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Порядковый номер</label>
                <input type="number" name="serialNumber" defaultValue={(edit as any)?.serialNumber || ''} required />
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
            <th>Путь</th>
            <th>Остановка</th>
            <th>Порядковый номер</th>
            <th>Регион</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((item) => (
            <tr key={item.id}>
              <td>{paths?.find((p: any) => p.id === item.pathId)?.pathName || item.pathId}</td>
              <td>{stops?.find((s: any) => s.id === item.stopId)?.stopName || item.stopId}</td>
              <td>{item.serialNumber}</td>
              <td>{regions?.find((r: any) => r.id === item.regionId)?.municipalDivision || item.regionId}</td>
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
