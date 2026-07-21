import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getCardsDistributors, createCardsDistributor, updateCardsDistributor, deleteCardsDistributor } from '../api/cardsDistributors';
import { getContracts, updateContract } from '../api/contracts';
import type { CardsDistributor } from '../types/reference';

export function CardsDistributorsPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['cards-distributors'], queryFn: getCardsDistributors });
  const [edit, setEdit] = useState<Partial<CardsDistributor> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [showContractBind, setShowContractBind] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const createMut = useMutation({
    mutationFn: (d: Partial<CardsDistributor>) => createCardsDistributor({
      ...d,
      distributorName: d.distributorName?.trim(),
      inn: d.inn?.trim(),
      kpp: d.kpp?.trim(),
    }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['cards-distributors'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.response?.data?.message || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<CardsDistributor> }) => updateCardsDistributor(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['cards-distributors'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.response?.data?.message || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deleteCardsDistributor,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cards-distributors'] })
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Дистрибьюторы карт</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <CardsDistributorForm
          initial={edit}
          error={formError}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); setFormError(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Название</th>
            <th>ИНН</th>
            <th>КПП</th>
            <th>Активен</th>
            <th>Договоры</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((d) => (
            <tr key={d.id}>
              <td>{d.distributorName}</td>
              <td>{d.inn}</td>
              <td>{d.kpp || '—'}</td>
              <td>{d.isActive ? '✅' : '❌'}</td>
              <td>{(d.contracts?.length || 0)} <button className="btn-secondary btn-sm" onClick={() => setShowContractBind(showContractBind === d.id ? null : d.id)}>Управлять</button></td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => { setEdit(d); setFormError(null); }}>✎</button>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(d.id); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {showContractBind && <ContractBindPanel distributorId={showContractBind} onClose={() => setShowContractBind(null)} />}
    </div>
  );
}

function CardsDistributorForm({ initial, onSave, onCancel, error }: { initial?: Partial<CardsDistributor> | null; onSave: (d: Partial<CardsDistributor>) => void; onCancel: () => void; error?: string | null }) {
  const [form, setForm] = useState<Partial<CardsDistributor>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      {error && <div className="form-error" style={{ marginBottom: 12 }}>{error}</div>}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Название <input value={form.distributorName || ''} onChange={(e) => setForm({ ...form, distributorName: e.target.value })} /></label>
        <label>ИНН <input value={form.inn || ''} onChange={(e) => setForm({ ...form, inn: e.target.value })} /></label>
        <label>КПП <input value={form.kpp || ''} onChange={(e) => setForm({ ...form, kpp: e.target.value })} /></label>
        <label>Юридический адрес <input value={form.legalAddress || ''} onChange={(e) => setForm({ ...form, legalAddress: e.target.value })} /></label>
        <label>Телефон <input value={form.contactPhone || ''} onChange={(e) => setForm({ ...form, contactPhone: e.target.value })} /></label>
        <label>Email <input value={form.contactEmail || ''} onChange={(e) => setForm({ ...form, contactEmail: e.target.value })} /></label>
        <label>Активен <input type="checkbox" checked={form.isActive ?? true} onChange={(e) => setForm({ ...form, isActive: e.target.checked })} /></label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}

function ContractBindPanel({ distributorId, onClose }: { distributorId: string; onClose: () => void }) {
  const qc = useQueryClient();
  const { data: contracts } = useQuery({ queryKey: ['contracts'], queryFn: getContracts });
  const { data: distributors } = useQuery({ queryKey: ['cards-distributors'], queryFn: getCardsDistributors });
  const distributor = distributors?.find((d) => d.id === distributorId);
  const boundIds = new Set(distributor?.contracts?.map((c) => c.id) || []);

  const bindMut = useMutation({
    mutationFn: (contractId: string) => updateContract(contractId, { cardsDistributorId: distributorId, clearCarrierId: true }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cards-distributors'] })
  });
  const unbindMut = useMutation({
    mutationFn: (contractId: string) => updateContract(contractId, { clearCardsDistributorId: true }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cards-distributors'] })
  });

  const available = contracts?.filter((c) => !boundIds.has(c.id) && !c.carrierId) || [];

  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h3 style={{ margin: 0 }}>Договоры дистрибьютора</h3>
        <button className="btn-secondary btn-sm" onClick={onClose}>Закрыть</button>
      </div>

      <table className="data-table" style={{ marginBottom: 12 }}>
        <thead>
          <tr><th>Номер</th><th>Статус</th><th>Дата начала</th><th /></tr>
        </thead>
        <tbody>
          {distributor?.contracts?.map((c) => (
            <tr key={c.id}>
              <td>{c.contractNumber}</td>
              <td>{c.status}</td>
              <td>{c.startDate}</td>
              <td><button className="btn-danger btn-sm" onClick={() => unbindMut.mutate(c.id)}>✕</button></td>
            </tr>
          ))}
          {(!distributor?.contracts || distributor.contracts.length === 0) && (
            <tr><td colSpan={4} style={{ textAlign: 'center', color: '#9ca3af' }}>Нет привязанных договоров</td></tr>
          )}
        </tbody>
      </table>

      <div style={{ display: 'flex', gap: 8 }}>
        <select
          value=""
          onChange={(e) => { if (e.target.value) bindMut.mutate(e.target.value); }}
          style={{ flex: 1, padding: '6px 10px', borderRadius: 6, border: '1px solid #d1d5db' }}
        >
          <option value="">Выберите договор...</option>
          {available.map((c) => (
            <option key={c.id} value={c.id}>{c.contractNumber} ({c.status})</option>
          ))}
        </select>
      </div>
    </div>
  );
}
