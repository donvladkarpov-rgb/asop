import { NavLink } from 'react-router-dom';
import { cn } from '../lib/utils';
import { useAuth } from '../auth/useAuth';

const navItems = [
  { to: '/', label: 'Панель управления', icon: '📊' },
  { to: '/users', label: 'Пользователи', icon: '👥' },
  { to: '/terminals', label: 'Терминалы', icon: '💳' },
  { to: '/cards', label: 'Карты', icon: '🪪' },
  { to: '/carriers', label: 'Перевозчики', icon: '🚌' },
  { to: '/sessions', label: 'Смены', icon: '🔐' },
];

const bottomItems = [
  { to: '/password', label: 'Сменить пароль', icon: '🔑' },
];

export function Sidebar() {
  const { logout } = useAuth();

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <h2>ASOP Admin</h2>
      </div>
      <nav className="sidebar-nav">
        {navItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/'}
            className={({ isActive }) =>
              cn('nav-link', isActive && 'active')
            }
          >
            <span className="nav-icon">{item.icon}</span>
            <span className="nav-label">{item.label}</span>
          </NavLink>
        ))}
      </nav>
      <div className="sidebar-footer">
        {bottomItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) =>
              cn('nav-link', isActive && 'active')
            }
          >
            <span className="nav-icon">{item.icon}</span>
            <span className="nav-label">{item.label}</span>
          </NavLink>
        ))}
        <button className="nav-link logout-btn" onClick={() => logout()}>
          <span className="nav-icon">🚪</span>
          <span className="nav-label">Выйти</span>
        </button>
      </div>
    </aside>
  );
}
