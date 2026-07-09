import { useQuery } from '@tanstack/react-query';
import { getCards } from '../api';
import { formatDate, statusColor } from '../lib/utils';

export function CardsPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['cards'],
    queryFn: () => getCards(),
  });

  if (isLoading) return <div>Loading...</div>;
  if (error) return <div>Error: {error.message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Cards</h1>
      </div>
      <table className="data-table">
        <thead>
          <tr>
            <th>UID</th>
            <th>Type</th>
            <th>Holder</th>
            <th>Status</th>
            <th>Issued</th>
            <th>Expires</th>
          </tr>
        </thead>
        <tbody>
          {data?.content.map((card) => (
            <tr key={card.id}>
              <td><code>{card.uid}</code></td>
              <td>{card.type}</td>
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
