import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getRegions, createRegion, updateRegion, deleteRegion } from '../api/reference';
import type { Region } from '../types/reference';

export function RegionsPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const [edit, setEdit] = useState<Partial<Region> | null>(null);
  const [showForm, setShowForm] = useState(false);

  const createMut = useMutation({ mutationFn: createRegion, onSuccess: () => { qc.invalidateQueries({ queryKey: ['regions'] }); setShowForm(false); } });
  const updateMut = useMutation({ mutationFn: ({ id, data }: { id: string; data: Partial<Region> }) => updateRegion(id, data), onSuccess: () => { qc.invalidateQueries({ queryKey: ['regions'] }); setEdit(null); } });
  const deleteMut = useMutation({ mutationFn: deleteRegion, onSuccess: () => qc.invalidateQueries({ queryKey: ['regions'] }) });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Регионы (ФИАС)</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <RegionForm
          initial={edit}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Муниципальное образование</th>
            <th>Административное деление</th>
            <th>Федеральный округ</th>
            <th>ОКАТО</th>
            <th>ОКТМО</th>
            <th>ФИАС ID</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={r.id}>
              <td>{r.municipalDivision}</td>
              <td>{r.adminDivision || '—'}</td>
              <td>{r.federalDistrict || '—'}</td>
              <td>{r.okatoCode || '—'}</td>
              <td>{r.oktmoCode || '—'}</td>
              <td><code>{r.fiasId || '—'}</code></td>
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

function RegionForm({ initial, onSave, onCancel }: { initial?: Partial<Region> | null; onSave: (d: Partial<Region>) => void; onCancel: () => void }) {
  const [form, setForm] = useState<Partial<Region>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Муниципальное образование <input value={form.municipalDivision || ''} onChange={(e) => setForm({ ...form, municipalDivision: e.target.value })} /></label>
        <label>Административное деление <input value={form.adminDivision || ''} onChange={(e) => setForm({ ...form, adminDivision: e.target.value })} /></label>
        <label>Федеральный округ <input value={form.federalDistrict || ''} onChange={(e) => setForm({ ...form, federalDistrict: e.target.value })} /></label>
        <label>Код ИФНС ФЛ <input value={form.ifnsFlCode || ''} onChange={(e) => setForm({ ...form, ifnsFlCode: e.target.value })} /></label>
        <label>Код ИФНС ЮЛ <input value={form.ifnsUlCode || ''} onChange={(e) => setForm({ ...form, ifnsUlCode: e.target.value })} /></label>
        <label>ОКАТО <input value={form.okatoCode || ''} onChange={(e) => setForm({ ...form, okatoCode: e.target.value })} /></label>
        <label>ОКТМО <input value={form.oktmoCode || ''} onChange={(e) => setForm({ ...form, oktmoCode: e.target.value })} /></label>
        <label>ОКТМО бюджетный <input value={form.oktmoBudgetCode || ''} onChange={(e) => setForm({ ...form, oktmoBudgetCode: e.target.value })} /></label>
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
