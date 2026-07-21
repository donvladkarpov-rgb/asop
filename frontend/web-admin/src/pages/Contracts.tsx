import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getContracts, createContract, updateContract, deleteContract } from '../api/contracts';
import { getCarriers } from '../api/carriers';
import { getCardsDistributors } from '../api/cardsDistributors';
import type { Contract } from '../types/reference';

export function ContractsPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['contracts'], queryFn: getContracts });
  const { data: carriers } = useQuery({ queryKey: ['carriers'], queryFn: getCarriers });
  const { data: distributors } = useQuery({ queryKey: ['cards-distributors'], queryFn: getCardsDistributors });
  const [edit, setEdit] = useState<Partial<Contract> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState('');

  const createMut = useMutation({
    mutationFn: createContract,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['contracts'] }); setShowForm(false); setFormError(''); }
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<Contract> }) => updateContract(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['contracts'] }); setEdit(null); setFormError(''); }
  });
  const deleteMut = useMutation({
    mutationFn: deleteContract,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['contracts'] })
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Договоры</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <div className="form-card" style={{ marginBottom: 20 }}>
          {formError && <div style={{ color: 'red', marginBottom: 8 }}>{formError}</div>}
          <ContractForm
            initial={edit}
            carriers={carriers || []}
            distributors={distributors || []}
            onSave={(d) => {
              if (d.carrierId && d.cardsDistributorId) {
                setFormError('Можно заполнить только одно поле: либо Carrier, либо Distributor');
                return;
              }
              setFormError('');
              edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d);
            }}
            onCancel={() => { setShowForm(false); setEdit(null); setFormError(''); }}
          />
        </div>
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Номер</th>
            <th>Тип</th>
            <th>Контрагент</th>
            <th>Начало</th>
            <th>Окончание</th>
            <th>Статус</th>
            <th>Комиссия</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((c) => (
            <tr key={c.id}>
              <td>{c.contractNumber}</td>
              <td>{c.contractorType || '—'}</td>
              <td>
                {c.carrierId ? (carriers?.find((cr) => cr.id === c.carrierId)?.carrierName || '—')
                  : c.cardsDistributorId ? (distributors?.find((d) => d.id === c.cardsDistributorId)?.distributorName || '—')
                  : '—'}
              </td>
              <td>{c.startDate}</td>
              <td>{c.endDate || '—'}</td>
              <td>{c.status}</td>
              <td>{c.commissionPercent != null ? `${c.commissionPercent}%` : '—'}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => setEdit(c)}>✎</button>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(c.id); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ContractForm({ initial, carriers, distributors, onSave, onCancel }: {
  initial?: Partial<Contract> | null;
  carriers: { id: string; carrierName: string }[];
  distributors: { id: string; distributorName: string }[];
  onSave: (d: Partial<Contract>) => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<Partial<Contract>>(initial || {});
  const contractorType = form.contractorType || '';

  return (
    <div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Тип контрагента
          <select value={contractorType} onChange={(e) => setForm({ ...form, contractorType: e.target.value, carrierId: undefined, cardsDistributorId: undefined })}>
            <option value="">—</option>
            <option value="CARRIER">Перевозчик</option>
            <option value="CARDS_DISTRIBUTOR">Дистрибьютор</option>
          </select>
        </label>
        <label>Номер договора <input value={form.contractNumber || ''} onChange={(e) => setForm({ ...form, contractNumber: e.target.value })} /></label>
        {contractorType === 'CARRIER' ? (
          <label>Перевозчик
            <select value={form.carrierId || ''} onChange={(e) => setForm({ ...form, carrierId: e.target.value || undefined })}>
              <option value="">Выберите...</option>
              {carriers.map((c) => <option key={c.id} value={c.id}>{c.carrierName}</option>)}
            </select>
          </label>
        ) : contractorType === 'CARDS_DISTRIBUTOR' ? (
          <label>Дистрибьютор
            <select value={form.cardsDistributorId || ''} onChange={(e) => setForm({ ...form, cardsDistributorId: e.target.value || undefined })}>
              <option value="">Выберите...</option>
              {distributors.map((d) => <option key={d.id} value={d.id}>{d.distributorName}</option>)}
            </select>
          </label>
        ) : null}
        <label>Дата начала <input type="date" value={form.startDate || ''} onChange={(e) => setForm({ ...form, startDate: e.target.value })} /></label>
        <label>Дата окончания <input type="date" value={form.endDate || ''} onChange={(e) => setForm({ ...form, endDate: e.target.value || undefined })} /></label>
        <label>Статус
          <select value={form.status || 'ACTIVE'} onChange={(e) => setForm({ ...form, status: e.target.value })}>
            <option value="DRAFT">DRAFT</option>
            <option value="ACTIVE">ACTIVE</option>
            <option value="SUSPENDED">SUSPENDED</option>
            <option value="TERMINATED">TERMINATED</option>
          </select>
        </label>
        <label>Комиссия (%) <input type="number" value={form.commissionPercent ?? ''} onChange={(e) => setForm({ ...form, commissionPercent: e.target.value ? Number(e.target.value) : undefined })} /></label>
      </div>
      <label style={{ marginTop: 8, display: 'block' }}>Attributes (JSON)
        <textarea
          value={form.attributes || ''}
          onChange={(e) => setForm({ ...form, attributes: e.target.value || undefined })}
          rows={3}
          style={{ width: '100%', padding: '6px 10px', borderRadius: 6, border: '1px solid #d1d5db', fontFamily: 'monospace' }}
        />
      </label>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
