import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getTransactionResults, createTransactionResult, updateTransactionResult, deleteTransactionResult } from '../api/reference';
import type { TransactionResult } from '../types/reference';

export function TransactionResultsPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['transaction-results'], queryFn: getTransactionResults });
  const [edit, setEdit] = useState<Partial<TransactionResult> | null>(null);
  const [showForm, setShowForm] = useState(false);

  const createMut = useMutation({ mutationFn: createTransactionResult, onSuccess: () => { qc.invalidateQueries({ queryKey: ['transaction-results'] }); setShowForm(false); } });
  const updateMut = useMutation({ mutationFn: ({ id, data }: { id: string; data: Partial<TransactionResult> }) => updateTransactionResult(id, data), onSuccess: () => { qc.invalidateQueries({ queryKey: ['transaction-results'] }); setEdit(null); } });
  const deleteMut = useMutation({ mutationFn: deleteTransactionResult, onSuccess: () => qc.invalidateQueries({ queryKey: ['transaction-results'] }) });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Результаты транзакций</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <TransactionResultForm
          initial={edit}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Название результата</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={r.id}>
              <td>{r.transactionResultName}</td>
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

function TransactionResultForm({ initial, onSave, onCancel }: { initial?: Partial<TransactionResult> | null; onSave: (d: Partial<TransactionResult>) => void; onCancel: () => void }) {
  const [form, setForm] = useState<Partial<TransactionResult>>(initial || {});
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <label>Название результата транзакции
        <input value={form.transactionResultName || ''} onChange={(e) => setForm({ ...form, transactionResultName: e.target.value })} />
      </label>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
