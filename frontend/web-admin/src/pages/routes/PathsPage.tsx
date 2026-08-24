import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useGlobalFilter } from '../../contexts/GlobalFilterContext';
import { getPaths, createPath, updatePath, deletePath, getRoutes, getTransportStops } from '../../api/routes';
import { getRegions } from '../../api/reference';
import type { Path } from '../../types/route';


const BENEFIT_POLICIES = ['ALL', 'ALLOWLIST', 'NONE'] as const;

export function PathsPage() {
  const qc = useQueryClient();
  const { regionId: globalRegionId } = useGlobalFilter();
  const { data, isLoading, error } = useQuery({ queryKey: ['paths', globalRegionId], queryFn: () => getPaths({ regionId: globalRegionId || undefined }) });
  const [edit, setEdit] = useState<Partial<Path> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const { data: routes } = useQuery({ queryKey: ['routes', globalRegionId], queryFn: () => getRoutes({ regionId: globalRegionId || undefined }) });
  const { data: stops } = useQuery({ queryKey: ['transport-stops', globalRegionId], queryFn: () => getTransportStops({ regionId: globalRegionId || undefined }) });

  const createMut = useMutation({
    mutationFn: createPath,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['paths'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<Path> }) => updatePath(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['paths'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deletePath,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['paths'] }),
  });

  if (isLoading) return <div className="loading">Загрузка...</div>;
  if (error) return <div className="form-error">Ошибка: {(error as any).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Пути</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>
          + Добавить
        </button>
      </div>

      {(showForm || edit) && (
        <div className="form-card">
          <h3>{edit?.id ? 'Редактировать' : 'Создать'} путь</h3>
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
                <label>Маршрут</label>
                <select name="routeId" defaultValue={(edit as any)?.routeId || ''} required>
                  <option value="">Выберите маршрут</option>
                  {routes?.map((r: any) => (
                    <option key={r.id} value={r.id}>{r.routeNumber} — {r.routeName}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Название пути</label>
                <input name="pathName" defaultValue={(edit as any)?.pathName || ''} required />
              </div>
              <div className="form-group">
                <label>Объект пути (JSON)</label>
                <textarea name="routeObject" defaultValue={(edit as any)?.routeObject || ''} rows={3} />
              </div>
              <div className="form-group">
                <label>Политика льгот</label>
                <select name="benefitPolicy" defaultValue={(edit as any)?.benefitPolicy || 'NONE'} required>
                  {BENEFIT_POLICIES.map((p) => (
                    <option key={p} value={p}>{p}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Начальная остановка</label>
                <select name="startStopId" defaultValue={(edit as any)?.startStopId || ''}>
                  <option value="">Не указана</option>
                  {stops?.map((s: any) => (
                    <option key={s.id} value={s.id}>{s.stopName}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Конечная остановка</label>
                <select name="endStopId" defaultValue={(edit as any)?.endStopId || ''}>
                  <option value="">Не указана</option>
                  {stops?.map((s: any) => (
                    <option key={s.id} value={s.id}>{s.stopName}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Дата начала</label>
                <input type="date" name="pathStartDate" defaultValue={(edit as any)?.pathStartDate || ''} />
              </div>
              <div className="form-group">
                <label>Дата окончания</label>
                <input type="date" name="pathEndDate" defaultValue={(edit as any)?.pathEndDate || ''} />
              </div>
              <div className="form-group">
                <label>Описание</label>
                <textarea name="description" defaultValue={(edit as any)?.description || ''} rows={2} />
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
            <th>Маршрут</th>
            <th>Название пути</th>
            <th>Политика льгот</th>
            <th>Начальная остановка</th>
            <th>Конечная остановка</th>
            <th>Регион</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((item) => (
            <tr key={item.id}>
              <td>{routes?.find((r: any) => r.id === item.routeId)?.routeNumber || item.routeId}</td>
              <td>{item.pathName}</td>
              <td>{item.benefitPolicy}</td>
              <td>{stops?.find((s: any) => s.id === item.startStopId)?.stopName || item.startStopId || '—'}</td>
              <td>{stops?.find((s: any) => s.id === item.endStopId)?.stopName || item.endStopId || '—'}</td>
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
