import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getConfigParams, createConfigParam, updateConfigParam, deleteConfigParam } from '../api/configParams';
import type { ConfigParam } from '../types/reference';
import { useGlobalFilter } from '../contexts/GlobalFilterContext';

function scopesOf(p: ConfigParam): string {
  const parts: string[] = [];
  if (p.krsId) parts.push('KRS');
  if (p.cardsDistributorId) parts.push('Дистрибьютор');
  if (p.carrierId) parts.push('Перевозчик');
  if (p.organizerId) parts.push('Организатор');
  if (p.regionId) parts.push('Регион');
  return parts.length ? parts.join(' → ') : 'base';
}

export function ConfigParamsPage() {
  const qc = useQueryClient();
  const { regionId: globalRegionId, carrierId: globalCarrierId, cardsDistributorId: globalDistributorId } = useGlobalFilter();
  const { data, isLoading, error } = useQuery({ queryKey: ['config-params'], queryFn: getConfigParams });
  // Глобальный фильтр: при заданном scope показываем base-строки (+ строки этого scope).
  const visibleParams = (data || []).filter((p) =>
    (!globalRegionId || p.regionId == null || p.regionId === globalRegionId) &&
    (!globalCarrierId || p.carrierId == null || p.carrierId === globalCarrierId) &&
    (!globalDistributorId || p.cardsDistributorId == null || p.cardsDistributorId === globalDistributorId));
  const [edit, setEdit] = useState<Partial<ConfigParam> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const createMut = useMutation({
    mutationFn: (d: Partial<ConfigParam>) => createConfigParam(d as any),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['config-params'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.response?.data?.message || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<ConfigParam> }) => updateConfigParam(id, data as any),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['config-params'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.response?.data?.message || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deleteConfigParam,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['config-params'] })
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Параметры АСОП</h1>
        <button className="btn-primary" onClick={() => { setEdit({ params: {} }); setShowForm(true); setFormError(null); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <ConfigParamForm
          initial={edit}
          error={formError}
          onSave={(d) => edit?.paramId ? updateMut.mutate({ id: edit.paramId!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); setFormError(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Scope</th>
            <th>Параметры (JSON)</th>
            <th>Обновлено</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {visibleParams.map((p) => (
            <tr key={p.paramId}>
              <td>{scopesOf(p)}</td>
              <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{JSON.stringify(p.params)}</td>
              <td>{new Date(p.updatedAt).toLocaleString()}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => { setEdit(p); setFormError(null); }}>✎</button>
                <button
                  className="btn-danger btn-sm"
                  onClick={() => { if (confirm('Удалить параметры?')) deleteMut.mutate(p.paramId); }}
                >✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ConfigParamForm({ initial, onSave, onCancel, error }: { initial?: Partial<ConfigParam> | null; onSave: (d: Partial<ConfigParam>) => void; onCancel: () => void; error?: string | null }) {
  const [form, setForm] = useState<Partial<ConfigParam>>(initial || {});
  const [paramsText, setParamsText] = useState<string>(JSON.stringify(initial?.params || {}, null, 2));
  const [jsonError, setJsonError] = useState<string | null>(null);

  const commit = () => {
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(paramsText);
    } catch (e: any) {
      setJsonError('Некорректный JSON: ' + e.message);
      return;
    }
    setJsonError(null);
    onSave({ ...form, params: parsed });
  };

  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      {error && <div className="form-error" style={{ marginBottom: 12 }}>{error}</div>}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Region ID <input value={form.regionId || ''} onChange={(e) => setForm({ ...form, regionId: e.target.value || null })} /></label>
        <label>Organizer ID <input value={form.organizerId || ''} onChange={(e) => setForm({ ...form, organizerId: e.target.value || null })} /></label>
        <label>Carrier ID <input value={form.carrierId || ''} onChange={(e) => setForm({ ...form, carrierId: e.target.value || null })} /></label>
        <label>CardsDistributor ID <input value={form.cardsDistributorId || ''} onChange={(e) => setForm({ ...form, cardsDistributorId: e.target.value || null })} /></label>
        <label>KRS ID <input value={form.krsId || ''} onChange={(e) => setForm({ ...form, krsId: e.target.value || null })} /></label>
      </div>
      <label style={{ marginTop: 16, display: 'block' }}>
        Параметры (JSON)
        <textarea
          value={paramsText}
          onChange={(e) => setParamsText(e.target.value)}
          rows={8}
          style={{ width: '100%', fontFamily: 'monospace', fontSize: 12, marginTop: 4 }}
        />
      </label>
      {jsonError && <div className="form-error" style={{ marginTop: 8 }}>{jsonError}</div>}
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={commit}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}