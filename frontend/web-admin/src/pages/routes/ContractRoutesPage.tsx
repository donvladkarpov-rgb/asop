import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useGlobalFilter } from '../../contexts/GlobalFilterContext';
import { getContractRoutes, createContractRoute, deleteContractRoute } from '../../api/routes';
import { getContracts } from '../../api/contracts';
import { getRoutes } from '../../api/routes';


export function ContractRoutesPage() {
  const qc = useQueryClient();
  const { regionId: globalRegionId, carrierId: globalCarrierId, cardsDistributorId: globalDistributorId } = useGlobalFilter();
  const { data, isLoading, error } = useQuery({ queryKey: ['contract-routes', globalRegionId], queryFn: () => getContractRoutes({ regionId: globalRegionId || undefined }) });
  const { data: contracts } = useQuery({ queryKey: ['contracts', globalCarrierId, globalDistributorId], queryFn: () => getContracts({ carrierId: globalCarrierId || undefined, cardsDistributorId: globalDistributorId || undefined }) });
  const { data: routes } = useQuery({ queryKey: ['routes', globalRegionId], queryFn: () => getRoutes({ regionId: globalRegionId || undefined }) });
  const [showForm, setShowForm] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const createMut = useMutation({
    mutationFn: createContractRoute,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['contract-routes'] }); setShowForm(false); setFormError(null); },
    onError: (e: any) => setFormError(e?.response?.data?.error || e?.message || 'Ошибка создания'),
  });
  const deleteMut = useMutation({
    mutationFn: ({ contractId, routeId }: { contractId: string; routeId: string }) => deleteContractRoute(contractId, routeId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['contract-routes'] }),
  });

  if (isLoading) return <div className="loading">Загрузка...</div>;
  if (error) return <div className="form-error">Ошибка: {(error as any).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Связи договоров и маршрутов</h1>
        <button className="btn-primary" onClick={() => { setShowForm(true); setFormError(null); }}>
          + Добавить
        </button>
      </div>

      {showForm && (
        <div className="form-card">
          <h3>Добавить связь</h3>
          {formError && <div className="form-error">{formError}</div>}
          <form onSubmit={(e) => {
            e.preventDefault();
            const form = e.currentTarget;
            const data = Object.fromEntries(new FormData(form)) as any;
            createMut.mutate(data);
          }}>
            <div className="form-grid">
              <div className="form-group">
                <label>Договор</label>
                <select name="contractId" required>
                  <option value="">Выберите договор</option>
                  {contracts?.map((c: any) => (
                    <option key={c.id} value={c.id}>{c.contractNumber || c.id}</option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Маршрут</label>
                <select name="routeId" required>
                  <option value="">Выберите маршрут</option>
                  {routes?.map((r: any) => (
                    <option key={r.id} value={r.id}>{r.routeNumber} - {r.routeName}</option>
                  ))}
                </select>
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <button type="submit" className="btn-primary" disabled={createMut.isPending}>
                Сохранить
              </button>
              <button type="button" className="btn-secondary" onClick={() => { setShowForm(false); setFormError(null); }}>
                Отмена
              </button>
            </div>
          </form>
        </div>
      )}

      <table className="data-table">
        <thead>
          <tr>
            <th>Договор</th>
            <th>Маршрут</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((item) => (
            <tr key={`${item.contractId}-${item.routeId}`}>
              <td>{item.contractNumber || item.contractId}</td>
              <td>{item.routeNumber || item.routeId}</td>
              <td style={{ display: 'flex', gap: 8 }}>
                <button className="btn-danger btn-sm"
                  onClick={() => { if (confirm('Удалить?')) deleteMut.mutate({ contractId: item.contractId, routeId: item.routeId }); }}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
