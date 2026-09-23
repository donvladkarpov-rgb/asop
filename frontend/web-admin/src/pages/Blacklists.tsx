import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getBlacklists, blockCard, unblockCard, type BlacklistBlockRequest } from '../api/blacklist';

const BLOCK_TYPES = ['NEGATIVE_BALANCE', 'PERMANENT'];

export function BlacklistsPage() {
  const qc = useQueryClient();
  const [blockType, setBlockType] = useState<string>('');
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ['blacklists', blockType],
    queryFn: () => getBlacklists({ blockType: blockType || undefined }),
  });

  const blockMut = useMutation({
    mutationFn: (d: BlacklistBlockRequest) => blockCard({ ...d, cardId: d.cardId.trim() }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['blacklists'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.response?.data?.message || e?.response?.data?.detail || e?.message || 'Ошибка блокировки'),
  });

  const unblockMut = useMutation({
    mutationFn: unblockCard,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['blacklists'] })
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Чёрный список карт</h1>
        <button className="btn-primary" onClick={() => { setShowForm(true); setFormError(null); }}>+ Блокировать</button>
      </div>

      <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
        <select value={blockType} onChange={(e) => setBlockType(e.target.value)} style={{ padding: '6px 10px', borderRadius: 6, border: '1px solid #d1d5db' }}>
          <option value="">Все типы блокировки</option>
          {BLOCK_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
      </div>

      {showForm && (
        <BlacklistForm
          error={formError}
          onSave={(d) => blockMut.mutate(d)}
          onCancel={() => { setShowForm(false); setFormError(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Карта (cardId)</th>
            <th>Тип</th>
            <th>Заблокирована</th>
            <th>Связанный долг</th>
            <th>Авто-unblock</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {(data?.length || 0) === 0 && (
            <tr><td colSpan={6} style={{ textAlign: 'center', color: '#9ca3af' }}>Нет заблокированных карт</td></tr>
          )}
          {data?.map((b) => (
            <tr key={b.cardId}>
              <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{b.cardId}</td>
              <td>{b.blockType}</td>
              <td>{new Date(b.blockedAt).toLocaleString()}</td>
              <td>{b.relatedDebtId ? <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{b.relatedDebtId}</span> : '—'}</td>
              <td>{b.autoUnblockOnRecovery ? '✅' : '—'}</td>
              <td>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Снять блокировку?')) unblockMut.mutate(b.cardId); }}>
                  Снять
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function BlacklistForm({ onSave, onCancel, error }: { onSave: (d: BlacklistBlockRequest) => void; onCancel: () => void; error?: string | null }) {
  const [form, setForm] = useState<BlacklistBlockRequest>({
    cardId: '',
    blockType: 'PERMANENT',
    relatedDebtId: null,
    autoUnblockOnRecovery: false,
  });

  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      {error && <div className="form-error" style={{ marginBottom: 12 }}>{error}</div>}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label style={{ gridColumn: 'span 2' }}>
          Card ID (UUID) <input value={form.cardId} onChange={(e) => setForm({ ...form, cardId: e.target.value })} placeholder="00000000-0000-0000-0000-000000000000" />
        </label>
        <label>Тип блокировки
          <select value={form.blockType} onChange={(e) => setForm({ ...form, blockType: e.target.value })}>
            {BLOCK_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>
        <label>
          Связанный долг (опц.)
          <input value={form.relatedDebtId || ''} onChange={(e) => setForm({ ...form, relatedDebtId: e.target.value || null })} placeholder="UUID долга" />
        </label>
        <label>
          Авто-unblock при погашении
          <input type="checkbox" checked={form.autoUnblockOnRecovery} onChange={(e) => setForm({ ...form, autoUnblockOnRecovery: e.target.checked })} />
        </label>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)}>Заблокировать</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}