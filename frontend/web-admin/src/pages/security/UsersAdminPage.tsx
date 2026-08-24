import { useEffect, useRef, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getAdminUsers, createAdminUser, updateAdminUser, deleteAdminUser } from '../../api/routes';
import { getCarriers } from '../../api/reference';
import type { AdminUser, AdminUserCreate } from '../../api/routes';
import { useGlobalFilter } from '../../contexts/GlobalFilterContext';

/** Человекочитаемая ошибка axios. */
function errText(e: unknown): string {
  const resp = (e as { response?: { data?: { message?: string; error?: string; errorMessage?: string } } })?.response?.data;
  if (resp?.message) return resp.message;
  if (resp?.errorMessage) return resp.errorMessage;
  if (resp?.error) return resp.error;
  return (e as Error).message || 'Неизвестная ошибка';
}

export function UsersAdminPage() {
  const qc = useQueryClient();
  const { regionId: globalRegionId, carrierId: globalCarrierId, cardsDistributorId: globalDistributorId } = useGlobalFilter();
  const { data, isLoading, error } = useQuery({ queryKey: ['admin-users', globalRegionId, globalCarrierId, globalDistributorId], queryFn: () => getAdminUsers({ regionId: globalRegionId || undefined, carrierId: globalCarrierId || undefined, cardsDistributorId: globalDistributorId || undefined }) });
  const { data: carriers } = useQuery({ queryKey: ['carriers', globalRegionId], queryFn: () => getCarriers(globalRegionId || undefined) });
  const [edit, setEdit] = useState<Partial<AdminUser> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const successTimer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(successTimer.current), []);

  const showSuccess = (msg: string) => {
    setSuccessMsg(msg);
    window.clearTimeout(successTimer.current);
    successTimer.current = window.setTimeout(() => setSuccessMsg(null), 3500);
  };

  const createMut = useMutation({
    mutationFn: (d: AdminUserCreate) => createAdminUser(d),
    onSuccess: (u) => {
      qc.invalidateQueries({ queryKey: ['admin-users'] });
      setShowForm(false);
      setFormError(null);
      const bindings: string[] = [];
      if (globalRegionId) bindings.push('регион');
      if (globalCarrierId) bindings.push('перевозчик');
      if (globalDistributorId) bindings.push('дистрибьютор');
      showSuccess(`✓ ${u.firstName} добавлен${bindings.length ? ` (+ привязка: ${bindings.join(', ')})` : ''}`);
    },
    onError: (e) => setFormError(errText(e)),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: AdminUserCreate }) => updateAdminUser(id, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-users'] });
      setEdit(null);
      setFormError(null);
      showSuccess('✓ Изменения сохранены');
    },
    onError: (e) => setFormError(errText(e)),
  });
  const deleteMut = useMutation({
    mutationFn: deleteAdminUser,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['admin-users'] }); showSuccess('✓ Пользователь удалён'); },
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  const carrierName = (id: string) => carriers?.find((c) => c.id === id)?.carrierName || id;

  return (
    <div>
      <div className="page-header">
        <h1>Пользователи</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>+ Добавить</button>
      </div>

      {successMsg && (
        <div style={{ color: '#16a34a', fontWeight: 600, margin: '0 0 12px', display: 'flex', alignItems: 'center', gap: 6 }}>
          {successMsg}
        </div>
      )}

      {(showForm || edit) && (
        <>
          <UserForm
            initial={edit}
            autoBindings={{
              regionId: globalRegionId || undefined,
              carrierId: globalCarrierId || undefined,
              carrierName: globalCarrierId ? carrierName(globalCarrierId) : undefined,
              cardsDistributorId: globalDistributorId || undefined,
            }}
            onSave={(d) => {
              // Авто-привязки из глобального фильтра — только при СОЗДАНИИ пользователя.
              if (edit?.id) {
                updateMut.mutate({ id: edit.id!, data: d as AdminUserCreate });
              } else {
                const payload = {
                  ...d,
                  regionIds: globalRegionId ? [globalRegionId] : undefined,
                  carrierIds: globalCarrierId ? [globalCarrierId] : undefined,
                  cardsDistributorIds: globalDistributorId ? [globalDistributorId] : undefined,
                } as AdminUserCreate;
                createMut.mutate(payload);
              }
            }}
            onCancel={() => { setShowForm(false); setEdit(null); setFormError(null); }}
          />
          {formError && (
            <div style={{ color: '#dc2626', marginTop: 8, fontWeight: 500 }}>
              ✕ Ошибка: {formError}
            </div>
          )}
        </>
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Фамилия (инициал)</th>
            <th>Имя</th>
            <th>Отчество (инициал)</th>
            <th>Телефон</th>
            <th>Keycloak ID</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={r.id}>
              <td>{r.lastNameInitial}.</td>
              <td>{r.firstName}</td>
              <td>{r.patronymicInitial ? r.patronymicInitial + '.' : ''}</td>
              <td>{r.phone || ''}</td>
              <td style={{ fontSize: '0.85em', opacity: 0.7 }}>{r.keycloakId || ''}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => { setEdit(r); setShowForm(false); setFormError(null); }}>✎</button>
                <button className="btn-danger btn-sm" onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(r.id); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

interface AutoBindings {
  regionId?: string;
  carrierId?: string;
  carrierName?: string;
  cardsDistributorId?: string;
}

function UserForm({ initial, autoBindings, onSave, onCancel }: {
  initial?: Partial<AdminUser> | null;
  autoBindings?: AutoBindings;
  onSave: (d: Partial<AdminUser>) => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<Partial<AdminUser>>(initial || {});
  const isCreate = !initial?.id;
  const bindingNotes: string[] = [];
  if (isCreate && autoBindings?.regionId) bindingNotes.push('регион (из фильтра)');
  if (isCreate && autoBindings?.carrierId) bindingNotes.push(`перевозчик${autoBindings.carrierName ? ` «${autoBindings.carrierName}»` : ''} (из фильтра)`);
  if (isCreate && autoBindings?.cardsDistributorId) bindingNotes.push('дистрибьютор карт (из фильтра)');

  return (
    <div className="form-card" style={{ marginBottom: 20 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
        <label>Имя <input value={form.firstName || ''} onChange={(e) => setForm({ ...form, firstName: e.target.value })} /></label>
        <label>Инициал фамилии <input value={form.lastNameInitial || ''} maxLength={1} onChange={(e) => setForm({ ...form, lastNameInitial: e.target.value })} /></label>
        <label>Инициал отчества <input value={form.patronymicInitial || ''} maxLength={1} onChange={(e) => setForm({ ...form, patronymicInitial: e.target.value })} /></label>
        <label>Телефон <input value={form.phone || ''} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></label>
      </div>
      {bindingNotes.length > 0 && (
        <div style={{ marginTop: 12, color: '#2563eb', fontSize: '0.9em' }}>
          ⓘ При сохранении пользователь будет автоматически привязан: {bindingNotes.join(', ')}
        </div>
      )}
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn-primary" onClick={() => onSave(form)} disabled={!form.firstName || !form.lastNameInitial}>Сохранить</button>
        <button className="btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}
