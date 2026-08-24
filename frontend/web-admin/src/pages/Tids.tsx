import { useState, useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getTids, createTid, updateTid, deleteTid } from '../api/tids';
import { getRegions } from '../api/reference';
import { getCarriers } from '../api/carriers';
import { getContracts } from '../api/contracts';
import { useGlobalFilter } from '../contexts/GlobalFilterContext';
import type { Tid, Carrier, Region, Contract } from '../types/reference';

/** Актуален ли договор (статус ACTIVE + даты действия покрывают сегодня). */
function isContractValid(c: Contract): boolean {
  if (c.status !== 'ACTIVE') return false;
  const today = new Date().toISOString().slice(0, 10);
  if (c.startDate > today) return false;
  if (c.endDate && c.endDate < today) return false;
  return true;
}

export function TidsPage() {
  const qc = useQueryClient();
  const { regionId: globalRegionId, carrierId: globalCarrierId } = useGlobalFilter();

  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const { data: carriers } = useQuery({ queryKey: ['carriers', globalRegionId], queryFn: () => getCarriers(globalRegionId || undefined) });
  const { data: contracts } = useQuery({ queryKey: ['contracts', '', ''], queryFn: () => getContracts() });

  const { data, isLoading, error } = useQuery({
    queryKey: ['tids', globalCarrierId, globalRegionId],
    queryFn: () => getTids(globalCarrierId || undefined, globalRegionId || undefined),
  });

  const [edit, setEdit] = useState<Partial<Tid> | null>(null);
  const [showForm, setShowForm] = useState(false);

  const createMut = useMutation({
    mutationFn: createTid,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['tids'] }); setShowForm(false); },
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<Tid> }) => updateTid(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['tids'] }); setEdit(null); },
  });
  const deleteMut = useMutation({
    mutationFn: deleteTid,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['tids'] }),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  const carrierName = (id: string) => carriers?.find((c) => c.id === id)?.carrierName || id;
  const contract = (id: string) => contracts?.find((c) => c.id === id);

  return (
    <div>
      <div className="page-header">
        <h1>TID (пулы)</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <TidForm
          initial={edit}
          regions={regions || []}
          carriers={carriers || []}
          contracts={(contracts || []).filter((c) => c.contractorType === 'BANK')}
          globalRegionId={globalRegionId}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d as Pick<Tid, 'contractId' | 'tidValue'>)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>TID</th>
            <th>Перевозчик</th>
            <th>Договор (банк)</th>
            <th>Терминал</th>
            <th>Статус</th>
            <th>Назначен</th>
            <th>Откреплён</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((t) => {
            const c = t.contractId ? contract(t.contractId) : undefined;
            return (
              <tr key={t.id}>
                <td><code>{t.tidValue}</code></td>
                <td>{carrierName(t.carrierId)}</td>
                <td>
                  {c ? (
                    <>
                      {c.contractNumber}
                      {!isContractValid(c) && <span style={{ color: '#dc2626' }}> ⚠ не актуален</span>}
                    </>
                  ) : '—'}
                </td>
                <td>{t.terminalId ? <a href={`/terminals/${t.terminalId}`}>{t.terminalId.slice(0, 8)}…</a> : '—'}</td>
                <td><span className={`badge badge-${t.status.toLowerCase()}`}>{t.status}</span></td>
                <td>{t.assignedAt ? new Date(t.assignedAt).toLocaleString() : '—'}</td>
                <td>{t.unassignedAt ? new Date(t.unassignedAt).toLocaleString() : '—'}</td>
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

function TidForm({ initial, regions, carriers, contracts, globalRegionId, onSave, onCancel }: {
  initial?: Partial<Tid> | null;
  regions: Region[];
  carriers: Carrier[];
  contracts: Contract[]; // только CONTRACTOR_TYPE='BANK'
  globalRegionId: string;
  onSave: (d: Partial<Tid>) => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<Partial<Tid & { regionId?: string }>>(() => {
    const f: Partial<Tid & { regionId?: string }> = { ...(initial || {}) };
    if (initial?.carrierId) {
      const c = carriers.find((x) => x.id === initial.carrierId);
      if (c) f.regionId = c.regionId;
    }
    if (!f.regionId && globalRegionId) f.regionId = globalRegionId;
    return f;
  });

  const effectiveRegionId = form.regionId || globalRegionId;
  const formCarriers = useMemo(() =>
    effectiveRegionId
      ? carriers.filter((c) => c.regionId === effectiveRegionId)
      : carriers,
    [carriers, effectiveRegionId],
  );
  // Актуальные банковские договоры выбранного перевозчика — только на них вешается TID.
  const formContracts = useMemo(() =>
    contracts.filter((c) => c.carrierId === form.carrierId && isContractValid(c)),
    [contracts, form.carrierId],
  );

  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Регион
          <select
            value={form.regionId || globalRegionId || ''}
            onChange={(e) => setForm({ ...form, regionId: e.target.value, carrierId: '', contractId: undefined })}
          >
            <option value="">— выберите регион —</option>
            {regions.map((r) => (
              <option key={r.id} value={r.id}>{r.municipalDivision}</option>
            ))}
          </select>
        </label>
        <label>Перевозчик
          <select
            value={form.carrierId || ''}
            onChange={(e) => setForm({ ...form, carrierId: e.target.value, contractId: undefined })}
          >
            <option value="">— выберите —</option>
            {formCarriers.map((c) => (
              <option key={c.id} value={c.id}>{c.carrierName}</option>
            ))}
          </select>
        </label>
        <label>Банковский договор (актуальный)
          <select
            value={form.contractId || ''}
            onChange={(e) => setForm({ ...form, contractId: e.target.value })}
          >
            <option value="">— выберите —</option>
            {formContracts.map((c) => (
              <option key={c.id} value={c.id}>
                {c.contractNumber} ({c.startDate} → {c.endDate || '∞'})
              </option>
            ))}
          </select>
        </label>
        <label>TID значение
          <input value={form.tidValue || ''} onChange={(e) => setForm({ ...form, tidValue: e.target.value })} maxLength={20} />
        </label>
        {form.id && (
          <>
            <label>Статус
              <select value={form.status || 'UNUSED'} onChange={(e) => setForm({ ...form, status: e.target.value as Tid['status'] })}>
                <option value="UNUSED">UNUSED</option>
                <option value="ASSIGNED">ASSIGNED</option>
                <option value="REVOKED">REVOKED</option>
              </select>
            </label>
            <label>Терминал ID
              <input value={form.terminalId || ''} onChange={(e) => setForm({ ...form, terminalId: e.target.value || undefined })} />
            </label>
          </>
        )}
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => { const { regionId, ...rest } = form; onSave(rest); }}
          disabled={!form.contractId || !form.tidValue}>
          Сохранить
        </button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
