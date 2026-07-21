import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getBenefitSteps, createBenefitStep, updateBenefitStep, deleteBenefitStep, getBenefits } from '../api/reference';
import type { BenefitStep } from '../types/reference';

const PERIOD_TYPES = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY'];

export function BenefitStepsPage() {
  const qc = useQueryClient();
  const [filterBenefit, setFilterBenefit] = useState('');
  const { data: benefits } = useQuery({ queryKey: ['benefits'], queryFn: () => getBenefits() });
  const { data, isLoading, error } = useQuery({ queryKey: ['benefit-steps', filterBenefit], queryFn: () => getBenefitSteps(filterBenefit || undefined) });
  const [edit, setEdit] = useState<Partial<BenefitStep> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const createMut = useMutation({
    mutationFn: createBenefitStep,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['benefit-steps'] }); setShowForm(false); setFormError(null); },
    onError: (e) => setFormError(JSON.stringify((e as { response?: { data?: unknown } })?.response?.data ?? (e as Error).message)),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<BenefitStep> }) => updateBenefitStep(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['benefit-steps'] }); setEdit(null); setFormError(null); },
    onError: (e) => setFormError(JSON.stringify((e as { response?: { data?: unknown } })?.response?.data ?? (e as Error).message)),
  });
  const deleteMut = useMutation({
    mutationFn: deleteBenefitStep,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['benefit-steps'] }),
    onError: (e) => console.error('Delete failed', e),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Шаги льгот</h1>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <select value={filterBenefit} onChange={(e) => setFilterBenefit(e.target.value)} style={{ padding: '6px 10px', borderRadius: 6, border: '1px solid #d1d5db' }}>
            <option value="">Все льготы</option>
            {benefits?.map((b) => <option key={b.id} value={b.id}>{b.benefitName}</option>)}
          </select>
          <button className="btn-primary" onClick={() => { setEdit({ benefitId: filterBenefit || undefined } as any); setShowForm(true); }}>+ Добавить</button>
        </div>
      </div>

      {formError && <div style={{ color: 'red', marginTop: 8, marginBottom: 8 }}>{formError}</div>}

      {(showForm || edit) && (
        <BenefitStepForm
          benefits={benefits || []}
          initial={edit}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); setFormError(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Льгота</th>
            <th>Порядок</th>
            <th>От</th>
            <th>До</th>
            <th>Скидка</th>
            <th>Период</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((s) => {
            const benefit = benefits?.find((b) => b.id === s.benefitId);
            return (
              <tr key={s.id}>
                <td>{benefit?.benefitName || '—'}</td>
                <td>{s.stepOrder}</td>
                <td>{s.tripThresholdFrom}</td>
                <td>{s.tripThresholdTo ?? '—'}</td>
                <td>{s.discountShare}</td>
                <td>{s.periodType}</td>
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

function BenefitStepForm({ benefits, initial, onSave, onCancel }: { benefits: { id: string; benefitName: string }[]; initial?: Partial<BenefitStep> | null; onSave: (d: Partial<BenefitStep>) => void; onCancel: () => void }) {
  const [form, setForm] = useState<Partial<BenefitStep>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Льгота
          <select value={form.benefitId || ''} onChange={(e) => setForm({ ...form, benefitId: e.target.value })}>
            <option value="">—</option>
            {benefits.map((b) => <option key={b.id} value={b.id}>{b.benefitName}</option>)}
          </select>
        </label>
        <label>Порядок <input type="number" value={form.stepOrder ?? ''} onChange={(e) => setForm({ ...form, stepOrder: Number(e.target.value) })} /></label>
        <label>Порог от <input type="number" value={form.tripThresholdFrom ?? ''} onChange={(e) => setForm({ ...form, tripThresholdFrom: Number(e.target.value) })} /></label>
        <label>Порог до <input type="number" value={form.tripThresholdTo ?? ''} onChange={(e) => setForm({ ...form, tripThresholdTo: e.target.value ? Number(e.target.value) : undefined })} /></label>
        <label>Доля скидки <input type="number" step="0.01" value={form.discountShare ?? ''} onChange={(e) => setForm({ ...form, discountShare: Number(e.target.value) })} /></label>
        <label>Тип периода
          <select value={form.periodType || ''} onChange={(e) => setForm({ ...form, periodType: e.target.value })}>
            <option value="">—</option>
            {PERIOD_TYPES.map((pt) => <option key={pt} value={pt}>{pt}</option>)}
          </select>
        </label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
