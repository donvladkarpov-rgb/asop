import { useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getAdminUsers, getUserBenefits, createUserBenefit, deleteUserBenefit, getUserRegions } from '../../api/routes';
import { getBenefits, getRegions } from '../../api/reference';
import { useGlobalFilter } from '../../contexts/GlobalFilterContext';

export function UserBenefitsPage() {
  const qc = useQueryClient();
  const { regionId: globalRegionId, setRegionId: setGlobalRegionId } = useGlobalFilter();
  const { data, isLoading, error } = useQuery({ queryKey: ['user-benefits'], queryFn: () => getUserBenefits() });
  const { data: users } = useQuery({ queryKey: ['admin-users'], queryFn: getAdminUsers });
  const { data: userRegions } = useQuery({ queryKey: ['user-regions'], queryFn: () => getUserRegions() });
  const { data: regions } = useQuery({ queryKey: ['regions'], queryFn: getRegions });
  const { data: benefits } = useQuery({ queryKey: ['benefits'], queryFn: () => getBenefits() });

  // Регион берём из глобального фильтра (панель справа). Если не задан — пусто = все регионы.
  const regionId = globalRegionId;
  const [userQuery, setUserQuery] = useState('');
  const [userId, setUserId] = useState('');
  const [benefitId, setBenefitId] = useState('');
  const [validUntil, setValidUntil] = useState('');
  const [formError, setFormError] = useState<string | null>(null);

  // Пользователи, привязанные к выбранному региону (через user_regions).
  // Если регион не выбран — все пользователи.
  const usersInRegion = useMemo(() => {
    if (!regionId) return users ?? [];
    const allowed = new Set(
      (userRegions ?? []).filter((ur) => ur.regionId === regionId).map((ur) => ur.userId)
    );
    return (users ?? []).filter((u) => allowed.has(u.id));
  }, [users, userRegions, regionId]);

  // Дополнительный контекстный поиск по имени/телефону.
  const filteredUsers = useMemo(() => {
    const q = userQuery.trim().toLowerCase();
    if (!q) return usersInRegion;
    return usersInRegion.filter((u) =>
      `${u.lastNameInitial}. ${u.firstName} ${u.patronymicInitial ?? ''} ${u.phone ?? ''}`
        .toLowerCase()
        .includes(q)
    );
  }, [usersInRegion, userQuery]);

  // Льготы, привязанные к выбранному региону. Если регион не выбран — все.
  const filteredBenefits = useMemo(() => {
    if (!regionId) return benefits ?? [];
    return (benefits ?? []).filter((b) => b.regionId === regionId);
  }, [benefits, regionId]);

  const createMut = useMutation({
    mutationFn: createUserBenefit,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['user-benefits'] });
      setUserId(''); setBenefitId(''); setValidUntil(''); setUserQuery(''); setFormError(null);
    },
    onError: (e) => setFormError(JSON.stringify((e as { response?: { data?: unknown } })?.response?.data ?? (e as Error).message)),
  });
  const deleteMut = useMutation({
    mutationFn: deleteUserBenefit,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['user-benefits'] }),
  });

  const userName = (id: string) => {
    const u = users?.find((x) => x.id === id);
    return u ? `${u.lastNameInitial}. ${u.firstName}` : id;
  };
  const benefitName = (id: string) => benefits?.find((b) => b.id === id)?.benefitName || id;
  const regionName = (id: string) => regions?.find((r) => r.id === id)?.municipalDivision || id;

  if (isLoading) return <div>Загрузка...</div>;
  if (error) return <div>Ошибка: {(error as Error).message}</div>;

  return (
    <div>
      <div className="page-header">
        <h1>Льготы пользователей</h1>
      </div>

      <div className="form-card" style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            Фильтр по региону:
            <select
              value={globalRegionId}
              onChange={(e) => { setGlobalRegionId(e.target.value); setUserId(''); setBenefitId(''); }}
              style={{ minWidth: 240 }}
            >
              <option value="">— все регионы —</option>
              {regions?.map((r) => (
                <option key={r.id} value={r.id}>{r.municipalDivision}</option>
              ))}
            </select>
          </label>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr) minmax(0,1fr)', gap: '0 16px' }}>
          <label style={{ minWidth: 0 }}>Поиск пользователя
            <input
              value={userQuery}
              onChange={(e) => setUserQuery(e.target.value)}
              placeholder="Фамилия, имя или телефон"
              style={{ width: '100%', boxSizing: 'border-box' }}
            />
          </label>
          <label style={{ minWidth: 0 }}>Пользователь ({filteredUsers.length})
            <select value={userId} onChange={(e) => setUserId(e.target.value)} style={{ width: '100%', boxSizing: 'border-box' }}>
              <option value="">— выберите —</option>
              {filteredUsers.map((u) => (
                <option key={u.id} value={u.id} title={`${u.lastNameInitial}. ${u.firstName} ${u.phone ?? ''}`}>
                  {u.lastNameInitial}. {u.firstName}{u.phone ? ` (${u.phone})` : ''}
                </option>
              ))}
            </select>
          </label>
          <label style={{ minWidth: 0 }}>Льгота ({filteredBenefits.length})
            <select value={benefitId} onChange={(e) => setBenefitId(e.target.value)} style={{ width: '100%', boxSizing: 'border-box' }}>
              <option value="">— выберите —</option>
              {filteredBenefits.map((b) => (
                <option key={b.id} value={b.id}>{b.benefitName}</option>
              ))}
            </select>
          </label>
        </div>

        <div style={{ display: 'flex', gap: 16, marginTop: 12, alignItems: 'flex-end' }}>
          <label>Действует до (опц.)
            <input
              type="date"
              value={validUntil}
              onChange={(e) => setValidUntil(e.target.value)}
            />
          </label>
          <button
            className="btn-primary"
            onClick={() => createMut.mutate({
              userId,
              benefitId,
              validUntil: validUntil ? new Date(`${validUntil}T23:59:59`).toISOString() : undefined,
            })}
            disabled={!userId || !benefitId || createMut.isPending}
          >
            Назначить
          </button>
        </div>
        {formError && <div style={{ color: 'red', marginTop: 8 }}>{formError}</div>}
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>Пользователь</th>
            <th>Льгота</th>
            <th>Регион льготы</th>
            <th>Действует с</th>
            <th>Действует до</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {data?.filter((r) => {
            // Промпт-требование: глобальный фильтр по региону (панель справа) фильтрует данные.
            // Оставляем назначение, если его льгота принадлежит выбранному региону
            // (и, для страховки, пользователь тоже привязан к региону).
            if (!regionId) return true;
            const benefit = benefits?.find((b) => b.id === r.benefitId);
            const benefitInRegion = benefit ? benefit.regionId === regionId : false;
            const userInRegion = (userRegions ?? []).some((ur) => ur.userId === r.userId && ur.regionId === regionId);
            return benefitInRegion || userInRegion;
          }).map((r) => {
            const benefit = benefits?.find((b) => b.id === r.benefitId);
            return (
              <tr key={r.assignmentId}>
                <td>{userName(r.userId)}</td>
                <td>{benefitName(r.benefitId)}</td>
                <td>{benefit ? regionName(benefit.regionId) : '—'}</td>
                <td>{new Date(r.validFrom).toLocaleDateString('ru-RU')}</td>
                <td>{r.validUntil ? new Date(r.validUntil).toLocaleDateString('ru-RU') : '∞'}</td>
                <td>
                  <button
                    className="btn-danger btn-sm"
                    onClick={() => { if (confirm('Удалить назначение?')) deleteMut.mutate(r.assignmentId); }}
                  >✕</button>
                </td>
              </tr>
            );
          })}
          {data?.filter((r) => {
            if (!regionId) return true;
            const benefit = benefits?.find((b) => b.id === r.benefitId);
            const benefitInRegion = benefit ? benefit.regionId === regionId : false;
            const userInRegion = (userRegions ?? []).some((ur) => ur.userId === r.userId && ur.regionId === regionId);
            return benefitInRegion || userInRegion;
          }).length === 0 && (
            <tr><td colSpan={6} style={{ textAlign: 'center' }}>Назначений нет</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
