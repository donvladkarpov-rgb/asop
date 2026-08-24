import { useQuery } from '@tanstack/react-query';
import { getTerminals } from '../api/terminals';
import { getCarriers } from '../api/carriers';
import { useGlobalFilter } from '../contexts/GlobalFilterContext';

export function TerminalsPage() {
  const { regionId, carrierId } = useGlobalFilter();
  const { data: carriers } = useQuery({ queryKey: ['carriers'], queryFn: () => getCarriers() });
  const { data, isLoading, error } = useQuery({
    queryKey: ['terminals', carrierId, regionId],
    queryFn: () => getTerminals(carrierId || undefined, regionId || undefined),
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
        <h1>Терминалы</h1>
      </div>
      <table className="data-table">
        <thead>
          <tr>
            <th>Серийный номер</th>
            <th>Номер</th>
            <th>Модель</th>
            <th>Перевозчик</th>
            <th>Часовой пояс</th>
            <th>Статус</th>
            <th>Создан</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((t) => (
            <tr key={t.id}>
              <td><code>{t.terminalSerial}</code></td>
              <td>{t.terminalNumber || '—'}</td>
              <td>{t.terminalModel || '—'}</td>
              <td>{carrierName(t.carrierId)}</td>
              <td>{t.timezone || '—'}</td>
              <td><span className={`badge badge-${t.status.toLowerCase()}`}>{t.status}</span></td>
              <td>{new Date(t.createdAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
