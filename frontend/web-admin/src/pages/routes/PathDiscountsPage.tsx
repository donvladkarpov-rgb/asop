import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getPathDiscounts, createPathDiscount, updatePathDiscount, deletePathDiscount, getPaths, getVehicles } from '../../api/routes';
import { getCarriers, getTariffTypes } from '../../api/reference';
import type { PathDiscount } from '../../types/route';

const DISCOUNT_TYPES = ['PERCENT', 'FIXED'] as const;

export function PathDiscountsPage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['path-discounts'], queryFn: getPathDiscounts });
  const [edit, setEdit] = useState<Partial<PathDiscount> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const { data: paths } = useQuery({ queryKey: ['paths'], queryFn: getPaths });
  const { data: carriers } = useQuery({ queryKey: ['carriers'], queryFn: getCarriers });
  const { data: vehicles } = useQuery({ queryKey: ['vehicles'], queryFn: getVehicles });
  const { data: tariffTypes } = useQuery({ queryKey: ['tariff-types'], queryFn: getTariffTypes });

  const createMut = useMutation({
    mutationFn: createPathDiscount,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['path-discounts'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<PathDiscount> }) => updatePathDiscount(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['path-discounts'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deletePathDiscount,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['path-discounts'] }),
  });

  if (isLoading) return <div className="loading">Загрузка...</div>;
  if (error) return <div className="form-error">Ошибка: {(error as any).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Скидки путей</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>
          + Добавить
        </button>
      </div>

      {(showForm || edit) && (
        <div className="form-card">
          <h3>{edit?.id ? 'Редактировать' : 'Создать'} скидку пути</h3>
          {formError && <div className="form-error">{formError}</div>}
          <form onSubmit={(e) => {
            e.preventDefault();
            const form = e.currentTarget;
            const data = Object.fromEntries(new FormData(form)) as any;
            data.discountValue = Number(data.discountValue);
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
                <label>Название скидки</label>
                <input name="discountName" defaultValue={(edit as any)?.discountName || ''} required />
              </div>
              <div className="form-group">
                <label>Тип скидки</label>
                <select name="discountType" defaultValue={(edit as any)?.discountType || 'PERCENT'} required>
                  {DISCOUNT_TYPES.map((dt) => (
                    <option key={dt} value={dt}>{dt === 'PERCENT' ? 'Процент' : 'Фиксированная'}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Значение скидки</label>
                <input type="number" name="discountValue" defaultValue={(edit as any)?.discountValue || ''} required />
              </div>
              <div className="form-group">
                <label>Действует с</label>
                <input type="datetime-local" name="validFrom" defaultValue={(edit as any)?.validFrom || ''} required />
              </div>
              <div className="form-group">
                <label>Действует до</label>
                <input type="datetime-local" name="validUntil" defaultValue={(edit as any)?.validUntil || ''} />
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
            <th>Название скидки</th>
            <th>Тип</th>
            <th>Значение</th>
            <th>Действует с</th>
            <th>Действует до</th>
            <th>Активно</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((item) => (
            <tr key={item.id}>
              <td>{paths?.find((p: any) => p.id === item.pathId)?.pathName || item.pathId}</td>
              <td>{item.discountName}</td>
              <td>{item.discountType === 'PERCENT' ? 'Процент' : 'Фиксированная'}</td>
              <td>{item.discountValue}</td>
              <td>{item.validFrom}</td>
              <td>{item.validUntil || '—'}</td>
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
