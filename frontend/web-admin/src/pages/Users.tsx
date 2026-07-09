import { useQuery } from '@tanstack/react-query';
import { getUsers } from '../api';
import { formatDate } from '../lib/utils';

export function UsersPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['users'],
    queryFn: () => getUsers(),
  });

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {error.message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Пользователи</h1>
      </div>
      <table className="data-table">
        <thead>
          <tr>
            <th>Email</th>
            <th>Имя</th>
            <th>Роли</th>
            <th>Статус</th>
            <th>Создан</th>
          </tr>
        </thead>
        <tbody>
          {data?.content.map((user) => (
            <tr key={user.id}>
              <td>{user.email}</td>
              <td>{user.firstName} {user.lastName}</td>
              <td>{user.roles.join(', ')}</td>
              <td>{user.enabled ? 'Активен' : 'Отключён'}</td>
              <td>{formatDate(user.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
