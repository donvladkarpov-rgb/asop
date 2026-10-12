import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import * as api from '../api/fiscalization';
import { getCarriers } from '../api/carriers';
import { getCardsDistributors } from '../api/cardsDistributors';
import { getContracts } from '../api/contracts';
import type {
  CarrierFiscalizer,
  DistributorFiscalizer,
  Fiscalizer,
  FiscalizerToken,
} from '../types/reference';

type Tab = 'fiscalizers' | 'carrier' | 'distributor' | 'tokens';

const TABS: { key: Tab; label: string }[] = [
  { key: 'fiscalizers', label: 'Фискализаторы' },
  { key: 'carrier', label: 'Связи · перевозчики' },
  { key: 'distributor', label: 'Связи · дистрибьюторы' },
  { key: 'tokens', label: 'Токены' },
];

function errMsg(e: any, fallback: string): string {
  return e?.response?.data?.error || e?.response?.data?.message || e?.message || fallback;
}

function errText(e: any): string {
  const data = e?.response?.data;
  if (typeof data === 'string' && data) return data;
  return data?.error || data?.message || e?.message || 'Неизвестная ошибка';
}

function toLocalInput(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function fromLocalInput(v: string): string | null {
  if (!v) return null;
  const d = new Date(v);
  return isNaN(d.getTime()) ? null : d.toISOString();
}

function fmtDate(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '—' : d.toLocaleString('ru-RU');
}

export function FiscalizationPage() {
  const [tab, setTab] = useState<Tab>('fiscalizers');
  return (
    <div>
      <div className="page-header">
        <h1>Фискализация</h1>
      </div>
      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        {TABS.map((t) => (
          <button
            key={t.key}
            className={tab === t.key ? 'btn-primary btn-sm' : 'btn-secondary btn-sm'}
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>
      {tab === 'fiscalizers' && <FiscalizersTab />}
      {tab === 'carrier' && <CarrierFiscalizersTab />}
      {tab === 'distributor' && <DistributorFiscalizersTab />}
      {tab === 'tokens' && <TokensTab />}
    </div>
  );
}

/* ------------------------------- Фискализаторы ------------------------------- */

function FiscalizersTab() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['fiscalizers'], queryFn: api.getFiscalizers });
  const [edit, setEdit] = useState<Partial<Fiscalizer> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const invalidate = () => qc.invalidateQueries({ queryKey: ['fiscalizers'] });
  const createMut = useMutation({
    mutationFn: api.createFiscalizer,
    onSuccess: () => { invalidate(); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(errMsg(e, 'Ошибка создания')),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<Fiscalizer> }) => api.updateFiscalizer(id, data),
    onSuccess: () => { invalidate(); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(errMsg(e, 'Ошибка обновления')),
  });
  const deleteMut = useMutation({
    mutationFn: api.deleteFiscalizer,
    onSuccess: invalidate,
    onError: (e: any) => alert(errText(e)),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>+ Добавить фискализатора</button>
      </div>
      {(showForm || edit) && (
        <FiscalizerForm
          initial={edit}
          error={formError}
          onSave={(d) => edit?.fiscalizerId ? updateMut.mutate({ id: edit.fiscalizerId, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); setFormError(null); }}
        />
      )}
      <table className="data-table">
        <thead>
          <tr><th>Код</th><th>Название</th><th>Base API URL</th><th>Описание</th><th>Активен</th><th /></tr>
        </thead>
        <tbody>
          {data?.map((f) => (
            <tr key={f.fiscalizerId}>
              <td>{f.fiscalizerCode}</td>
              <td>{f.fiscalizerName}</td>
              <td>{f.baseApiUrl || '—'}</td>
              <td>{f.description || '—'}</td>
              <td>{f.isActive ? '✅' : '❌'}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => { setEdit(f); setFormError(null); }}>✎</button>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить фискализатора?')) deleteMut.mutate(f.fiscalizerId); }}>✕</button>
              </td>
            </tr>
          ))}
          {(!data || data.length === 0) && <tr><td colSpan={6} style={{ textAlign: 'center', color: '#9ca3af' }}>Нет записей</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

function FiscalizerForm({ initial, onSave, onCancel, error }: { initial?: Partial<Fiscalizer> | null; onSave: (d: Partial<Fiscalizer>) => void; onCancel: () => void; error?: string | null }) {
  const [form, setForm] = useState<Partial<Fiscalizer>>(initial || { isActive: true });
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      {error && <div className="form-error" style={{ marginBottom: 12 }}>{error}</div>}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Код <input value={form.fiscalizerCode || ''} onChange={(e) => setForm({ ...form, fiscalizerCode: e.target.value })} /></label>
        <label>Название <input value={form.fiscalizerName || ''} onChange={(e) => setForm({ ...form, fiscalizerName: e.target.value })} /></label>
        <label>Base API URL <input value={form.baseApiUrl || ''} onChange={(e) => setForm({ ...form, baseApiUrl: e.target.value })} placeholder="https://..." /></label>
        <label>Активен <input type="checkbox" checked={form.isActive ?? true} onChange={(e) => setForm({ ...form, isActive: e.target.checked })} /></label>
        <label style={{ gridColumn: '1 / span 2' }}>Описание <textarea value={form.description || ''} onChange={(e) => setForm({ ...form, description: e.target.value })} rows={2} /></label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}

/* --------------------------- Связи · перевозчики --------------------------- */

function CarrierFiscalizersTab() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['carrier-fiscalizers'], queryFn: () => api.getCarrierFiscalizers() });
  const { data: fiscalizers } = useQuery({ queryKey: ['fiscalizers'], queryFn: api.getFiscalizers });
  const { data: carriers } = useQuery({ queryKey: ['carriers'], queryFn: () => getCarriers() });
  const { data: contracts } = useQuery({ queryKey: ['contracts'], queryFn: () => getContracts() });
  const [edit, setEdit] = useState<Partial<CarrierFiscalizer> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const invalidate = () => qc.invalidateQueries({ queryKey: ['carrier-fiscalizers'] });
  const createMut = useMutation({
    mutationFn: api.createCarrierFiscalizer,
    onSuccess: () => { invalidate(); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(errMsg(e, 'Ошибка создания')),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<CarrierFiscalizer> }) => api.updateCarrierFiscalizer(id, data),
    onSuccess: () => { invalidate(); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(errMsg(e, 'Ошибка обновления')),
  });
  const deleteMut = useMutation({
    mutationFn: api.deleteCarrierFiscalizer,
    onSuccess: invalidate,
    onError: (e: any) => alert(errText(e)),
  });

  const fiscalizerName = (id: string) => fiscalizers?.find((f) => f.fiscalizerId === id)?.fiscalizerName || id;
  const carrierName = (id: string) => carriers?.find((c) => c.id === id)?.carrierName || id;
  const contractNumber = (id: string) => contracts?.find((c) => c.id === id)?.contractNumber || id;

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>+ Добавить связь</button>
      </div>
      {(showForm || edit) && (
        <CarrierFiscalizerForm
          initial={edit}
          fiscalizers={fiscalizers || []}
          carriers={carriers || []}
          contracts={contracts || []}
          error={formError}
          onSave={(d) => edit?.carrierFiscalizerId ? updateMut.mutate({ id: edit.carrierFiscalizerId, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); setFormError(null); }}
        />
      )}
      <table className="data-table">
        <thead>
          <tr><th>Перевозчик</th><th>Фискализатор</th><th>Договор</th><th>Custom API URL</th><th>Основной</th><th>Активен</th><th /></tr>
        </thead>
        <tbody>
          {data?.map((c) => (
            <tr key={c.carrierFiscalizerId}>
              <td>{carrierName(c.carrierId)}</td>
              <td>{fiscalizerName(c.fiscalizerId)}</td>
              <td>{contractNumber(c.contractId)}</td>
              <td>{c.customApiUrl || '—'}</td>
              <td>{c.isPrimary ? '✅' : '—'}</td>
              <td>{c.isActive ? '✅' : '❌'}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => { setEdit(c); setFormError(null); }}>✎</button>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить связь?')) deleteMut.mutate(c.carrierFiscalizerId); }}>✕</button>
              </td>
            </tr>
          ))}
          {(!data || data.length === 0) && <tr><td colSpan={7} style={{ textAlign: 'center', color: '#9ca3af' }}>Нет записей</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

function CarrierFiscalizerForm({ initial, fiscalizers, carriers, contracts, onSave, onCancel, error }: {
  initial?: Partial<CarrierFiscalizer> | null;
  fiscalizers: Fiscalizer[];
  carriers: { id: string; carrierName: string }[];
  contracts: { id: string; contractNumber: string; carrierId?: string }[];
  onSave: (d: Partial<CarrierFiscalizer>) => void;
  onCancel: () => void;
  error?: string | null;
}) {
  const [form, setForm] = useState<Partial<CarrierFiscalizer>>(initial || { isActive: true, isPrimary: false });
  const [settingsText, setSettingsText] = useState(
    initial?.settings && Object.keys(initial.settings).length ? JSON.stringify(initial.settings, null, 2) : ''
  );
  const [localError, setLocalError] = useState<string | null>(null);
  const contractOptions = contracts.filter((c) => !form.carrierId || c.carrierId === form.carrierId);

  const submit = () => {
    if (!form.carrierId || !form.fiscalizerId || !form.contractId) {
      setLocalError('Перевозчик, фискализатор и договор обязательны');
      return;
    }
    let settings: Record<string, unknown> = {};
    if (settingsText.trim()) {
      try {
        settings = JSON.parse(settingsText);
      } catch {
        setLocalError('SETTINGS: некорректный JSON');
        return;
      }
    }
    setLocalError(null);
    onSave({ ...form, settings });
  };

  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      {(error || localError) && <div className="form-error" style={{ marginBottom: 12 }}>{error || localError}</div>}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Перевозчик
          <select value={form.carrierId || ''} onChange={(e) => setForm({ ...form, carrierId: e.target.value, contractId: '' })}>
            <option value="">— выберите —</option>
            {carriers.map((c) => <option key={c.id} value={c.id}>{c.carrierName}</option>)}
          </select>
        </label>
        <label>Фискализатор
          <select value={form.fiscalizerId || ''} onChange={(e) => setForm({ ...form, fiscalizerId: e.target.value })}>
            <option value="">— выберите —</option>
            {fiscalizers.map((f) => <option key={f.fiscalizerId} value={f.fiscalizerId}>{f.fiscalizerName} ({f.fiscalizerCode})</option>)}
          </select>
        </label>
        <label>Договор
          <select value={form.contractId || ''} onChange={(e) => setForm({ ...form, contractId: e.target.value })}>
            <option value="">— выберите —</option>
            {contractOptions.map((c) => <option key={c.id} value={c.id}>{c.contractNumber}</option>)}
          </select>
        </label>
        <label>Custom API URL <input value={form.customApiUrl || ''} onChange={(e) => setForm({ ...form, customApiUrl: e.target.value })} placeholder="https://..." /></label>
        <label>Основной <input type="checkbox" checked={form.isPrimary ?? false} onChange={(e) => setForm({ ...form, isPrimary: e.target.checked })} /></label>
        <label>Активен <input type="checkbox" checked={form.isActive ?? true} onChange={(e) => setForm({ ...form, isActive: e.target.checked })} /></label>
        <label style={{ gridColumn: '1 / span 2' }}>Settings (JSON) <textarea value={settingsText} onChange={(e) => setSettingsText(e.target.value)} rows={4} placeholder="{ }" /></label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={submit}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}

/* -------------------------- Связи · дистрибьюторы -------------------------- */

function DistributorFiscalizersTab() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['distributor-fiscalizers'], queryFn: () => api.getDistributorFiscalizers() });
  const { data: fiscalizers } = useQuery({ queryKey: ['fiscalizers'], queryFn: api.getFiscalizers });
  const { data: distributors } = useQuery({ queryKey: ['cards-distributors'], queryFn: () => getCardsDistributors() });
  const { data: contracts } = useQuery({ queryKey: ['contracts'], queryFn: () => getContracts() });
  const [edit, setEdit] = useState<Partial<DistributorFiscalizer> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const invalidate = () => qc.invalidateQueries({ queryKey: ['distributor-fiscalizers'] });
  const createMut = useMutation({
    mutationFn: api.createDistributorFiscalizer,
    onSuccess: () => { invalidate(); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(errMsg(e, 'Ошибка создания')),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<DistributorFiscalizer> }) => api.updateDistributorFiscalizer(id, data),
    onSuccess: () => { invalidate(); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(errMsg(e, 'Ошибка обновления')),
  });
  const deleteMut = useMutation({
    mutationFn: api.deleteDistributorFiscalizer,
    onSuccess: invalidate,
    onError: (e: any) => alert(errText(e)),
  });

  const fiscalizerName = (id: string) => fiscalizers?.find((f) => f.fiscalizerId === id)?.fiscalizerName || id;
  const distributorName = (id: string) => distributors?.find((d) => d.id === id)?.distributorName || id;
  const contractNumber = (id: string) => contracts?.find((c) => c.id === id)?.contractNumber || id;

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>+ Добавить связь</button>
      </div>
      {(showForm || edit) && (
        <DistributorFiscalizerForm
          initial={edit}
          fiscalizers={fiscalizers || []}
          distributors={distributors || []}
          contracts={contracts || []}
          error={formError}
          onSave={(d) => edit?.distributorFiscalizerId ? updateMut.mutate({ id: edit.distributorFiscalizerId, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); setFormError(null); }}
        />
      )}
      <table className="data-table">
        <thead>
          <tr><th>Дистрибьютор</th><th>Фискализатор</th><th>Договор</th><th>Custom API URL</th><th>Основной</th><th>Активен</th><th /></tr>
        </thead>
        <tbody>
          {data?.map((c) => (
            <tr key={c.distributorFiscalizerId}>
              <td>{distributorName(c.cardsDistributorId)}</td>
              <td>{fiscalizerName(c.fiscalizerId)}</td>
              <td>{contractNumber(c.contractId)}</td>
              <td>{c.customApiUrl || '—'}</td>
              <td>{c.isPrimary ? '✅' : '—'}</td>
              <td>{c.isActive ? '✅' : '❌'}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => { setEdit(c); setFormError(null); }}>✎</button>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить связь?')) deleteMut.mutate(c.distributorFiscalizerId); }}>✕</button>
              </td>
            </tr>
          ))}
          {(!data || data.length === 0) && <tr><td colSpan={7} style={{ textAlign: 'center', color: '#9ca3af' }}>Нет записей</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

function DistributorFiscalizerForm({ initial, fiscalizers, distributors, contracts, onSave, onCancel, error }: {
  initial?: Partial<DistributorFiscalizer> | null;
  fiscalizers: Fiscalizer[];
  distributors: { id: string; distributorName: string }[];
  contracts: { id: string; contractNumber: string; cardsDistributorId?: string }[];
  onSave: (d: Partial<DistributorFiscalizer>) => void;
  onCancel: () => void;
  error?: string | null;
}) {
  const [form, setForm] = useState<Partial<DistributorFiscalizer>>(initial || { isActive: true, isPrimary: false });
  const [settingsText, setSettingsText] = useState(
    initial?.settings && Object.keys(initial.settings).length ? JSON.stringify(initial.settings, null, 2) : ''
  );
  const [localError, setLocalError] = useState<string | null>(null);
  const contractOptions = contracts.filter((c) => !form.cardsDistributorId || c.cardsDistributorId === form.cardsDistributorId);

  const submit = () => {
    if (!form.cardsDistributorId || !form.fiscalizerId || !form.contractId) {
      setLocalError('Дистрибьютор, фискализатор и договор обязательны');
      return;
    }
    let settings: Record<string, unknown> = {};
    if (settingsText.trim()) {
      try {
        settings = JSON.parse(settingsText);
      } catch {
        setLocalError('SETTINGS: некорректный JSON');
        return;
      }
    }
    setLocalError(null);
    onSave({ ...form, settings });
  };

  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      {(error || localError) && <div className="form-error" style={{ marginBottom: 12 }}>{error || localError}</div>}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Дистрибьютор
          <select value={form.cardsDistributorId || ''} onChange={(e) => setForm({ ...form, cardsDistributorId: e.target.value, contractId: '' })}>
            <option value="">— выберите —</option>
            {distributors.map((d) => <option key={d.id} value={d.id}>{d.distributorName}</option>)}
          </select>
        </label>
        <label>Фискализатор
          <select value={form.fiscalizerId || ''} onChange={(e) => setForm({ ...form, fiscalizerId: e.target.value })}>
            <option value="">— выберите —</option>
            {fiscalizers.map((f) => <option key={f.fiscalizerId} value={f.fiscalizerId}>{f.fiscalizerName} ({f.fiscalizerCode})</option>)}
          </select>
        </label>
        <label>Договор
          <select value={form.contractId || ''} onChange={(e) => setForm({ ...form, contractId: e.target.value })}>
            <option value="">— выберите —</option>
            {contractOptions.map((c) => <option key={c.id} value={c.id}>{c.contractNumber}</option>)}
          </select>
        </label>
        <label>Custom API URL <input value={form.customApiUrl || ''} onChange={(e) => setForm({ ...form, customApiUrl: e.target.value })} placeholder="https://..." /></label>
        <label>Основной <input type="checkbox" checked={form.isPrimary ?? false} onChange={(e) => setForm({ ...form, isPrimary: e.target.checked })} /></label>
        <label>Активен <input type="checkbox" checked={form.isActive ?? true} onChange={(e) => setForm({ ...form, isActive: e.target.checked })} /></label>
        <label style={{ gridColumn: '1 / span 2' }}>Settings (JSON) <textarea value={settingsText} onChange={(e) => setSettingsText(e.target.value)} rows={4} placeholder="{ }" /></label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={submit}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}

/* ---------------------------------- Токены ---------------------------------- */

function TokensTab() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['fiscalizer-tokens'], queryFn: () => api.getFiscalizerTokens() });
  const { data: carrierLinks } = useQuery({ queryKey: ['carrier-fiscalizers'], queryFn: () => api.getCarrierFiscalizers() });
  const { data: distributorLinks } = useQuery({ queryKey: ['distributor-fiscalizers'], queryFn: () => api.getDistributorFiscalizers() });
  const { data: fiscalizers } = useQuery({ queryKey: ['fiscalizers'], queryFn: api.getFiscalizers });
  const { data: carriers } = useQuery({ queryKey: ['carriers'], queryFn: () => getCarriers() });
  const { data: distributors } = useQuery({ queryKey: ['cards-distributors'], queryFn: () => getCardsDistributors() });
  const [edit, setEdit] = useState<FiscalizerToken | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const invalidate = () => qc.invalidateQueries({ queryKey: ['fiscalizer-tokens'] });

  const fiscalizerName = (id: string) => fiscalizers?.find((f) => f.fiscalizerId === id)?.fiscalizerName || id;
  const carrierName = (id: string) => carriers?.find((c) => c.id === id)?.carrierName || id;
  const distributorName = (id: string) => distributors?.find((d) => d.id === id)?.distributorName || id;
  const linkLabel = (t: FiscalizerToken) => {
    if (t.carrierFiscalizerId) {
      const l = carrierLinks?.find((c) => c.carrierFiscalizerId === t.carrierFiscalizerId);
      return l ? `Перевозчик: ${carrierName(l.carrierId)} · ${fiscalizerName(l.fiscalizerId)}` : `Перевозчик · ${t.carrierFiscalizerId}`;
    }
    if (t.distributorFiscalizerId) {
      const l = distributorLinks?.find((c) => c.distributorFiscalizerId === t.distributorFiscalizerId);
      return l ? `Дистрибьютор: ${distributorName(l.cardsDistributorId)} · ${fiscalizerName(l.fiscalizerId)}` : `Дистрибьютор · ${t.distributorFiscalizerId}`;
    }
    return '—';
  };

  const createMut = useMutation({
    mutationFn: api.createFiscalizerToken,
    onSuccess: () => { invalidate(); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(errMsg(e, 'Ошибка создания')),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Record<string, unknown> }) => api.updateFiscalizerToken(id, data),
    onSuccess: () => { invalidate(); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(errMsg(e, 'Ошибка обновления')),
  });
  const deleteMut = useMutation({
    mutationFn: api.deleteFiscalizerToken,
    onSuccess: invalidate,
    onError: (e: any) => alert(errText(e)),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <span style={{ color: '#6b7280', fontSize: 13 }}>Токены хранятся зашифрованными и НЕ синхронизируются на терминалы/дистрибьюторов.</span>
        <button className="btn-primary" onClick={() => { setEdit(null); setShowForm(true); setFormError(null); }}>+ Добавить токен</button>
      </div>
      {(showForm || edit) && (
        <TokenForm
          initial={edit}
          carrierLinks={carrierLinks || []}
          distributorLinks={distributorLinks || []}
          fiscalizers={fiscalizers || []}
          carriers={carriers || []}
          distributors={distributors || []}
          error={formError}
          onSave={(d) => edit ? updateMut.mutate({ id: edit.tokenId, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); setFormError(null); }}
        />
      )}
      <table className="data-table">
        <thead>
          <tr><th>Привязка</th><th>Подсказка</th><th>Действует с</th><th>Действует до</th><th>Статус</th><th /></tr>
        </thead>
        <tbody>
          {data?.map((t) => (
            <tr key={t.tokenId}>
              <td>{linkLabel(t)}</td>
              <td>{t.tokenHint || '—'}</td>
              <td>{fmtDate(t.validFrom)}</td>
              <td>{fmtDate(t.validUntil)}</td>
              <td>{t.status}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => { setEdit(t); setShowForm(false); setFormError(null); }}>✎</button>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить токен?')) deleteMut.mutate(t.tokenId); }}>✕</button>
              </td>
            </tr>
          ))}
          {(!data || data.length === 0) && <tr><td colSpan={6} style={{ textAlign: 'center', color: '#9ca3af' }}>Нет записей</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

function TokenForm({ initial, carrierLinks, distributorLinks, fiscalizers, carriers, distributors, onSave, onCancel, error }: {
  initial?: FiscalizerToken | null;
  carrierLinks: CarrierFiscalizer[];
  distributorLinks: DistributorFiscalizer[];
  fiscalizers: Fiscalizer[];
  carriers: { id: string; carrierName: string }[];
  distributors: { id: string; distributorName: string }[];
  onSave: (d: Record<string, unknown>) => void;
  onCancel: () => void;
  error?: string | null;
}) {
  const fiscalizerName = (id: string) => fiscalizers.find((f) => f.fiscalizerId === id)?.fiscalizerName || id;
  const carrierName = (id: string) => carriers.find((c) => c.id === id)?.carrierName || id;
  const distributorName = (id: string) => distributors.find((d) => d.id === id)?.distributorName || id;
  const initialLink = initial?.carrierFiscalizerId
    ? `c:${initial.carrierFiscalizerId}`
    : initial?.distributorFiscalizerId
      ? `d:${initial.distributorFiscalizerId}`
      : '';
  const [link, setLink] = useState(initialLink);
  const [token, setToken] = useState('');
  const [validFrom, setValidFrom] = useState(toLocalInput(initial?.validFrom));
  const [validUntil, setValidUntil] = useState(toLocalInput(initial?.validUntil));
  const [status, setStatus] = useState(initial?.status || 'ACTIVE');
  const [localError, setLocalError] = useState<string | null>(null);

  const submit = () => {
    if (!link) {
      setLocalError('Выберите привязку');
      return;
    }
    if (!initial && !token.trim()) {
      setLocalError('Введите токен');
      return;
    }
    const [kind, id] = link.split(':');
    const payload: Record<string, unknown> = {
      carrierFiscalizerId: kind === 'c' ? id : null,
      distributorFiscalizerId: kind === 'd' ? id : null,
      validFrom: fromLocalInput(validFrom),
      validUntil: fromLocalInput(validUntil),
      status,
    };
    if (token.trim()) payload.token = token.trim();
    setLocalError(null);
    onSave(payload);
  };

  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      {(error || localError) && <div className="form-error" style={{ marginBottom: 12 }}>{error || localError}</div>}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Привязка
          <select value={link} onChange={(e) => setLink(e.target.value)}>
            <option value="">— выберите —</option>
            <optgroup label="Перевозчики">
              {carrierLinks.map((l) => (
                <option key={l.carrierFiscalizerId} value={`c:${l.carrierFiscalizerId}`}>{carrierName(l.carrierId)} · {fiscalizerName(l.fiscalizerId)}</option>
              ))}
            </optgroup>
            <optgroup label="Дистрибьюторы">
              {distributorLinks.map((l) => (
                <option key={l.distributorFiscalizerId} value={`d:${l.distributorFiscalizerId}`}>{distributorName(l.cardsDistributorId)} · {fiscalizerName(l.fiscalizerId)}</option>
              ))}
            </optgroup>
          </select>
        </label>
        <label>Статус
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="ACTIVE">ACTIVE</option>
            <option value="EXPIRED">EXPIRED</option>
            <option value="REVOKED">REVOKED</option>
          </select>
        </label>
        <label style={{ gridColumn: '1 / span 2' }}>
          Токен {initial ? '(оставьте пустым, чтобы не менять)' : ''}
          <input value={token} onChange={(e) => setToken(e.target.value)} placeholder={initial ? '•••• (не изменять)' : 'plaintext токен'} />
        </label>
        <label>Действует с <input type="datetime-local" value={validFrom} onChange={(e) => setValidFrom(e.target.value)} /></label>
        <label>Действует до <input type="datetime-local" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} /></label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={submit}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
