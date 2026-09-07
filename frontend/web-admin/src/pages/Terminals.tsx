import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getTerminals } from '../api/terminals';
import { getCarriers } from '../api/carriers';
import { getCardsDistributors } from '../api/cardsDistributors';
import { getTerminalProfiles, createTerminalProfile, updateTerminalProfile, deleteTerminalProfile } from '../api/terminalProfiles';
import { getDistributorTerminals, createDistributorTerminal, updateDistributorTerminal, deleteDistributorTerminal } from '../api/distributorTerminals';
import type { TerminalProfile, DistributorTerminal } from '../types/reference';
import { useGlobalFilter } from '../contexts/GlobalFilterContext';

type Tab = 'carrier' | 'distributor' | 'profiles';

const TABS: { key: Tab; label: string }[] = [
  { key: 'carrier', label: 'Перевозчики' },
  { key: 'distributor', label: 'Дистрибьюторы' },
  { key: 'profiles', label: 'Профили' },
];

export function TerminalsPage() {
  const [tab, setTab] = useState<Tab>('carrier');
  return (
    <div>
      <div className="page-header">
        <h1>Терминалы</h1>
      </div>
      <div className="tabs" style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
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
      {tab === 'carrier' && <CarrierTerminalsTab />}
      {tab === 'distributor' && <DistributorTerminalsTab />}
      {tab === 'profiles' && <TerminalProfilesTab />}
    </div>
  );
}

