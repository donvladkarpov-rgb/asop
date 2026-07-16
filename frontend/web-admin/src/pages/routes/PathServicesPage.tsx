import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getPathServices, createPathService, updatePathService, deletePathService, getPaths, getVehicles } from '../../api/routes';
import { getCarriers, getServices, getTariffTypes } from '../../api/reference';
import type { PathService } from '../../types/route';

export function PathServicesPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['path-services'], queryFn: getPathServices });
  const [edit, setEdit] = useState<Partial<PathService> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const { data: paths } = useQuery({ queryKey: ['paths'], queryFn: getPaths });
  const { data: services } = useQuery({ queryKey: ['services'], queryFn: () => getServices() });
  const { data: carriers } = useQuery({ queryKey: ['carriers'], queryFn: getCarriers });
  const { data: vehicles } = useQuery({ queryKey: ['vehicles'], queryFn: getVehicles });
  const { data: tariffTypes } = useQuery({ queryKey: ['tariff-types'], queryFn: getTariffTypes });

  const createMut = useMutation({
    mutationFn: createPathService,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['path-services'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<PathService> }) => updatePathService(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['path-services'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deletePathService,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['path-services'] }),
  });

  if (isLoading) return <div className="loading">Загрузка...</div>;
  if (error) return <div className="form-error">Ошибка: {(error as any).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Услуги путей</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>
          + Добавить
        </button>
      </div>

      {(showForm || edit) && (
        <div className="form-card">
          <h3>{edit?.id ? 'Редактировать' : 'Создать'} услугу пути</h3>
          {formError && <div className="form-error">{formError}</div>}
          <form onSubmit={(e) => {
            e.preventDefault();
            const form = e.currentTarget;
            const data = Object.fromEntries(new FormData(form)) as any;
            data.price = Number(data.price);
            data.isActive = form.querySelector<HTMLInputElement>('input[name="isActive"]')?.checked || false;
            if (edit?.id) updateMut.mutate({ id: edit.id, data });
            else createMut.mutate(data);
          }}>
            <div className="form-grid">
              <div className="form-group">
                <label>Путь</label>
                <select name="pathId" defaultValue={(edit as any)?.pathId || ''} required>
                  <option value="">Выберите путь</option>
                  {paths?.map((p: any) => (
                    <option key={p.id} value={p.id}>{p.pathName}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Услуга</label>
                <select name="serviceId" defaultValue={(edit as any)?.serviceId || ''} required>
                  <option value="">Выберите услугу</option>
                  {services?.map((s: any) => (
                    <option key={s.id} value={s.id}>{s.serviceName}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Перевозчик</label>
                <select name="carrierId" defaultValue={(edit as any)?.carrierId || ''}>
                  <option value="">Без перевозчика</option>
                  {carriers?.map((c: any) => (
                    <option key={c.id} value={c.id}>{c.carrierName}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Транспортное средство</label>
                <select name="vehicleId" defaultValue={(edit as any)?.vehicleId || ''}>
                  <option value="">Не выбрано</option>
                  {vehicles?.map((v: any) => (
                    <option key={v.id} value={v.id}>{v.vehicleName || v.vehicleNumber}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Тип тарифа</label>
                <select name="tariffTypeId" defaultValue={(edit as any)?.tariffTypeId || ''}>
                  <option value="">Без типа тарифа</option>
                  {tariffTypes?.map((t: any) => (
                    <option key={t.id} value={t.id}>{t.name}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Цена</label>
                <input type="number" name="price" defaultValue={(edit as any)?.price || ''} required />
              </div>
              <div className="form-group">
                <label>
                  <input type="checkbox" name="isActive" defaultChecked={(edit as any)?.isActive ?? true} />
                  {' '}Активно
                </label>
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <button type="submit" className="btn-primary" disabled={createMut.isPending || updateMut.isPending}>
                Сохранить
              </button>
              <button type="button" className="btn-secondary" onClick={() => { setShowForm(false); setEdit(null); setFormError(null); }}>
                Отмена
              </button>
            </div>
          </form>
        </div>
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Путь</th>
            <th>Услуга</th>
            <th>Перевозчик</th>
            <th>ТС</th>
            <th>Тип тарифа</th>
            <th>Цена</th>
            <th>Активно</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((item) => (
            <tr key={item.id}>
              <td>{paths?.find((p: any) => p.id === item.pathId)?.pathName || item.pathId}</td>
              <td>{services?.find((s: any) => s.id === item.serviceId)?.serviceName || item.serviceId}</td>
              <td>{carriers?.find((c: any) => c.id === item.carrierId)?.carrierName || item.carrierId || '—'}</td>
              <td>{vehicles?.find((v: any) => v.id === item.vehicleId)?.vehicleName || item.vehicleId || '—'}</td>
              <td>{tariffTypes?.find((t: any) => t.id === item.tariffTypeId)?.name || item.tariffTypeId || '—'}</td>
              <td>{item.price}</td>
              <td>{item.isActive ? 'Да' : 'Нет'}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => { setEdit(item); setFormError(null); }}>✎</button>
                <button className="btn-danger btn-sm"
                  onClick={() => { if (confirm('Удалить?')) deleteMut.mutate(item.id); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
