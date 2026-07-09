import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getBenefits, createBenefit, updateBenefit, deleteBenefit, getRegions } from '../api/reference';
import type { Benefit } from '../types/reference';

export function BenefitsPage() {
  const qc = useQueryClient();
  const [filterRegion, setFilterRegion] = useState('');
  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const { data, isLoading, error } = useQuery({ queryKey: ['benefits', filterRegion], queryFn: () => getBenefits(filterRegion || undefined) });
  const [edit, setEdit] = useState<Partial<Benefit> | null>(null);
  const [showForm, setShowForm] = useState(false);

  const createMut = useMutation({ mutationFn: createBenefit, onSuccess: () => { qc.invalidateQueries({ queryKey: ['benefits'] }); setShowForm(false); } });
  const updateMut = useMutation({ mutationFn: ({ id, data }: { id: string; data: Partial<Benefit> }) => updateBenefit(id, data), onSuccess: () => { qc.invalidateQueries({ queryKey: ['benefits'] }); setEdit(null); } });
  const deleteMut = useMutation({ mutationFn: deleteBenefit, onSuccess: () => qc.invalidateQueries({ queryKey: ['benefits'] }) });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Льготы</h1>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <select value={filterRegion} onChange={(e) => setFilterRegion(e.target.value)} style={{ padding: '6px 10px', borderRadius: 6, border: '1px solid #d1d5db' }}>
            <option value="">Все регионы</option>
            {regions?.map((r) => <option key={r.id} value={r.id}>{r.municipalDivision}</option>)}
          </select>
          <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
        </div>
      </div>

      {(showForm || edit) && (
        <BenefitForm
          regions={regions || []}
          initial={edit}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Код</th>
            <th>Название</th>
            <th>Регион</th>
            <th>Описание</th>
            <th>Активна</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((b) => {
            const region = regions?.find((r) => r.id === b.regionId);
            return (
              <tr key={b.id}>
                <td>{b.benefitCode}</td>
                <td>{b.benefitName}</td>
                <td>{region?.municipalDivision || '—'}</td>
                <td>{b.description || '—'}</td>
                <td>{b.isActive ? 'Да' : 'Нет'}</td>
                <td style={{ display: 'flex', gap: 8 }}>
                  <button className="btn-secondary btn-sm" onClick={() => setEdit(b)}>✎</button>
                  <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(b.id); }}>✕</button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function BenefitForm({ regions, initial, onSave, onCancel }: { regions: { id: string; municipalDivision: string }[]; initial?: Partial<Benefit> | null; onSave: (d: Partial<Benefit>) => void; onCancel: () => void }) {
  const [form, setForm] = useState<Partial<Benefit>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Код льготы <input value={form.benefitCode || ''} onChange={(e) => setForm({ ...form, benefitCode: e.target.value })} /></label>
        <label>Название <input value={form.benefitName || ''} onChange={(e) => setForm({ ...form, benefitName: e.target.value })} /></label>
        <label>Регион
          <select value={form.regionId || ''} onChange={(e) => setForm({ ...form, regionId: e.target.value })}>
            <option value="">—</option>
            {regions.map((r) => <option key={r.id} value={r.id}>{r.municipalDivision}</option>)}
          </select>
        </label>
        <label>Описание <input value={form.description || ''} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
        <label>
          <input type="checkbox" checked={form.isActive ?? false} onChange={(e) => setForm({ ...form, isActive: e.target.checked })} />
          {' '}Активна
        </label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
