import { useQuery } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { getCards } from '../api/cards';
import { getCarriers } from '../api/carriers';
import { formatDate, statusColor } from '../lib/utils';
import { useGlobalFilter } from '../contexts/GlobalFilterContext';

/**
 * VCM1 (промпт 008) — bitmask → role badges для UI Cards page.
 * Bitmask упорядочен по AsopCardType ordinal (0..13).
 */
const VCM1_ROLE_BITS: Array<{ bit: number; label: string; color: string }> = [
  { bit: 0,  label: 'SUPER_ADMIN',         color: 'status-purple' },
  { bit: 1,  label: 'REGION_ADMIN',        color: 'status-blue' },
  { bit: 2,  label: 'ORGANIZER_ADMIN',     color: 'status-blue' },
  { bit: 3,  label: 'CARRIER_ADMIN',       color: 'status-teal' },
  { bit: 4,  label: 'DISTRIBUTOR_ADMIN',   color: 'status-teal' },
  { bit: 5,  label: 'KRS_ADMIN',           color: 'status-yellow' },
  { bit: 6,  label: 'CARRIER_DISPATCHER',  color: 'status-teal' },
  { bit: 7,  label: 'DISTRIBUTOR_DISPATCHER', color: 'status-teal' },
  { bit: 8,  label: 'KRS_DISPATCHER',      color: 'status-orange' },
  { bit: 9,  label: 'DRIVER',              color: 'status-green' },
  { bit: 10, label: 'KRS_FOREMAN',         color: 'status-orange' },
  { bit: 11, label: 'KRS_CONTROLLER',      color: 'status-orange' },
  { bit: 12, label: 'PASSENGER',           color: 'status-gray' },
  { bit: 13, label: 'PASSENGER_ANONYMOUS', color: 'status-gray' }
];

function BitmaskBadges({ bitmask, isClassic }: { bitmask?: number; isClassic?: boolean }) {
  if (!isClassic) return <span className="status-badge status-gray">—</span>;
  if (bitmask == null) return <span className="status-badge status-gray">—</span>;
  const tags: ReactElement[] = [];
  let firstBit = -1;
  for (const entry of VCM1_ROLE_BITS) {
    if (((bitmask >> entry.bit) & 1) === 1) {
      if (firstBit < 0) firstBit = entry.bit;
      tags.push(
        <span key={entry.bit} className={`status-badge ${entry.color}`} style={{ marginRight: 4 }}>
          {entry.label}
        </span>
      );
    }
  }
  if (tags.length === 0) return <span className="status-badge status-gray">—</span>;
  return (
    <span title={`bitmask=0x${bitmask.toString(16).padStart(4, '0')}, highest-bit ordinal=${firstBit}`}>
      {tags}
    </span>
  );
}

export function CardsPage() {
  const { regionId, carrierId } = useGlobalFilter();
  const { data: carriers } = useQuery({ queryKey: ['carriers'], queryFn: getCarriers });
  const { data, isLoading, error } = useQuery({
    queryKey: ['cards', regionId, carrierId],
    queryFn: () => getCards({
      regionId: regionId || undefined,
      carrierId: carrierId || undefined,
    }),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  const carrierName = (id?: string) => {
    if (!id) return '—';
    return carriers?.find((c) => c.id === id)?.carrierName || id.slice(0, 8) + '…';
  };

  return (
    <div>
      <div className="page-header">
        <h1>Карты</h1>
      </div>
      <table className="data-table">
        <thead>
          <tr>
            <th>UID</th>
            <th>Тип</th>
            <th>Технология</th>
            <th>Роли (bitmask)</th>
            <th>Владелец</th>
            <th>Перевозчик</th>
            <th>Статус</th>
            <th>Зарегистрирована</th>
            <th>Истекает</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((card) => (
            <tr key={card.id}>
              <td><code>{card.uid || '—'}</code></td>
              <td>{card.cardTypeName || card.cardRole || '—'}</td>
              <td>
                {card.cardTech === 'CLASSIC' ? (
                  <span className="status-badge status-orange">Classic</span>
                ) : (
                  <span className="status-badge status-green">DESFire</span>
                )}
              </td>
              <td><BitmaskBadges bitmask={card.bitmask} isClassic={card.isClassic} /></td>
              <td>{card.holderName || (card.userId ? card.userId.slice(0, 8) + '…' : '—')}</td>
              <td>{card.carrierId ? carrierName(card.carrierId) : '—'}</td>
              <td>
                <span className={`status-badge status-${statusColor(card.status || 'active')}`}>
                  {card.status || 'active'}
                </span>
              </td>
              <td>{formatDate(card.registeredAt)}</td>
              <td>{formatDate(card.validUntil)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}