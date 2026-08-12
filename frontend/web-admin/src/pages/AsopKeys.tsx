import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getAsopKeys, generateAsopKey, deleteAsopKey } from '../api/asopKeys';
import type { AsopKey } from '../types/reference';

export function AsopKeysPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['asop-keys'], queryFn: getAsopKeys });
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const generateMut = useMutation({
    mutationFn: generateAsopKey,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['asop-keys'] }); setErrorMsg(null); },
    onError: (e: any) => setErrorMsg(e?.response?.data?.error || e?.response?.data?.message || e?.message || 'Ошибка генерации ключа'),
  });
  const deleteMut = useMutation({
    mutationFn: deleteAsopKey,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['asop-keys'] }),
    onError: (e: any) => setErrorMsg(e?.response?.data?.error || e?.response?.data?.message || e?.message || 'Ошибка удаления'),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  const display = (d: AsopKey) =>
    d.keyMaterial && d.keyMaterial.length > 18 ? `${d.keyMaterial.slice(0, 12)}…${d.keyMaterial.slice(-8)}` : (d.keyMaterial || '—');

  return (
    <div>
      <div className="page-header">
        <h1>Ключи АСОП</h1>
        <button className="btn-primary" onClick={() => generateMut.mutate()} disabled={generateMut.isPending}>
          {generateMut.isPending ? 'Генерация…' : '+ Сгенерировать'}
        </button>
      </div>

      {errorMsg && <div className="form-error" style={{ marginBottom: 12 }}>{errorMsg}</div>}

      <table className="data-table">
        <thead>
          <tr>
            <th>ID ключа</th>
            <th>KEY_MATERIAL (зашифрован)</th>
            <th>Создан</th>
            <th>Удалён</th>
            <th>Версия</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {(data || []).map((d) => (
            <tr key={d.keyId}>
              <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{d.keyId}</td>
              <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{display(d)}</td>
              <td>{new Date(d.createdAt).toLocaleString()}</td>
              <td>{d.deletedAt ? new Date(d.deletedAt).toLocaleString() : '—'}</td>
              <td>{d.version ?? '—'}</td>
              <td>
                {!d.deletedAt && (
                  <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить ключ? (soft delete)')) deleteMut.mutate(d.keyId); }}>✕</button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}