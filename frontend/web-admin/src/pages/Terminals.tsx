import { useQuery } from '@tanstack/react-query';
import { getTerminals } from '../api';
import { formatDate, statusColor } from '../lib/utils';

export function TerminalsPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['terminals'],
    queryFn: () => getTerminals(),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {error.message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Терминалы</h1>
      </div>
      <table className="data-table">
        <thead>
          <tr>
            <th>Серийный номер</th>
            <th>Модель</th>
            <th>Статус</th>
            <th>Расположение</th>
            <th>Последняя активность</th>
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
