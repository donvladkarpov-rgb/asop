import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getTransactionTypes, createTransactionType, updateTransactionType, deleteTransactionType } from '../api/reference';
import type { TransactionType } from '../types/reference';

export function TransactionTypesPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['transaction-types'], queryFn: getTransactionTypes });
  const [edit, setEdit] = useState<Partial<TransactionType> | null>(null);
  const [showForm, setShowForm] = useState(false);

  const createMut = useMutation({ mutationFn: createTransactionType, onSuccess: () => { qc.invalidateQueries({ queryKey: ['transaction-types'] }); setShowForm(false); } });
  const updateMut = useMutation({ mutationFn: ({ id, data }: { id: string; data: Partial<TransactionType> }) => updateTransactionType(id, data), onSuccess: () => { qc.invalidateQueries({ queryKey: ['transaction-types'] }); setEdit(null); } });
  const deleteMut = useMutation({ mutationFn: deleteTransactionType, onSuccess: () => qc.invalidateQueries({ queryKey: ['transaction-types'] }) });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Типы транзакций</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <TransactionTypeForm
          initial={edit}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Название типа транзакции</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={r.id}>
              <td>{r.transactionTypeName}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => setEdit(r)}>✎</button>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(r.id); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TransactionTypeForm({ initial, onSave, onCancel }: { initial?: Partial<TransactionType> | null; onSave: (d: Partial<TransactionType>) => void; onCancel: () => void }) {
  const [form, setForm] = useState<Partial<TransactionType>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <label>Название типа транзакции
        <input value={form.transactionTypeName || ''} onChange={(e) => setForm({ ...form, transactionTypeName: e.target.value })} />
      </label>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
