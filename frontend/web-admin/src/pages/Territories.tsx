import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getRegions, getTerritories, createTerritory, updateTerritory, deleteTerritory } from '../api/reference';
import { useGlobalFilter } from '../contexts/GlobalFilterContext';
import type { Territory } from '../types/reference';

export function TerritoriesPage() {
  const qc = useQueryClient();
  const { regionId: globalRegionId } = useGlobalFilter();
  const [filterRegion, setFilterRegion] = useState('');
  // Локальный фильтр региона не может выйти за пределы глобального.
  const effectiveRegion = filterRegion || globalRegionId;
  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const regionOptions = globalRegionId
    ? (regions || []).filter((r) => r.id === globalRegionId)
    : (regions || []);
  const { data, isLoading, error } = useQuery({ queryKey: ['territories', effectiveRegion], queryFn: () => getTerritories(effectiveRegion || undefined) });
  const [edit, setEdit] = useState<Partial<Territory> | null>(null);
  const [showForm, setShowForm] = useState(false);

  const createMut = useMutation({ mutationFn: createTerritory, onSuccess: () => { qc.invalidateQueries({ queryKey: ['territories'] }); setShowForm(false); } });
  const updateMut = useMutation({ mutationFn: ({ id, data }: { id: string; data: Partial<Territory> }) => updateTerritory(id, data), onSuccess: () => { qc.invalidateQueries({ queryKey: ['territories'] }); setEdit(null); } });
  const deleteMut = useMutation({ mutationFn: deleteTerritory, onSuccess: () => qc.invalidateQueries({ queryKey: ['territories'] }) });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Территории (ФИАС)</h1>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <select value={filterRegion} onChange={(e) => setFilterRegion(e.target.value)} style={{ padding: '6px 10px', borderRadius: 6, border: '1px solid #d1d5db' }}>
            <option value="">Все регионы</option>
            {regionOptions.map((r) => <option key={r.id} value={r.id}>{r.municipalDivision}</option>)}
          </select>
          <button className="btn-primary" onClick={() => { setEdit({ regionId: effectiveRegion || undefined } as any); setShowForm(true); }}>+ Добавить</button>
        </div>
      </div>

      {(showForm || edit) && (
        <TerritoryForm
          regions={regions || []}
          initial={edit}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Муниципальное образование</th>
            <th>Регион</th>
            <th>Федеральный округ</th>
            <th>ОКАТО</th>
            <th>ОКТМО</th>
            <th>ФИАС ID</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((t) => {
            const region = regions?.find((r) => r.id === t.regionId);
            return (
              <tr key={t.id}>
                <td>{t.municipalDivision}</td>
                <td>{region?.municipalDivision || '—'}</td>
                <td>{t.federalDistrict || '—'}</td>
                <td>{t.okatoCode || '—'}</td>
                <td>{t.oktmoCode || '—'}</td>
                <td><code>{t.fiasId || '—'}</code></td>
                <td style={{ display: 'flex', gap: 8 }}>
                  <button className="btn-secondary btn-sm" onClick={() => setEdit(t)}>✎</button>
                  <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(t.id); }}>✕</button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function TerritoryForm({ regions, initial, onSave, onCancel }: { regions: { id: string; municipalDivision: string }[]; initial?: Partial<Territory> | null; onSave: (d: Partial<Territory>) => void; onCancel: () => void }) {
  const [form, setForm] = useState<Partial<Territory>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Регион
          <select value={form.regionId || ''} onChange={(e) => setForm({ ...form, regionId: e.target.value })}>
            <option value="">—</option>
            {regions.map((r) => <option key={r.id} value={r.id}>{r.municipalDivision}</option>)}
          </select>
        </label>
        <label>Муниципальное образование <input value={form.municipalDivision || ''} onChange={(e) => setForm({ ...form, municipalDivision: e.target.value })} /></label>
        <label>Административное деление <input value={form.adminDivision || ''} onChange={(e) => setForm({ ...form, adminDivision: e.target.value })} /></label>
        <label>Федеральный округ <input value={form.federalDistrict || ''} onChange={(e) => setForm({ ...form, federalDistrict: e.target.value })} /></label>
        <label>ОКАТО <input value={form.okatoCode || ''} onChange={(e) => setForm({ ...form, okatoCode: e.target.value })} /></label>
        <label>ОКТМО <input value={form.oktmoCode || ''} onChange={(e) => setForm({ ...form, oktmoCode: e.target.value })} /></label>
        <label>ФИАС ID <input value={form.fiasId || ''} onChange={(e) => setForm({ ...form, fiasId: e.target.value })} /></label>
        <label>Идентификатор записи <input value={form.registryRecordId || ''} onChange={(e) => setForm({ ...form, registryRecordId: e.target.value })} /></label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
