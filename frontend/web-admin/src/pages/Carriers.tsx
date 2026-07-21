import { useState, useCallback } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getCarriers, createCarrier, updateCarrier, deleteCarrier } from '../api/carriers';
import { getRegions } from '../api/reference';
import type { Carrier } from '../types/reference';
import { useCommand } from '../hooks/useCommand';

export function CarriersPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['carriers'], queryFn: getCarriers });
  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const [edit, setEdit] = useState<Partial<Carrier> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const { execute, pollResult } = useCommand({ pollIntervalMs: 1500, maxPolls: 20 });
  const createMut = useMutation({
    mutationFn: createCarrier,
    onSuccess: () => {
      setShowForm(false);
      setFormError(null);
    },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.response?.data?.message || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<Carrier> }) => updateCarrier(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['carriers'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.response?.data?.message || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deleteCarrier,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['carriers'] })
  });

  const handleCreate = useCallback(async () => {
    const name = (document.getElementById('create-carrier-name') as HTMLInputElement)?.value;
    const inn = (document.getElementById('create-carrier-inn') as HTMLInputElement)?.value;
    const regionId = (document.getElementById('create-carrier-region') as HTMLSelectElement)?.value;
    if (!name || !inn || !regionId) { setFormError('Заполните все поля'); return; }
    setFormError(null);
    setShowForm(false);

    const result = await execute(() => createCarrier({ carrierName: name.trim(), inn: inn.trim(), regionId }));
    if (result?.status === 'failed') {
      setFormError(result.error || 'Ошибка создания перевозчика');
      setShowForm(true);
    } else {
      qc.invalidateQueries({ queryKey: ['carriers'] });
    }
  }, [execute, qc]);

  const isProcessing = createMut.isPending || !!pollResult?.status && pollResult.status === 'pending';

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Перевозчики</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>+ Добавить</button>
      </div>

      {pollResult?.status === 'pending' && <div style={{ padding: '8px 12px', background: '#d1fae5', color: '#065f46', borderRadius: 6, marginBottom: 12 }}>Перевозчик отправлен на создание...</div>}
      {pollResult?.status === 'failed' && <div style={{ padding: '8px 12px', background: '#fee2e2', color: '#991b1b', borderRadius: 6, marginBottom: 12 }}>Ошибка: {pollResult.error}</div>}

      {(showForm || edit) && !edit?.id && (
        <div className="form-card" style={{ marginBottom: 20 }}>
          {formError && <div className="form-error" style={{ marginBottom: 12 }}>{formError}</div>}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
            <label>Название <input id="create-carrier-name" defaultValue={(edit as any)?.carrierName || ''} /></label>
            <label>ИНН <input id="create-carrier-inn" defaultValue={(edit as any)?.inn || ''} /></label>
            <label>Регион
              <select id="create-carrier-region" defaultValue={(edit as any)?.regionId || ''}>
                <option value="">Выберите...</option>
                {regions?.map((r) => <option key={r.id} value={r.id}>{r.municipalDivision}</option>)}
              </select>
            </label>
          </div>
          <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
            <button className="btn-primary" disabled={isProcessing}
              onClick={handleCreate}>{isProcessing ? 'Отправка...' : 'Сохранить'}</button>
            <button className="btn-secondary" onClick={() => { setShowForm(false); setEdit(null); setFormError(null); }}>Отмена</button>
          </div>
        </div>
      )}

      {edit?.id && (
        <div className="form-card" style={{ marginBottom: 20 }}>
          {formError && <div className="form-error" style={{ marginBottom: 12 }}>{formError}</div>}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
            <label>Название <input value={edit.carrierName || ''} onChange={(e) => setEdit({ ...edit, carrierName: e.target.value })} /></label>
            <label>ИНН <input value={edit.inn || ''} onChange={(e) => setEdit({ ...edit, inn: e.target.value })} /></label>
            <label>Регион
              <select value={edit.regionId || ''} onChange={(e) => setEdit({ ...edit, regionId: e.target.value })}>
                <option value="">Выберите...</option>
                {regions?.map((r) => <option key={r.id} value={r.id}>{r.municipalDivision}</option>)}
              </select>
            </label>
          </div>
          <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
            <button className="btn-primary" onClick={() => updateMut.mutate({ id: edit.id!, data: edit })}>Сохранить</button>
            <button className="btn-secondary" onClick={() => setEdit(null)}>Отмена</button>
          </div>
        </div>
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Название</th>
            <th>ИНН</th>
            <th>Регион</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((c) => (
            <tr key={c.id}>
              <td>{c.carrierName}</td>
              <td>{c.inn}</td>
              <td>{regions?.find((r) => r.id === c.regionId)?.municipalDivision || '—'}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => { setEdit(c); setFormError(null); }}>✎</button>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(c.id); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
