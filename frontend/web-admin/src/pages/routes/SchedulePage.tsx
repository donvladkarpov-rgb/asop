import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getSchedules, createSchedule, updateSchedule, deleteSchedule, getPaths, getTransportStops } from '../../api/routes';
import { getRegions } from '../../api/reference';
import type { Schedule } from '../../types/route';

const DAY_LABELS = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];
const DAY_BITS = [1, 2, 4, 8, 16, 32, 64];

function maskToDays(mask: number): boolean[] {
  return DAY_BITS.map((b) => (mask & b) !== 0);
}
function daysToMask(days: boolean[]): number {
  return days.reduce((m, checked, i) => (checked ? m | DAY_BITS[i] : m), 0);
}
function maskToDayNames(mask: number): string {
  return DAY_LABELS.filter((_, i) => (mask & DAY_BITS[i]) !== 0).join(', ') || '—';
}


export function SchedulePage() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['schedule'], queryFn: getSchedules });
  const [edit, setEdit] = useState<Partial<Schedule> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [dayMaskChecked, setDayMaskChecked] = useState<boolean[]>(maskToDays(127));

  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const { data: paths } = useQuery({ queryKey: ['paths'], queryFn: getPaths });
  const { data: stops } = useQuery({ queryKey: ['transport-stops'], queryFn: getTransportStops });

  const createMut = useMutation({
    mutationFn: createSchedule,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['schedule'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<Schedule> }) => updateSchedule(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['schedule'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deleteSchedule,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['schedule'] }),
  });

  if (isLoading) return <div className="loading">Загрузка...</div>;
  if (error) return <div className="form-error">Ошибка: {(error as any).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Расписание</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); setDayMaskChecked(maskToDays(127)); }}>
          + Добавить
        </button>
      </div>

      {(showForm || edit) && (
        <div className="form-card">
          <h3>{edit?.id ? 'Редактировать' : 'Создать'} расписание</h3>
          {formError && <div className="form-error">{formError}</div>}
          <form onSubmit={(e) => {
            e.preventDefault();
            const form = e.currentTarget;
            const data = Object.fromEntries(new FormData(form)) as any;
            data.dayMask = daysToMask(dayMaskChecked);
            data.dwellTimeSec = data.dwellTimeSec ? Number(data.dwellTimeSec) : null;
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
                <label>Остановка</label>
                <select name="stopId" defaultValue={(edit as any)?.stopId || ''} required>
                  <option value="">Выберите остановку</option>
                  {stops?.map((s: any) => (
                    <option key={s.id} value={s.id}>{s.stopName}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Дни недели</label>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 4 }}>
                  {DAY_LABELS.map((label, i) => (
                    <label key={i} style={{ display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer' }}>
                      <input type="checkbox" checked={dayMaskChecked[i]}
                        onChange={() => setDayMaskChecked(prev => prev.map((v, j) => j === i ? !v : v))} />
                      {label}
                    </label>
                  ))}
                </div>
              </div>
              <div className="form-group">
                <label>Время прибытия</label>
                <input type="time" name="arrivalTime" defaultValue={(edit as any)?.arrivalTime || ''} required />
              </div>
              <div className="form-group">
                <label>Время стоянки (сек)</label>
                <input type="number" name="dwellTimeSec" defaultValue={(edit as any)?.dwellTimeSec || ''} />
              </div>
              <div className="form-group">
                <label>Регион</label>
                <select name="regionId" defaultValue={(edit as any)?.regionId || ''} required>
                  <option value="">Выберите регион</option>
                  {regions?.map((r: any) => (
                    <option key={r.id} value={r.id}>{r.municipalDivision}</option>
                  ))}
                </select>
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
            <th>Остановка</th>
            <th>Маска дней</th>
            <th>Время прибытия</th>
            <th>Стоянка (сек)</th>
            <th>Регион</th>
            <th>Активно</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((item) => (
            <tr key={item.id}>
              <td>{paths?.find((p: any) => p.id === item.pathId)?.pathName || item.pathId}</td>
              <td>{stops?.find((s: any) => s.id === item.stopId)?.stopName || item.stopId}</td>
              <td>{maskToDayNames(item.dayMask)}</td>
              <td>{item.arrivalTime}</td>
              <td>{item.dwellTimeSec ?? '—'}</td>
              <td>{regions?.find((r: any) => r.id === item.regionId)?.municipalDivision || item.regionId}</td>
              <td>{item.isActive ? 'Да' : 'Нет'}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-secondary btn-sm" onClick={() => { setEdit(item); setFormError(null); setDayMaskChecked(maskToDays(item.dayMask)); }}>✎</button>
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
