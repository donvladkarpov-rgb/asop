import { useQuery } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { getCards } from '../api';
import { formatDate, statusColor } from '../lib/utils';

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

function BitmaskBadges({ bitmask, cardTech }: { bitmask?: number; cardTech?: 'DESFIRE' | 'CLASSIC' }) {
  if (cardTech !== 'CLASSIC') return <span className="status-badge status-gray">—</span>;
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
  const { data, isLoading, error } = useQuery({
    queryKey: ['cards'],
    queryFn: () => getCards(),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {error.message}</div>;

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
            <th>Bitmask (роли)</th>
            <th>Владелец</th>
            <th>Статус</th>
            <th>Выпущена</th>
            <th>Истекает</th>
          </tr>
        </thead>
        <tbody>
          {data?.content.map((card) => (
            <tr key={card.id}>
              <td><code>{card.uid}</code></td>
              <td>{card.type}</td>
              <td>
                {card.cardTech === 'CLASSIC' ? (
                  <span className="status-badge status-orange">Classic</span>
                ) : (
                  <span className="status-badge status-green">DESFire</span>
                )}
              </td>
              <td><BitmaskBadges bitmask={card.bitmask} cardTech={card.cardTech} /></td>
              <td>{card.holderName || '—'}</td>
              <td><span className={`status-badge status-${statusColor(card.status)}`}>{card.status}</span></td>
              <td>{formatDate(card.issuedAt)}</td>
              <td>{formatDate(card.expiresAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
