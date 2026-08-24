import { useGlobalFilter } from '../contexts/GlobalFilterContext';
import { useQuery } from '@tanstack/react-query';
import { getRegions } from '../api/reference';
import { getCarriers } from '../api/carriers';
import { getCardsDistributors } from '../api/cardsDistributors';
import { useMemo } from 'react';

export function GlobalFilterPanel() {
  const { regionId, carrierId, cardsDistributorId, setRegionId, setCarrierId, setCardsDistributorId } = useGlobalFilter();
  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const { data: carriers } = useQuery({ queryKey: ['carriers'], queryFn: () => getCarriers() });
  const { data: distributors } = useQuery({ queryKey: ['cardsDistributors'], queryFn: getCardsDistributors });

  const filteredCarriers = useMemo(() =>
    regionId ? (carriers || []).filter((c) => c.regionId === regionId) : (carriers || []),
    [carriers, regionId],
  );

  return (
    <aside className="filter-sidebar">
      <div className="filter-sidebar-header">
        <h3>Глобальные фильтры</h3>
      </div>
      <div className="filter-sidebar-body">
        <label className="filter-label">Регион
          <select value={regionId} onChange={(e) => setRegionId(e.target.value)}>
            <option value="">Все регионы</option>
            {regions?.map((r) => (
              <option key={r.id} value={r.id}>{r.municipalDivision}</option>
            ))}
          </select>
        </label>
        <label className="filter-label">Перевозчик
          <select value={carrierId} onChange={(e) => setCarrierId(e.target.value)}>
            <option value="">Все перевозчики</option>
            {filteredCarriers.map((c) => (
              <option key={c.id} value={c.id}>{c.carrierName}</option>
            ))}
          </select>
        </label>
        <label className="filter-label">Дистрибьютор карт
          <select value={cardsDistributorId} onChange={(e) => setCardsDistributorId(e.target.value)}>
            <option value="">Все дистрибьюторы</option>
            {distributors?.map((d) => (
              <option key={d.id} value={d.id}>{d.distributorName}</option>
            ))}
          </select>
        </label>
      </div>
    </aside>
  );
}
