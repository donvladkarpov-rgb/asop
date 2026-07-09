import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getServices, createService, updateService, deleteService, getRegions } from '../api/reference';
import type { Service } from '../types/reference';

export function ServicesPage() {
  const qc = useQueryClient();
  const [filterRegion, setFilterRegion] = useState('');
  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const { data, isLoading, error } = useQuery({ queryKey: ['services', filterRegion], queryFn: () => getServices(filterRegion || undefined) });
  const [edit, setEdit] = useState<Partial<Service> | null>(null);
  const [showForm, setShowForm] = useState(false);

  const createMut = useMutation({ mutationFn: createService, onSuccess: () => { qc.invalidateQueries({ queryKey: ['services'] }); setShowForm(false); } });
  const updateMut = useMutation({ mutationFn: ({ id, data }: { id: string; data: Partial<Service> }) => updateService(id, data), onSuccess: () => { qc.invalidateQueries({ queryKey: ['services'] }); setEdit(null); } });
  const deleteMut = useMutation({ mutationFn: deleteService, onSuccess: () => qc.invalidateQueries({ queryKey: ['services'] }) });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Услуги</h1>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <select value={filterRegion} onChange={(e) => setFilterRegion(e.target.value)} style={{ padding: '6px 10px', borderRadius: 6, border: '1px solid #d1d5db' }}>
            <option value="">Все регионы</option>
            {regions?.map((r) => <option key={r.id} value={r.id}>{r.municipalDivision}</option>)}
          </select>
          <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
        </div>
      </div>

      {(showForm || edit) && (
        <ServiceForm
          regions={regions || []}
          initial={edit}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Название</th>
            <th>Описание</th>
            <th>Приоритет</th>
            <th>Регион</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((s) => {
            const region = regions?.find((r) => r.id === s.regionId);
            return (
              <tr key={s.id}>
                <td>{s.serviceName}</td>
                <td>{s.description || '—'}</td>
                <td>{s.priority}</td>
                <td>{region?.municipalDivision || '—'}</td>
                <td style={{ display: 'flex', gap: 8 }}>
                  <button className="btn-secondary btn-sm" onClick={() => setEdit(s)}>✎</button>
                  <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(s.id); }}>✕</button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function ServiceForm({ regions, initial, onSave, onCancel }: { regions: { id: string; municipalDivision: string }[]; initial?: Partial<Service> | null; onSave: (d: Partial<Service>) => void; onCancel: () => void }) {
  const [form, setForm] = useState<Partial<Service>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Название <input value={form.serviceName || ''} onChange={(e) => setForm({ ...form, serviceName: e.target.value })} /></label>
        <label>Приоритет <input type="number" value={form.priority ?? ''} onChange={(e) => setForm({ ...form, priority: Number(e.target.value) })} /></label>
        <label>Регион
          <select value={form.regionId || ''} onChange={(e) => setForm({ ...form, regionId: e.target.value })}>
            <option value="">—</option>
            {regions.map((r) => <option key={r.id} value={r.id}>{r.municipalDivision}</option>)}
          </select>
        </label>
        <label>Описание <input value={form.description || ''} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
