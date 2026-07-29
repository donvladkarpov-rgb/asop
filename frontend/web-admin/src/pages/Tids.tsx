import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getTids, createTid, updateTid, deleteTid } from '../api/tids';
import { getCarriers } from '../api/carriers';
import type { Tid, Carrier } from '../types/reference';

export function TidsPage() {
  const qc = useQueryClient();
  const [filterCarrierId, setFilterCarrierId] = useState('');
  const { data: carriers } = useQuery({ queryKey: ['carriers'], queryFn: getCarriers });
  const { data, isLoading, error } = useQuery({
    queryKey: ['tids', filterCarrierId],
    queryFn: () => getTids(filterCarrierId || undefined),
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

  return (
    <div>
      <div className="page-header">
        <h1>TID (пулы)</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
      </div>

      <div style={{ marginBottom: 16 }}>
        <label>Фильтр по перевозчику: </label>
        <select value={filterCarrierId} onChange={(e) => setFilterCarrierId(e.target.value)}>
          <option value="">Все</option>
          {carriers?.map((c) => (
            <option key={c.id} value={c.id}>{c.carrierName}</option>
          ))}
        </select>
      </div>

      {(showForm || edit) && (
        <TidForm
          initial={edit}
          carriers={carriers || []}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d as Pick<Tid, 'carrierId' | 'tidValue'>)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>TID</th>
            <th>Перевозчик</th>
            <th>Терминал</th>
            <th>Статус</th>
            <th>Назначен</th>
            <th>Откреплён</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((t) => (
            <tr key={t.id}>
              <td><code>{t.tidValue}</code></td>
              <td>{carrierName(t.carrierId)}</td>
              <td>{t.terminalId ? <a href={`/terminals/${t.terminalId}`}>{t.terminalId.slice(0, 8)}…</a> : '—'}</td>
              <td><span className={`badge badge-${t.status.toLowerCase()}`}>{t.status}</span></td>
              <td>{t.assignedAt ? new Date(t.assignedAt).toLocaleString() : '—'}</td>
              <td>{t.unassignedAt ? new Date(t.unassignedAt).toLocaleString() : '—'}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => setEdit(t)}>✎</button>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(t.id); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TidForm({ initial, carriers, onSave, onCancel }: {
  initial?: Partial<Tid> | null;
  carriers: Carrier[];
  onSave: (d: Partial<Tid>) => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<Partial<Tid>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Перевозчик
          <select value={form.carrierId || ''} onChange={(e) => setForm({ ...form, carrierId: e.target.value })}>
            <option value="">— выберите —</option>
            {carriers.map((c) => (
              <option key={c.id} value={c.id}>{c.carrierName}</option>
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
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