function CarrierTerminalsTab() {
  const { regionId, carrierId } = useGlobalFilter();
  const { data: carriers } = useQuery({ queryKey: ['carriers'], queryFn: () => getCarriers() });
  const { data: profiles } = useQuery({ queryKey: ['terminal-profiles'], queryFn: getTerminalProfiles });
  const { data, isLoading, error } = useQuery({
    queryKey: ['terminals', carrierId, regionId],
    queryFn: () => getTerminals(carrierId || undefined, regionId || undefined),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  const carrierName = (id?: string) => {
    if (!id) return '—';
    return carriers?.find((c) => c.id === id)?.carrierName || id.slice(0, 8) + '…';
  };
  const profileName = (id?: string) => {
    if (!id) return '—';
    return profiles?.find((p) => p.profileId === id)?.profileName || id.slice(0, 8) + '…';
  };

  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>Серийный номер</th>
          <th>Номер</th>
          <th>Модель</th>
          <th>Перевозчик</th>
          <th>Профиль</th>
          <th>Часовой пояс</th>
          <th>Статус</th>
          <th>Создан</th>
        </tr>
      </thead>
      <tbody>
        {data?.map((t) => (
          <tr key={t.id}>
            <td><code>{t.terminalSerial}</code></td>
            <td>{t.terminalNumber || '—'}</td>
            <td>{t.terminalModel || '—'}</td>
            <td>{carrierName(t.carrierId)}</td>
            <td>{profileName(t.profileId)}</td>
            <td>{t.timezone || '—'}</td>
            <td><span className={`badge badge-${t.status.toLowerCase()}`}>{t.status}</span></td>
            <td>{new Date(t.createdAt).toLocaleString()}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function DistributorTerminalsTab() {
  const qc = useQueryClient();
  const { cardsDistributorId: globalDistributorId } = useGlobalFilter();
  const [distributorId, setDistributorId] = useState<string>(globalDistributorId || '');
  const { data: distributors } = useQuery({ queryKey: ['cards-distributors'], queryFn: getCardsDistributors });
  const { data, isLoading, error } = useQuery({
    queryKey: ['distributor-terminals', distributorId],
    queryFn: () => getDistributorTerminals(distributorId || undefined),
  });
  const [edit, setEdit] = useState<Partial<DistributorTerminal> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const createMut = useMutation({
    mutationFn: (d: Partial<DistributorTerminal>) => createDistributorTerminal(d as any),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['distributor-terminals'] }); setShowForm(false); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.response?.data?.message || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<DistributorTerminal> }) => updateDistributorTerminal(id, data as any),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['distributor-terminals'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.response?.data?.message || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deleteDistributorTerminal,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['distributor-terminals'] }),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  const distributorName = (id?: string | null) => {
    if (!id) return '—';
    return distributors?.find((d) => d.id === id)?.distributorName || id.slice(0, 8) + '…';
  };

  return (
    <div>
      <div style={{ display: 'flex', gap: 16, alignItems: 'center', marginBottom: 12 }}>
        <label>
          Дистрибьютор
          <select
            style={{ marginLeft: 8 }}
            value={distributorId}
            onChange={(e) => { setDistributorId(e.target.value); setShowForm(false); setEdit(null); }}
          >
            <option value="">Все</option>
            {distributors?.map((d) => (
              <option key={d.id} value={d.id}>{d.distributorName}</option>
            ))}
          </select>
        </label>
        <button
          className="btn-primary"
          onClick={() => { setEdit({ cardsDistributorId: distributorId || undefined }); setShowForm(true); setFormError(null); }}
        >+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <DistributorTerminalForm
          initial={edit}
          distributors={distributors || []}
          error={formError}
          onSave={(d) => edit?.distributorTerminalId ? updateMut.mutate({ id: edit.distributorTerminalId!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); setFormError(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Номер</th>
            <th>Серийный номер</th>
            <th>Модель</th>
            <th>Дистрибьютор</th>
            <th>Провайдер</th>
            <th>Статус</th>
            <th>Профиль</th>
            <th>Создан</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((t) => (
            <tr key={t.distributorTerminalId}>
              <td>{t.terminalNumber}</td>
              <td><code>{t.terminalSerial}</code></td>
              <td>{t.terminalModel || '—'}</td>
              <td>{distributorName(t.cardsDistributorId)}</td>
              <td>{t.paymentProviderId}</td>
              <td><span className={`badge badge-${t.status.toLowerCase()}`}>{t.status}</span></td>
              <td>{t.profileId?.slice(0, 8) + '…' || '—'}</td>
              <td>{new Date(t.createdAt).toLocaleString()}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => { setEdit(t); setFormError(null); }}>✎</button>
                <button
                  className="btn-danger btn-sm"
                  onClick={() => { if (confirm('Удалить терминал дистрибьютора?')) deleteMut.mutate(t.distributorTerminalId); }}
                >✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function DistributorTerminalForm({ initial, distributors, onSave, onCancel, error }: {
  initial?: Partial<DistributorTerminal> | null;
  distributors: { id: string; distributorName: string }[];
  onSave: (d: Partial<DistributorTerminal>) => void;
  onCancel: () => void;
  error?: string | null;
}) {
  const [form, setForm] = useState<Partial<DistributorTerminal>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      {error && <div className="form-error" style={{ marginBottom: 12 }}>{error}</div>}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>
          Дистрибьютор
          <select
            value={form.cardsDistributorId || ''}
            onChange={(e) => setForm({ ...form, cardsDistributorId: e.target.value || undefined })}
          >
            <option value="">—</option>
            {distributors.map((d) => (
              <option key={d.id} value={d.id}>{d.distributorName}</option>
            ))}
          </select>
        </label>
        <label>Номер терминала <input value={form.terminalNumber || ''} onChange={(e) => setForm({ ...form, terminalNumber: e.target.value })} /></label>
        <label>Серийный номер <input value={form.terminalSerial || ''} onChange={(e) => setForm({ ...form, terminalSerial: e.target.value })} /></label>
        <label>Модель <input value={form.terminalModel || ''} onChange={(e) => setForm({ ...form, terminalModel: e.target.value })} /></label>
        <label>Провайдер <input value={form.paymentProviderId || ''} onChange={(e) => setForm({ ...form, paymentProviderId: e.target.value })} /></label>
        <label>
          Статус
          <select value={form.status || 'WAREHOUSE'} onChange={(e) => setForm({ ...form, status: e.target.value })}>
            {['WAREHOUSE', 'ACTIVE', 'BLOCKED', 'RETIRED'].map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </label>
        <label>Contract ID <input value={form.contractId || ''} onChange={(e) => setForm({ ...form, contractId: e.target.value || null })} /></label>
        <label>Profile ID <input value={form.profileId || ''} onChange={(e) => setForm({ ...form, profileId: e.target.value || null })} /></label>
        <label>MOL User ID <input value={form.molUserId || ''} onChange={(e) => setForm({ ...form, molUserId: e.target.value || null })} /></label>
        <label>SoftwareVersion ID <input value={form.softwareVersionId || ''} onChange={(e) => setForm({ ...form, softwareVersionId: e.target.value || null })} /></label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}

function TerminalProfilesTab() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['terminal-profiles'], queryFn: getTerminalProfiles });
  const [edit, setEdit] = useState<Partial<TerminalProfile> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const createMut = useMutation({
    mutationFn: (d: Partial<TerminalProfile>) => createTerminalProfile(d as any),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['terminal-profiles'] }); setShowForm(false); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.response?.data?.message || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<TerminalProfile> }) => updateTerminalProfile(id, data as any),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['terminal-profiles'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.response?.data?.message || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deleteTerminalProfile,
    onError: (e: any) => {
      const msg = e?.response?.data?.error || e?.response?.data?.message || e?.message;
      setFormError(msg || 'Ошибка удаления');
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['terminal-profiles'] }),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      {formError && <div className="form-error" style={{ marginBottom: 12 }}>{formError}</div>}
      <div className="page-header" style={{ marginBottom: 12 }}>
        <button
          className="btn-primary"
          onClick={() => { setEdit({ profileParams: {} }); setShowForm(true); setFormError(null); }}
        >+ Добавить профиль</button>
      </div>

      {(showForm || edit) && (
        <TerminalProfileForm
          initial={edit}
          error={formError}
          onSave={(d) => edit?.profileId ? updateMut.mutate({ id: edit.profileId!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); setFormError(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Название</th>
            <th>Параметры (JSON)</th>
            <th>Базовый</th>
            <th>Обновлено</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {(data || []).map((p) => (
            <tr key={p.profileId}>
              <td>{p.profileName}</td>
              <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{JSON.stringify(p.profileParams)}</td>
              <td>{p.isBase ? '✓' : ''}</td>
              <td>{new Date(p.updatedAt).toLocaleString()}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => { setEdit(p); setFormError(null); }}>✎</button>
                <button
                  className="btn-danger btn-sm"
                  disabled={p.isBase}
                  title={p.isBase ? 'Базовый профиль удалить нельзя' : ''}
                  onClick={() => { setFormError(null); if (confirm('Удалить профиль?')) deleteMut.mutate(p.profileId); }}
                >✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TerminalProfileForm({ initial, onSave, onCancel, error }: {
  initial?: Partial<TerminalProfile> | null;
  onSave: (d: Partial<TerminalProfile>) => void;
  onCancel: () => void;
  error?: string | null;
}) {
  const [form, setForm] = useState<Partial<TerminalProfile>>(initial || {});
  const [paramsText, setParamsText] = useState<string>(JSON.stringify(initial?.profileParams || {}, null, 2));
  const [jsonError, setJsonError] = useState<string | null>(null);

  const commit = () => {
    if (!form.profileName?.trim()) {
      setJsonError('Название профиля обязательно');
      return;
    }
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(paramsText);
    } catch (e: any) {
      setJsonError('Некорректный JSON: ' + e.message);
      return;
    }
    setJsonError(null);
    onSave({ ...form, profileParams: parsed });
  };

  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      {error && <div className="form-error" style={{ marginBottom: 12 }}>{error}</div>}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px', alignItems: 'center' }}>
        <label>Название <input value={form.profileName || ''} onChange={(e) => setForm({ ...form, profileName: e.target.value })} /></label>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <input
            type="checkbox"
            checked={form.isBase || false}
            onChange={(e) => setForm({ ...form, isBase: e.target.checked })}
          /> Базовый профиль
        </label>
      </div>
      <label style={{ marginTop: 16, display: 'block' }}>
        Параметры (JSON)
        <textarea
          value={paramsText}
          onChange={(e) => setParamsText(e.target.value)}
          rows={10}
          style={{ width: '100%', fontFamily: 'monospace', fontSize: 12, marginTop: 4 }}
        />
      </label>
      <div style={{ fontSize: 12, color: '#666', marginTop: 4 }}>
        Ключи: syncIntervalMs, eventPollIntervalMs, deltaSyncIntervalMs, deltaPollIntervalMs, watermarkIntervalMs (мс)
      </div>
      {jsonError && <div className="form-error" style={{ marginTop: 8 }}>{jsonError}</div>}
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={commit}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}