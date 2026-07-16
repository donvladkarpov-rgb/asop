import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getRoutes, createRoute, updateRoute, deleteRoute } from '../../api/routes';
import { getRegions, getOrganizers } from '../../api/reference';
import type { Route } from '../../types/route';

const ROUTE_CATEGORIES = ['CITY', 'SUBURBAN', 'INTERCITY', 'EXPRESS'] as const;

export function RoutesPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['routes'], queryFn: getRoutes });
  const [edit, setEdit] = useState<Partial<Route> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const { data: organizers } = useQuery({ queryKey: ['organizers'], queryFn: getOrganizers });

  const createMut = useMutation({
    mutationFn: createRoute,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['routes'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<Route> }) => updateRoute(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['routes'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deleteRoute,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['routes'] }),
  });

  if (isLoading) return <div className="loading">Загрузка...</div>;
  if (error) return <div className="form-error">Ошибка: {(error as any).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Маршруты</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>
          + Добавить
        </button>
      </div>

      {(showForm || edit) && (
        <div className="form-card">
          <h3>{edit?.id ? 'Редактировать' : 'Создать'} маршрут</h3>
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
                <label>Номер маршрута</label>
                <input name="routeNumber" defaultValue={(edit as any)?.routeNumber || ''} required />
              </div>
              <div className="form-group">
                <label>Название маршрута</label>
                <input name="routeName" defaultValue={(edit as any)?.routeName || ''} required />
              </div>
              <div className="form-group">
                <label>Категория маршрута</label>
                <select name="routeCategory" defaultValue={(edit as any)?.routeCategory || ''} required>
                  <option value="">Выберите категорию</option>
                  {ROUTE_CATEGORIES.map((cat) => (
                    <option key={cat} value={cat}>{cat}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Организатор</label>
                <select name="organizerId" defaultValue={(edit as any)?.organizerId || ''}>
                  <option value="">Без организатора</option>
                  {organizers?.map((o: any) => (
                    <option key={o.id} value={o.id}>{o.organizerName}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Реестровый номер Минтранса</label>
                <input name="ministryRegistryNo" defaultValue={(edit as any)?.ministryRegistryNo || ''} />
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
            <th>Номер маршрута</th>
            <th>Название маршрута</th>
            <th>Категория</th>
            <th>Организатор</th>
            <th>Реестровый номер</th>
            <th>Регион</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((item) => (
            <tr key={item.id}>
              <td>{item.routeNumber}</td>
              <td>{item.routeName}</td>
              <td>{item.routeCategory}</td>
              <td>{organizers?.find((o: any) => o.id === item.organizerId)?.organizerName || item.organizerId || '—'}</td>
              <td>{item.ministryRegistryNo || '—'}</td>
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
