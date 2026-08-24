import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useGlobalFilter } from '../../contexts/GlobalFilterContext';
import { getPathBenefits, createPathBenefit, updatePathBenefit, deletePathBenefit, getPaths } from '../../api/routes';
import { getBenefits } from '../../api/reference';
import type { PathBenefit } from '../../types/route';


export function PathBenefitsPage() {
  const qc = useQueryClient();
  const { regionId: globalRegionId } = useGlobalFilter();
  const { data, isLoading, error } = useQuery({ queryKey: ['path-benefits', globalRegionId], queryFn: () => getPathBenefits({ regionId: globalRegionId || undefined }) });
  const [edit, setEdit] = useState<Partial<PathBenefit> | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const { data: paths } = useQuery({ queryKey: ['paths', globalRegionId], queryFn: () => getPaths({ regionId: globalRegionId || undefined }) });
  const { data: benefits } = useQuery({ queryKey: ['benefits', globalRegionId], queryFn: () => getBenefits(globalRegionId || undefined) });

  const createMut = useMutation({
    mutationFn: createPathBenefit,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['path-benefits'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка создания'),
  });
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<PathBenefit> }) => updatePathBenefit(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['path-benefits'] }); setEdit(null); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка обновления'),
  });
  const deleteMut = useMutation({
    mutationFn: deletePathBenefit,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['path-benefits'] }),
  });

  if (isLoading) return <div className="loading">Загрузка...</div>;
  if (error) return <div className="form-error">Ошибка: {(error as any).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Льготы путей</h1>
        <button className="btn-primary" onClick={() => { setEdit({}); setShowForm(true); setFormError(null); }}>
          + Добавить
        </button>
      </div>

      {(showForm || edit) && (
        <div className="form-card">
          <h3>{edit?.id ? 'Редактировать' : 'Создать'} льготу пути</h3>
          {formError && <div className="form-error">{formError}</div>}
          <form onSubmit={(e) => {
            e.preventDefault();
            const form = e.currentTarget;
            const data = Object.fromEntries(new FormData(form)) as any;
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
                <label>Льгота</label>
                <select name="benefitId" defaultValue={(edit as any)?.benefitId || ''} required>
                  <option value="">Выберите льготу</option>
                  {benefits?.map((b: any) => (
                    <option key={b.id} value={b.id}>{b.benefitName}</option>
                  ))}
                </select>
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
            <th>Льгота</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((item) => (
            <tr key={item.id}>
              <td>{paths?.find((p: any) => p.id === item.pathId)?.pathName || item.pathId}</td>
              <td>{benefits?.find((b: any) => b.id === item.benefitId)?.benefitName || item.benefitId}</td>
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
