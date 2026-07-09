import { useQuery } from '@tanstack/react-query';
import { getTerminals } from '../api';
import { formatDate, statusColor } from '../lib/utils';

export function TerminalsPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['terminals'],
    queryFn: () => getTerminals(),
  });

  if (isLoading) return <div>Loading...</div>;
  if (error) return <div>Error: {error.message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Terminals</h1>
      </div>
      <table className="data-table">
        <thead>
          <tr>
            <th>Serial Number</th>
            <th>Model</th>
            <th>Status</th>
            <th>Location</th>
            <th>Last Seen</th>
          </tr>
        </thead>
        <tbody>
          {data?.content.map((terminal) => (
            <tr key={terminal.id}>
              <td>{terminal.serialNumber}</td>
              <td>{terminal.model}</td>
              <td><span className={`status-badge status-${statusColor(terminal.status)}`}>{terminal.status}</span></td>
              <td>{terminal.location || '—'}</td>
              <td>{formatDate(terminal.lastSeenAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
