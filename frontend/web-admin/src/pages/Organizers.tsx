import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getOrganizers, createOrganizer, updateOrganizer, deleteOrganizer, getOrganizerTerritories, assignTerritory, unassignTerritory, getTerritories } from '../api/reference';
import type { Organizer } from '../types/reference';

export function OrganizersPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['organizers'], queryFn: getOrganizers });
  const [edit, setEdit] = useState<Partial<Organizer> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [manageId, setManageId] = useState<string | null>(null);

  const createMut = useMutation({ mutationFn: createOrganizer, onSuccess: () => { qc.invalidateQueries({ queryKey: ['organizers'] }); setShowForm(false); } });
  const updateMut = useMutation({ mutationFn: ({ id, data }: { id: string; data: Partial<Organizer> }) => updateOrganizer(id, data), onSuccess: () => { qc.invalidateQueries({ queryKey: ['organizers'] }); setEdit(null); } });
  const deleteMut = useMutation({ mutationFn: deleteOrganizer, onSuccess: () => qc.invalidateQueries({ queryKey: ['organizers'] }) });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Организаторы перевозок</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); }}>+ Добавить</button>
      </div>

      {(showForm || edit) && (
        <OrganizerForm
          initial={edit}
          onSave={(d) => edit?.id ? updateMut.mutate({ id: edit.id!, data: d }) : createMut.mutate(d)}
          onCancel={() => { setShowForm(false); setEdit(null); }}
        />
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Название</th>
            <th>Территории</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((o) => (
            <tr key={o.id}>
              <td>{o.organizerName}</td>
              <td><button className="btn-secondary btn-sm" onClick={() => setManageId(manageId === o.id ? null : o.id)}>Управлять</button></td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => setEdit(o)}>✎</button>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(o.id); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {manageId && <OrganizerTerritoryPanel organizerId={manageId} onClose={() => setManageId(null)} />}
    </div>
  );
}

function OrganizerForm({ initial, onSave, onCancel }: { initial?: Partial<Organizer> | null; onSave: (d: Partial<Organizer>) => void; onCancel: () => void }) {
  const [name, setName] = useState(initial?.organizerName || '');
  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <label>Название организатора
        <input value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave({ organizerName: name })}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}

function OrganizerTerritoryPanel({ organizerId, onClose }: { organizerId: string; onClose: () => void }) {
  const qc = useQueryClient();
  const { data: territories } = useQuery({ queryKey: ['organizer-territories', organizerId], queryFn: () => getOrganizerTerritories(organizerId) });
  const { data: allTerritories } = useQuery({ queryKey: ['territories'], queryFn: () => getTerritories() });
  const [selected, setSelected] = useState('');

  const assignMut = useMutation({
    mutationFn: (territoryId: string) => assignTerritory(organizerId, territoryId),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['organizer-territories'] }); setSelected(''); }
  });
  const unassignMut = useMutation({
    mutationFn: (territoryId: string) => unassignTerritory(organizerId, territoryId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['organizer-territories'] })
  });

  const assignedIds = new Set(territories?.map((t) => t.territoryId) || []);
  const available = allTerritories?.filter((t) => !assignedIds.has(t.id)) || [];

  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h3 style={{ margin: 0 }}>Территории организатора</h3>
        <button className="btn-secondary btn-sm" onClick={onClose}>Закрыть</button>
      </div>

      <table className="data-table" style={{ marginBottom: 12 }}>
        <thead>
          <tr><th>Территория</th><th>Регион</th><th /></tr>
        </thead>
        <tbody>
          {territories?.map((t) => (
            <tr key={t.territoryId}>
              <td>{t.territoryName || '—'}</td>
              <td>{t.regionName || '—'}</td>
              <td><button className="btn-danger btn-sm" onClick={() => unassignMut.mutate(t.territoryId)}>✕</button></td>
            </tr>
          ))}
          {(!territories || territories.length === 0) && (
            <tr><td colSpan={3} style={{ textAlign: 'center', color: '#9ca3af' }}>Нет назначенных территорий</td></tr>
          )}
        </tbody>
      </table>

      <div style={{ display: 'flex', gap: 8 }}>
        <select value={selected} onChange={(e) => setSelected(e.target.value)} style={{ flex: 1, padding: '6px 10px', borderRadius: 6, border: '1px solid #d1d5db' }}>
          <option value="">Выберите территорию...</option>
          {available.map((t) => <option key={t.id} value={t.id}>{t.municipalDivision}</option>)}
        </select>
        <button className="btn-primary btn-sm" disabled={!selected} onClick={() => assignMut.mutate(selected)}>Назначить</button>
      </div>
    </div>
  );
}
